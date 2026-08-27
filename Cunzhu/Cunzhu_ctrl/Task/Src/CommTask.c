#include "CommTask.h"
#include "app_crc.h"
#include "string.h"
#include "tim.h"
#include "usart.h"

#define Mesg_Head 0xAAU
#define Mesg_Tail 0x55U
#define CZ_FRAME_LEN 14U
#define UART_QUEUE_SIZE 512U
#define UART_QUEUE_MASK (UART_QUEUE_SIZE - 1U)
#define UART_PROCESS_CHUNK 64U

#define COLLECT_MOTOR_SPEED 85U
#define COLLECT_PULSE_VALID_US 100U
#define COLLECT_NO_PULSE_TIMEOUT_MS 3000U
#define COLLECT_REVERSE_DELAY_MS 1U
#define COLLECT_REVERSE_MS 300U
#define COLLECT_RETRY_TIMES 3U
#define COLLECT_DEFAULT_TIMEOUT_MS 300000UL
#define TERMINAL_RESEND_INTERVAL_MS 500U
#define TERMINAL_RESEND_TIMES 3U
#define COMMAND_DEDUP_COUNT 16U
#define COMMAND_DEDUP_TIME_MS 5000U

typedef enum
{
    MOTOR_STATE_IDLE = 0,
    MOTOR_STATE_FORWARD,
    MOTOR_STATE_REVERSE_DELAY,
    MOTOR_STATE_REVERSE
} CollectMotorState_t;

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
} CommandDedupe_t;

/* 兼容旧控台文件保留，新的存珠协议不再使用旧Rx解析器。 */
Tx_HandleTypeDef Tx;
Rx_HandleTypeDef Rx;

static ByteQueue_t AndroidRxQueue;
static uint8_t AndroidRxByte;
static uint8_t AndroidFrame[CZ_FRAME_LEN];
static uint8_t AndroidFrameIndex;
static CommandDedupe_t CommandDedupe[COMMAND_DEDUP_COUNT];
static uint8_t BoardTxID;
static uint8_t LegacyTxID;

static volatile uint16_t CollectMaximumQuantity;
static volatile uint16_t CollectActualQuantity;
static volatile uint8_t CollectPulseStarted;
static volatile uint8_t CountReportPending;
static volatile uint8_t FinishRequestPending;
static volatile uint8_t FinishReasonPending;
static uint32_t CollectStartTick;
static uint32_t CollectTimeoutMs;
static uint32_t MotorRuntimeTick;
static uint32_t MotorPhaseTick;
static uint8_t CollectState = CZ_STATE_IDLE;
static uint8_t CurrentErrorCode = CZ_RESULT_OK;
static uint8_t MotorRetryCount;
static CollectMotorState_t MotorState = MOTOR_STATE_IDLE;

static uint8_t TerminalPending;
static uint8_t TerminalFrame[CZ_FRAME_LEN];
static uint8_t TerminalResendCount;
static uint32_t TerminalLastSendTick;
static uint8_t TerminalFrameID;
static uint8_t TerminalCode2;

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

static uint16_t Protocol_CalcCrc(const uint8_t *Frame)
{
    return CRC16_calculate((uint8_t *)Frame, 11U);
}

static uint8_t Protocol_FrameVerify(const uint8_t *Frame)
{
    uint16_t crc;
    uint16_t rx_crc;

    if ((Frame[0] != Mesg_Head) || (Frame[13] != Mesg_Tail))
        return 0U;

    crc = Protocol_CalcCrc(Frame);
    rx_crc = (uint16_t)((uint16_t)Frame[11] | ((uint16_t)Frame[12] << 8U));
    return (crc == rx_crc) ? 1U : 0U;
}

static void Protocol_FillCrc(uint8_t *Frame)
{
    uint16_t crc = Protocol_CalcCrc(Frame);
    Frame[11] = (uint8_t)(crc & 0xFFU);
    Frame[12] = (uint8_t)((crc >> 8U) & 0xFFU);
}

static uint8_t Protocol_NextID(void)
{
    BoardTxID++;
    if (BoardTxID == 0U)
        BoardTxID = 1U;
    return BoardTxID;
}

static void Protocol_BuildBoardFrame(
    uint8_t ResendID,
    uint8_t ID,
    uint8_t Code2,
    uint8_t Data1,
    uint8_t Data2,
    uint8_t Data3,
    uint8_t Data4,
    uint8_t AckByte,
    uint8_t ExpandCode,
    uint8_t *OutFrame)
{
    memset(OutFrame, 0, CZ_FRAME_LEN);
    OutFrame[0] = Mesg_Head;
    OutFrame[1] = ResendID;
    OutFrame[2] = ID;
    OutFrame[3] = Board_to_Android;
    OutFrame[4] = Code2;
    OutFrame[5] = Data1;
    OutFrame[6] = Data2;
    OutFrame[7] = Data3;
    OutFrame[8] = Data4;
    OutFrame[9] = AckByte;
    OutFrame[10] = ExpandCode;
    Protocol_FillCrc(OutFrame);
    OutFrame[13] = Mesg_Tail;
}

static void Protocol_TransmitToAndroid(const uint8_t *Data, uint16_t Len)
{
    if ((Data == 0) || (Len == 0U))
        return;
    (void)HAL_UART_Transmit(&huart3, (uint8_t *)Data, Len, 100U);
}

static void Protocol_SendAckEcho(const uint8_t *Frame, uint8_t ExpandCode)
{
    uint8_t ack[CZ_FRAME_LEN];

    /* ACK echo必须回显原帧ResendID、ID、Code2和Data1..Data4，只替换方向、ACKbyte和结果码。 */
    Protocol_BuildBoardFrame(
        Frame[1],
        Frame[2],
        Frame[4],
        Frame[5],
        Frame[6],
        Frame[7],
        Frame[8],
        CZ_ACK_ECHO,
        ExpandCode,
        ack);
    Protocol_TransmitToAndroid(ack, CZ_FRAME_LEN);
}

static uint8_t CommandDedupe_IsRepeat(uint8_t ID, uint8_t Code2)
{
    uint32_t now = HAL_GetTick();
    uint32_t oldest_age = 0U;
    uint8_t oldest_index = 0U;
    uint8_t i;

    for (i = 0U; i < COMMAND_DEDUP_COUNT; i++)
    {
        if ((CommandDedupe[i].Valid != 0U) &&
            (CommandDedupe[i].ID == ID) &&
            (CommandDedupe[i].Code2 == Code2) &&
            ((now - CommandDedupe[i].Tick) <= COMMAND_DEDUP_TIME_MS))
        {
            CommandDedupe[i].Tick = now;
            return 1U;
        }
    }

    for (i = 0U; i < COMMAND_DEDUP_COUNT; i++)
    {
        if (CommandDedupe[i].Valid == 0U)
        {
            oldest_index = i;
            break;
        }
        if ((now - CommandDedupe[i].Tick) >= oldest_age)
        {
            oldest_age = now - CommandDedupe[i].Tick;
            oldest_index = i;
        }
    }

    CommandDedupe[oldest_index].Valid = 1U;
    CommandDedupe[oldest_index].ID = ID;
    CommandDedupe[oldest_index].Code2 = Code2;
    CommandDedupe[oldest_index].Tick = now;
    return 0U;
}

static uint8_t Collect_GetSensorState(void)
{
    uint8_t state = 0U;

    if (HAL_GPIO_ReadPin(HoolleOutput2_GPIO_Port, HoolleOutput2_Pin) == GPIO_PIN_RESET)
        state |= 0x02U;
    if (MotorState != MOTOR_STATE_IDLE)
        state |= 0x04U;
    return state;
}

static void Motor_SetCompare(uint32_t Channel, uint16_t Percent)
{
    uint32_t compare = ((uint32_t)htim3.Init.Period * Percent) / 100U;
    __HAL_TIM_SET_COMPARE(&htim3, Channel, compare);
}

static void Motor_RunForward(void)
{
    /* 存珠收珠方向沿用当前样机实测方向：PA7/BI输出PWM，PA6/FI保持低电平。 */
    Motor_SetCompare(TIM_CHANNEL_1, 0U);
    Motor_SetCompare(TIM_CHANNEL_2, COLLECT_MOTOR_SPEED);
    MotorRuntimeTick = HAL_GetTick();
    MotorState = MOTOR_STATE_FORWARD;
}

static void Motor_RunReverse(void)
{
    /* 堵珠排障方向与正常收珠方向相反。 */
    Motor_SetCompare(TIM_CHANNEL_1, COLLECT_MOTOR_SPEED);
    Motor_SetCompare(TIM_CHANNEL_2, 0U);
}

static void Motor_Brake(void)
{
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_1, htim3.Init.Period);
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_2, htim3.Init.Period);
}

static void Motor_LosePower(void)
{
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_1, 0U);
    __HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_2, 0U);
}

static void Motor_StopOutput(void)
{
    Motor_Brake();
    MotorState = MOTOR_STATE_IDLE;
    MotorRetryCount = 0U;
    CollectPulseStarted = 0U;
}

static void Protocol_SendStatusFrame(uint8_t Code2)
{
    uint8_t frame[CZ_FRAME_LEN];
    uint16_t actual = CollectActualQuantity;

    Protocol_BuildBoardFrame(
        0U,
        Protocol_NextID(),
        Code2,
        CollectState,
        (uint8_t)((actual >> 8U) & 0xFFU),
        (uint8_t)(actual & 0xFFU),
        Collect_GetSensorState(),
        CZ_ACK_NONE,
        CurrentErrorCode,
        frame);
    Protocol_TransmitToAndroid(frame, CZ_FRAME_LEN);
}

static void Protocol_SendCountChanged(void)
{
    uint8_t frame[CZ_FRAME_LEN];
    uint16_t actual = CollectActualQuantity;

    Protocol_BuildBoardFrame(
        0U,
        Protocol_NextID(),
        CZ_CODE2_COUNT_CHANGED,
        (uint8_t)((actual >> 8U) & 0xFFU),
        (uint8_t)(actual & 0xFFU),
        CollectState,
        Collect_GetSensorState(),
        CZ_ACK_NONE,
        CurrentErrorCode,
        frame);
    Protocol_TransmitToAndroid(frame, CZ_FRAME_LEN);
}

static void Protocol_SendTerminalFrame(
    uint8_t Code2,
    uint8_t Data3,
    uint8_t Data4,
    uint8_t ExpandCode)
{
    uint16_t actual = CollectActualQuantity;

    Protocol_BuildBoardFrame(
        0U,
        Protocol_NextID(),
        Code2,
        (uint8_t)((actual >> 8U) & 0xFFU),
        (uint8_t)(actual & 0xFFU),
        Data3,
        Data4,
        CZ_ACK_NONE,
        ExpandCode,
        TerminalFrame);

    TerminalPending = 1U;
    TerminalResendCount = 0U;
    TerminalLastSendTick = HAL_GetTick();
    TerminalFrameID = TerminalFrame[2];
    TerminalCode2 = Code2;
    Protocol_TransmitToAndroid(TerminalFrame, CZ_FRAME_LEN);
}

static void Collect_Finish(uint8_t FinishReason)
{
    if ((CollectState != CZ_STATE_COLLECTING) && (CollectState != CZ_STATE_STOPPING))
        return;

    Motor_StopOutput();
    CollectState = CZ_STATE_IDLE;
    CurrentErrorCode = CZ_RESULT_OK;
    FinishRequestPending = 0U;
    Protocol_SendTerminalFrame(
        CZ_CODE2_COLLECT_FINISHED,
        FinishReason,
        CZ_STATE_IDLE,
        CZ_RESULT_OK);
}

static void Collect_Fault(uint8_t ErrorCode)
{
    if (CollectState == CZ_STATE_IDLE)
        return;

    Motor_StopOutput();
    CollectState = CZ_STATE_FAULT;
    CurrentErrorCode = ErrorCode;
    FinishRequestPending = 0U;
    Protocol_SendTerminalFrame(
        CZ_CODE2_FAULT_EVENT,
        CZ_STATE_FAULT,
        Collect_GetSensorState(),
        ErrorCode);
}

static void Collect_Start(uint16_t MaximumQuantity, uint16_t TimeoutSeconds)
{
    CollectMaximumQuantity = MaximumQuantity;
    CollectActualQuantity = 0U;
    CollectPulseStarted = 0U;
    CountReportPending = 0U;
    FinishRequestPending = 0U;
    FinishReasonPending = CZ_FINISH_REACH_MAX;
    MotorRetryCount = 0U;
    CurrentErrorCode = CZ_RESULT_OK;
    CollectState = CZ_STATE_COLLECTING;
    CollectStartTick = HAL_GetTick();
    if (TimeoutSeconds == 0U)
        CollectTimeoutMs = COLLECT_DEFAULT_TIMEOUT_MS;
    else
        CollectTimeoutMs = (uint32_t)TimeoutSeconds * 1000UL;
    Motor_RunForward();
    Protocol_SendStatusFrame(CZ_CODE2_STATUS);
}

static void TerminalAck_Task(void)
{
    uint32_t now;

    if (TerminalPending == 0U)
        return;

    now = HAL_GetTick();
    if ((now - TerminalLastSendTick) < TERMINAL_RESEND_INTERVAL_MS)
        return;

    if (TerminalResendCount >= TERMINAL_RESEND_TIMES)
    {
        /* 三次终态重发仍未收到Android ACK时，释放本地终态等待，避免永久锁死下一次收珠。 */
        TerminalPending = 0U;
        return;
    }

    TerminalResendCount++;
    TerminalFrame[1] = TerminalResendCount;
    Protocol_FillCrc(TerminalFrame);
    TerminalLastSendTick = now;
    Protocol_TransmitToAndroid(TerminalFrame, CZ_FRAME_LEN);
}

static void Collect_Task(void)
{
    uint32_t now = HAL_GetTick();

    if (CountReportPending != 0U)
    {
        CountReportPending = 0U;
        Protocol_SendCountChanged();
    }

    if (FinishRequestPending != 0U)
    {
        Collect_Finish(FinishReasonPending);
        return;
    }

    if (CollectState != CZ_STATE_COLLECTING)
        return;

    if ((now - CollectStartTick) >= CollectTimeoutMs)
    {
        Collect_Finish(CZ_FINISH_SESSION_TIMEOUT);
        return;
    }

    if (MotorState == MOTOR_STATE_FORWARD)
    {
        if ((now - MotorRuntimeTick) > COLLECT_NO_PULSE_TIMEOUT_MS)
        {
            /* 长时间没有有效光眼脉冲时，先断电1ms，再短反转排障。 */
            Motor_LosePower();
            MotorPhaseTick = now;
            MotorState = MOTOR_STATE_REVERSE_DELAY;
        }
    }
    else if (MotorState == MOTOR_STATE_REVERSE_DELAY)
    {
        if ((now - MotorPhaseTick) >= COLLECT_REVERSE_DELAY_MS)
        {
            Motor_RunReverse();
            MotorPhaseTick = now;
            MotorState = MOTOR_STATE_REVERSE;
        }
    }
    else if (MotorState == MOTOR_STATE_REVERSE)
    {
        if ((now - MotorPhaseTick) > COLLECT_REVERSE_MS)
        {
            if (MotorRetryCount < COLLECT_RETRY_TIMES)
            {
                MotorRetryCount++;
                Motor_RunForward();
            }
            else
            {
                /*
                 * 单纯“长时间没有脉冲”无法区分用户已经投完与机构堵珠。
                 * 排障重试耗尽后，只有光眼仍持续被遮挡才判定 JAM；光眼已经释放时
                 * 按自然结束上报当前真实计数，避免正常停止投珠被误判成故障。
                 */
                if (HAL_GPIO_ReadPin(HoolleOutput2_GPIO_Port, HoolleOutput2_Pin) == GPIO_PIN_RESET)
                    Collect_Fault(CZ_RESULT_JAM);
                else
                    Collect_Finish(CZ_FINISH_NATURAL_END);
            }
        }
    }
}

static void Protocol_HandleTerminalAck(const uint8_t *Frame)
{
    if ((TerminalPending != 0U) && (Frame[2] == TerminalFrameID) && (Frame[4] == TerminalCode2))
        TerminalPending = 0U;
}

static void Protocol_HandleStartCollect(const uint8_t *Frame)
{
    uint16_t maximum = (uint16_t)(((uint16_t)Frame[5] << 8U) | Frame[6]);
    uint16_t timeout = (uint16_t)(((uint16_t)Frame[7] << 8U) | Frame[8]);

    if ((maximum == 0U) || (timeout == 0U))
    {
        Protocol_SendAckEcho(Frame, CZ_RESULT_PARAM_INVALID);
        return;
    }

    if ((CollectState == CZ_STATE_COLLECTING) || (TerminalPending != 0U))
    {
        Protocol_SendAckEcho(Frame, CZ_RESULT_BUSY);
        return;
    }

    Protocol_SendAckEcho(Frame, CZ_RESULT_OK);
    Collect_Start(maximum, timeout);
}

static void Protocol_HandleStopCollect(const uint8_t *Frame)
{
    uint8_t stop_reason = Frame[5];

    Protocol_SendAckEcho(Frame, CZ_RESULT_OK);
    if (CollectState == CZ_STATE_COLLECTING)
    {
        CollectState = CZ_STATE_STOPPING;
        if (stop_reason == 0U)
            Collect_Finish(CZ_FINISH_ANDROID_STOP);
        else
            Collect_Finish(stop_reason);
    }
    else
    {
        Protocol_SendStatusFrame(CZ_CODE2_STATUS);
    }
}

static void Protocol_HandleClearFault(const uint8_t *Frame)
{
    uint8_t reset_type = Frame[5];

    Protocol_SendAckEcho(Frame, CZ_RESULT_OK);
    Motor_StopOutput();
    TerminalPending = 0U;
    CurrentErrorCode = CZ_RESULT_OK;
    CollectState = CZ_STATE_IDLE;
    FinishRequestPending = 0U;
    CountReportPending = 0U;

    if (reset_type == 0x01U)
        CollectActualQuantity = 0U;

    Protocol_SendStatusFrame(CZ_CODE2_STATUS);
}

static void Protocol_HandleAndroidFrame(const uint8_t *Frame)
{
    if (Frame[3] != Android_to_Board)
        return;

    if (Frame[9] == CZ_ACK_ECHO)
    {
        Protocol_HandleTerminalAck(Frame);
        return;
    }

    switch (Frame[4])
    {
    case CZ_CODE2_START_COLLECT:
    case CZ_CODE2_STOP_COLLECT:
    case CZ_CODE2_CLEAR_FAULT:
        if (CommandDedupe_IsRepeat(Frame[2], Frame[4]) != 0U)
        {
            Protocol_SendAckEcho(Frame, CZ_RESULT_DUPLICATE_ACCEPTED);
            return;
        }
        break;
    default:
        break;
    }

    switch (Frame[4])
    {
    case CZ_CODE2_START_COLLECT:
        Protocol_HandleStartCollect(Frame);
        break;
    case CZ_CODE2_STOP_COLLECT:
        Protocol_HandleStopCollect(Frame);
        break;
    case CZ_CODE2_STATUS:
        Protocol_SendStatusFrame(CZ_CODE2_STATUS);
        break;
    case CZ_CODE2_CLEAR_FAULT:
        Protocol_HandleClearFault(Frame);
        break;
    case CZ_CODE2_HEARTBEAT:
        Protocol_SendStatusFrame(CZ_CODE2_HEARTBEAT);
        break;
    default:
        Protocol_SendAckEcho(Frame, CZ_RESULT_UNKNOWN_CODE2);
        break;
    }
}

static void Protocol_HandleInvalidAndroidFrame(const uint8_t *Frame)
{
    if ((Frame[0] == Mesg_Head) && (Frame[13] == Mesg_Tail) && (Frame[3] == Android_to_Board))
        Protocol_SendAckEcho(Frame, CZ_RESULT_CRC_ERROR);
}

static void Protocol_ProcessAndroidRx(void)
{
    uint8_t data;
    uint16_t process_count = 0U;

    while ((process_count < UART_PROCESS_CHUNK) && (ByteQueue_Pop(&AndroidRxQueue, &data) != 0U))
    {
        process_count++;
        if (AndroidFrameIndex == 0U)
        {
            if (data == Mesg_Head)
            {
                AndroidFrame[0] = data;
                AndroidFrameIndex = 1U;
            }
            continue;
        }

        AndroidFrame[AndroidFrameIndex++] = data;
        if (AndroidFrameIndex >= CZ_FRAME_LEN)
        {
            if (Protocol_FrameVerify(AndroidFrame) != 0U)
                Protocol_HandleAndroidFrame(AndroidFrame);
            else
                Protocol_HandleInvalidAndroidFrame(AndroidFrame);
            AndroidFrameIndex = 0U;
        }
    }
}

void Comm_HoolleOutput2IRQ(void)
{
    if (HAL_GPIO_ReadPin(HoolleOutput2_GPIO_Port, HoolleOutput2_Pin) == GPIO_PIN_RESET)
    {
        /* 光眼低电平开始：使用1MHz TIM2统计脉宽，并重置收珠无脉冲超时。 */
        __HAL_TIM_SET_COUNTER(&htim2, 0U);
        CollectPulseStarted = 1U;
        MotorRuntimeTick = HAL_GetTick();
        return;
    }

    if (CollectPulseStarted == 0U)
        return;

    CollectPulseStarted = 0U;
    if (__HAL_TIM_GET_COUNTER(&htim2) <= COLLECT_PULSE_VALID_US)
        return;

    if (CollectState == CZ_STATE_COLLECTING)
    {
        if (CollectActualQuantity < CollectMaximumQuantity)
        {
            CollectActualQuantity++;
            CountReportPending = 1U;
            MotorRetryCount = 0U;
            MotorRuntimeTick = HAL_GetTick();
        }

        if (CollectActualQuantity >= CollectMaximumQuantity)
        {
            FinishReasonPending = CZ_FINISH_REACH_MAX;
            FinishRequestPending = 1U;
        }
    }
}

uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *TxHandle, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode)
{
    uint8_t frame[CZ_FRAME_LEN];
    UART_HandleTypeDef *huart = &huart3;

    LegacyTxID++;
    if (LegacyTxID == 0U)
        LegacyTxID = 1U;

    memset(frame, 0, sizeof(frame));
    frame[0] = Mesg_Head;
    frame[2] = LegacyTxID;
    frame[3] = code_1;
    frame[4] = code_2;
    frame[5] = (uint8_t)(data >> 24U);
    frame[6] = (uint8_t)(data >> 16U);
    frame[7] = (uint8_t)(data >> 8U);
    frame[8] = (uint8_t)data;
    frame[9] = CZ_ACK_NONE;
    frame[10] = expandCode;
    Protocol_FillCrc(frame);
    frame[13] = Mesg_Tail;

    if ((TxHandle != 0) && (TxHandle->huart != 0))
        huart = TxHandle->huart;
    (void)HAL_UART_Transmit(huart, frame, CZ_FRAME_LEN, 100U);
    return LegacyTxID;
}

uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *TxHandle, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode, ListHandle_t *List)
{
    /* 旧控台重发接口已不参与新存珠协议，仅保留链接兼容。 */
    (void)List;
    return Comm_SendMesg_FillData(TxHandle, code_1, code_2, data, expandCode);
}

void Resend_Task(void) {}
void MesgDeal_Task(void) {}

void CommInit(void)
{
    memset(&AndroidRxQueue, 0, sizeof(AndroidRxQueue));
    memset(CommandDedupe, 0, sizeof(CommandDedupe));
    AndroidFrameIndex = 0U;
    BoardTxID = 0U;
    LegacyTxID = 0U;

    memset(&Tx, 0, sizeof(Tx));
    memset(&Rx, 0, sizeof(Rx));
    Tx.huart = &huart3;

    CollectMaximumQuantity = 0U;
    CollectActualQuantity = 0U;
    CollectPulseStarted = 0U;
    CountReportPending = 0U;
    FinishRequestPending = 0U;
    FinishReasonPending = CZ_FINISH_REACH_MAX;
    CollectTimeoutMs = COLLECT_DEFAULT_TIMEOUT_MS;
    CollectState = CZ_STATE_IDLE;
    CurrentErrorCode = CZ_RESULT_OK;
    MotorRetryCount = 0U;
    MotorState = MOTOR_STATE_IDLE;
    TerminalPending = 0U;
    TerminalResendCount = 0U;

    /* TIM2为1MHz光眼脉宽计时；TIM3_CH1/CH2驱动电机。 */
    (void)HAL_TIM_Base_Start(&htim2);
    (void)HAL_TIM_PWM_Start(&htim3, TIM_CHANNEL_1);
    (void)HAL_TIM_PWM_Start(&htim3, TIM_CHANNEL_2);
    Motor_LosePower();

    /* USART3：PB10/PB11连接Android，协议固定115200 8N1。 */
    (void)HAL_UART_Receive_IT(&huart3, &AndroidRxByte, 1U);

    Protocol_SendStatusFrame(CZ_CODE2_BOARD_BOOT);
}

void CommTask(void)
{
    Protocol_ProcessAndroidRx();
    Collect_Task();
    TerminalAck_Task();
}

void Comm_UART_RxCpltCallback(UART_HandleTypeDef *huart)
{
    if (huart == &huart3)
    {
        (void)ByteQueue_Push(&AndroidRxQueue, AndroidRxByte);
        (void)HAL_UART_Receive_IT(&huart3, &AndroidRxByte, 1U);
    }
}

void Comm_UART_ErrorCallback(UART_HandleTypeDef *huart)
{
    uint32_t error_code;

    if (huart != &huart3)
        return;

    error_code = huart->ErrorCode;
    if ((error_code & (HAL_UART_ERROR_ORE | HAL_UART_ERROR_FE | HAL_UART_ERROR_NE | HAL_UART_ERROR_PE)) == 0U)
        return;

    /* STM32F1通过先读SR再读DR统一清除PE/FE/NE/ORE，避免串口异常后永久停收。 */
    __HAL_UART_CLEAR_PEFLAG(huart);
    if (huart->RxState == HAL_UART_STATE_READY)
    {
        huart->ErrorCode = HAL_UART_ERROR_NONE;
        (void)HAL_UART_Receive_IT(&huart3, &AndroidRxByte, 1U);
    }
}
