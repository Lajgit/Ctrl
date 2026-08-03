#include "KeyTask.h"
#include "MesgTask.h"
#include "CtrlTask.h"
#include "FlashTask.h"
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
} PulseInput_t;

static Key_HandleTypeDef EncoderKey[3];
static Key_HandleTypeDef *EncoderKeyList[3];
static PulseInput_t CoinPulse;

static uint8_t BillRxQueue[32];
static volatile uint8_t BillRxHead = 0U;
static volatile uint8_t BillRxTail = 0U;
static uint8_t BillEscrowType = 0U;
static bool BillEnableState = false;
static bool BillRequestedEnable = false;
static bool CoinEnableState = false;
static BillRxState_t BillRxState = BILL_RX_IDLE;
static uint32_t BillRxStateTick = 0U;
static uint32_t BillPollTick = 0U;
static uint8_t BillLastReportStatus = 0xFFU;
static uint8_t BillLastType = 0U;
static uint32_t CashConfigVersion = 0U;
static uint8_t CashEnableMask = 0U;
static bool CashApplyPending = false;
static uint32_t CashPendingConfigVersion = 0U;
static uint8_t CashPendingEnableMask = 0U;

static bool BillStateCommandPending = false;
static uint8_t BillStateCommand = 0U;
static uint8_t BillExpectedStatus = 0U;
static uint8_t BillStateCommandRetryCount = 0U;
static uint32_t BillStateCommandTick = 0U;

uint8_t BillAcceptor_RxByte;

extern Event_Handle_t Mesg_event;
extern Setting_TypeDef Setting;

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
#define ICT_RESP_JAM_STACKING 0x83U
#define ICT_DENOM_UNKNOWN 0x3FU
#define ICT_DENOM_MIN 0x40U
#define ICT_DENOM_MAX 0x4FU
#define ICT_POLL_INTERVAL 150U
#define ICT_SEQUENCE_TIMEOUT 100U
#define ICT_STATE_COMMAND_RETRY_TIME 1000U
#define ICT_STATE_COMMAND_MAX_RETRY 3U

bool CashHardware_SetCoinEnable(bool enable)
{
    GPIO_PinState target = enable ? GPIO_PIN_SET : GPIO_PIN_RESET;
    HAL_GPIO_WritePin(CoinPower_GPIO_Port, CoinPower_Pin, target);
    return HAL_GPIO_ReadPin(CoinPower_GPIO_Port, CoinPower_Pin) == target;
}

static uint16_t BillTypeToFen(uint8_t bill_type)
{
    switch (bill_type)
    {
    case 0x40U:
        return 100U;
    case 0x41U:
        return 500U;
    case 0x42U:
        return 1000U;
    case 0x43U:
        return 2000U;
    case 0x44U:
        return 5000U;
    case 0x45U:
        return 10000U;
    default:
        return 0U;
    }
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

static void CashAcceptance_UpdateActualMask(void)
{
    CashEnableMask =
        (BillEnableState ? CASH_ACCEPT_BANKNOTE_MASK : 0U) |
        (CoinEnableState ? CASH_ACCEPT_COIN_MASK : 0U);
}

static bool CoinAcceptor_SetEnableInternal(bool enable)
{
    if (!CashHardware_SetCoinEnable(enable))
    {
        CoinEnableState = false;
        CashAcceptance_UpdateActualMask();
        return false;
    }
    CoinEnableState = enable;
    CashAcceptance_UpdateActualMask();
    return true;
}

static bool CashAcceptance_CommitPendingIfMatched(void)
{
    if (!CashApplyPending ||
        (CashEnableMask != CashPendingEnableMask))
    {
        return false;
    }

    CashConfigVersion = CashPendingConfigVersion;
    CashApplyPending = false;
    CashPendingConfigVersion = 0U;
    CashPendingEnableMask = 0U;
    EventGroupSetBits(&Mesg_event, MesgEvent_CashAcceptanceStatus);
    return true;
}

static void BillAcceptor_SetEnableInternal(bool enable)
{
    BillRequestedEnable = enable;
    BillAcceptor_StartStateCommand(enable ? ICT_CMD_ENABLE : ICT_CMD_DISABLE);
}

static uint32_t CashEvent_QueueIndex(uint32_t offset)
{
    return (Setting.CashQueueHead + offset) % CASH_EVENT_QUEUE_CAPACITY;
}

bool CashEvent_HasPending(void)
{
    return Setting.CashQueueCount > 0U;
}

bool CashEvent_HasCapacity(void)
{
    return Setting.CashQueueCount < CASH_EVENT_QUEUE_CAPACITY;
}

static bool CashEvent_RecordAccepted(uint8_t medium, uint16_t amount_fen)
{
    uint32_t index;
    uint16_t sequence;

    if ((amount_fen == 0U) ||
        ((medium != CASH_MEDIUM_COIN) && (medium != CASH_MEDIUM_BANKNOTE)))
    {
        return false;
    }

    if (!CashEvent_HasCapacity())
    {
        Setting.HardwareFlags |= HARDWARE_FLAG_CASH_QUEUE_OVERFLOW;
        FlashTask_RequestSave();
        EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
        return false;
    }

    sequence = (uint16_t)((Setting.CashSequenceCounter + 1U) & 0xFFFFU);
    if (sequence == 0U)
    {
        sequence = 1U;
    }
    Setting.CashSequenceCounter = sequence;

    index = CashEvent_QueueIndex(Setting.CashQueueCount);
    Setting.CashQueueSequence[index] = sequence;
    Setting.CashQueuePacked[index] = CASH_EVENT_PACK(medium, amount_fen);
    Setting.CashQueueCount++;
    Setting.HardwareFlags &= ~HARDWARE_FLAG_CASH_QUEUE_OVERFLOW;

    FlashTask_RequestSave();
    EventGroupSetBits(&Mesg_event, MesgEvent_CashAccepted);
    EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
    return true;
}

bool CashAcceptance_Apply(uint8_t enable_mask, uint32_t config_version)
{
    uint8_t valid_mask = CASH_ACCEPT_BANKNOTE_MASK | CASH_ACCEPT_COIN_MASK;
    bool coin_enable;

    if ((config_version == 0U) || (config_version > 0x00FFFFFFUL) ||
        ((enable_mask & (uint8_t)(~valid_mask)) != 0U))
    {
        CashAcceptance_Disable();
        return false;
    }

    coin_enable = (enable_mask & CASH_ACCEPT_COIN_MASK) != 0U;
    if (!CoinAcceptor_SetEnableInternal(coin_enable))
    {
        CashAcceptance_Disable();
        return false;
    }

    CashPendingConfigVersion = config_version;
    CashPendingEnableMask = enable_mask;
    CashApplyPending = true;
    BillAcceptor_SetEnableInternal(
        (enable_mask & CASH_ACCEPT_BANKNOTE_MASK) != 0U);
    return true;
}

void CashAcceptance_Disable(void)
{
    CashApplyPending = false;
    CashPendingConfigVersion = 0U;
    CashPendingEnableMask = 0U;
    (void)CoinAcceptor_SetEnableInternal(false);
    BillAcceptor_SetEnableInternal(false);
    EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
}

void CashAcceptance_RequestStatus(void)
{
    EventGroupSetBits(&Mesg_event, MesgEvent_CashAcceptanceStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
    if (CashEvent_HasPending())
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CashAccepted);
    }
}

uint8_t CashAcceptance_GetEnableMask(void)
{
    return CashEnableMask;
}

uint32_t CashAcceptance_GetConfigVersion(void)
{
    return CashConfigVersion;
}

uint32_t CashDevice_GetStatusData(void)
{
    uint32_t queue_count = Setting.CashQueueCount & 0x7FU;
    uint32_t overflow =
        (Setting.HardwareFlags & HARDWARE_FLAG_CASH_QUEUE_OVERFLOW) != 0U
            ? 0x80000000UL
            : 0U;

    return overflow |
           (queue_count << 24U) |
           ((uint32_t)BillLastType << 16U) |
           ((uint32_t)BillLastReportStatus << 8U) |
           ((BillEnableState ? 1UL : 0UL) << 1U) |
           (CoinEnableState ? 1UL : 0UL);
}

uint8_t CashEvent_GetPendingMedium(void)
{
    uint32_t packed;
    if (!CashEvent_HasPending())
    {
        return 0U;
    }
    packed = Setting.CashQueuePacked[Setting.CashQueueHead];
    return (uint8_t)CASH_EVENT_PACKED_MEDIUM(packed);
}

uint16_t CashEvent_GetPendingAmountFen(void)
{
    uint32_t packed;
    if (!CashEvent_HasPending())
    {
        return 0U;
    }
    packed = Setting.CashQueuePacked[Setting.CashQueueHead];
    return (uint16_t)CASH_EVENT_PACKED_AMOUNT(packed);
}

uint16_t CashEvent_GetPendingSequence(void)
{
    if (!CashEvent_HasPending())
    {
        return 0U;
    }
    return (uint16_t)Setting.CashQueueSequence[Setting.CashQueueHead];
}

void CashEvent_ConfirmTransport(uint16_t sequence)
{
    uint32_t head;

    if (!CashEvent_HasPending() ||
        (sequence == 0U) ||
        (sequence != CashEvent_GetPendingSequence()))
    {
        return;
    }

    head = Setting.CashQueueHead;
    Setting.CashQueueSequence[head] = 0U;
    Setting.CashQueuePacked[head] = 0U;
    Setting.CashQueueHead = (head + 1U) % CASH_EVENT_QUEUE_CAPACITY;
    Setting.CashQueueCount--;
    FlashTask_RequestSave();

    if (CashEvent_HasPending())
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CashAccepted);
    }
    EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
}

void CashEvent_RestorePending(void)
{
    if (CashEvent_HasPending())
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CashAccepted);
    }
}

static void Encoder_ShortCallback(uint16_t id)
{
    if (id == 2U)
    {
        Hardware_Refill();
    }
}

static void PulseInput_Init(PulseInput_t *input,
                            GPIO_TypeDef *gpio,
                            uint16_t pin)
{
    input->gpio = gpio;
    input->pin = pin;
    input->state = PULSE_IDLE;
    input->tick = HAL_GetTick();
}

static void PulseInput_Scan(PulseInput_t *input)
{
    GPIO_PinState pin_state = HAL_GPIO_ReadPin(input->gpio, input->pin);
    uint32_t current_tick = HAL_GetTick();

    if (!CoinEnableState)
    {
        input->state = PULSE_IDLE;
        input->tick = current_tick;
        return;
    }

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
            /* 当前硬币器每个有效脉冲代表1元，即100分。 */
            (void)CashEvent_RecordAccepted(CASH_MEDIUM_COIN, 100U);
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
    uint8_t previous_status = BillLastReportStatus;
    uint8_t previous_mask = CashEnableMask;
    bool config_committed = false;

    BillLastReportStatus = status;
    if ((status == ICT_CMD_ENABLE) || (status == ICT_CMD_DISABLE))
    {
        BillEnableState = status == ICT_CMD_ENABLE;
        CashAcceptance_UpdateActualMask();
        if (BillStateCommandPending && (status == BillExpectedStatus))
        {
            BillStateCommandPending = false;
            BillRequestedEnable = BillEnableState;
        }
        config_committed = CashAcceptance_CommitPendingIfMatched();
        if (!config_committed && (CashEnableMask != previous_mask))
        {
            EventGroupSetBits(&Mesg_event, MesgEvent_CashAcceptanceStatus);
        }
    }
    if ((status != previous_status) || (CashEnableMask != previous_mask))
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
    }
}

static void BillAcceptor_CompletePayment(uint8_t bill_type, uint8_t complete_status)
{
    uint16_t amount_fen = BillTypeToFen(bill_type);
    BillLastType = bill_type;
    BillAcceptor_ReportStatus(complete_status);

    if ((amount_fen > 0U) && BillEnableState)
    {
        (void)CashEvent_RecordAccepted(CASH_MEDIUM_BANKNOTE, amount_fen);
    }
}

static void BillAcceptor_HandleByte(uint8_t data)
{
    if (BillRxState == BILL_RX_WAIT_POWER_ON_2)
    {
        if (data == ICT_RESP_POWER_ON_2)
        {
            BillAcceptor_SetRxState(BILL_RX_IDLE);
            BillAcceptor_StartStateCommand(
                BillRequestedEnable ? ICT_CMD_ENABLE : ICT_CMD_DISABLE);
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
            if (BillEnableState && CashEvent_HasCapacity())
            {
                BillAcceptor_SendCommand(ICT_CMD_ACCEPT);
            }
            else
            {
                BillAcceptor_SendCommand(ICT_CMD_REJECT);
                BillEscrowType = 0U;
            }
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
    BillEnableState = false;
    BillRequestedEnable = false;
    CoinEnableState = false;
    CashEnableMask = 0U;
    CashConfigVersion = 0U;
    CashApplyPending = false;
    CashPendingConfigVersion = 0U;
    CashPendingEnableMask = 0U;
    BillRxState = BILL_RX_IDLE;
    BillLastReportStatus = 0xFFU;
    BillLastType = 0U;
    BillPollTick = HAL_GetTick();
    BillStateCommandPending = false;
    HAL_UART_Receive_IT(&huart3, &BillAcceptor_RxByte, 1U);

    BillAcceptor_StartStateCommand(ICT_CMD_DISABLE);
    (void)CoinAcceptor_SetEnableInternal(false);
}

static void BillAcceptor_Task(void)
{
    uint8_t data;
    uint32_t now = HAL_GetTick();

    while (BillAcceptor_ReadByte(&data))
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
    if (BillStateCommandPending &&
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
            if (CashApplyPending)
            {
                CashApplyPending = false;
                CashPendingConfigVersion = 0U;
                CashPendingEnableMask = 0U;
                (void)CoinAcceptor_SetEnableInternal(false);
                BillRequestedEnable = false;
                BillAcceptor_SendCommand(ICT_CMD_DISABLE);
                EventGroupSetBits(
                    &Mesg_event,
                    MesgEvent_CashAcceptanceStatus);
            }
            EventGroupSetBits(&Mesg_event, MesgEvent_CashDeviceStatus);
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

void BillAcceptor_Reset(void)
{
    BillEscrowType = 0U;
    BillLastType = 0U;
    BillStateCommandPending = false;
    BillAcceptor_SetRxState(BILL_RX_IDLE);
    BillAcceptor_SendCommand(ICT_CMD_RESET);
}

void KeyAll_Init(void)
{
    Key_Init(&EncoderKey[0], 0U, KeyBoard1_GPIO_Port, KeyBoard1_Pin,
             KEY_DEBOUNCE_TIME, 800U, 1U,
             Encoder_ShortCallback, NULL, NULL, GPIO_PIN_RESET);
    EncoderKeyList[0] = &EncoderKey[0];

    Key_Init(&EncoderKey[1], 1U, KeyBoard2_GPIO_Port, KeyBoard2_Pin,
             KEY_DEBOUNCE_TIME, 800U, 1U,
             Encoder_ShortCallback, NULL, NULL, GPIO_PIN_RESET);
    EncoderKeyList[1] = &EncoderKey[1];

    Key_Init(&EncoderKey[2], 2U, KeyBoard3_GPIO_Port, KeyBoard3_Pin,
             KEY_DEBOUNCE_TIME, 800U, 1U,
             Encoder_ShortCallback, NULL, NULL, GPIO_PIN_RESET);
    EncoderKeyList[2] = &EncoderKey[2];

    PulseInput_Init(&CoinPulse, CoinInput_GPIO_Port, CoinInput_Pin);
    BillAcceptor_Init();
}

void Key_Task(void)
{
    Key_Scan(EncoderKeyList, 3U);
    PulseInput_Scan(&CoinPulse);
    BillAcceptor_Task();
}
