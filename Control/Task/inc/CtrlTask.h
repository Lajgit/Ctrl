#ifndef __CTRLTASK_H__
#define __CTRLTASK_H__

#include "main.h"
#include "port_device.h"

#define HoolleMotorTimeout_time 3000
#define CardMotorTimeout_time 5000
#define HoolleMotorReverse_Time 300
#define HoolleMotorRetry_Times 3
#define ValveTimeout_time 800

#define HoolleMotor_Speed 85
#define HoolleMotor_Dir 1

typedef struct
{
    uint16_t Hoolle_num;
    uint8_t RetryCount;
    motor_t Motor;
} Motor_Hoolle;

typedef struct
{
    uint16_t Card_num;
    uint8_t RetryCount;
    switch_t Switch;
} Motor_Card;

typedef struct
{
    switch_t Switch;
} Switch_Valve;

void Device_Init(void);
void Hoolle_Output(Motor_Hoolle *Motor, uint16_t num);
void Card_Output(Motor_Card *Switch, uint16_t num);
void Device_StopAllImmediately(void);
void CtrlTask(void);

#endif
