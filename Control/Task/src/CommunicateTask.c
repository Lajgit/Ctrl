#include "CommunicateTask.h"
#include "MesgTask.h"
#include "CtrlTask.h"
#include "KeyTask.h"
#include "app_crc.h"
#include "app_list.h"
#include "string.h"
#include "usart.h"

static void USART_RequestMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg);
static bool Board_WriteBootRequest(uint32_t request_magic);
static void Board_SystemRestart(bool enter_bootloader);
static void USART_RemoveCashResend(uint16_t sequence);
static void USART_RemoveBoardEventResend(uint8_t event_code, uint8_t token);
static bool USART_IsDurableBoardEvent(uint8_t code2);

#define PENDING_TX_ENTRY_COUNT 100U
#define RETIRED_FRAME_ID_COUNT 255U
#define FRAME_ID_RETIRE_TIME 5000U

typedef enum
{
    PENDING_TX_KIND_NONE = 0,
    PENDING_TX_KIND_LINE_ACK,
    PENDING_TX_KIND_CASH_EVENT,
    PENDING_TX_KIND_PHYSICAL_EVENT
} PendingTxKind_t;

typedef struct
{
    bool used;
    bool durable;
    bool lineAcked;
    uint8_t frameId;
    Mesg_TypeDef frame;
    uint32_t lastSendTick;
    uint8_t resendCount;
    PendingTxKind_t kind;
} PendingTxEntry_t;

typedef struct
{
    bool used;
    uint8_t frameId;
    uint32_t retiredTick;
} RetiredFrameId_t;

ListHandle_t DealList;
static ListNode_t DealList_buffer[100];

static PendingTxEntry_t PendingTxTable[PENDING_TX_ENTRY_COUNT];
static RetiredFrameId_t RetiredFrameIds[RETIRED_FRAME_ID_COUNT];
static uint8_t NextFrameId = 0U;
static uint8_t rx1_buffer[512];
static Mesg_TypeDef Receive1_mesg;

Tx_HandleTypeDef Tx1;
Rx_HandleTypeDef Rx1;

extern Event_Handle_t Mesg_event;
extern Lock_t Lock;

static bool Board_WriteBootRequest(uint32_t request_magic)
{
    uint32_t timeout;
    uint32_t retry;

    __HAL_RCC_PWR_CLK_ENABLE();
    __DSB();
    (void)RCC->APB1ENR;
    HAL_PWR_EnableBkUpAccess();

    timeout = 100000U;
    while (((PWR->CR & PWR_CR_DBP) == 0U) && (timeout > 0U))
    {
        timeout--;
    }
    if (timeout == 0U)
    {
        return false;
    }

    __HAL_RCC_RTC_ENABLE();
    __DSB();
    (void)RCC->BDCR;
    for (retry = 0U; retry < 3U; retry++)
    {
        RTC->BKP0R = request_magic;
        __DSB();
        __ISB();
        if (RTC->BKP0R == request_magic)
        {
            return true;
        }
    }
    return false;
}

static void Board_SystemRestart(bool enter_bootloader)
{
    uint32_t request_magic = enter_bootloader ? OTA_REQUEST_MAGIC : 0U;
    if (!Board_WriteBootRequest(request_magic))
    {
        return;
    }
    Device_StopAllImmediately();
    CashAcceptance_Disable();
    HAL_Delay(100U);
    NVIC_SystemReset();
    while (1)
    {
    }
}

static bool USART_ReceiveMesg_Verify(void *self, void *mesg)
{
    Rx_HandleTypeDef *rx = (Rx_HandleTypeDef *)self;
    Mesg_TypeDef *rx_mesg = (Mesg_TypeDef *)mesg;
    uint16_t crc16 = CRC16_calculate(rx->Queue.Buf, 11U);
    uint16_t mesg_crc16 = ((uint16_t)rx_mesg->CRC16_H << 8U) | rx_mesg->CRC16_L;
    return crc16 == mesg_crc16;
}

static uint32_t USART_GetData32(Mesg_TypeDef *mesg)
{
    return ((uint32_t)mesg->Data1 << 24U) |
           ((uint32_t)mesg->Data2 << 16U) |
           ((uint32_t)mesg->Data3 << 8U) |
           (uint32_t)mesg->Data4;
}

static uint32_t USART_GetValue24(Mesg_TypeDef *mesg)
{
    return ((uint32_t)mesg->Data2 << 16U) |
           ((uint32_t)mesg->Data3 << 8U) |
           (uint32_t)mesg->Data4;
}

static uint16_t USART_GetCashSequence(const Mesg_TypeDef *mesg)
{
    return ((uint16_t)mesg->Data4 << 8U) |
           (uint16_t)mesg->ExpandCode;
}

static bool USART_IsDurableBoardEvent(uint8_t code2)
{
    return (code2 == DispenseStarted) ||
           (code2 == DispenseCompleted) ||
           (code2 == DispenseFailed) ||
           (code2 == CollectStarted) ||
           (code2 == CollectCompleted) ||
           (code2 == CollectFailed);
}

static bool PendingTx_IsDurableCode(uint8_t code2)
{
    return (code2 == CashAccepted) || USART_IsDurableBoardEvent(code2);
}

static PendingTxKind_t PendingTx_GetKind(uint8_t code2)
{
    if (code2 == CashAccepted)
    {
        return PENDING_TX_KIND_CASH_EVENT;
    }
    if (USART_IsDurableBoardEvent(code2))
    {
        return PENDING_TX_KIND_PHYSICAL_EVENT;
    }
    return PENDING_TX_KIND_LINE_ACK;
}

static bool PendingTx_IsDispenseTerminalCode(uint8_t code2)
{
    return (code2 == DispenseCompleted) || (code2 == DispenseFailed);
}

static bool PendingTx_IsCollectTerminalCode(uint8_t code2)
{
    return (code2 == CollectCompleted) || (code2 == CollectFailed);
}

static bool PendingTx_IsPhysicalTerminalCode(uint8_t code2)
{
    return PendingTx_IsDispenseTerminalCode(code2) ||
           PendingTx_IsCollectTerminalCode(code2);
}

static PendingTxEntry_t *PendingTx_FindCashAccepted(uint16_t sequence)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_CASH_EVENT) &&
            (PendingTxTable[i].frame.Code2 == CashAccepted) &&
            (USART_GetCashSequence(&PendingTxTable[i].frame) == sequence))
        {
            return &PendingTxTable[i];
        }
    }
    return NULL;
}

static bool PendingTx_IsFrameIdUsed(uint8_t frame_id)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used && (PendingTxTable[i].frameId == frame_id))
        {
            return true;
        }
    }
    return false;
}

static bool PendingTx_IsFrameIdRetired(uint8_t frame_id, uint32_t now)
{
    uint16_t i;
    for (i = 0U; i < RETIRED_FRAME_ID_COUNT; i++)
    {
        if (RetiredFrameIds[i].used &&
            (RetiredFrameIds[i].frameId == frame_id) &&
            ((now - RetiredFrameIds[i].retiredTick) < FRAME_ID_RETIRE_TIME))
        {
            return true;
        }
    }
    return false;
}

static void PendingTx_RetireFrameId(uint8_t frame_id)
{
    uint16_t i;
    uint16_t selected = 0U;
    uint32_t now = HAL_GetTick();
    uint32_t oldest_age = 0U;

    if (frame_id == 0U)
    {
        return;
    }

    for (i = 0U; i < RETIRED_FRAME_ID_COUNT; i++)
    {
        if (RetiredFrameIds[i].used &&
            (RetiredFrameIds[i].frameId == frame_id))
        {
            RetiredFrameIds[i].retiredTick = now;
            return;
        }
    }

    for (i = 0U; i < RETIRED_FRAME_ID_COUNT; i++)
    {
        if (!RetiredFrameIds[i].used ||
            ((now - RetiredFrameIds[i].retiredTick) >= FRAME_ID_RETIRE_TIME))
        {
            selected = i;
            break;
        }
        if ((now - RetiredFrameIds[i].retiredTick) >= oldest_age)
        {
            oldest_age = now - RetiredFrameIds[i].retiredTick;
            selected = i;
        }
    }

    RetiredFrameIds[selected].used = true;
    RetiredFrameIds[selected].frameId = frame_id;
    RetiredFrameIds[selected].retiredTick = now;
}

static uint8_t PendingTx_AllocateFrameId(bool skip_retired)
{
    uint16_t attempts;
    uint8_t candidate;
    uint32_t now = HAL_GetTick();

    for (attempts = 0U; attempts < 255U; attempts++)
    {
        NextFrameId++;
        if (NextFrameId == 0U)
        {
            NextFrameId = 1U;
        }
        candidate = NextFrameId;
        if (PendingTx_IsFrameIdUsed(candidate))
        {
            continue;
        }
        if (skip_retired && PendingTx_IsFrameIdRetired(candidate, now))
        {
            continue;
        }
        return candidate;
    }
    return 0U;
}

static PendingTxEntry_t *PendingTx_FindFreeEntry(void)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (!PendingTxTable[i].used)
        {
            return &PendingTxTable[i];
        }
    }
    return NULL;
}

static void PendingTx_RemoveEntry(PendingTxEntry_t *entry, bool retire_frame_id)
{
    uint8_t frame_id;

    if ((entry == NULL) || !entry->used)
    {
        return;
    }

    frame_id = entry->frameId;
    memset(entry, 0, sizeof(*entry));
    if (retire_frame_id)
    {
        PendingTx_RetireFrameId(frame_id);
    }
}

static bool PendingTx_IsEchoMatch(const PendingTxEntry_t *entry, const Mesg_TypeDef *echo)
{
    if ((entry == NULL) || !entry->used || (echo == NULL))
    {
        return false;
    }

    return (entry->frame.ID == echo->ID) &&
           (entry->frame.Code1 == echo->Code1) &&
           (entry->frame.Code2 == echo->Code2) &&
           (entry->frame.Data1 == echo->Data1) &&
           (entry->frame.Data2 == echo->Data2) &&
           (entry->frame.Data3 == echo->Data3) &&
           (entry->frame.Data4 == echo->Data4) &&
           (entry->frame.ACKbyte == echo->ACKbyte) &&
           (entry->frame.ExpandCode == echo->ExpandCode);
}

static void USART_ConfirmBoardEvent(Mesg_TypeDef *mesg)
{
    uint16_t i;

    /* 原样 ACK 只确认线路；现金和关键操作事件需业务层显式持久化确认。 */
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTx_IsEchoMatch(&PendingTxTable[i], mesg))
        {
            if (PendingTxTable[i].durable)
            {
                PendingTxTable[i].lineAcked = true;
            }
            else
            {
                PendingTx_RemoveEntry(&PendingTxTable[i], true);
            }
            return;
        }
    }
}

static void USART_RemoveCashResend(uint16_t sequence)
{
    PendingTxEntry_t *entry = PendingTx_FindCashAccepted(sequence);
    if (entry != NULL)
    {
        PendingTx_RemoveEntry(entry, true);
    }
}

static void USART_RemoveBoardEventResend(uint8_t event_code, uint8_t token)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_PHYSICAL_EVENT) &&
            (PendingTxTable[i].frame.Code2 == event_code) &&
            (PendingTxTable[i].frame.Data1 == token) &&
            USART_IsDurableBoardEvent(event_code))
        {
            /* V2.1 confirms only eventCode + token. V2.2 will use frameId + eventCode + operationSequence. */
            PendingTx_RemoveEntry(&PendingTxTable[i], true);
            return;
        }
    }
}

bool Comm_HasPendingPhysicalTerminal(void)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_PHYSICAL_EVENT) &&
            PendingTx_IsPhysicalTerminalCode(PendingTxTable[i].frame.Code2))
        {
            return true;
        }
    }
    return false;
}

bool Comm_HasPendingDispenseTerminal(void)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_PHYSICAL_EVENT) &&
            PendingTx_IsDispenseTerminalCode(PendingTxTable[i].frame.Code2))
        {
            return true;
        }
    }
    return false;
}

bool Comm_HasPendingCollectTerminal(void)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_PHYSICAL_EVENT) &&
            PendingTx_IsCollectTerminalCode(PendingTxTable[i].frame.Code2))
        {
            return true;
        }
    }
    return false;
}

static void USART1_Deal(void *rx_mesg)
{
    uint32_t data;
    uint32_t value;
    uint8_t token;
    Mesg_TypeDef *mesg = (Mesg_TypeDef *)rx_mesg;

    if (mesg->Code1 == Android_to_Board)
    {
        USART_RequestMesg(&Tx1, mesg);
        if (List_IsExistID(&DealList, mesg->ID) == false)
        {
            token = mesg->Data1;
            value = USART_GetValue24(mesg);
            switch (mesg->Code2)
            {
            case VersionRequest:
                EventGroupSetBits(&Mesg_event, MesgEvent_VersionRequest);
                break;
            case DispenseStart:
                (void)Hardware_StartDispense(token, value);
                break;
            case CollectStart:
                (void)Hardware_StartCollect(token, value);
                break;
            case CollectStop:
                Hardware_StopCollect(token);
                break;
            case Unlock:
                Lock.sw.state = DEVICE_STATE_START;
                EventGroupSetBits(&Mesg_event, MesgEvent_Unlock);
                break;
            case CashAcceptanceApply:
                (void)CashAcceptance_Apply(mesg->Data1, value);
                break;
            case BillReset:
                BillAcceptor_Reset();
                break;
            case CashEventStored:
            {
                uint16_t sequence = (uint16_t)value;
                CashEvent_ConfirmTransport(sequence);
                USART_RemoveCashResend(sequence);
                break;
            }
            case BoardEventStored:
                USART_RemoveBoardEventResend(mesg->Data1, mesg->Data2);
                break;
            case HardwareStatusRequest:
                Hardware_RequestStatus();
                break;
            case BoardRestart:
                data = USART_GetData32(mesg);
                Board_SystemRestart(data == OTA_REQUEST_MAGIC);
                break;
            case EmergencyStop:
                Hardware_AbortAll();
                CashAcceptance_Disable();
                break;
            default:
                break;
            }
            List_AddNode(&DealList, mesg->ID, HAL_GetTick());
        }
    }
    else if (mesg->Code1 == Board_to_Android)
    {
        USART_ConfirmBoardEvent(mesg);
    }
}

static void USART_FillFrameBytes(Mesg_TypeDef *mesg, uint8_t data[14])
{
    uint16_t crc;

    data[0] = Mesg_Head;
    data[1] = mesg->ResendID;
    data[2] = mesg->ID;
    data[3] = mesg->Code1;
    data[4] = mesg->Code2;
    data[5] = mesg->Data1;
    data[6] = mesg->Data2;
    data[7] = mesg->Data3;
    data[8] = mesg->Data4;
    data[9] = mesg->ACKbyte;
    data[10] = mesg->ExpandCode;
    crc = CRC16_calculate(data, 11U);
    data[11] = (uint8_t)(crc >> 8U);
    data[12] = (uint8_t)crc;
    data[13] = Mesg_Tail;
    mesg->Head = Mesg_Head;
    mesg->CRC16_H = data[11];
    mesg->CRC16_L = data[12];
    mesg->Tail = Mesg_Tail;
}

static void USART_TransmitMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg)
{
    uint8_t data[14];

    USART_FillFrameBytes(mesg, data);
    tx->Transimit(tx, data, sizeof(data));
}

static uint8_t USART_SendMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg)
{
    uint8_t frame_id;
    PendingTxEntry_t *entry = NULL;

    if (mesg->ACKbyte == 0x01U)
    {
        entry = PendingTx_FindFreeEntry();
        if (entry == NULL)
        {
            return 0U;
        }
    }

    frame_id = PendingTx_AllocateFrameId(mesg->ACKbyte == 0x01U);
    if (frame_id == 0U)
    {
        return 0U;
    }

    mesg->ResendID = 0U;
    mesg->ID = frame_id;
    USART_TransmitMesg(tx, mesg);

    if (entry != NULL)
    {
        entry->used = true;
        entry->durable = PendingTx_IsDurableCode(mesg->Code2);
        entry->lineAcked = false;
        entry->frameId = frame_id;
        entry->frame = *mesg;
        entry->lastSendTick = HAL_GetTick();
        entry->resendCount = 0U;
        entry->kind = PendingTx_GetKind(mesg->Code2);
    }

    return frame_id;
}

uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *tx,
                               uint8_t code_1,
                               uint8_t code_2,
                               uint32_t data,
                               uint8_t expandCode)
{
    Mesg_TypeDef mesg = {0};
    mesg.Head = Mesg_Head;
    mesg.Code1 = code_1;
    mesg.Code2 = code_2;
    mesg.Data1 = (uint8_t)(data >> 24U);
    mesg.Data2 = (uint8_t)(data >> 16U);
    mesg.Data3 = (uint8_t)(data >> 8U);
    mesg.Data4 = (uint8_t)data;
    mesg.ACKbyte = 0x00U;
    mesg.ExpandCode = expandCode;
    mesg.Tail = Mesg_Tail;
    return USART_SendMesg(tx, &mesg);
}

uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *tx,
                                          uint8_t code_1,
                                          uint8_t code_2,
                                          uint32_t data,
                                          uint8_t expandCode)
{
    uint16_t cash_sequence;
    PendingTxEntry_t *cash_entry;
    Mesg_TypeDef mesg = {0};
    mesg.Head = Mesg_Head;
    mesg.Code1 = code_1;
    mesg.Code2 = code_2;
    mesg.Data1 = (uint8_t)(data >> 24U);
    mesg.Data2 = (uint8_t)(data >> 16U);
    mesg.Data3 = (uint8_t)(data >> 8U);
    mesg.Data4 = (uint8_t)data;
    mesg.ACKbyte = 0x01U;
    mesg.ExpandCode = expandCode;
    mesg.Tail = Mesg_Tail;

    if (code_2 == CashAccepted)
    {
        cash_sequence = ((uint16_t)mesg.Data4 << 8U) | (uint16_t)mesg.ExpandCode;
        cash_entry = PendingTx_FindCashAccepted(cash_sequence);
        if (cash_entry != NULL)
        {
            return cash_entry->frameId;
        }
    }

    return USART_SendMesg(tx, &mesg);
}

static uint8_t USART_ReSendMesg(Tx_HandleTypeDef *tx, PendingTxEntry_t *entry)
{
    if ((entry == NULL) || !entry->used)
    {
        return 1U;
    }

    entry->resendCount++;
    if ((entry->resendCount > Max_Resend_Times) && !entry->durable)
    {
        return 1U;
    }
    if (entry->durable && (entry->resendCount > Max_Resend_Times))
    {
        entry->resendCount = 1U;
    }

    entry->frame.ResendID = entry->resendCount;
    USART_TransmitMesg(tx, &entry->frame);
    return 0U;
}

static void USART_RequestMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg)
{
    uint8_t data[14];
    uint16_t crc;

    data[0] = mesg->Head;
    data[1] = mesg->ResendID;
    data[2] = mesg->ID;
    data[3] = mesg->Code1;
    data[4] = mesg->Code2;
    data[5] = mesg->Data1;
    data[6] = mesg->Data2;
    data[7] = mesg->Data3;
    data[8] = mesg->Data4;
    data[9] = mesg->ACKbyte;
    data[10] = mesg->ExpandCode;
    crc = CRC16_calculate(data, 11U);
    data[11] = (uint8_t)(crc >> 8U);
    data[12] = (uint8_t)crc;
    data[13] = mesg->Tail;
    tx->Transimit(tx, data, sizeof(data));
}

void Resend_Task(void)
{
    uint16_t i;
    uint32_t current_time = HAL_GetTick();
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            ((current_time - PendingTxTable[i].lastSendTick) > ResendTrigger_Time))
        {
            if (USART_ReSendMesg(&Tx1, &PendingTxTable[i]) != 0U)
            {
                PendingTx_RemoveEntry(&PendingTxTable[i], true);
            }
            else
            {
                PendingTxTable[i].lastSendTick = current_time;
            }
        }
    }
}

void MesgDeal_Task(void)
{
    ListNode_t *current = DealList.Head;
    uint32_t current_time = HAL_GetTick();
    while (current != NULL)
    {
        ListNode_t *next = current->Next;
        if (current_time - current->Value > MesgDeal_Time)
        {
            List_DeleteNode(&DealList, current->ID);
        }
        current = next;
    }
}

void Communicate_Init(void)
{
    Rx_InitTypeDef rx_init;
    Tx_InitTypeDef tx_init;

    List_Create(&DealList, DealList_buffer, 100U);
    memset(PendingTxTable, 0, sizeof(PendingTxTable));
    memset(RetiredFrameIds, 0, sizeof(RetiredFrameIds));
    NextFrameId = 0U;
    rx_init.huart = &huart1;
    rx_init.RingBuf = rx1_buffer;
    rx_init.RingBuf_Size = sizeof(rx1_buffer);
    rx_init.Frame_Head = Mesg_Head;
    rx_init.Frame_Tail = Mesg_Tail;
    rx_init.Mesg_Len = sizeof(Mesg_TypeDef);
    rx_init.Receive = Rx_Receive;
    rx_init.Verify = USART_ReceiveMesg_Verify;
    rx_init.Deal = USART1_Deal;
    Communicate_Rx_Init(&Rx1, rx_init);

    tx_init.huart = &huart1;
    tx_init.hdma = NULL;
    tx_init.TxBuf = NULL;
    tx_init.TxBuf_Size = 0U;
    Communicate_Tx_Init(&Tx1, tx_init);
}

void Communicate_Task(void)
{
    Rx1.Receive(&Rx1, &Receive1_mesg, sizeof(Mesg_TypeDef));
}
