#include "InterruptTask.h"
#include "CtrlTask.h"
#include "MesgTask.h"
#include "CommunicateTask.h"
#include "KeyTask.h"
#include "usart.h"

extern Event_Handle_t Mesg_event;
extern BeadMotor_t BeadMotor1;
extern BeadMotor_t BeadMotor2;
extern Rx_HandleTypeDef Rx1;
extern UART_HandleTypeDef huart3;

/*
 * 外部中断只登记脉冲，不在中断上下文直接修改电机状态和消息事件。
 * 主循环每次最多处理每路一个脉冲，避免多个反馈被事件位合并丢失。
 */
static volatile uint16_t BeadMotor1FeedbackPending = 0U;
static volatile uint16_t BeadMotor2FeedbackPending = 0U;

static void BeadMotor1Feedback_IRQ(void)
{
    if (BeadMotor1FeedbackPending < 0xFFFFU)
    {
        BeadMotor1FeedbackPending++;
    }
}

static void BeadMotor2Feedback_IRQ(void)
{
    if (BeadMotor2FeedbackPending < 0xFFFFU)
    {
        BeadMotor2FeedbackPending++;
    }
}

void InterruptTask_Process(void)
{
    uint32_t primask;
    bool process_motor1 = false;
    bool process_motor2 = false;

    /* 临界区内只取出一个待处理脉冲，缩短关中断时间。 */
    primask = __get_PRIMASK();
    __disable_irq();

    if (BeadMotor1FeedbackPending > 0U)
    {
        BeadMotor1FeedbackPending--;
        process_motor1 = true;
    }

    if (BeadMotor2FeedbackPending > 0U)
    {
        BeadMotor2FeedbackPending--;
        process_motor2 = true;
    }

    if (primask == 0U)
    {
        __enable_irq();
    }

    if (process_motor1 == true)
    {
        /* PD3：吐珠电机光眼反馈。 */
        BeadMotor_Feedback(&BeadMotor1);
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadMotor1Feedback);
        EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
    }

    if (process_motor2 == true)
    {
        /* PD4：存珠电机光眼反馈。 */
        BeadMotor_Feedback(&BeadMotor2);
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadMotor2Feedback);
        EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
    }
}

void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)
{
    switch (GPIO_Pin)
    {
    case HoolleOutput_Pin:
        BeadMotor1Feedback_IRQ();
        break;

    case CardFeedback_Pin:
        BeadMotor2Feedback_IRQ();
        break;

    default:
        break;
    }
}

void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart)
{
    if (huart == Rx1.Handle.huart)
    {
        Rx1.Handle.RingBuf.f_WriteByte(&Rx1.Handle.RingBuf, Rx1.Handle.temp_data);
        HAL_UART_Receive_IT(huart, &Rx1.Handle.temp_data, 1U);
    }
    else if (huart == &huart3)
    {
        /* 量产纸钞机仅使用 USART3 TTL 接口。 */
        BillAcceptor_RxCpltCallback();
    }
}