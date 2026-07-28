#ifndef __APP_DEVICE_H__
#define __APP_DEVICE_H__

#include "main.h"

/*
 *--------------------- 舵机 ---------------------*
 */
void Servo_SetAngle(TIM_HandleTypeDef *htim, uint32_t channel, uint16_t angle);

/*
 *--------------------- 电机 ---------------------*
 */

void Motor_SetSpeed(TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n, uint16_t speed, uint8_t dir);
void Motor_Stop(TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n);
void Motor_LosePower(TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n);

/*
 *--------------------- MOS管开关器件 ---------------------*
 */
void Switch_SetState(GPIO_TypeDef *GPIOx, uint16_t GPIO_Pin, GPIO_PinState state);

#endif
