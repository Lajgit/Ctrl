#ifndef __CTRLTASK_H__
#define __CTRLTASK_H__

#include "main.h"
#include "port_device.h"

#define BeadMotorTimeout_time 3000U
#define BeadMotorReverse_Time 300U
#define BeadMotorRetry_Times 3U
#define BeadMotor_Speed 85U
#define BeadMotor_Dir 1U
#define LockOpen_Time 800U

typedef struct
{
    uint16_t remain_num;
    uint8_t retry_count;
    motor_t motor;
} BeadMotor_t;

typedef struct
{
    switch_t sw;
} Lock_t;

void Device_Init(void);
void BeadMotor_Output(BeadMotor_t *bead_motor, uint16_t num);
void BeadMotor_Feedback(BeadMotor_t *bead_motor);
void Device_StopAllImmediately(void);
void CtrlTask(void);

#endif
