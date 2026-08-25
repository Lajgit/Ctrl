#include "CommTask.h"
#include "MainTask.h"
#include "app_crc.h"
#include "string.h"
#include "tim.h"
#include "usart.h"

#define Mesg_Head 0xAA
#define Mesg_Tail 0x55
#define RELAY_FRAME_LEN 14U
#define UART_QUEUE_SIZE 1024U
#define UART_QUEUE_MASK (UART_QUEUE_SIZE - 1U)
#define UART_FORWARD_CHUNK 64U
#define MOTOR2_SPEED 85U
#define MOTOR2_TIMEOUT_MS 3000U
#define MOTOR2_REVERSE_MS 300U
#define MOTOR2_REVERSE_DELAY_MS 1U
#define MOTOR2_RETRY_TIMES 3U
#define MOTOR2_VALID_PULSE_US 100U
#define LOCAL_DEDUP_COUNT 16U
#define LOCAL_DEDUP_TIME_MS 5000U
#define RAW_EXIT_MIN_TIME_MS 500U

typedef enum
{
    RELAY_MODE_NORMAL = 0,
    RELAY_MODE_RAW
} RelayMode_t;

typedef enum
{
    MOTOR2_STATE_IDLE = 0,
    MOTOR2_STATE_BUSY,
    MOTOR2_STATE_REVERSE_DELAY,
    MOTOR2_STATE_REVERSING
} Motor2State_t;

typedef struct
{
    volatile uint16_t Read;
    volatile uint16_t Write;
    uint8_t Buffer[UART_QUEUE_SIZE];
} ByteQueue_t;

typedef struct
{
    uint8_t Valid;
    uint8_t ID;
    uint8_t Code2;
    uint32_t Tick;
} LocalDedupe_t;

/* 兼容旧控台文件保留，新的转发逻辑不再使用旧Rx解析器。 */
Tx_HandleTypeDef Tx;
Rx_HandleTypeDef Rx;

static ByteQueue_t AndroidRxQueue;
static ByteQueue_t MainBoardRxQueue;
static uint8_t AndroidRxByte;
static uint8_t MainBoardRxByte;
static RelayMode_t RelayMode = RELAY_MODE_NORMAL;
static uint32_t RawModeEnterTick;
static uint8_t AndroidFrame[RELAY_FRAME_LEN];
static uint8_t AndroidFrameIndex;
static uint8_t RawProbeFrame[RELAY_FRAME_LEN];
static uint8_t RawProbeIndex;
static LocalDedupe_t LocalDedupe[LOCAL_DEDUP_COUNT];
static volatile uint16_t Motor2RemainingCount;
static volatile uint8_t Motor2RetryCount;
static volatile uint8_t Motor2PulseStarted;
static volatile uint8_t Motor2StopRequested;
static volatile uint8_t Motor2RemainingReportPending;
static volatile uint8_t Motor2TimeoutReportPending;
static Motor2State_t Motor2State = MOTOR2_STATE_IDLE;
static uint32_t Motor2RuntimeTick;
static uint32_t Motor2PhaseTick;
static uint8_t RelayTxID;
static uint8_t LegacyTxID;

static uint8_t ByteQueue_Push(ByteQueue_t *Queue, uint8_t Data)
{
    uint16_t next = (uint16_t)((Queue->Write + 1U) & UART_QUEUE_MASK);
    if (next == Queue->Read)
        return 0U;
    Queue->Buffer[Queue->Write] = Data;
    Queue->Write = next;
    return 1U;
}

static uint8_t ByteQueue_Pop(ByteQueue_t *Queue, uint8_t *Data)
{
    if (Queue->Read == Queue->Write)
        return 0U;
    *Data = Queue->Buffer[Queue->Read];
    Queue->Read = (uint16_t)((Queue->Read + 1U) & UART_QUEUE_MASK);
    return 1U;
}

static uint16_t ByteQueue_Read(ByteQueue_t *Queue, uint8_t *Data, uint16_t MaxLen)
{
    uint16_t len = 0U;
    while ((len < MaxLen) && (ByteQueue_Pop(Queue, &Data[len]) != 0U))
        len++;
    return len;
}

static uint8_t Relay_FrameVerify(const uint8_t *Frame)
{
    uint16_t crc;
    uint16_t receive_crc;
    if ((Frame[0] != Mesg_Head) || (Frame[13] != Mesg_Tail))
        return 0U;
    crc = CRC16_calculate((uint8_t *)Frame, 11U);
    receive_crc = (uint16_t)(((uint16_t)Frame[11] << 8U) | Frame[12]);
    return (crc == receive_crc) ? 1U : 0U;
}

static void Relay_TransmitToAndroid(const uint8_t *Data, uint16_t Len)
{
    if ((Data == NULL) || (Len == 0U))
        return;
    (void)HAL_UART_Transmit(&huart3, (uint8_t *)Data, Len, 100U);
}

static void Relay_TransmitToMainBoard(const uint8_t *Data, uint16_t Len)
{
    if ((Data == NULL) || (Len == 0U))
        return;
    (void)HAL_UART_Transmit(&huart2, (uint8_t *)Data, Len, 100U);
}

static uint8_t LocalDedupe_IsRepeat(uint8_t ID, uint8_t Code2)
{
    uint32_t now = HAL_GetTick();
    uint32_t oldest_age = 0U;
    uint8_t oldest_index = 0U;
    uint8_t i;

    for (i = 0U; i < LOCAL_DEDUP_COUNT; i++)
    {
        if ((LocalDedupe[i].Valid != 0U) && (LocalDedupe[i].ID == ID) &&
            (LocalDedupe[i].Code2 == Code2) && ((now - LocalDedupe[i].Tick) <= LOCAL_DEDUP_TIME_MS))
        {
            /* 重发包继续刷新时间窗，但只返回ACK，不重复执行。 */
            LocalDedupe[i].Tick = now;
            return 1U;
        }
    }

    for (i = 0U; i < LOCAL_DEDUP_COUNT; i++)
    {
        if (LocalDedupe[i].Valid == 0U)
        {
            oldest_index = i;
            break;
        }
        if ((now - LocalDedupe[i].Tick) >= oldest_age)
        {
            oldest_age = now - LocalDedupe[i].Tick;
            oldest_index = i;
        }
    }

    LocalDedupe[oldest_index].Valid = 1U;
    LocalDedupe[oldest_index].ID = ID;
    LocalDedupe[oldest_index].Code2 = Code2;
    LocalDedupe[oldest_index].Tick = now;
    return 0U;
}

static void Motor2_SetCompare(uint32_t Channel, uint16_t Percent)
{
    uint32_t compare = ((uint32_t)htim3.Init.Period * Percent) / 100U;
    __HAL_TIM_SET_COMPARE(&htim3, Channel, compare);
}

static void Motor2_RunForward(void)
{
    /* 实机确认原方向相反：正常吐珠时PA7/BI输出PWM，PA6/FI保持低电平。 */
    Motor2_SetCompare(TIM_CHANNEL_1, 0U);
    Motor2_SetCompare(TIM_CHANNEL_2, MOTOR2_SPEED);
    Motor2RuntimeTick = HAL_GetTick();
    Motor2State = MOTOR2_STATE_BUSY;
}

static void Motor2_RunReverse(void)
{
    /* 堵珠反转与正常吐珠方向相反。 */
    Motor2_SetCompare(TIM_CHANNEL_1, MOTOR2_SPEED);
    Motor2_SetCompare(TIM_CHANNEL_2, 0U);
}

static void Motor2_Brake(void)
{
    /* 与原球盘Motor_Stop保持一致：两路均拉到100%作为停止制动。 */
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_1, htim3.Init.Period);
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_2, htim3.Init.Period);
}

static void Motor2_LosePower(void)
{
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_1, 0U);
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_2, 0U);
}

static void Motor2_Stop(uint8_t ReportRemaining)
{
    Motor2_Brake();
    Motor2State = MOTOR2_STATE_IDLE;
    Motor2RemainingCount = 0U;
    Motor2RetryCount = 0U;
    Motor2PulseStarted = 0U;
    Motor2StopRequested = 0U;
    if (ReportRemaining != 0U)
        Motor2RemainingReportPending = 1U;
}

static void Motor2_StopForRawMode(void)
{
    /* OTA透明模式期间不允许插入本地上报，直接断开电机输出。 */
    Motor2_LosePower();
    Motor2State = MOTOR2_STATE_IDLE;
    Motor2RemainingCount = 0U;
    Motor2RetryCount = 0U;
    Motor2PulseStarted = 0U;
    Motor2StopRequested = 0U;
    Motor2RemainingReportPending = 0U;
    Motor2TimeoutReportPending = 0U;
}

static void Motor2_AddOutput(uint16_t Num)
{
    Motor2RemainingCount = (uint16_t)(Motor2RemainingCount + Num);
    if (Motor2RemainingCount != 0U)
    {
        Motor2RetryCount = 0U;
        Motor2StopRequested = 0U;
        Motor2_RunForward();
    }
    Motor2RemainingReportPending = 1U;
}

static void Motor2_Task(void)
{
    uint32_t now = HAL_GetTick();

    if (Motor2StopRequested != 0U)
    {
        Motor2_Stop(0U);
        Motor2RemainingReportPending = 1U;
        return;
    }

    if (Motor2State == MOTOR2_STATE_BUSY)
    {
        if ((now - Motor2RuntimeTick) > MOTOR2_TIMEOUT_MS)
        {
            /* 超时后先断电1ms，再按原球盘策略反转300ms。 */
            Motor2_LosePower();
            Motor2PhaseTick = now;
            Motor2State = MOTOR2_STATE_REVERSE_DELAY;
        }
    }
    else if (Motor2State == MOTOR2_STATE_REVERSE_DELAY)
    {
        if ((now - Motor2PhaseTick) >= MOTOR2_REVERSE_DELAY_MS)
        {
            Motor2_RunReverse();
            Motor2PhaseTick = now;
            Motor2State = MOTOR2_STATE_REVERSING;
        }
    }
    else if (Motor2State == MOTOR2_STATE_REVERSING)
    {
        if ((now - Motor2PhaseTick) > MOTOR2_REVERSE_MS)
        {
            if (Motor2RetryCount < MOTOR2_RETRY_TIMES)
            {
                Motor2RetryCount++;
                Motor2_RunForward();
            }
            else
            {
                Motor2_Brake();
                Motor2State = MOTOR2_STATE_IDLE;
                Motor2TimeoutReportPending = 1U;
            }
        }
    }
}

void Comm_HoolleOutput2IRQ(void)
{
    if (HAL_GPIO_ReadPin(HoolleOutput2_GPIO_Port, HoolleOutput2_Pin) == GPIO_PIN_RESET)
    {
        /* 光眼低电平开始：使用1MHz TIM2统计脉宽，并重置无出珠超时。 */
        __HAL_TIM_SET_COUNTER(&htim2, 0U);
        Motor2PulseStarted = 1U;
        Motor2RuntimeTick = HAL_GetTick();
        return;
    }

    if (Motor2PulseStarted == 0U)
        return;

    Motor2PulseStarted = 0U;
    if (__HAL_TIM_GET_COUNTER(&htim2) <= MOTOR2_VALID_PULSE_US)
        return;

    if (Motor2RemainingCount > 0U)
    {
        Motor2RemainingCount--;
        Motor2RetryCount = 0U;
        Motor2RemainingReportPending = 1U;
        if (Motor2RemainingCount == 0U)
        {
            /* 中断里只置请求，PWM寄存器操作放回主循环执行。 */
            Motor2StopRequested = 1U;
        }
    }
}

static void Relay_SendLocalFrame(uint8_t Code2, uint16_t Value)
{
    uint8_t data[RELAY_FRAME_LEN] = {0};
    uint16_t crc;
    RelayTxID++;
    data[0] = Mesg_Head;
    data[2] = RelayTxID;
    data[3] = Relay_to_Android;
    data[4] = Code2;
    data[7] = (uint8_t)(Value >> 8U);
    data[8] = (uint8_t)Value;
    crc = CRC16_calculate(data, 11U);
    data[11] = (uint8_t)(crc >> 8U);
    data[12] = (uint8_t)crc;
    data[13] = Mesg_Tail;
    Relay_TransmitToAndroid(data, RELAY_FRAME_LEN);
}

static void Motor2_ReportTask(void)
{
    uint16_t remaining;
    if (RelayMode != RELAY_MODE_NORMAL)
        return;

    if (Motor2RemainingReportPending != 0U)
    {
        Motor2RemainingReportPending = 0U;
        remaining = Motor2RemainingCount;
        Relay_SendLocalFrame(Motor2Remaining, remaining);
    }
    if (Motor2TimeoutReportPending != 0U)
    {
        Motor2TimeoutReportPending = 0U;
        remaining = Motor2RemainingCount;
        Relay_SendLocalFrame(Motor2Timeout, remaining);
    }
}

static uint8_t Relay_IsBootRequest(const uint8_t *Frame)
{
    uint32_t data;
    if ((Frame[3] != Android_to_Board) || (Frame[4] != BoardRestart))
        return 0U;
    data = ((uint32_t)Frame[5] << 24U) | ((uint32_t)Frame[6] << 16U) |
           ((uint32_t)Frame[7] << 8U) | (uint32_t)Frame[8];
    return (data == OTA_REQUEST_MAGIC) ? 1U : 0U;
}

static void Relay_EnterRawMode(void)
{
    /* BOTA帧已经先转给主板；后续Bootloader流量全部按原始字节透明转发。 */
    Motor2_StopForRawMode();
    RelayMode = RELAY_MODE_RAW;
    RawModeEnterTick = HAL_GetTick();
    RawProbeIndex = 0U;
    AndroidFrameIndex = 0U;
}

static void Relay_HandleLocalFrame(const uint8_t *Frame)
{
    uint16_t num;
    if ((Frame[4] != Motor2Output) && (Frame[4] != Motor2Stop))
    {
        /* Code1属于转发板但功能码未知时不下发给原主板，避免协议串扰。 */
        return;
    }

    /* 与原主板ACK行为一致：合法命令先原样回发，再做去重判断。 */
    Relay_TransmitToAndroid(Frame, RELAY_FRAME_LEN);
    if (LocalDedupe_IsRepeat(Frame[2], Frame[4]) != 0U)
        return;

    if (Frame[4] == Motor2Output)
    {
        num = (uint16_t)(((uint16_t)Frame[7] << 8U) | Frame[8]);
        Motor2_AddOutput(num);
    }
    else
    {
        Motor2_Stop(1U);
    }
}

static void Relay_HandleAndroidFrame(const uint8_t *Frame)
{
    if (Frame[3] == Android_to_Relay)
    {
        Relay_HandleLocalFrame(Frame);
        return;
    }

    if ((Frame[3] == Android_to_Board) && (Frame[4] == StopAllDevice))
    {
        /* 原0xFF仍原样发给主板，同时本地停止第二组吐珠电机。 */
        Motor2_Stop(0U);
    }

    Relay_TransmitToMainBoard(Frame, RELAY_FRAME_LEN);
    if (Relay_IsBootRequest(Frame) != 0U)
        Relay_EnterRawMode();
}

static void Relay_ProcessAndroidNormal(void)
{
    uint8_t data;
    uint16_t process_count = 0U;

    while ((process_count < UART_FORWARD_CHUNK) && (ByteQueue_Pop(&AndroidRxQueue, &data) != 0U))
    {
        process_count++;
        if (AndroidFrameIndex == 0U)
        {
            if (data == Mesg_Head)
            {
                AndroidFrame[0] = data;
                AndroidFrameIndex = 1U;
            }
            else
            {
                Relay_TransmitToMainBoard(&data, 1U);
            }
            continue;
        }

        AndroidFrame[AndroidFrameIndex++] = data;
        if (AndroidFrameIndex >= RELAY_FRAME_LEN)
        {
            if (Relay_FrameVerify(AndroidFrame) != 0U)
                Relay_HandleAndroidFrame(AndroidFrame);
            else
                /* 非法或非本协议数据也必须原样转发，保证透明性。 */
                Relay_TransmitToMainBoard(AndroidFrame, RELAY_FRAME_LEN);

            AndroidFrameIndex = 0U;
            if (RelayMode == RELAY_MODE_RAW)
                break;
        }
    }
}

static void Relay_ProbeRawMainByte(uint8_t Data)
{
    uint8_t next_head = 0U;
    uint8_t i;

    if ((HAL_GetTick() - RawModeEnterTick) < RAW_EXIT_MIN_TIME_MS)
        return;

    if (RawProbeIndex == 0U)
    {
        if (Data == Mesg_Head)
        {
            RawProbeFrame[0] = Data;
            RawProbeIndex = 1U;
        }
        return;
    }

    RawProbeFrame[RawProbeIndex++] = Data;
    if (RawProbeIndex < RELAY_FRAME_LEN)
        return;

    if ((Relay_FrameVerify(RawProbeFrame) != 0U) && (RawProbeFrame[3] == Board_to_Android))
    {
        /* 主板APP恢复并重新发出正常14字节帧后，退出Bootloader透明模式。 */
        RelayMode = RELAY_MODE_NORMAL;
        RawProbeIndex = 0U;
        return;
    }

    for (i = 1U; i < RELAY_FRAME_LEN; i++)
    {
        if (RawProbeFrame[i] == Mesg_Head)
        {
            next_head = i;
            break;
        }
    }

    if (next_head == 0U)
        RawProbeIndex = 0U;
    else
    {
        RawProbeIndex = (uint8_t)(RELAY_FRAME_LEN - next_head);
        memmove(RawProbeFrame, &RawProbeFrame[next_head], RawProbeIndex);
    }
}

static void Relay_ProcessMainBoardNormal(void)
{
    uint8_t data[UART_FORWARD_CHUNK];
    uint16_t len = ByteQueue_Read(&MainBoardRxQueue, data, sizeof(data));
    if (len > 0U)
        Relay_TransmitToAndroid(data, len);
}

static void Relay_ProcessRaw(void)
{
    uint8_t data[UART_FORWARD_CHUNK];
    uint16_t len;
    uint16_t i;

    len = ByteQueue_Read(&AndroidRxQueue, data, sizeof(data));
    if (len > 0U)
        Relay_TransmitToMainBoard(data, len);

    len = ByteQueue_Read(&MainBoardRxQueue, data, sizeof(data));
    if (len > 0U)
    {
        Relay_TransmitToAndroid(data, len);
        for (i = 0U; i < len; i++)
            Relay_ProbeRawMainByte(data[i]);
    }
}

uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *TxHandle, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode)
{
    uint8_t frame[RELAY_FRAME_LEN] = {0};
    uint16_t crc;
    UART_HandleTypeDef *huart = &huart3;

    LegacyTxID++;
    frame[0] = Mesg_Head;
    frame[2] = LegacyTxID;
    frame[3] = code_1;
    frame[4] = code_2;
    frame[5] = (uint8_t)(data >> 24U);
    frame[6] = (uint8_t)(data >> 16U);
    frame[7] = (uint8_t)(data >> 8U);
    frame[8] = (uint8_t)data;
    frame[10] = expandCode;
    crc = CRC16_calculate(frame, 11U);
    frame[11] = (uint8_t)(crc >> 8U);
    frame[12] = (uint8_t)crc;
    frame[13] = Mesg_Tail;

    if ((TxHandle != NULL) && (TxHandle->huart != NULL))
        huart = TxHandle->huart;
    (void)HAL_UART_Transmit(huart, frame, RELAY_FRAME_LEN, 100U);
    return LegacyTxID;
}

uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *TxHandle, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode, ListHandle_t *List)
{
    /* 旧控台重发接口已不参与新转发逻辑，仅保留链接兼容。 */
    (void)List;
    return Comm_SendMesg_FillData(TxHandle, code_1, code_2, data, expandCode);
}

void Resend_Task(void) {}
void MesgDeal_Task(void) {}

void CommInit(void)
{
    memset(&AndroidRxQueue, 0, sizeof(AndroidRxQueue));
    memset(&MainBoardRxQueue, 0, sizeof(MainBoardRxQueue));
    memset(LocalDedupe, 0, sizeof(LocalDedupe));
    RelayMode = RELAY_MODE_NORMAL;
    AndroidFrameIndex = 0U;
    RawProbeIndex = 0U;

    /* 保留旧控台全局Tx对象，默认指向安卓侧串口，防止旧文件链接失败。 */
    memset(&Tx, 0, sizeof(Tx));
    memset(&Rx, 0, sizeof(Rx));
    Tx.huart = &huart3;

    Motor2RemainingCount = 0U;
    Motor2RetryCount = 0U;
    Motor2PulseStarted = 0U;
    Motor2StopRequested = 0U;
    Motor2RemainingReportPending = 0U;
    Motor2TimeoutReportPending = 0U;
    Motor2State = MOTOR2_STATE_IDLE;

    /* TIM2为1MHz光眼脉宽计时；TIM3_CH1/CH2驱动SS6285L。 */
    (void)HAL_TIM_Base_Start(&htim2);
    (void)HAL_TIM_PWM_Start(&htim3, TIM_CHANNEL_1);
    (void)HAL_TIM_PWM_Start(&htim3, TIM_CHANNEL_2);
    Motor2_LosePower();

    /* USART2接原球盘主板，USART3接安卓板。 */
    (void)HAL_UART_Receive_IT(&huart2, &MainBoardRxByte, 1U);
    (void)HAL_UART_Receive_IT(&huart3, &AndroidRxByte, 1U);
}

void CommTask(void)
{
    if (RelayMode == RELAY_MODE_RAW)
    {
        Relay_ProcessRaw();
        return;
    }

    Relay_ProcessMainBoardNormal();
    Relay_ProcessAndroidNormal();
    /* BOTA命令可能在处理安卓帧时切换为RAW，切换后禁止发送任何本地协议帧。 */
    if (RelayMode == RELAY_MODE_RAW)
        return;

    Motor2_Task();
    Motor2_ReportTask();
}

void Comm_UART_RxCpltCallback(UART_HandleTypeDef *huart)
{
    if (huart == &huart2)
    {
        (void)ByteQueue_Push(&MainBoardRxQueue, MainBoardRxByte);
        (void)HAL_UART_Receive_IT(&huart2, &MainBoardRxByte, 1U);
    }
    else if (huart == &huart3)
    {
        (void)ByteQueue_Push(&AndroidRxQueue, AndroidRxByte);
        (void)HAL_UART_Receive_IT(&huart3, &AndroidRxByte, 1U);
    }
}

void Comm_UART_ErrorCallback(UART_HandleTypeDef *huart)
{
    uint32_t error_code;
    uint8_t *rx_byte = NULL;

    if (huart == &huart2)
        rx_byte = &MainBoardRxByte;
    else if (huart == &huart3)
        rx_byte = &AndroidRxByte;
    else
        return;

    error_code = huart->ErrorCode;
    if ((error_code & (HAL_UART_ERROR_ORE | HAL_UART_ERROR_FE | HAL_UART_ERROR_NE | HAL_UART_ERROR_PE)) == 0U)
        return;

    /* STM32F1通过先读SR再读DR统一清除PE/FE/NE/ORE，避免串口异常后永久停收。 */
    __HAL_UART_CLEAR_PEFLAG(huart);
    if (huart->RxState == HAL_UART_STATE_READY)
    {
        huart->ErrorCode = HAL_UART_ERROR_NONE;
        (void)HAL_UART_Receive_IT(huart, rx_byte, 1U);
    }
}
