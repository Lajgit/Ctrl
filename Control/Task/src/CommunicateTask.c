#include "CommunicateTask.h"
#include "MesgTask.h"
#include "CtrlTask.h"
#include "KeyTask.h"
#include "MainTask.h"
#include "LightTask.h"
#include "FlashTask.h"
#include "app_crc.h"
#include "app_list.h"
#include "port_digitaltube.h"
#include "string.h"
#include "usart.h"

static void USART_RequestMesg(Tx_HandleTypeDef *Tx, Mesg_TypeDef *mesg);
static bool Board_WriteBootRequest(uint32_t request_magic);
static void Board_SystemRestart(bool enter_bootloader);

ListHandle_t ResendList, DealList;
static ListNode_t ResendList_buffer[100];
static ListNode_t DealList_buffer[100];

static Mesg_TypeDef MesgTable[256];
static uint8_t rx1_buffer[512];
static uint8_t rx3_buffer[512];
static Mesg_TypeDef Receive1_mesg;
static Mesg_TypeDef Receive3_mesg;

Tx_HandleTypeDef Tx1;
Tx_HandleTypeDef Tx3;
Rx_HandleTypeDef Rx1;
Rx_HandleTypeDef Rx3;

extern DMA_HandleTypeDef hdma_usart1_rx;
extern Event_Handle_t Mesg_event;
extern Event_Handle_t Key_event;
extern Event_Handle_t Event;
extern Motor_Hoolle Motor_Hoolle1;
extern Motor_Card Card;
extern servo_t Servo;
extern Switch_Valve Lock_Valve, Valve;
extern ListHandle_t ResendList, DealList;
extern Scene_t Scene;
extern Light_t Light;
extern DigitalTube_t DigitalTube;
extern DigitalTube_t DigitalTubeOutside;
extern BreathLight_t *BreathList[];
extern Setting_TypeDef Setting;

static bool Board_WriteBootRequest(uint32_t request_magic)
{
    uint32_t timeout;
    uint32_t retry;

    /*
     * 使能PWR时钟，并回读寄存器，确保时钟已经真正生效。
     */
    __HAL_RCC_PWR_CLK_ENABLE();
    __DSB();
    (void)RCC->APB1ENR;

    /*
     * 打开RTC备份域写权限。
     */
    HAL_PWR_EnableBkUpAccess();

    /*
     * 等待DBP位真正置位。
     * 使用超时而不是无限等待，避免异常情况下卡死整个APP。
     */
    timeout = 100000U;

    while (((PWR->CR & PWR_CR_DBP) == 0U) &&
           (timeout > 0U))
    {
        timeout--;
    }

    if (timeout == 0U)
    {
        return false;
    }

    /*
     * 使能RTC接口，并回读BDCR。
     */
    __HAL_RCC_RTC_ENABLE();
    __DSB();
    (void)RCC->BDCR;

    /*
     * 写入后读回确认。
     * 最多尝试3次，只有确认BKP0R内容正确才返回成功。
     */
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
    uint32_t request_magic;

    request_magic = enter_bootloader ?
                    OTA_REQUEST_MAGIC :
                    0U;

    /*
     * 必须先确认备份寄存器写入成功。
     * 写入失败时不复位，否则Bootloader只会按普通启动处理。
     */
    if (!Board_WriteBootRequest(request_magic))
    {
        return;
    }

    Device_StopAllImmediately();

    /*
     * 这里不再调用HAL_PWR_DisableBkUpAccess()。
     * 因为马上进行系统复位，避免关闭DBP与复位之间产生额外状态变化。
     */

    /*
     * 原14字节命令已经在调用本函数前原样回发，
     * 保留发送完成时间。
     */
    HAL_Delay(100U);

    NVIC_SystemReset();

    while (1)
    {
    }
}

/// 串口处理
/// 串口消息验证
static bool USART_ReceiveMesg_Verify(void *self, void *mesg)
{
    Rx_HandleTypeDef *rx = (Rx_HandleTypeDef *)self;
    Mesg_TypeDef *Rx_mesg = (Mesg_TypeDef *)mesg;
    uint16_t crc16, mesg_crc16;
    crc16 = CRC16_calculate(rx->Queue.Buf, 11);
    mesg_crc16 = Rx_mesg->CRC16_H << 8 | Rx_mesg->CRC16_L;
    if (crc16 == mesg_crc16)
        return true;
    return false;
}
/// 串口1消息处理
static void USART1_Deal(void *Rx_mesg)
{
    uint32_t data;
    Mesg_TypeDef *mesg = (Mesg_TypeDef *)Rx_mesg;
    if (mesg->Code1 == Android_to_Board)
    {
        USART_RequestMesg(&Tx1, mesg);
        if (List_IsExistID(&DealList, mesg->ID) == false)
        {
            switch (mesg->Code2)
            {
            /// 版本请求
            case VersionRequest:
                EventGroupSetBits(&Mesg_event, MesgEvent_VersionRequest);
                break;
            /// 吐珠
            case HoolleOutput:
                data = ((mesg->Data3 << 8) | mesg->Data4);
                if (EventGroupCheckBits(&Key_event, Event_AllHoleSwitchTrigger) == false)
                    Hoolle_Output(&Motor_Hoolle1, data);
                else
                    Motor_Hoolle1.Hoolle_num += data;
                EventGroupSetBits(&Mesg_event, MesgEvent_RemainingHoolle);
                break;
            /// 吐卡
            case CardOutput:
                data = ((mesg->Data3 << 8) | mesg->Data4);
                Card_Output(&Card, data);
                break;
            /// 球盘亮度
            case BoardLightness:
                Setting.Board_Lightness = mesg->Data4;
                SemaphoreGive(Light.Semaphore);
                Light.Init = true;
                break;
            /// 灯带亮度
            case LightBeltLightness:
                Setting.LightBelt_Lightness = mesg->Data4;
                BreathLight_RefreshState(BreathList, 4);
                Comm_SendMesg_FillData(&Tx3, Board_to_Ctrl, 0x02, mesg->Data4, 0x00); // 发送给控台板
                break;
            /// 控台亮度
            case CtrlLightness:
                Comm_SendMesg_FillData(&Tx3, Board_to_Ctrl, 0x04, mesg->Data4, 0x00); // 控台亮度
                break;
                /// 控台灯光分组
            case CtrlTubeLight:
                data = ((uint32_t)mesg->Data3 << 8) | mesg->Data4;
                Comm_SendMesg_FillData(&Tx3, Board_to_Ctrl, 0x05, data, mesg->ExpandCode);

                break;
            /// 场景切换
            case SceneChange:
                Comm_SendMesg_FillData(&Tx3,Board_to_Ctrl,0x03, mesg->Data4,0x00);
                Scene = mesg->Data4;
                break;
            /// 清珠
            case OutputAllHoolle:
                Hoolle_Output(&Motor_Hoolle1, 0xFFFF - Motor_Hoolle1.Hoolle_num);
                break;
            /// 吐出剩余
            case OutputRemainingItem:
                Hoolle_Output(&Motor_Hoolle1, 0);
                Card_Output(&Card, 0);
                break;
            /// 恢复默认设置
            case ResumeDefultSetting:
                ResumeSetting();
                break;
            /// 保存设置
            case SaveSetting:
                EventGroupSetBits(&Event, Event_SaveSetting);
                break;
            ///  洞内电磁阀触发
            case HoleValveTrigger:
                Valve.Switch.state = DEVICE_STATE_START;
                break;
            /// 开锁
            case Unlock:
                Lock_Valve.Switch.state = DEVICE_STATE_START;
                EventGroupSetBits(&Mesg_event, MesgEvent_Unlock);
                break;
            /// 设置数码管数据
            case DigitalTubeData:
                data = ((mesg->Data3 << 8) | mesg->Data4);
                if (mesg->ExpandCode == 0x00)
                {
                    DigitalTube.Set_Num(&DigitalTube, 13, mesg->Data4, 1);
                    DigitalTube.Set_Num(&DigitalTube, 12, mesg->Data3, 1);
                    DigitalTube.Set_Num(&DigitalTube, 11, mesg->Data2, 1);
                    DigitalTube.Set_Num(&DigitalTube, 10, mesg->Data1, 1);
                }
                else if (mesg->ExpandCode == 0x01)
                {
                    DigitalTube.Set_Num(&DigitalTube, 9, mesg->Data4, 1);
                    DigitalTube.Set_Num(&DigitalTube, 8, mesg->Data3, 1);
                    DigitalTube.Set_Num(&DigitalTube, 7, mesg->Data2, 1);
                    DigitalTube.Set_Num(&DigitalTube, 6, mesg->Data1, 1);
                }
                else if (mesg->ExpandCode == 0x02)
                {
                    DigitalTube.Set_Num(&DigitalTube, 5, mesg->Data4, 1);
                    DigitalTube.Set_Num(&DigitalTube, 4, mesg->Data3, 1);
                    DigitalTube.Set_Num(&DigitalTube, 3, mesg->Data2, 1);
                    DigitalTube.Set_Num(&DigitalTube, 2, mesg->Data1, 1);
                }
                else if (mesg->ExpandCode == 0x03)
                {
                    // uint32_t temp1 = mesg->Data4 % 10; // 取个位
                    // uint32_t temp2 = mesg->Data4 / 10; // 取十位
                    // data = temp1 * 10 + temp2;
                    // DigitalTube.Set_Num(&DigitalTube, 0, data, 2);
                    DigitalTube.Set_Num(&DigitalTube, 0, mesg->Data4, 2);
                }
                else if (mesg->ExpandCode == 0x04)
                {
                    uint32_t data = mesg->Data1 << 24 | mesg->Data2 << 16 | mesg->Data3 << 8 | mesg->Data4;
                    DigitalTubeOutside.Set_Num(&DigitalTubeOutside, 0, data, 4);
                    DigitalTubeOutside.Refresh(&DigitalTubeOutside);
                    Comm_SendMesg_FillData(&Tx3, Board_to_Ctrl, 0x01, data, 0x00); // 发送给控台板
                }
                EventGroupSetBits(&Mesg_event, MesgEvent_DigitalTubeRefresh);
                // EventGroupSetBits(&Event, Event_SaveSetting);
                break;
                /// 舵机归零
            case r_ServoReset:
                Servo.SetAngle(&Servo, 90);
                break;
            /// 球盘重启：数据全0为普通重启，Data1~4=PANT时进入串口升级Bootloader
            case BoardRestart:
                data = ((uint32_t)mesg->Data1 << 24U) |
                       ((uint32_t)mesg->Data2 << 16U) |
                       ((uint32_t)mesg->Data3 << 8U) |
                       (uint32_t)mesg->Data4;
                Board_SystemRestart(data == OTA_REQUEST_MAGIC);
                break;

            /// 停止所有设备
            case StopAllDevice:
                Motor_Hoolle1.Motor.state = DEVICE_STATE_STOP;
                Card.Switch.state = DEVICE_STATE_STOP;
                EventGroupSetBits(&Mesg_event, MesgEvent_RemainingHoolle);
                break;
            }
            /// 将该消息包加入已处理列表，防止短时间内重复处理同样ID的消息包
            List_AddNode(&DealList, mesg->ID, HAL_GetTick());
        }
    }
    /// 收到的是应答
    else if (mesg->Code1 == Board_to_Android)
    {
        /// 重发列表中去除该消息包
        List_DeleteNode(&ResendList, mesg->ID);
    }
}

/// 串口3消息处理
static void USART3_Deal(void *Rx_mesg)
{
    Mesg_TypeDef *mesg = (Mesg_TypeDef *)Rx_mesg;
    if (mesg->Code1 == Ctrl_to_Board)
    {
        switch (mesg->Code2)
        {
        /// 按键
        case 0x01:
            Comm_SendMesg_FillData(&Tx1, Board_to_Android, Button, mesg->Data4, mesg->ExpandCode);
            break;
        /// 键盘
        case 0x02:
            Comm_SendMesg_FillData(&Tx1, Board_to_Android, 0x15, mesg->Data4, mesg->ExpandCode);
            break;
        /// 编码器
        case 0x03:
            if (mesg->ExpandCode == 0x00)
            {
                Servo.DecreaseAngle(&Servo, 1);
                Comm_SendMesg_FillData(&Tx1, Board_to_Android, Encoder, 0x00, 0x01);
            }
            else if (mesg->ExpandCode == 0x01)
            {
                Servo.IncreaseAngle(&Servo, 1);
                Comm_SendMesg_FillData(&Tx1, Board_to_Android, Encoder, 0x00, 0x00);
                
            }
            else if (mesg->ExpandCode == 0x02)
                Servo.SetAngle(&Servo, 90);
            break;
        }
    }
}

///====================================================================================

/// 发送消息，无重传
static uint8_t USART_SendMesg(Tx_HandleTypeDef *Tx, Mesg_TypeDef *mesg)
{
    static uint8_t ID = 0;
    uint8_t data[14];
    uint16_t crc;

    ID++;                  // 每次发送新消息都会自增
    mesg->ResendID = 0;    // 重发次数清零
    mesg->ID = ID;         // 赋予新ID号
    MesgTable[ID] = *mesg; // 保存消息包
    memcpy(data, mesg, 14);
    crc = CRC16_calculate(data, 11);
    data[11] = crc >> 8;
    data[12] = crc;
    Tx->Transimit(Tx, data, 14);
    // HAL_UART_Transmit(Tx->huart, data, 14, 100);
    return ID;
}
/// 填入参数发送消息，无重传
uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode)
{
    Mesg_TypeDef mesg = {0};
    mesg.Head = Mesg_Head;
    mesg.ResendID = 0;
    mesg.ID = 0;
    mesg.Code1 = code_1;
    mesg.Code2 = code_2;
    mesg.Data1 = (uint8_t)(data >> 24);
    mesg.Data2 = (uint8_t)(data >> 16);
    mesg.Data3 = (uint8_t)(data >> 8);
    mesg.Data4 = (uint8_t)(data);
    mesg.ACKbyte = 0x00;
    mesg.ExpandCode = expandCode;
    mesg.Tail = Mesg_Tail;
    return USART_SendMesg(Tx, &mesg);
}

/// 填充数据发送消息并加入重发列表
uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode, ListHandle_t *List)
{
    uint8_t ID;
    Mesg_TypeDef mesg = {0};
    mesg.Head = Mesg_Head;
    mesg.ResendID = 0;
    mesg.ID = 0;
    mesg.Code1 = code_1;
    mesg.Code2 = code_2;
    mesg.Data1 = (uint8_t)(data >> 24);
    mesg.Data2 = (uint8_t)(data >> 16);
    mesg.Data3 = (uint8_t)(data >> 8);
    mesg.Data4 = (uint8_t)(data);
    mesg.ACKbyte = 0x01;
    mesg.ExpandCode = expandCode;
    mesg.Tail = Mesg_Tail;
    ID = USART_SendMesg(Tx, &mesg);
    List_AddNode(List, ID, HAL_GetTick());
    return ID;
}

/// 发送重发消息
static uint8_t USART_ReSendMesg(Tx_HandleTypeDef *Tx, Mesg_TypeDef *mesg)
{
    uint8_t data[14];
    uint16_t crc;
    mesg->ResendID++;
    if (mesg->ResendID > Max_Resend_Times)
        return 1;
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
    crc = CRC16_calculate(data, 11);
    data[11] = crc >> 8;
    data[12] = crc;
    data[13] = Mesg_Tail;
    Tx->Transimit(Tx, data, 14);
    // HAL_UART_Transmit(Tx->huart, data, 14, 100);
    return 0;
}
/// 发送应答消息
static void USART_RequestMesg(Tx_HandleTypeDef *Tx, Mesg_TypeDef *mesg)
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
    crc = CRC16_calculate(data, 11);
    data[11] = crc >> 8;
    data[12] = crc;
    data[13] = Mesg_Tail;
    Tx->Transimit(Tx, data, 14);
    // HAL_UART_Transmit(Tx->huart, data, 14, 100);
}

/// ----------检测重发消息----------
void Resend_Task(void)
{
    ListNode_t *Current = ResendList.Head;
    uint32_t CurrentTime = HAL_GetTick();
    for (uint8_t i = 0; i < ResendList.NodeCount; i++)
    {
        // 超时时间内未收到应答，立即重发
        if (CurrentTime - Current->Value > ResendTrigger_Time)
        {
            USART_ReSendMesg(&Tx1, &(MesgTable[Current->ID]));
            Current->Value = CurrentTime;
            // 如果重发次数达到最大次数，则从重发列表中删除
            if (MesgTable[Current->ID].ResendID >= Max_Resend_Times)
                List_DeleteNode(&ResendList, Current->ID);
        }
        Current = Current->Next;
    }
}

/// ----------清除已执行消息任务----------
void MesgDeal_Task(void)
{
    ListNode_t *Current = DealList.Head;
    uint32_t CurrentTime = HAL_GetTick();
    for (uint8_t i = 0; i < DealList.NodeCount; i++)
    {
        // 达到超时时间则从列表中删除，表示可接收同样ID的新消息
        if (CurrentTime - Current->Value > MesgDeal_Time)
            List_DeleteNode(&DealList, Current->ID);
        Current = Current->Next;
    }
}

/*
 * ----------通信初始化----------
 */
void Communicate_Init(void)
{
    List_Create(&ResendList, ResendList_buffer, 100);
    List_Create(&DealList, DealList_buffer, 100);

    /// 串口1接收
    Rx_InitTypeDef Rxinit;
    Rxinit.huart = &huart1;
    Rxinit.RingBuf = rx1_buffer;
    Rxinit.RingBuf_Size = sizeof(rx1_buffer);
    Rxinit.Frame_Head = Mesg_Head;
    Rxinit.Frame_Tail = Mesg_Tail;
    Rxinit.Receive = Rx_Receive;
    Rxinit.Verify = USART_ReceiveMesg_Verify;
    Rxinit.Deal = USART1_Deal;
    Communicate_Rx_Init(&Rx1, Rxinit);

    /// 串口3接收
    Rxinit.huart = &huart3;
    Rxinit.RingBuf = rx3_buffer;
    Rxinit.RingBuf_Size = sizeof(rx3_buffer);
    Rxinit.Frame_Head = Mesg_Head;
    Rxinit.Frame_Tail = Mesg_Tail;
    Rxinit.Receive = Rx_Receive;
    Rxinit.Verify = USART_ReceiveMesg_Verify;
    Rxinit.Deal = USART3_Deal;
    Communicate_Rx_Init(&Rx3, Rxinit);

    /// 串口3发送
    Tx_InitTypeDef Tx_init;
    Tx_init.huart = &huart1;
    Tx_init.hdma = NULL;
    Tx_init.TxBuf = NULL;
    Tx_init.TxBuf_Size = 0;
    Communicate_Tx_Init(&Tx1, Tx_init);
    /// 串口1发送
    Tx_init.huart = &huart3;
    Tx_init.hdma = NULL;
    Tx_init.TxBuf = NULL;
    Tx_init.TxBuf_Size = 0;
    Communicate_Tx_Init(&Tx3, Tx_init);
}

void Communicate_Task(void)
{
    Rx1.Receive(&Rx1, &Receive1_mesg, 14);
    Rx3.Receive(&Rx3, &Receive3_mesg, 14);
}