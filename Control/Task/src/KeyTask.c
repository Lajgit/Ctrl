#include "KeyTask.h"
#include "MesgTask.h"
#include "CtrlTask.h"
#include "port_key.h"
#include "usart.h"

typedef enum
{
    PULSE_IDLE = 0,
    PULSE_LOW_FILTER,
    PULSE_WAIT_RELEASE,
    PULSE_HIGH_FILTER,
} PulseState_t;

typedef enum
{
    BILL_RX_IDLE = 0,
    BILL_RX_WAIT_POWER_ON_2,
    BILL_RX_WAIT_ESCROW_VALUE,
    BILL_RX_WAIT_JAM_VALUE,
} BillRxState_t;

typedef struct
{
    GPIO_TypeDef *gpio;
    uint16_t pin;
    PulseState_t state;
    uint32_t tick;
    event_bits_t event;
} PulseInput_t;

static Key_HandleTypeDef EncoderKey[3];
static Key_HandleTypeDef *EncoderKeyList[3];

static PulseInput_t CoinPulse;
static uint8_t BillRxQueue[32];
static volatile uint8_t BillRxHead = 0U;
static volatile uint8_t BillRxTail = 0U;
static uint8_t BillEscrowType = 0U;
static bool BillEnableState = true;
static bool CoinEnableState = true;
static BillRxState_t BillRxState = BILL_RX_IDLE;
static uint32_t BillRxStateTick = 0U;
static uint32_t BillPollTick = 0U;
static uint8_t BillLastReportStatus = 0xFFU;

/* 最近一次现金事件：Data1=介质，Data2:Data4=整数人民币元。 */
static volatile uint32_t CashEventPackedData = 0U;

/* 启用、禁用命令只做有限次数重试，不再每秒永久发送。 */
static bool BillStateCommandPending = false;
static uint8_t BillStateCommand = 0U;
static uint8_t BillExpectedStatus = 0U;
static uint8_t BillStateCommandRetryCount = 0U;
static uint32_t BillStateCommandTick = 0U;

uint8_t BillAcceptor_RxByte;
uint8_t BillAcceptor_LastType = 0U;
uint8_t BillAcceptor_LastStatus = 0U;
uint8_t BillAcceptor_CurrencyMode = BILL_CURRENCY_RMB;

extern Event_Handle_t Mesg_event;
extern Tx_HandleTypeDef Tx1;

#define ICT_CMD_ACCEPT 0x02U
#define ICT_CMD_POLL 0x0CU
#define ICT_CMD_ENABLE 0x3EU
#define ICT_CMD_DISABLE 0x5EU
#define ICT_CMD_RESET 0x30U
#define ICT_RESP_POWER_ON_1 0x80U
#define ICT_RESP_POWER_ON_2 0x8FU
#define ICT_RESP_ESCROW 0x81U
#define ICT_RESP_STACKING 0x10U
#define ICT_RESP_REJECT 0x11U
#define ICT_RESP_JAM_STACKING 0x83U
#define ICT_DENOM_UNKNOWN 0x3FU
#define ICT_DENOM_MIN 0x40U
#define ICT_DENOM_MAX 0x4FU
#define ICT_POLL_INTERVAL 150U
#define ICT_SEQUENCE_TIMEOUT 100U
#define ICT_STATE_COMMAND_RETRY_TIME 1000U
#define ICT_STATE_COMMAND_MAX_RETRY 3U

/**
 * 量产硬件适配点：控制投币器 inhibit/enable 引脚。
 *
 * 当前控制板源码未给出真实 inhibit 引脚，默认弱实现只保留软件状态；
 * 硬件引脚确认后在其他源文件提供同名强实现覆盖本函数。
 */
__weak bool CashHardware_SetCoinEnable(bool enable)
{
    (void)enable;
    return true;
}

/**
 * 量产硬件适配点：执行真实退币动作。
 *
 * medium：0=硬币，1=纸币；amount_yuan：整数人民币元。
 * 当前原理图未标注退币机构控制引脚或串口协议，因此弱实现返回失败，
 * 防止在未确认硬件接口时误驱动其他输出。
 */
__weak bool CashHardware_DoReturn(uint8_t medium, uint32_t amount_yuan)
{
    (void)medium;
    (void)amount_yuan;
    return false;
}

static uint32_t BillTypeToYuan(uint8_t bill_type)
{
    switch (bill_type)
    {
    case 0x40U:
        return 1U;
    case 0x41U:
        return 5U;
    case 0x42U:
        return 10U;
    case 0x43U:
        return 20U;
    case 0x44U:
        return 50U;
    case 0x45U:
        return 100U;
    default:
        return 0U;
    }
}

static void CashEvent_Set(uint8_t medium, uint32_t amount_yuan)
{
    if ((amount_yuan == 0U) || (amount_yuan > 0x00FFFFFFUL))
    {
        return;
    }

    CashEventPackedData = ((uint32_t)medium << 24U) |
                          (amount_yuan & 0x00FFFFFFUL);
}

uint32_t CashEvent_GetPackedData(void)
{
    return CashEventPackedData;
}

void CoinAcceptor_SetEnable(bool enable)
{
    CoinEnableState = enable;
    (void)CashHardware_SetCoinEnable(enable);
}

bool CoinAcceptor_IsEnabled(void)
{
    return CoinEnableState;
}

bool CashHardware_RequestReturn(uint8_t medium, uint32_t amount_yuan)
{
    if (((medium != CASH_MEDIUM_COIN) &&
         (medium != CASH_MEDIUM_BANKNOTE)) ||
        (amount_yuan == 0U) ||
        (amount_yuan > 0x00FFFFFFUL))
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CashReturnFailed);
        return false;
    }

    CashEvent_Set(medium, amount_yuan);

    if (CashHardware_DoReturn(medium, amount_yuan) == false)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CashReturnFailed);
        return false;
    }

    CashEvent_Set(medium, amount_yuan);
    EventGroupSetBits(&Mesg_event, MesgEvent_CashReturnedAmount);
    return true;
}

static void Encoder_ShortCallback(uint16_t id)
{
    /* id: 0=CCW, 1=CW, 2=DOWN；K1 编码器按键对应 DOWN。 */
    if (id == 2U)
    {
        /* K1 表示已补充珠子：恢复库存、启用纸钞机，并延时处理欠吐珠子。 */
        Purchase_Refill();
    }

    Comm_SendMesg_FillData(&Tx1, Board_to_Android, Encoder, (uint32_t)id + 1U, 0x00U);
}

static void PulseInput_Init(PulseInput_t *input,
                            GPIO_TypeDef *gpio,
                            uint16_t pin,
                            event_bits_t event)
{
    input->gpio = gpio;
    input->pin = pin;
    input->state = PULSE_IDLE;
    input->tick = HAL_GetTick();
    input->event = event;
}

static void PulseInput_Scan(PulseInput_t *input)
{
    GPIO_PinState pin_state = HAL_GPIO_ReadPin(input->gpio, input->pin);
    uint32_t current_tick = HAL_GetTick();

    switch (input->state)
    {
    case PULSE_IDLE:
        if (pin_state == GPIO_PIN_RESET)
        {
            input->tick = current_tick;
            input->state = PULSE_LOW_FILTER;
        }
        break;

    case PULSE_LOW_FILTER:
        if (pin_state == GPIO_PIN_SET)
        {
            input->state = PULSE_IDLE;
        }
        else if (current_tick - input->tick >= PULSE_DEBOUNCE_TIME)
        {
            input->state = PULSE_WAIT_RELEASE;
        }
        break;

    case PULSE_WAIT_RELEASE:
        if (pin_state == GPIO_PIN_SET)
        {
            input->tick = current_tick;
            input->state = PULSE_HIGH_FILTER;
        }
        break;

    case PULSE_HIGH_FILTER:
        if (pin_state == GPIO_PIN_RESET)
        {
            input->state = PULSE_WAIT_RELEASE;
        }
        else if (current_tick - input->tick >= PULSE_DEBOUNCE_TIME)
        {
            if ((input->event != MesgEvent_CoinInput) ||
                (CoinEnableState == true))
            {
                EventGroupSetBits(&Mesg_event, input->event);
            }

            if ((input->event == MesgEvent_CoinInput) &&
                (CoinEnableState == true))
            {
                /* 1 个有效硬币脉冲固定为 1 元，由控制板独立计价和吐珠。 */
                Purchase_AddCoinPayment();
                CashEvent_Set(CASH_MEDIUM_COIN, 1U);
                EventGroupSetBits(&Mesg_event, MesgEvent_CashAcceptedAmount);
            }

            input->state = PULSE_IDLE;
        }
        break;

    default:
        input->state = PULSE_IDLE;
        break;
    }
}

static bool BillAcceptor_ReadByte(uint8_t *data)
{
    if (BillRxHead == BillRxTail)
    {
        return false;
    }

    *data = BillRxQueue[BillRxTail];
    BillRxTail = (uint8_t)((BillRxTail + 1U) % sizeof(BillRxQueue));
    return true;
}

static void BillAcceptor_SendCommand(uint8_t cmd)
{
    HAL_UART_Transmit(&huart3, &cmd, 1U, 20U);
}

static void BillAcceptor_SetRxState(BillRxState_t state)
{
    BillRxState = state;
    BillRxStateTick = HAL_GetTick();
}

static void BillAcceptor_StartStateCommand(uint8_t cmd)
{
    BillStateCommand = cmd;
    BillExpectedStatus = cmd;
    BillStateCommandRetryCount = 0U;
    BillStateCommandTick = HAL_GetTick();
    BillStateCommandPending = true;
    BillAcceptor_SendCommand(cmd);
}

static bool BillAcceptor_IsDenomination(uint8_t data)
{
    return (data == ICT_DENOM_UNKNOWN) ||
           ((data >= ICT_DENOM_MIN) && (data <= ICT_DENOM_MAX));
}

static bool BillAcceptor_IsStatus(uint8_t data)
{
    return ((data >= 0x20U) && (data <= 0x2FU)) ||
           (data == ICT_CMD_ENABLE) ||
           (data == ICT_CMD_DISABLE) ||
           (data == 0x71U) ||
           (data == 0xA1U);
}

static void BillAcceptor_ReportStatus(uint8_t status)
{
    BillAcceptor_LastStatus = status;

    if ((BillStateCommandPending == true) &&
        (status == BillExpectedStatus))
    {
        BillStateCommandPending = false;
    }

    if (status != BillLastReportStatus)
    {
        BillLastReportStatus = status;
        EventGroupSetBits(&Mesg_event, MesgEvent_BillStatus);
    }
}

static void BillAcceptor_CompletePayment(uint8_t bill_type, uint8_t complete_status)
{
    uint32_t amount_yuan;

    BillAcceptor_LastType = bill_type;
    BillAcceptor_LastStatus = complete_status;
    EventGroupSetBits(&Mesg_event, MesgEvent_BillAccepted);

    if (BillAcceptor_CurrencyMode == BILL_CURRENCY_RMB)
    {
        Purchase_AddBillPayment(bill_type);
        amount_yuan = BillTypeToYuan(bill_type);
        if (amount_yuan > 0U)
        {
            CashEvent_Set(CASH_MEDIUM_BANKNOTE, amount_yuan);
            EventGroupSetBits(&Mesg_event, MesgEvent_CashAcceptedAmount);
        }
    }
}

static void BillAcceptor_HandleByte(uint8_t data)
{
    if (BillRxState == BILL_RX_WAIT_POWER_ON_2)
    {
        if (data == ICT_RESP_POWER_ON_2)
        {
            BillAcceptor_SendCommand(ICT_CMD_ACCEPT);
            BillAcceptor_SetRxState(BILL_RX_IDLE);
            if (BillEnableState == false)
            {
                BillAcceptor_StartStateCommand(ICT_CMD_DISABLE);
            }
            return;
        }

        if (data == ICT_RESP_POWER_ON_1)
        {
            BillRxStateTick = HAL_GetTick();
            return;
        }
        BillAcceptor_SetRxState(BILL_RX_IDLE);
    }
    else if (BillRxState == BILL_RX_WAIT_ESCROW_VALUE)
    {
        if (data == ICT_RESP_POWER_ON_2)
        {
            BillRxStateTick = HAL_GetTick();
            return;
        }

        if (BillAcceptor_IsDenomination(data))
        {
            BillEscrowType = data;
            BillAcceptor_SendCommand(ICT_CMD_ACCEPT);
            BillAcceptor_SetRxState(BILL_RX_IDLE);
            return;
        }
        BillAcceptor_SetRxState(BILL_RX_IDLE);
    }
    else if (BillRxState == BILL_RX_WAIT_JAM_VALUE)
    {
        if (BillAcceptor_IsDenomination(data))
        {
            BillAcceptor_CompletePayment(data, ICT_RESP_JAM_STACKING);
            BillEscrowType = 0U;
            BillAcceptor_SetRxState(BILL_RX_IDLE);
            return;
        }
        BillAcceptor_SetRxState(BILL_RX_IDLE);
    }

    switch (data)
    {
    case ICT_RESP_POWER_ON_1:
        BillAcceptor_SetRxState(BILL_RX_WAIT_POWER_ON_2);
        break;

    case ICT_RESP_ESCROW:
        BillEscrowType = 0U;
        BillAcceptor_SetRxState(BILL_RX_WAIT_ESCROW_VALUE);
        break;

    case ICT_RESP_STACKING:
        if (BillAcceptor_IsDenomination(BillEscrowType))
        {
            BillAcceptor_CompletePayment(BillEscrowType, ICT_RESP_STACKING);
        }
        BillEscrowType = 0U;
        break;

    case ICT_RESP_REJECT:
        BillEscrowType = 0U;
        BillAcceptor_ReportStatus(ICT_RESP_REJECT);
        break;

    case ICT_RESP_JAM_STACKING:
        BillEscrowType = 0U;
        BillAcceptor_SetRxState(BILL_RX_WAIT_JAM_VALUE);
        break;

    default:
        if (BillAcceptor_IsStatus(data))
        {
            BillAcceptor_ReportStatus(data);
        }
        break;
    }
}

static void BillAcceptor_Init(void)
{
    BillRxHead = 0U;
    BillRxTail = 0U;
    BillEscrowType = 0U;
    BillEnableState = true;
    CoinEnableState = true;
    BillRxState = BILL_RX_IDLE;
    BillLastReportStatus = 0xFFU;
    BillPollTick = HAL_GetTick();
    BillStateCommandPending = false;
    HAL_UART_Receive_IT(&huart3, &BillAcceptor_RxByte, 1U);

    BillAcceptor_StartStateCommand(ICT_CMD_ENABLE);
    (void)CashHardware_SetCoinEnable(true);
}

static void BillAcceptor_Task(void)
{
    uint8_t data;
    uint32_t now = HAL_GetTick();

    while (BillAcceptor_ReadByte(&data) == true)
    {
        BillAcceptor_HandleByte(data);
    }

    if ((BillRxState != BILL_RX_IDLE) &&
        (now - BillRxStateTick >= ICT_SEQUENCE_TIMEOUT))
    {
        BillEscrowType = 0U;
        BillAcceptor_SetRxState(BILL_RX_IDLE);
    }

    if ((BillRxState == BILL_RX_IDLE) &&
        (now - BillPollTick >= ICT_POLL_INTERVAL))
    {
        BillAcceptor_SendCommand(ICT_CMD_POLL);
        BillPollTick = now;
    }

    if ((BillStateCommandPending == true) &&
        (now - BillStateCommandTick >= ICT_STATE_COMMAND_RETRY_TIME))
    {
        if (BillStateCommandRetryCount < ICT_STATE_COMMAND_MAX_RETRY)
        {
            BillStateCommandRetryCount++;
            BillStateCommandTick = now;
            BillAcceptor_SendCommand(BillStateCommand);
        }
        else
        {
            BillStateCommandPending = false;
        }
    }
}

void BillAcceptor_RxCpltCallback(void)
{
    uint8_t next_head = (uint8_t)((BillRxHead + 1U) % sizeof(BillRxQueue));
    if (next_head != BillRxTail)
    {
        BillRxQueue[BillRxHead] = BillAcceptor_RxByte;
        BillRxHead = next_head;
    }
    HAL_UART_Receive_IT(&huart3, &BillAcceptor_RxByte, 1U);
}

void BillAcceptor_SetCurrencyMode(uint8_t mode)
{
    BillAcceptor_CurrencyMode =
        (mode == BILL_CURRENCY_FOREIGN) ? BILL_CURRENCY_FOREIGN : BILL_CURRENCY_RMB;
    EventGroupSetBits(&Mesg_event, MesgEvent_BillCurrencyMode);
}

void BillAcceptor_SetEnable(bool enable)
{
    BillEnableState = enable;
    BillAcceptor_StartStateCommand(enable ? ICT_CMD_ENABLE : ICT_CMD_DISABLE);
}

void BillAcceptor_Reset(void)
{
    BillEscrowType = 0U;
    BillAcceptor_LastType = 0U;
    BillStateCommandPending = false;
    BillAcceptor_SetRxState(BILL_RX_IDLE);
    BillAcceptor_SendCommand(ICT_CMD_RESET);
}

void KeyAll_Init(void)
{
    Key_Init(&EncoderKey[0],
             0U,
             KeyBoard1_GPIO_Port,
             KeyBoard1_Pin,
             KEY_DEBOUNCE_TIME,
             800U,
             1U,
             Encoder_ShortCallback,
             NULL,
             NULL,
             GPIO_PIN_RESET);
    EncoderKeyList[0] = &EncoderKey[0];

    Key_Init(&EncoderKey[1],
             1U,
             KeyBoard2_GPIO_Port,
             KeyBoard2_Pin,
             KEY_DEBOUNCE_TIME,
             800U,
             1U,
             Encoder_ShortCallback,
             NULL,
             NULL,
             GPIO_PIN_RESET);
    EncoderKeyList[1] = &EncoderKey[1];

    Key_Init(&EncoderKey[2],
             2U,
             KeyBoard3_GPIO_Port,
             KeyBoard3_Pin,
             KEY_DEBOUNCE_TIME,
             800U,
             1U,
             Encoder_ShortCallback,
             NULL,
             NULL,
             GPIO_PIN_RESET);
    EncoderKeyList[2] = &EncoderKey[2];

    PulseInput_Init(&CoinPulse, CoinInput_GPIO_Port, CoinInput_Pin, MesgEvent_CoinInput);
    BillAcceptor_Init();
}

void Key_Task(void)
{
    Key_Scan(EncoderKeyList, 3U);
    PulseInput_Scan(&CoinPulse);
    BillAcceptor_Task();
}
