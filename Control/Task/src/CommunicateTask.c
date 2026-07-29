#include "CommunicateTask.h"
#include "MesgTask.h"
#include "CtrlTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "app_crc.h"
#include "app_list.h"
#include "string.h"
#include "usart.h"

static void USART_RequestMesg(Tx_HandleTypeDef *tx, Mesg_TypeDef *mesg);
static bool Board_WriteBootRequest(uint32_t request_magic);
static void Board_SystemRestart(bool enter_bootloader);
static void Purchase_AddPaidOutput(uint32_t bead_count);
static void Collection_StopImmediately(void);

ListHandle_t ResendList, DealList;
static ListNode_t ResendList_buffer[100];
static ListNode_t DealList_buffer[100];

static Mesg_TypeDef MesgTable[256];
static uint8_t rx1_buffer[512];
static Mesg_TypeDef Receive1_mesg;

Tx_HandleTypeDef Tx1;
Rx_HandleTypeDef Rx1;

extern Event_Handle_t Mesg_event;
extern BeadMotor_t BeadMotor1;
extern BeadMotor_t BeadMotor2;
extern Lock_t Lock;
extern Setting_TypeDef Setting;

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
    HAL_Delay(100U);
    NVIC_SystemReset();
    while (1)
    {
    }
}

/*
 * 平台固定数量出珠仍通过安卓下发数量，数量进入原有掉电保存欠吐队列。
 * 本地现金购买继续由控制板独立换算和执行，安卓只接收现金事实上报。
 */
static void Purchase_AddPaidOutput(uint32_t bead_count)
{
    if (bead_count == 0U)
    {
        return;
    }
    if (bead_count > (0xFFFFFFFFUL - Setting.PendingBeads))
    {
        Setting.PendingBeads = 0xFFFFFFFFUL;
    }
    else
    {
        Setting.PendingBeads += bead_count;
    }
    FlashTask_RequestSave();
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
    if ((Setting.BeadStock == 0U) ||
        ((Setting.PurchaseFlags & PURCHASE_FLAG_NO_BEAD) != 0U))
    {
        Purchase_OnDispenseTimeout();
    }
}

static void Collection_StopImmediately(void)
{
    /* 仅停止存珠电机，不影响本地现金吐珠和电子锁。 */
    BeadMotor2.motor.LosePower(&BeadMotor2.motor);
    BeadMotor2.motor.state = DEVICE_STATE_IDLE;
    BeadMotor2.remain_num = 0U;
    BeadMotor2.retry_count = 0U;
    EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
}

static bool USART_ReceiveMesg_Verify(void *self, void *mesg)
{
    Rx_HandleTypeDef *rx = (Rx_HandleTypeDef *)self;
    Mesg_TypeDef *rx_mesg = (Mesg_TypeDef *)mesg;
    uint16_t crc16 = CRC16_calculate(rx->Queue.Buf, 11U);
    uint16_t mesg_crc16 = ((uint16_t)rx_mesg->CRC16_H << 8U) | rx_mesg->CRC16_L;
    return crc16 == mesg_crc16;
}

static uint16_t USART_GetData16(Mesg_TypeDef *mesg)
{
    return ((uint16_t)mesg->Data3 << 8U) | mesg->Data4;
}

static uint32_t USART_GetData32(Mesg_TypeDef *mesg)
{
    return ((uint32_t)mesg->Data1 << 24U) |
           ((uint32_t)mesg->Data2 << 16U) |
           ((uint32_t)mesg->Data3 << 8U) |
           (uint32_t)mesg->Data4;
}

static void USART1_Deal(void *rx_mesg)
{
    uint32_t data;
    uint8_t medium;
    uint32_t amount_yuan;
    Mesg_TypeDef *mesg = (Mesg_TypeDef *)rx_mesg;

    if (mesg->Code1 == Android_to_Board)
    {
        /* 合法帧先原样应答；相同 ID 在去重窗口内不重复执行业务。 */
        USART_RequestMesg(&Tx1, mesg);
        if (List_IsExistID(&DealList, mesg->ID) == false)
        {
            switch (mesg->Code2)
            {
            case VersionRequest:
                EventGroupSetBits(&Mesg_event, MesgEvent_VersionRequest);
                break;
            case BeadMotor1Output:
                if (Purchase_GetBeadStock() > 0U)
                {
                    BeadMotor_Output(&BeadMotor1, USART_GetData16(mesg));
                    EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
                }
                else
                {
                    EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
                }
                break;
            case BeadMotor2Output:
                /* 用户在屏幕确认倒珠完成后，安卓才发送本命令启动存珠。 */
                BeadMotor_Output(&BeadMotor2, USART_GetData16(mesg));
                EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
                break;
            case BeadMotor2Stop:
                Collection_StopImmediately();
                break;
            case Unlock:
                Lock.sw.state = DEVICE_STATE_START;
                EventGroupSetBits(&Mesg_event, MesgEvent_Unlock);
                break;
            case BillCurrencyModeSet:
                BillAcceptor_SetCurrencyMode(mesg->Data4);
                break;
            case BillEnable:
                if (Purchase_GetBeadStock() > 0U)
                {
                    BillAcceptor_SetEnable(true);
                }
                else
                {
                    EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
                }
                break;
            case BillDisable:
                BillAcceptor_SetEnable(false);
                break;
            case BillReset:
                BillAcceptor_Reset();
                break;
            case CoinEnable:
                CoinAcceptor_SetEnable(true);
                break;
            case CoinDisable:
                CoinAcceptor_SetEnable(false);
                break;
            case CashReturnRequest:
                /* Data1=介质，Data2:Data4=整数人民币元。 */
                data = USART_GetData32(mesg);
                medium = (uint8_t)(data >> 24U);
                amount_yuan = data & 0x00FFFFFFUL;
                (void)CashHardware_RequestReturn(medium, amount_yuan);
                break;
            case BeadPriceSet:
                /* 本地现金单颗价格仍按“分”保存，避免浮点。 */
                Purchase_SetBeadPrice(USART_GetData32(mesg));
                break;
            case PurchaseStatusRequest:
                Purchase_RequestStatus();
                break;
            case PaidPurchaseOutput:
                /* 平台业务固定数量出珠；现金购珠不使用此命令。 */
                Purchase_AddPaidOutput(USART_GetData32(mesg));
                break;
            case BoardRestart:
                data = USART_GetData32(mesg);
                Board_SystemRestart(data == OTA_REQUEST_MAGIC);
                break;
            case StopAllDevice:
                Device_StopAllImmediately();
                Purchase_PauseDispense();
                EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
                break;
            default:
                break;
            }
            List_AddNode(&DealList, mesg->ID, HAL_GetTick());
        }
    }
    else if (mesg->Code1 == Board_to_Android)
    {
        List_DeleteNode(&ResendList, mesg->ID);
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
    if (mesg->ResendID > Max_Resend_Times)
    {
        return 1U;
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
            if (MesgTable[current->ID].ResendID >= Max_Resend_Times)
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
