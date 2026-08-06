/*
 * 串口通信任务：解析 Android 14 字节协议帧，完成 CRC 校验、命令去重和硬件调度。
 * 对现金事实及出珠终态使用持久重发语义；普通 ACK 只代表线路收到，不代表业务已保存。
 */
#include "CommunicateTask.h"
#include "MesgTask.h"
#include "CtrlTask.h"
#include "KeyTask.h"
#include "app_crc.h"
#include "app_list.h"
#include "string.h"
#include "usart.h"

/* 内部命令处理、重启和可靠事件辅助函数声明。 */
static void USART_RequestMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg);
static bool Board_WriteBootRequest(uint32_t request_magic);
static void Board_SystemRestart(bool enter_bootloader);
static void USART_RemoveCashResend(uint16_t sequence);
static bool USART_IsDurableBoardEvent(uint8_t code2);

/* 待确认发送表容量、帧 ID 退休表容量和退休保护时间。 */
#define PENDING_TX_ENTRY_COUNT 100U
#define RETIRED_FRAME_ID_COUNT 255U
#define FRAME_ID_RETIRE_TIME 5000U

/* 待确认消息类型决定线路 ACK 后是否仍需业务确认。 */
typedef enum
{
    PENDING_TX_KIND_NONE = 0,
    PENDING_TX_KIND_LINE_ACK,
    PENDING_TX_KIND_CASH_EVENT,
    PENDING_TX_KIND_PHYSICAL_EVENT
} PendingTxKind_t;

/* 一条待确认发送记录，保存原始帧和重发状态。 */
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

/* 已释放帧 ID 在保护时间内禁止立即复用，避免迟到 ACK 误匹配新帧。 */
typedef struct
{
    bool used;
    uint8_t frameId;
    uint32_t retiredTick;
} RetiredFrameId_t;

static uint8_t USART_ReSendMesg(Tx_HandleTypeDef *tx, PendingTxEntry_t *entry);

/* Android 命令 ID 去重列表。 */
ListHandle_t DealList;
static ListNode_t DealList_buffer[100];

/* 可靠发送表、退休帧 ID 表和 USART1 接收缓冲区。 */
static PendingTxEntry_t PendingTxTable[PENDING_TX_ENTRY_COUNT];
static RetiredFrameId_t RetiredFrameIds[RETIRED_FRAME_ID_COUNT];
static uint8_t NextFrameId = 0U;
static uint8_t rx1_buffer[512];
static Mesg_TypeDef Receive1_mesg;

Tx_HandleTypeDef Tx1;
Rx_HandleTypeDef Rx1;

extern Event_Handle_t Mesg_event;
extern Lock_t Lock;

/* 将是否进入 Bootloader 的请求写入 RTC 备份寄存器，并回读确认。 */
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

/* 安全停止执行机构和现金入口后复位 MCU。 */
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

/* 对接收帧字节 0～10 计算 CRC16，并与帧中高低字节比较。 */
static bool USART_ReceiveMesg_Verify(void *self, void *mesg)
{
    Rx_HandleTypeDef *rx = (Rx_HandleTypeDef *)self;
    Mesg_TypeDef *rx_mesg = (Mesg_TypeDef *)mesg;
    uint16_t crc16 = CRC16_calculate(rx->Queue.Buf, 11U);
    uint16_t mesg_crc16 = ((uint16_t)rx_mesg->CRC16_H << 8U) | rx_mesg->CRC16_L;
    return crc16 == mesg_crc16;
}

/* 从 Data1～Data4 读取一个大端 32 位值。 */
static uint32_t USART_GetData32(Mesg_TypeDef *mesg)
{
    return ((uint32_t)mesg->Data1 << 24U) |
           ((uint32_t)mesg->Data2 << 16U) |
           ((uint32_t)mesg->Data3 << 8U) |
           (uint32_t)mesg->Data4;
}

/* 从 Data2～Data4 读取 24 位配置值。 */
static uint32_t USART_GetValue24(Mesg_TypeDef *mesg)
{
    return ((uint32_t)mesg->Data2 << 16U) |
           ((uint32_t)mesg->Data3 << 8U) |
           (uint32_t)mesg->Data4;
}

/* 现金事件序号由 Data4 和 ExpandCode 组成。 */
static uint16_t USART_GetCashSequence(const Mesg_TypeDef *mesg)
{
    return ((uint16_t)mesg->Data4 << 8U) |
           (uint16_t)mesg->ExpandCode;
}

/* 出珠终态属于业务持久事件，线路 ACK 后仍需 Android 明确确认。 */
static bool USART_IsDurableBoardEvent(uint8_t code2)
{
    return code2 == DispenseTerminal;
}

static bool PendingTx_IsDurableCode(uint8_t code2)
{
    return (code2 == CashAccepted) || USART_IsDurableBoardEvent(code2);
}

/* 根据事件码选择普通线路 ACK、现金事实或物理终态类型。 */
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

/* 按现金序号查找已经入队的同一现金事实，防止重复创建发送帧。 */
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

/* 出珠协议数据的高 16 位为订单序号。 */
static uint16_t USART_GetOrderSequence(const Mesg_TypeDef *mesg)
{
    return ((uint16_t)mesg->Data1 << 8U) | (uint16_t)mesg->Data2;
}

/* 出珠协议数据的低 16 位为应出或实出数量。 */
static uint16_t USART_GetOrderValue(const Mesg_TypeDef *mesg)
{
    return ((uint16_t)mesg->Data3 << 8U) | (uint16_t)mesg->Data4;
}

/* 按订单序号、实际数量和结果码查找已经入队的同一出珠终态。 */
static PendingTxEntry_t *PendingTx_FindDispenseTerminal(uint16_t order_sequence,
                                                        uint16_t actual_quantity,
                                                        uint8_t result_code)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_PHYSICAL_EVENT) &&
            (PendingTxTable[i].frame.Code2 == DispenseTerminal) &&
            (USART_GetOrderSequence(&PendingTxTable[i].frame) == order_sequence) &&
            (USART_GetOrderValue(&PendingTxTable[i].frame) == actual_quantity) &&
            (PendingTxTable[i].frame.ExpandCode == result_code))
        {
            return &PendingTxTable[i];
        }
    }
    return NULL;
}

/* 检查帧 ID 是否仍被待确认发送表占用。 */
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

/* 检查帧 ID 是否仍处于释放后的保护时间内。 */
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

/* 将已释放帧 ID 放入退休表，表满时复用最旧项。 */
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

/* 分配 1～255 的帧 ID，可选择跳过仍在退休保护期内的 ID。 */
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

/* 从固定发送表中找到一个空闲记录。 */
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

/* 删除待确认记录，并按需将原帧 ID 放入退休保护表。 */
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

/* 原样比较业务字段，确认收到的是某条发送帧的线路回显。 */
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

/* 处理 Android 对控制板上报帧的原样线路回显。 */
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

/* Android 明确保存现金事实后，按现金序号删除对应重发项。 */
static void USART_RemoveCashResend(uint16_t sequence)
{
    PendingTxEntry_t *entry = PendingTx_FindCashAccepted(sequence);
    if (entry != NULL)
    {
        PendingTx_RemoveEntry(entry, true);
    }
}

/* Android 明确保存出珠终态后，按订单序号和终态帧 ID 删除重发项。 */
void Comm_RemoveDispenseTerminal(uint16_t order_sequence, uint8_t terminal_frame_id)
{
    uint16_t i;
    for (i = 0U; i < PENDING_TX_ENTRY_COUNT; i++)
    {
        if (PendingTxTable[i].used &&
            (PendingTxTable[i].kind == PENDING_TX_KIND_PHYSICAL_EVENT) &&
            (PendingTxTable[i].frame.Code2 == DispenseTerminal) &&
            (PendingTxTable[i].frameId == terminal_frame_id) &&
            (USART_GetOrderSequence(&PendingTxTable[i].frame) == order_sequence))
        {
            PendingTx_RemoveEntry(&PendingTxTable[i], true);
            return;
        }
    }
}

/*
 * 处理一帧已通过校验的 USART1 消息。
 * Android 命令先原样回显，再按消息 ID 去重并分发到硬件、现金或重启接口。
 */
static void USART1_Deal(void *rx_mesg)
{
    uint32_t data;
    uint32_t value;
    uint16_t order_sequence;
    uint16_t order_value;
    Mesg_TypeDef *mesg = (Mesg_TypeDef *)rx_mesg;

    if (mesg->Code1 == Android_to_Board)
    {
        USART_RequestMesg(&Tx1, mesg);
        if (List_IsExistID(&DealList, mesg->ID) == false)
        {
            order_sequence = USART_GetOrderSequence(mesg);
            order_value = USART_GetOrderValue(mesg);
            value = USART_GetValue24(mesg);
            switch (mesg->Code2)
            {
            case VersionRequest:
                EventGroupSetBits(&Mesg_event, MesgEvent_VersionRequest);
                break;
            case DispenseStartOrder:
                (void)Hardware_StartDispenseOrder(order_sequence, order_value);
                break;
            case CollectStart:
                (void)Hardware_StartCollect(value);
                break;
            case CollectStop:
                Hardware_StopCollect();
                break;
            case Unlock:
                Lock.sw.state = DEVICE_STATE_START;
                EventGroupSetBits(&Mesg_event, MesgEvent_Unlock);
                break;
            case CashAcceptanceApply:
                (void)CashAcceptance_Apply(mesg->Data1, value);
                break;
            case CashAcceptanceApplyV22:
                (void)CashAcceptance_ApplyV22(mesg->Data1, value);
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
            case DispenseTerminalAck:
                Hardware_ConfirmDispenseTerminal(order_sequence, mesg->Data3);
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

/* 将消息结构展开为 14 字节帧并计算 CRC16。 */
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

/* 填充帧字节后通过底层发送接口输出。 */
static void USART_TransmitMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg)
{
    uint8_t data[14];

    USART_FillFrameBytes(mesg, data);
    tx->Transimit(tx, data, sizeof(data));
}

/* 分配帧 ID；需要 ACK 的消息先占用发送表，再进行实际发送。 */
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

    USART_TransmitMesg(tx, mesg);
    return frame_id;
}

/* 组装并发送不需要 ACK 的普通协议帧。 */
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

/* 组装并发送需要可靠确认的协议帧；同一现金序号复用已有帧。 */
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

/* 发送或重发同一出珠终态，返回其稳定帧 ID。 */
uint8_t Comm_SendDispenseTerminal(uint16_t order_sequence,
                                  uint16_t actual_quantity,
                                  uint8_t result_code)
{
    PendingTxEntry_t *entry = PendingTx_FindDispenseTerminal(order_sequence,
                                                             actual_quantity,
                                                             result_code);
    uint32_t data = ((uint32_t)order_sequence << ORDER_DATA_SEQUENCE_SHIFT) |
                    (uint32_t)actual_quantity;

    if (entry != NULL)
    {
        (void)USART_ReSendMesg(&Tx1, entry);
        entry->lastSendTick = HAL_GetTick();
        return entry->frameId;
    }

    return Comm_SendMesg_FillData_withResend(&Tx1,
                                             Board_to_Android,
                                             DispenseTerminal,
                                             data,
                                             result_code);
}

/* 发送一次性出珠拒绝结果，不占用可靠发送表。 */
uint8_t Comm_SendDispenseTerminalOnce(uint16_t order_sequence,
                                      uint16_t actual_quantity,
                                      uint8_t result_code)
{
    uint32_t data = ((uint32_t)order_sequence << ORDER_DATA_SEQUENCE_SHIFT) |
                    (uint32_t)actual_quantity;

    return Comm_SendMesg_FillData(&Tx1,
                                  Board_to_Android,
                                  DispenseTerminal,
                                  data,
                                  result_code);
}

/* 增加 ResendID 并重发；普通消息超过次数后结束，持久事件循环重发。 */
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

/* 对 Android 命令原样回传应答，并重新计算 CRC。 */
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

/* 周期检查发送表，并按重发周期处理所有超时项。 */
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

/* 清除超过去重窗口的 Android 命令 ID，使帧 ID 可以在未来安全复用。 */
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

/* 初始化命令去重表、可靠发送表以及 USART1 收发对象。 */
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

/* 主循环从 USART1 环形缓冲区尝试提取并处理一帧消息。 */
void Communicate_Task(void)
{
    Rx1.Receive(&Rx1, &Receive1_mesg, sizeof(Mesg_TypeDef));
}
