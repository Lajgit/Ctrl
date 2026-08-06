/*
 * 中断任务：中断服务函数只登记串口字节和光眼脉冲，
 * 出珠、存珠计数及业务状态转换统一放到主循环中完成，缩短关中断时间。
 */
#include "InterruptTask.h"
#include "CtrlTask.h"
#include "CommunicateTask.h"
#include "KeyTask.h"
#include "usart.h"

/* 两路电机对象分别对应吐珠和存珠机构。 */
extern BeadMotor_t BeadMotor1;
extern BeadMotor_t BeadMotor2;
/* Android 串口接收对象和 ICT 纸钞机串口句柄。 */
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

/* 每次主循环最多取出两路各一个待处理脉冲，避免中断与主循环并发修改计数。 */
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

/* 根据触发引脚将外部中断登记到对应电机反馈计数器。 */
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

/* 将 UART 中断收到的单字节分别交给 Android 环形缓冲区或纸钞机接收队列。 */
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
