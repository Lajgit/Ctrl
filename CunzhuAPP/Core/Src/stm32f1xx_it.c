/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file    stm32f1xx_it.c
  * @brief   Interrupt Service Routines.
  ******************************************************************************
  */
/* USER CODE END Header */

#include "main.h"
#include "stm32f1xx_it.h"

extern UART_HandleTypeDef huart2;
extern UART_HandleTypeDef huart3;

void NMI_Handler(void)
{
  while (1) {}
}

void HardFault_Handler(void)
{
  while (1) {}
}

void MemManage_Handler(void)
{
  while (1) {}
}

void BusFault_Handler(void)
{
  while (1) {}
}

void UsageFault_Handler(void)
{
  while (1) {}
}

void SVC_Handler(void) {}
void DebugMon_Handler(void) {}
void PendSV_Handler(void) {}

void SysTick_Handler(void)
{
  HAL_IncTick();
}

/* 第二组出珠光眼PB0中断。 */
void EXTI0_IRQHandler(void)
{
  HAL_GPIO_EXTI_IRQHandler(HoolleOutput2_Pin);
}

/* USART2连接原球盘主板。 */
void USART2_IRQHandler(void)
{
  HAL_UART_IRQHandler(&huart2);
}

/* USART3连接安卓板。 */
void USART3_IRQHandler(void)
{
  HAL_UART_IRQHandler(&huart3);
}
