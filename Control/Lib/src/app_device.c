#include "app_device.h"

/*
 *--------------------- 舵机 ---------------------*
 */

void Servo_SetAngle(TIM_HandleTypeDef *htim, uint32_t channel, uint16_t angle)
{
    uint16_t crr = (htim->Init.Period + 1) / 20 * (angle + 45) / 90;
    __HAL_TIM_SetCompare(htim, channel, crr);
}

/*
 *--------------------- 电机 ---------------------*
 */

void Motor_SetSpeed(TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n, uint16_t speed, uint8_t dir)
{
    if (dir == 0)
    {
        uint16_t compare = htim_p->Init.Period * speed / 100;
        __HAL_TIM_SetCompare(htim_p, channel_p, compare);
        __HAL_TIM_SetCompare(htim_n, channel_n, 0);
    }
    else
    {
        uint16_t compare = htim_p->Init.Period * speed / 100;
        __HAL_TIM_SetCompare(htim_p, channel_p, 0);
        __HAL_TIM_SetCompare(htim_n, channel_n, compare);
    }
}

void Motor_Stop(TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n)
{
    __HAL_TIM_SetCompare(htim_p, channel_p, htim_p->Init.Period);
    __HAL_TIM_SetCompare(htim_n, channel_n, htim_n->Init.Period);
}

void Motor_LosePower(TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n)
{
    __HAL_TIM_SetCompare(htim_p, channel_p, 0);
    __HAL_TIM_SetCompare(htim_n, channel_n, 0);
}

/*
 *--------------------- MOS管开关器件 ---------------------*
 */

void Switch_SetState(GPIO_TypeDef *GPIOx, uint16_t GPIO_Pin, GPIO_PinState state)
{
    HAL_GPIO_WritePin(GPIOx, GPIO_Pin, state);
}