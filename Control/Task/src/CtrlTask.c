#include "CtrlTask.h"
#include "MesgTask.h"
#include "tim.h"

BeadMotor_t BeadMotor1;
BeadMotor_t BeadMotor2;
Lock_t Lock;

extern Event_Handle_t Mesg_event;

static void Ctrl_BeadMotor(BeadMotor_t *bead_motor,
                           uint16_t speed,
                           uint8_t dir,
                           uint32_t timeout,
                           uint32_t reverse_time,
                           uint8_t retry_times,
                           event_bits_t timeout_event)
{
    if (bead_motor->motor.state == DEVICE_STATE_START)
    {
        bead_motor->motor.SetSpeed(&bead_motor->motor, speed, dir);
        bead_motor->motor.state = DEVICE_STATE_BUSY;
    }

    if (bead_motor->motor.state == DEVICE_STATE_STOP)
    {
        bead_motor->motor.Stop(&bead_motor->motor);
        bead_motor->motor.state = DEVICE_STATE_IDLE;
        bead_motor->remain_num = 0U;
    }

    if (bead_motor->motor.state == DEVICE_STATE_TIMEOUT)
    {
        if (bead_motor->motor.GetRuntime(&bead_motor->motor) > reverse_time)
        {
            if (bead_motor->retry_count < retry_times)
            {
                bead_motor->retry_count++;
                bead_motor->motor.state = DEVICE_STATE_START;
            }
            else
            {
                bead_motor->motor.Stop(&bead_motor->motor);
                bead_motor->motor.state = DEVICE_STATE_IDLE;
                EventGroupSetBits(&Mesg_event, timeout_event);
            }
        }
    }

    if ((bead_motor->motor.state != DEVICE_STATE_IDLE) &&
        (bead_motor->motor.state != DEVICE_STATE_TIMEOUT) &&
        (bead_motor->motor.GetRuntime(&bead_motor->motor) > timeout))
    {
        bead_motor->motor.state = DEVICE_STATE_TIMEOUT;
        bead_motor->motor.LosePower(&bead_motor->motor);
        HAL_Delay(1U);
        bead_motor->motor.SetSpeed(&bead_motor->motor, speed, !dir);
    }
}

static void Ctrl_Lock(Lock_t *lock, uint32_t timeout)
{
    if (lock->sw.state == DEVICE_STATE_START)
    {
        lock->sw.on(&lock->sw);
        lock->sw.state = DEVICE_STATE_BUSY;
    }

    if (lock->sw.state == DEVICE_STATE_STOP)
    {
        lock->sw.off(&lock->sw);
        lock->sw.state = DEVICE_STATE_IDLE;
    }

    if ((lock->sw.state == DEVICE_STATE_BUSY) &&
        (lock->sw.GetRuntime(&lock->sw) > timeout))
    {
        lock->sw.state = DEVICE_STATE_STOP;
    }
}

void BeadMotor_Output(BeadMotor_t *bead_motor, uint16_t num)
{
    if ((bead_motor == NULL) || (num == 0U))
    {
        return;
    }

    /* 防止多次追加数量导致 16 位剩余数量回绕。 */
    if (num > (uint16_t)(0xFFFFU - bead_motor->remain_num))
    {
        return;
    }

    bead_motor->remain_num += num;

    /*
     * 电机运行期间的新命令只追加数量，不重置超时和反转重试状态。
     * 仅在空闲或刚停止时启动新的动作。
     */
    if ((bead_motor->motor.state == DEVICE_STATE_IDLE) ||
        (bead_motor->motor.state == DEVICE_STATE_STOP))
    {
        bead_motor->retry_count = 0U;
        bead_motor->motor.ResetRuntime(&bead_motor->motor);
        bead_motor->motor.state = DEVICE_STATE_START;
    }
}

void BeadMotor_Feedback(BeadMotor_t *bead_motor)
{
    if (bead_motor == NULL)
    {
        return;
    }

    bead_motor->motor.ResetRuntime(&bead_motor->motor);
    bead_motor->retry_count = 0U;

    if (bead_motor->remain_num > 0U)
    {
        bead_motor->remain_num--;
    }

    if ((bead_motor->remain_num == 0U) &&
        (bead_motor->motor.state != DEVICE_STATE_IDLE))
    {
        bead_motor->motor.state = DEVICE_STATE_STOP;
    }
}

void Device_Init(void)
{
    /* 电机1：PE9/PE11 驱动，PD3 光眼反馈，用于吐珠。 */
    Device_Motor_Init(&BeadMotor1.motor, &htim1, TIM_CHANNEL_1, &htim1, TIM_CHANNEL_2);

    /* 电机2：PE13/PE14 驱动，PD4 光眼反馈，用于存珠。 */
    Device_Motor_Init(&BeadMotor2.motor, &htim1, TIM_CHANNEL_3, &htim1, TIM_CHANNEL_4);

    Device_Switch_Init(&Lock.sw, Lock_Valve_GPIO_Port, Lock_Valve_Pin, GPIO_PIN_SET);

    BeadMotor1.remain_num = 0U;
    BeadMotor1.retry_count = 0U;
    BeadMotor2.remain_num = 0U;
    BeadMotor2.retry_count = 0U;
}

void Device_StopAllImmediately(void)
{
    BeadMotor1.motor.LosePower(&BeadMotor1.motor);
    BeadMotor1.motor.state = DEVICE_STATE_IDLE;
    BeadMotor1.remain_num = 0U;
    BeadMotor1.retry_count = 0U;

    BeadMotor2.motor.LosePower(&BeadMotor2.motor);
    BeadMotor2.motor.state = DEVICE_STATE_IDLE;
    BeadMotor2.remain_num = 0U;
    BeadMotor2.retry_count = 0U;

    Lock.sw.off(&Lock.sw);
    Lock.sw.state = DEVICE_STATE_IDLE;
}

void CtrlTask(void)
{
    /* 吐珠电机控制。 */
    Ctrl_BeadMotor(&BeadMotor1,
                   BeadMotor_Speed,
                   BeadMotor_Dir,
                   BeadMotorTimeout_time,
                   BeadMotorReverse_Time,
                   BeadMotorRetry_Times,
                   MesgEvent_BeadMotor1Timeout);

    /* 存珠电机控制。 */
    Ctrl_BeadMotor(&BeadMotor2,
                   BeadMotor_Speed,
                   BeadMotor_Dir,
                   BeadMotorTimeout_time,
                   BeadMotorReverse_Time,
                   BeadMotorRetry_Times,
                   MesgEvent_BeadMotor2Timeout);

    Ctrl_Lock(&Lock, LockOpen_Time);
}