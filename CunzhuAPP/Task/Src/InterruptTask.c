#include "InterruptTask.h"
#include "CommTask.h"
#include "main.h"

void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)
{
    if (GPIO_Pin == HoolleOutput2_Pin)
    {
        Comm_HoolleOutput2IRQ();
    }
}

void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart)
{
    Comm_UART_RxCpltCallback(huart);
}

void HAL_UART_ErrorCallback(UART_HandleTypeDef *huart)
{
    Comm_UART_ErrorCallback(huart);
}
