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

static void BeadMotor1Feedback_IRQ(void)
{
    BeadMotor_Feedback(&BeadMotor1);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadMotor1Feedback);
    EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
}

static void BeadMotor2Feedback_IRQ(void)
{
    BeadMotor_Feedback(&BeadMotor2);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadMotor2Feedback);
    EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
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
