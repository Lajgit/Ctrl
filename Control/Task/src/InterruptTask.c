#include "InterruptTask.h"
#include "CtrlTask.h"
#include "CommunicateTask.h"
#include "KeyTask.h"
#include "usart.h"

extern BeadMotor_t BeadMotor1;
extern BeadMotor_t BeadMotor2;
extern Rx_HandleTypeDef Rx1;
extern UART_HandleTypeDef huart3;

/* 中断只累计脉冲，真实计数和终态在主循环中处理。 */
static volatile uint16_t BeadMotor1FeedbackPending = 0U;
static volatile uint16_t BeadMotor2FeedbackPending = 0U;

/* 出珠光眼：PD4。 */
static void BeadMotor1Feedback_IRQ(void)
{
    if (BeadMotor1FeedbackPending < 0xFFFFU)
    {
        BeadMotor1FeedbackPending++;
    }
}

/* 存珠光眼：PD3。 */
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

    if (process_motor1)
    {
        Hardware_OnDispensePulse();
    }
    if (process_motor2)
    {
        Hardware_OnCollectPulse();
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
        BillAcceptor_RxCpltCallback();
    }
}
