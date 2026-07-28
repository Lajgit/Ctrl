#include "KeyTask.h"
#include "MesgTask.h"
#include "port_key.h"
#include "usart.h"

typedef enum
{
    PULSE_IDLE = 0,
    PULSE_LOW_FILTER,
    PULSE_WAIT_RELEASE,
    PULSE_HIGH_FILTER,
} PulseState_t;

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
static bool BillWaitPowerAck = false;
static bool BillWaitEscrowValue = false;
static bool BillEnableState = true;
static uint32_t BillPollTick = 0U;
static uint32_t BillEnableTick = 0U;
static uint8_t BillLastReportStatus = 0xFFU;

uint8_t BillAcceptor_RxByte;
uint8_t BillAcceptor_LastType = 0U;
uint8_t BillAcceptor_LastStatus = 0U;
uint8_t BillAcceptor_CurrencyMode = BILL_CURRENCY_RMB;

extern Event_Handle_t Mesg_event;
extern Tx_HandleTypeDef Tx1;

#define ICT_CMD_ACCEPT 0x02U
#define ICT_CMD_REJECT 0x0FU
#define ICT_CMD_POLL 0x0CU
#define ICT_CMD_ENABLE 0x3EU
#define ICT_CMD_DISABLE 0x5EU
#define ICT_CMD_RESET 0x30U
#define ICT_RESP_POWER_ON_1 0x80U
#define ICT_RESP_POWER_ON_2 0x8FU
#define ICT_RESP_ESCROW 0x81U
#define ICT_RESP_STACKING 0x10U
#define ICT_RESP_REJECT 0x11U
#define ICT_DENOM_UNKNOWN 0x3FU
#define ICT_DENOM_MIN 0x40U
#define ICT_DENOM_MAX 0x4FU
#define ICT_POLL_INTERVAL 150U
#define ICT_ENABLE_REPEAT_TIME 1000U

static void Encoder_ShortCallback(uint16_t id)
{
    /* id: 0=CCW, 1=CW, 2=DOWN */
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
            EventGroupSetBits(&Mesg_event, input->event);
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

    if (status != BillLastReportStatus)
    {
        BillLastReportStatus = status;
        EventGroupSetBits(&Mesg_event, MesgEvent_BillStatus);
    }
}

static void BillAcceptor_HandleByte(uint8_t data)
{
    if (BillWaitPowerAck == true)
    {
        BillWaitPowerAck = false;
        if (data == ICT_RESP_POWER_ON_2)
        {
            BillAcceptor_SendCommand(ICT_CMD_ACCEPT);
            return;
        }
    }

    if (BillWaitEscrowValue == true)
    {
        if (data == ICT_RESP_POWER_ON_2)
        {
            return;
        }

        if (BillAcceptor_IsDenomination(data))
        {
            BillEscrowType = data;
            BillAcceptor_SendCommand(ICT_CMD_ACCEPT);
            BillWaitEscrowValue = false;
            return;
        }
    }

    switch (data)
    {
    case ICT_RESP_POWER_ON_1:
        BillWaitPowerAck = true;
        break;

    case ICT_RESP_ESCROW:
        BillWaitEscrowValue = true;
        break;

    case ICT_RESP_STACKING:
        BillAcceptor_LastType = BillEscrowType;
        BillAcceptor_LastStatus = ICT_RESP_STACKING;
        EventGroupSetBits(&Mesg_event, MesgEvent_BillAccepted);
        break;

    case ICT_RESP_REJECT:
        BillAcceptor_ReportStatus(ICT_RESP_REJECT);
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
    BillWaitPowerAck = false;
    BillWaitEscrowValue = false;
    BillEnableState = true;
    BillLastReportStatus = 0xFFU;
    BillPollTick = HAL_GetTick();
    BillEnableTick = HAL_GetTick();
    HAL_UART_Receive_IT(&huart3, &BillAcceptor_RxByte, 1U);
    BillAcceptor_SendCommand(ICT_CMD_ENABLE);
}

static void BillAcceptor_Task(void)
{
    uint8_t data;
    uint32_t now = HAL_GetTick();

    while (BillAcceptor_ReadByte(&data) == true)
    {
        BillAcceptor_HandleByte(data);
    }

    if ((BillEnableState == true) &&
        (now - BillPollTick >= ICT_POLL_INTERVAL))
    {
        BillAcceptor_SendCommand(ICT_CMD_POLL);
        BillPollTick = now;
    }

    if (now - BillEnableTick >= ICT_ENABLE_REPEAT_TIME)
    {
        BillAcceptor_SendCommand(BillEnableState ? ICT_CMD_ENABLE : ICT_CMD_DISABLE);
        BillEnableTick = now;
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
    if (mode == BILL_CURRENCY_FOREIGN)
    {
        BillAcceptor_CurrencyMode = BILL_CURRENCY_FOREIGN;
    }
    else
    {
        BillAcceptor_CurrencyMode = BILL_CURRENCY_RMB;
    }

    EventGroupSetBits(&Mesg_event, MesgEvent_BillCurrencyMode);
}

void BillAcceptor_SetEnable(bool enable)
{
    BillEnableState = enable;
    BillAcceptor_SendCommand(enable ? ICT_CMD_ENABLE : ICT_CMD_DISABLE);
}

void BillAcceptor_Reset(void)
{
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
