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

ListHandle_t ResendList, DealList;
static ListNode_t ResendList_buffer[100];
static ListNode_t DealList_buffer[100];

static Mesg_TypeDef MesgTable[256];
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

static void USART_ConfirmBoardEvent(Mesg_TypeDef *mesg)
{
    Mesg_TypeDef *original = &MesgTable[mesg->ID];

    /*
     * 普通原样回传只确认 UART 链路。现金事实必须等 Android 写入持久化存储后，
     * 再发送 CashEventStored；因此此处不能清除现金重发或控制板 Flash 记录。
     */
    if (original->Code2 == CashAccepted)
    {
        return;
    }
    List_DeleteNode(&ResendList, mesg->ID);
}

static void USART_RemoveCashResend(uint16_t sequence)
{
    ListNode_t *current = ResendList.Head;
    while (current != NULL)
    {
        ListNode_t *next = current->Next;
        Mesg_TypeDef *original = &MesgTable[current->ID];
        if ((original->Code2 == CashAccepted) &&
            (USART_GetCashSequence(original) == sequence))
        {
            List_DeleteNode(&ResendList, current->ID);
        }
        current = next;
    }
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

static uint8_t USART_SendMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg)
{
    static uint8_t id = 0U;
    uint8_t data[14];
    uint16_t crc;

    id++;
    mesg->ResendID = 0U;
    mesg->ID = id;
    MesgTable[id] = *mesg;
    memcpy(data, mesg, sizeof(data));
    crc = CRC16_calculate(data, 11U);
    data[11] = (uint8_t)(crc >> 8U);
    data[12] = (uint8_t)crc;
    tx->Transimit(tx, data, sizeof(data));
    return id;
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
                                          uint8_t expandCode,
                                          ListHandle_t *list)
{
    uint8_t id;
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
    id = USART_SendMesg(tx, &mesg);
    List_AddNode(list, id, HAL_GetTick());
    return id;
}

static uint8_t USART_ReSendMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg)
{
    uint8_t data[14];
    uint16_t crc;

    mesg->ResendID++;
    if ((mesg->ResendID > Max_Resend_Times) &&
        (mesg->Code2 != CashAccepted))
    {
        return 1U;
    }
    if (mesg->Code2 == CashAccepted &&
        mesg->ResendID > Max_Resend_Times)
    {
        mesg->ResendID = 1U;
    }

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
    tx->Transimit(tx, data, sizeof(data));
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
    ListNode_t *current = ResendList.Head;
    uint32_t current_time = HAL_GetTick();
    while (current != NULL)
    {
        ListNode_t *next = current->Next;
        if (current_time - current->Value > ResendTrigger_Time)
        {
            USART_ReSendMesg(&Tx1, &(MesgTable[current->ID]));
            current->Value = current_time;
            if ((MesgTable[current->ID].Code2 != CashAccepted) &&
                (MesgTable[current->ID].ResendID >= Max_Resend_Times))
            {
                List_DeleteNode(&ResendList, current->ID);
            }
        }
        current = next;
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

    List_Create(&ResendList, ResendList_buffer, 100U);
    List_Create(&DealList, DealList_buffer, 100U);
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
