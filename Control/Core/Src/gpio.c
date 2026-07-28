/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file    gpio.c
  * @brief   This file provides code for the configuration
  *          of all used GPIO pins.
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2026 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */

/* Includes ------------------------------------------------------------------*/
#include "gpio.h"

/* USER CODE BEGIN 0 */

/* USER CODE END 0 */

/*----------------------------------------------------------------------------*/
/* Configure GPIO                                                             */
/*----------------------------------------------------------------------------*/
/* USER CODE BEGIN 1 */

/* USER CODE END 1 */

/** Configure pins as
        * Analog
        * Input
        * Output
        * EVENT_OUT
        * EXTI
*/
void MX_GPIO_Init(void)
{

  GPIO_InitTypeDef GPIO_InitStruct = {0};

  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOE_CLK_ENABLE();
  __HAL_RCC_GPIOC_CLK_ENABLE();
  __HAL_RCC_GPIOH_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();
  __HAL_RCC_GPIOD_CLK_ENABLE();

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(LED_GPIO_Port, LED_Pin, GPIO_PIN_SET);

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOC, Valve_Pin|Lock_Valve_Pin|SPI3_CS_Pin, GPIO_PIN_RESET);

  /*Configure GPIO pin Output Level */
  // HAL_GPIO_WritePin(GPIOA, SPI1_CS_Pin|SPI3_OE_Pin, GPIO_PIN_RESET);
  /* SPI1_CS默认低电平 */
  HAL_GPIO_WritePin(SPI1_CS_GPIO_Port,SPI1_CS_Pin,GPIO_PIN_RESET);

  /* SPI3数码管先关闭输出，防止开机乱码 */
  HAL_GPIO_WritePin(SPI3_OE_GPIO_Port,SPI3_OE_Pin,GPIO_PIN_SET);

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOE, CardOutput_Pin|ButtonLight_Pin, GPIO_PIN_RESET);

  /*Configure GPIO pin Output Level */
  // HAL_GPIO_WritePin(GPIOB, SPI2_CS_Pin|SPI2_OE_Pin, GPIO_PIN_RESET);
  /* SPI2_CS默认低电平 */
  HAL_GPIO_WritePin(SPI2_CS_GPIO_Port,SPI2_CS_Pin,GPIO_PIN_RESET);

  /* SPI2数码管先关闭输出，防止开机乱码 */
  HAL_GPIO_WritePin(SPI2_OE_GPIO_Port,SPI2_OE_Pin,GPIO_PIN_SET);

  /*Configure GPIO pins : LED_Pin Valve_Pin Lock_Valve_Pin SPI3_CS_Pin */
  GPIO_InitStruct.Pin = LED_Pin|Valve_Pin|Lock_Valve_Pin|SPI3_CS_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOC, &GPIO_InitStruct);

  /*Configure GPIO pins : HoolleInput_Pin CoinInput_Pin Button3_Pin Button2_Pin */
  GPIO_InitStruct.Pin = HoolleInput_Pin|CoinInput_Pin|Button3_Pin|Button2_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(GPIOC, &GPIO_InitStruct);

  /*Configure GPIO pins : HoolleOutput_Pin CardFeedback_Pin */
  GPIO_InitStruct.Pin = HoolleOutput_Pin|CardFeedback_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_IT_RISING;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(GPIOC, &GPIO_InitStruct);

  /*Configure GPIO pins : SPI1_CS_Pin SPI3_OE_Pin */
  GPIO_InitStruct.Pin = SPI1_CS_Pin|SPI3_OE_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);

  /*Configure GPIO pins : Hole1_Pin Hole2_Pin Switch3_Pin Switch2_Pin
                           Switch1_Pin Switch0_Pin */
  GPIO_InitStruct.Pin = Hole1_Pin|Hole2_Pin|Switch3_Pin|Switch2_Pin
                          |Switch1_Pin|Switch0_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);

  /*Configure GPIO pins : Hole3_Pin Hole4_Pin */
  GPIO_InitStruct.Pin = Hole3_Pin|Hole4_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(GPIOE, &GPIO_InitStruct);

  /*Configure GPIO pins : CardOutput_Pin ButtonLight_Pin */
  GPIO_InitStruct.Pin = CardOutput_Pin|ButtonLight_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOE, &GPIO_InitStruct);

  /*Configure GPIO pins : SPI2_CS_Pin SPI2_OE_Pin */
  GPIO_InitStruct.Pin = SPI2_CS_Pin|SPI2_OE_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);

  /*Configure GPIO pins : Button4_Pin Button5_Pin Button6_Pin SettingButton_Pin
                           KeyBoard3_Pin KeyBoard2_Pin KeyBoard1_Pin Switch11_Pin
                           Switch10_Pin Switch9_Pin Switch8_Pin Switch7_Pin
                           Switch6_Pin Switch5_Pin Switch4_Pin */
  GPIO_InitStruct.Pin = Button4_Pin|Button5_Pin|Button6_Pin|SettingButton_Pin
                          |KeyBoard3_Pin|KeyBoard2_Pin|KeyBoard1_Pin|Switch11_Pin
                          |Switch10_Pin|Switch9_Pin|Switch8_Pin|Switch7_Pin
                          |Switch6_Pin|Switch5_Pin|Switch4_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(GPIOD, &GPIO_InitStruct);

  /*Configure GPIO pin : Button1_Pin */
  GPIO_InitStruct.Pin = Button1_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  HAL_GPIO_Init(Button1_GPIO_Port, &GPIO_InitStruct);

  /* EXTI interrupt init*/
  HAL_NVIC_SetPriority(EXTI1_IRQn, 2, 0);
  HAL_NVIC_EnableIRQ(EXTI1_IRQn);

  HAL_NVIC_SetPriority(EXTI2_IRQn, 0, 0);
  HAL_NVIC_EnableIRQ(EXTI2_IRQn);

}

/* USER CODE BEGIN 2 */

/* USER CODE END 2 */
