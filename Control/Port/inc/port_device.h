#ifndef __PORT_DEVICE_H__
#define __PORT_DEVICE_H__

#include "app_device.h"

// 设备状态
typedef enum
{
    DEVICE_STATE_IDLE = 0,
    DEVICE_STATE_START = 1,
    DEVICE_STATE_BUSY = 2,
    DEVICE_STATE_STOP = 3,
    DEVICE_STATE_TIMEOUT = 4,
    DEVICE_STATE_PAUSE = 5,
} Device_State;
// 舵机结构体
typedef struct
{
    TIM_HandleTypeDef *htim;
    uint32_t channel;
    uint16_t angle;
    uint16_t min_angle;
    uint16_t max_angle;
    uint16_t (*GetAngle)(void *self);
    void (*SetAngle)(void *self, uint16_t angle);
    void (*IncreaseAngle)(void *self, uint16_t angle);
    void (*DecreaseAngle)(void *self, uint16_t angle);
} servo_t;
// 电机结构体
typedef struct
{
    TIM_HandleTypeDef *htim_p;
    TIM_HandleTypeDef *htim_n;
    uint32_t channel_p;
    uint32_t channel_n;
    uint32_t runtick;
    uint16_t speed;
    uint8_t direction;
    Device_State state;
    void (*SetSpeed)(void *self, uint16_t speed, uint8_t direction);
    void (*Stop)(void *self);
    void (*LosePower)(void *self);
    void (*ResetRuntime)(void *self);
    uint32_t (*GetRuntime)(void *self);
} motor_t;
// 开关器件结构体s
typedef struct
{
    GPIO_TypeDef *gpio;
    uint16_t pin;
    uint32_t runtick;
    Device_State state;
    GPIO_PinState level;
    void (*on)(void *self);
    void (*off)(void *self);
    void (*ResetRuntime)(void *self);
    uint32_t (*GetRuntime)(void *self);
} switch_t;

// 设备初始化
void Device_Servo_Init(servo_t *servo, TIM_HandleTypeDef *htim, uint32_t channel, uint16_t min_angle, uint16_t max_angle, uint16_t default_angle);
void Device_Motor_Init(motor_t *motor, TIM_HandleTypeDef *htim_p, uint32_t channel_p, TIM_HandleTypeDef *htim_n, uint32_t channel_n);
void Device_Switch_Init(switch_t *Switch, GPIO_TypeDef *gpio, uint16_t pin, GPIO_PinState level);

#endif
