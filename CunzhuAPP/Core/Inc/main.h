/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.h
  * @brief          : Header for main.c file.
  *                   This file contains the common defines of the application.
  ******************************************************************************
  */
/* USER CODE END Header */

#ifndef __MAIN_H
#define __MAIN_H

#ifdef __cplusplus
extern "C" {
#endif

#include "stm32f1xx_hal.h"

void Error_Handler(void);

/* UART转发板实际硬件定义 */
#define LED_Pin GPIO_PIN_8
#define LED_GPIO_Port GPIOB
#define Motor2_FI_Pin GPIO_PIN_6
#define Motor2_FI_GPIO_Port GPIOA
#define Motor2_BI_Pin GPIO_PIN_7
#define Motor2_BI_GPIO_Port GPIOA
#define HoolleOutput2_Pin GPIO_PIN_0
#define HoolleOutput2_GPIO_Port GPIOB
#define HoolleOutput2_EXTI_IRQn EXTI0_IRQn

/*
 * 以下旧控台引脚别名仅用于让原工程中未调用的旧模块继续参与编译。
 * 新转发板运行路径不会初始化或访问这些旧业务引脚。
 */
#define Encoder_A_Pin GPIO_PIN_0
#define Encoder_A_GPIO_Port GPIOA
#define Encoder_B_Pin GPIO_PIN_1
#define Encoder_B_GPIO_Port GPIOA
#define Button6_Pin GPIO_PIN_4
#define Button6_GPIO_Port GPIOA
#define Button1_Pin GPIO_PIN_5
#define Button1_GPIO_Port GPIOA
#define WS2812_1_Pin Motor2_FI_Pin
#define WS2812_1_GPIO_Port Motor2_FI_GPIO_Port
#define Button2_Pin Motor2_BI_Pin
#define Button2_GPIO_Port Motor2_BI_GPIO_Port
#define Button5_Pin HoolleOutput2_Pin
#define Button5_GPIO_Port HoolleOutput2_GPIO_Port
#define WS2812_2_Pin GPIO_PIN_1
#define WS2812_2_GPIO_Port GPIOB
#define SPI2_OE_Pin GPIO_PIN_12
#define SPI2_OE_GPIO_Port GPIOB
#define SPI2_LE_Pin GPIO_PIN_14
#define SPI2_LE_GPIO_Port GPIOB
#define PWM1_Pin GPIO_PIN_8
#define PWM1_GPIO_Port GPIOA
#define PWM2_Pin GPIO_PIN_9
#define PWM2_GPIO_Port GPIOA
#define Button3_Pin GPIO_PIN_10
#define Button3_GPIO_Port GPIOA
#define Encoder_K_Pin GPIO_PIN_15
#define Encoder_K_GPIO_Port GPIOA
#define KeyBoard1_Pin GPIO_PIN_3
#define KeyBoard1_GPIO_Port GPIOB
#define KeyBoard2_Pin GPIO_PIN_4
#define KeyBoard2_GPIO_Port GPIOB
#define KeyBoard3_Pin GPIO_PIN_5
#define KeyBoard3_GPIO_Port GPIOB
#define KeyBoard4_Pin GPIO_PIN_6
#define KeyBoard4_GPIO_Port GPIOB
#define Button4_Pin GPIO_PIN_7
#define Button4_GPIO_Port GPIOB

#ifdef __cplusplus
}
#endif

#endif /* __MAIN_H */
