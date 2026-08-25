/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file    gpio.c
  * @brief   This file provides code for the configuration
  *          of all used GPIO pins.
  ******************************************************************************
  */
/* USER CODE END Header */

#include "gpio.h"

void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};

  /* 新转发板仅使用GPIOB的状态灯和第二组出珠光眼。 */
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();
  __HAL_RCC_GPIOD_CLK_ENABLE();

  /* 状态灯由3.3V经电阻和LED接到PB8，默认拉高熄灭。 */
  HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_SET);

  GPIO_InitStruct.Pin = LED_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(LED_GPIO_Port, &GPIO_InitStruct);

  /*
   * HOOLLE_OUTPUT2低电平开始计时、上升沿完成计数，
   * 因此需要同时开启下降沿和上升沿中断。
   */
  GPIO_InitStruct.Pin = HoolleOutput2_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_IT_RISING_FALLING;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(HoolleOutput2_GPIO_Port, &GPIO_InitStruct);

  HAL_NVIC_SetPriority(HoolleOutput2_EXTI_IRQn, 1, 0);
  HAL_NVIC_EnableIRQ(HoolleOutput2_EXTI_IRQn);
}
