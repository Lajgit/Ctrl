/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.h
  * @brief          : Header for main.c file.
  *                   This file contains the common defines of the application.
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

/* Define to prevent recursive inclusion -------------------------------------*/
#ifndef __MAIN_H
#define __MAIN_H

#ifdef __cplusplus
extern "C" {
#endif

/* Includes ------------------------------------------------------------------*/
#include "stm32f4xx_hal.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */

/* USER CODE END Includes */

/* Exported types ------------------------------------------------------------*/
/* USER CODE BEGIN ET */

/* USER CODE END ET */

/* Exported constants --------------------------------------------------------*/
/* USER CODE BEGIN EC */

/* USER CODE END EC */

/* Exported macro ------------------------------------------------------------*/
/* USER CODE BEGIN EM */

/* USER CODE END EM */

/* Exported functions prototypes ---------------------------------------------*/
void Error_Handler(void);

/* USER CODE BEGIN EFP */

/* USER CODE END EFP */

/* Private defines -----------------------------------------------------------*/
#define J4_Pin GPIO_PIN_5
#define J4_GPIO_Port GPIOE
#define J5_Pin GPIO_PIN_6
#define J5_GPIO_Port GPIOE
#define LED_Pin GPIO_PIN_13
#define LED_GPIO_Port GPIOC
#define Valve_Pin GPIO_PIN_14
#define Valve_GPIO_Port GPIOC
#define Lock_Valve_Pin GPIO_PIN_15
#define Lock_Valve_GPIO_Port GPIOC
#define HoolleInput_Pin GPIO_PIN_0
#define HoolleInput_GPIO_Port GPIOC
#define HoolleOutput_Pin GPIO_PIN_1
#define HoolleOutput_GPIO_Port GPIOC
#define HoolleOutput_EXTI_IRQn EXTI1_IRQn
#define CardFeedback_Pin GPIO_PIN_2
#define CardFeedback_GPIO_Port GPIOC
#define CardFeedback_EXTI_IRQn EXTI2_IRQn
#define CoinInput_Pin GPIO_PIN_3
#define CoinInput_GPIO_Port GPIOC
#define J1_Pin GPIO_PIN_1
#define J1_GPIO_Port GPIOA
#define J2_Pin GPIO_PIN_2
#define J2_GPIO_Port GPIOA
#define J3_Pin GPIO_PIN_3
#define J3_GPIO_Port GPIOA
#define SPI1_CS_Pin GPIO_PIN_4
#define SPI1_CS_GPIO_Port GPIOA
#define Hole1_Pin GPIO_PIN_1
#define Hole1_GPIO_Port GPIOB
#define Hole2_Pin GPIO_PIN_2
#define Hole2_GPIO_Port GPIOB
#define Hole3_Pin GPIO_PIN_7
#define Hole3_GPIO_Port GPIOE
#define Hole4_Pin GPIO_PIN_8
#define Hole4_GPIO_Port GPIOE
#define HoolleMotor_A_Pin GPIO_PIN_9
#define HoolleMotor_A_GPIO_Port GPIOE
#define CardOutput_Pin GPIO_PIN_10
#define CardOutput_GPIO_Port GPIOE
#define HoolleMotor_B_Pin GPIO_PIN_11
#define HoolleMotor_B_GPIO_Port GPIOE
#define SPI2_CS_Pin GPIO_PIN_12
#define SPI2_CS_GPIO_Port GPIOB
#define SPI2_OE_Pin GPIO_PIN_14
#define SPI2_OE_GPIO_Port GPIOB
#define Button4_Pin GPIO_PIN_8
#define Button4_GPIO_Port GPIOD
#define Button5_Pin GPIO_PIN_9
#define Button5_GPIO_Port GPIOD
#define Button6_Pin GPIO_PIN_10
#define Button6_GPIO_Port GPIOD
#define SettingButton_Pin GPIO_PIN_11
#define SettingButton_GPIO_Port GPIOD
#define KeyBoard3_Pin GPIO_PIN_12
#define KeyBoard3_GPIO_Port GPIOD
#define KeyBoard2_Pin GPIO_PIN_13
#define KeyBoard2_GPIO_Port GPIOD
#define KeyBoard1_Pin GPIO_PIN_14
#define KeyBoard1_GPIO_Port GPIOD
#define WS2812B_Pin GPIO_PIN_7
#define WS2812B_GPIO_Port GPIOC
#define Button3_Pin GPIO_PIN_8
#define Button3_GPIO_Port GPIOC
#define Button2_Pin GPIO_PIN_9
#define Button2_GPIO_Port GPIOC
#define Button1_Pin GPIO_PIN_8
#define Button1_GPIO_Port GPIOA
#define SPI3_OE_Pin GPIO_PIN_15
#define SPI3_OE_GPIO_Port GPIOA
#define SPI3_CS_Pin GPIO_PIN_11
#define SPI3_CS_GPIO_Port GPIOC
#define Switch11_Pin GPIO_PIN_0
#define Switch11_GPIO_Port GPIOD
#define Switch10_Pin GPIO_PIN_1
#define Switch10_GPIO_Port GPIOD
#define Switch9_Pin GPIO_PIN_2
#define Switch9_GPIO_Port GPIOD
#define Switch8_Pin GPIO_PIN_3
#define Switch8_GPIO_Port GPIOD
#define Switch7_Pin GPIO_PIN_4
#define Switch7_GPIO_Port GPIOD
#define Switch6_Pin GPIO_PIN_5
#define Switch6_GPIO_Port GPIOD
#define Switch5_Pin GPIO_PIN_6
#define Switch5_GPIO_Port GPIOD
#define Switch4_Pin GPIO_PIN_7
#define Switch4_GPIO_Port GPIOD
#define Switch3_Pin GPIO_PIN_3
#define Switch3_GPIO_Port GPIOB
#define Switch2_Pin GPIO_PIN_4
#define Switch2_GPIO_Port GPIOB
#define Switch1_Pin GPIO_PIN_5
#define Switch1_GPIO_Port GPIOB
#define Switch0_Pin GPIO_PIN_6
#define Switch0_GPIO_Port GPIOB
#define J6_Pin GPIO_PIN_8
#define J6_GPIO_Port GPIOB
#define ButtonLight_Pin GPIO_PIN_1
#define ButtonLight_GPIO_Port GPIOE

/* USER CODE BEGIN Private defines */

/* USER CODE END Private defines */

#ifdef __cplusplus
}
#endif

#endif /* __MAIN_H */
