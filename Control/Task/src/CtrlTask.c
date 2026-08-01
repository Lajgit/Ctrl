#include "CtrlTask.h"
#include "MesgTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "tim.h"
#include "string.h"

BeadMotor_t BeadMotor1;
BeadMotor_t BeadMotor2;
Lock_t Lock;

extern Event_Handle_t Mesg_event;
extern Setting_TypeDef Setting;

static HardwareOperation_t DispenseOperation;
static HardwareOperation_t CollectOperation;
static bool LowStockNotified = false;

static void Hardware_ResetOperation(HardwareOperation_t *operation)
{
    memset(operation, 0, sizeof(*operation));
    operation->result = HW_RESULT_OK;
}

static void Hardware_SetImmediateResult(HardwareOperation_t *operation,
                                        uint8_t token,
                                        uint32_t requested,
                                        uint8_t result)
{
    Hardware_ResetOperation(operation);
    operation->token = token;
    operation->requested = requested;
    operation->actual = 0U;
    operation->active = false;
    operation->result = result;
}

static void Ctrl_BeadMotor(BeadMotor_t *bead_motor,
                           uint16_t speed,
                           uint8_t dir,
                           uint32_t timeout,
                           uint32_t reverse_time,
                           uint8_t retry_times,
                           bool dispense_motor)
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
            bead_motor->retry_count++;
            bead_motor->motor.ResetRuntime(&bead_motor->motor);
            bead_motor->motor.state = DEVICE_STATE_START;
        }
    }

    if ((bead_motor->motor.state != DEVICE_STATE_IDLE) &&
        (bead_motor->motor.state != DEVICE_STATE_TIMEOUT) &&
        (bead_motor->motor.GetRuntime(&bead_motor->motor) > timeout))
    {
        if (bead_motor->retry_count < retry_times)
        {
            bead_motor->motor.state = DEVICE_STATE_TIMEOUT;
            bead_motor->motor.LosePower(&bead_motor->motor);
            HAL_Delay(1U);
            bead_motor->motor.SetSpeed(&bead_motor->motor, speed, !dir);
        }
        else
        {
            bead_motor->motor.Stop(&bead_motor->motor);
            bead_motor->motor.state = DEVICE_STATE_IDLE;
            bead_motor->remain_num = 0U;
            if (dispense_motor)
            {
                Hardware_OnDispenseTimeout();
            }
            else
            {
                Hardware_OnCollectTimeout();
            }
        }
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
    if ((bead_motor == NULL) || (num == 0U) ||
        (bead_motor->motor.state != DEVICE_STATE_IDLE))
    {
        return;
    }

    bead_motor->remain_num = num;
    bead_motor->retry_count = 0U;
    bead_motor->motor.ResetRuntime(&bead_motor->motor);
    bead_motor->motor.state = DEVICE_STATE_START;
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
    Device_Motor_Init(&BeadMotor1.motor, &htim1, TIM_CHANNEL_1, &htim1, TIM_CHANNEL_2);
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

void Hardware_Init(void)
{
    Hardware_ResetOperation(&DispenseOperation);
    Hardware_ResetOperation(&CollectOperation);
    LowStockNotified = Setting.BeadStock <= HARDWARE_LOW_STOCK_THRESHOLD;

    /* 新协议上电一律关闭现金，等待平台完整现金配置下发并应用。 */
    CashAcceptance_Disable();
}

bool Hardware_StartDispense(uint8_t token, uint32_t quantity)
{
    if ((token == 0U) || (quantity == 0U) ||
        (quantity > HARDWARE_MAX_OPERATION_QUANTITY))
    {
        Hardware_SetImmediateResult(&DispenseOperation,
                                    token,
                                    quantity,
                                    HW_RESULT_INVALID_QUANTITY);
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseFailed);
        return false;
    }
    if (DispenseOperation.active || CollectOperation.active ||
        (BeadMotor1.motor.state != DEVICE_STATE_IDLE) ||
        (BeadMotor2.motor.state != DEVICE_STATE_IDLE))
    {
        Hardware_SetImmediateResult(&DispenseOperation, token, quantity, HW_RESULT_BUSY);
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseFailed);
        return false;
    }
    if ((Setting.BeadStock == 0U) ||
        ((Setting.HardwareFlags & HARDWARE_FLAG_NO_BEAD) != 0U))
    {
        Hardware_SetImmediateResult(&DispenseOperation, token, quantity, HW_RESULT_NO_BEAD);
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseFailed);
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
        return false;
    }

    Hardware_ResetOperation(&DispenseOperation);
    DispenseOperation.active = true;
    DispenseOperation.token = token;
    DispenseOperation.requested = quantity;
    BeadMotor_Output(&BeadMotor1, (uint16_t)quantity);
    EventGroupSetBits(&Mesg_event, MesgEvent_DispenseStarted);
    return true;
}

bool Hardware_StartCollect(uint8_t token, uint32_t maximum_quantity)
{
    if ((token == 0U) || (maximum_quantity == 0U) ||
        (maximum_quantity > HARDWARE_MAX_OPERATION_QUANTITY))
    {
        Hardware_SetImmediateResult(&CollectOperation,
                                    token,
                                    maximum_quantity,
                                    HW_RESULT_INVALID_QUANTITY);
        EventGroupSetBits(&Mesg_event, MesgEvent_CollectFailed);
        return false;
    }
    if (DispenseOperation.active || CollectOperation.active ||
        (BeadMotor1.motor.state != DEVICE_STATE_IDLE) ||
        (BeadMotor2.motor.state != DEVICE_STATE_IDLE))
    {
        Hardware_SetImmediateResult(&CollectOperation,
                                    token,
                                    maximum_quantity,
                                    HW_RESULT_BUSY);
        EventGroupSetBits(&Mesg_event, MesgEvent_CollectFailed);
        return false;
    }

    Hardware_ResetOperation(&CollectOperation);
    CollectOperation.active = true;
    CollectOperation.token = token;
    CollectOperation.requested = maximum_quantity;
    BeadMotor_Output(&BeadMotor2, (uint16_t)maximum_quantity);
    EventGroupSetBits(&Mesg_event, MesgEvent_CollectStarted);
    return true;
}

void Hardware_StopCollect(uint8_t token)
{
    if (!CollectOperation.active || (token != CollectOperation.token))
    {
        Hardware_SetImmediateResult(&CollectOperation, token, 0U, HW_RESULT_NOT_ACTIVE);
        EventGroupSetBits(&Mesg_event, MesgEvent_CollectFailed);
        return;
    }

    BeadMotor2.motor.Stop(&BeadMotor2.motor);
    BeadMotor2.motor.state = DEVICE_STATE_IDLE;
    BeadMotor2.remain_num = 0U;
    BeadMotor2.retry_count = 0U;
    CollectOperation.active = false;
    CollectOperation.result = HW_RESULT_OK;
    EventGroupSetBits(&Mesg_event, MesgEvent_CollectCompleted);
}

void Hardware_AbortAll(void)
{
    bool dispense_was_active = DispenseOperation.active;
    bool collect_was_active = CollectOperation.active;

    Device_StopAllImmediately();
    if (dispense_was_active)
    {
        DispenseOperation.active = false;
        DispenseOperation.result = HW_RESULT_ABORTED;
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseFailed);
    }
    if (collect_was_active)
    {
        CollectOperation.active = false;
        CollectOperation.result = HW_RESULT_ABORTED;
        EventGroupSetBits(&Mesg_event, MesgEvent_CollectFailed);
    }
}

void Hardware_OnDispensePulse(void)
{
    if (!DispenseOperation.active)
    {
        return;
    }

    BeadMotor_Feedback(&BeadMotor1);
    if (DispenseOperation.actual < DispenseOperation.requested)
    {
        DispenseOperation.actual++;
    }
    if (Setting.BeadStock > 0U)
    {
        Setting.BeadStock--;
    }
    FlashTask_RequestSave();
    EventGroupSetBits(&Mesg_event, MesgEvent_DispenseProgress);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);

    if ((!LowStockNotified) &&
        (Setting.BeadStock <= HARDWARE_LOW_STOCK_THRESHOLD))
    {
        LowStockNotified = true;
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadLowStock);
    }

    if (DispenseOperation.actual >= DispenseOperation.requested)
    {
        DispenseOperation.active = false;
        DispenseOperation.result = HW_RESULT_OK;
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseCompleted);
    }
}

void Hardware_OnCollectPulse(void)
{
    if (!CollectOperation.active)
    {
        return;
    }

    BeadMotor_Feedback(&BeadMotor2);
    if (CollectOperation.actual < CollectOperation.requested)
    {
        CollectOperation.actual++;
    }
    EventGroupSetBits(&Mesg_event, MesgEvent_CollectProgress);
    if (CollectOperation.actual >= CollectOperation.requested)
    {
        CollectOperation.active = false;
        CollectOperation.result = HW_RESULT_OK;
        EventGroupSetBits(&Mesg_event, MesgEvent_CollectCompleted);
    }
}

void Hardware_OnDispenseTimeout(void)
{
    if (!DispenseOperation.active)
    {
        return;
    }

    DispenseOperation.active = false;
    DispenseOperation.result = HW_RESULT_SENSOR_TIMEOUT;
    Setting.BeadStock = 0U;
    Setting.HardwareFlags |= HARDWARE_FLAG_NO_BEAD;
    LowStockNotified = true;
    CashAcceptance_Disable();
    FlashTask_RequestSave();

    EventGroupSetBits(&Mesg_event, MesgEvent_DispenseFailed);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
}

void Hardware_OnCollectTimeout(void)
{
    if (!CollectOperation.active)
    {
        return;
    }
    CollectOperation.active = false;
    CollectOperation.result = HW_RESULT_SENSOR_TIMEOUT;
    EventGroupSetBits(&Mesg_event, MesgEvent_CollectFailed);
}

void Hardware_Refill(void)
{
    Setting.BeadStock = HARDWARE_DEFAULT_STOCK;
    Setting.HardwareFlags &= ~HARDWARE_FLAG_NO_BEAD;
    LowStockNotified = false;
    FlashTask_RequestSave();

    /* 补珠只恢复库存事实，不恢复旧任务，也不自动开启现金接收。 */
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadRefilled);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
}

void Hardware_RequestStatus(void)
{
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
    CashAcceptance_RequestStatus();
    if (DispenseOperation.active)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseProgress);
    }
    if (CollectOperation.active)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_CollectProgress);
    }
}

uint32_t Hardware_GetBeadStock(void)
{
    return Setting.BeadStock;
}

bool Hardware_IsNoBead(void)
{
    return (Setting.HardwareFlags & HARDWARE_FLAG_NO_BEAD) != 0U;
}

bool Hardware_IsDispenseActive(void)
{
    return DispenseOperation.active;
}

bool Hardware_IsCollectActive(void)
{
    return CollectOperation.active;
}

const HardwareOperation_t *Hardware_GetDispenseReport(void)
{
    return &DispenseOperation;
}

const HardwareOperation_t *Hardware_GetCollectReport(void)
{
    return &CollectOperation;
}

void CtrlTask(void)
{
    Ctrl_BeadMotor(&BeadMotor1,
                   BeadMotor_Speed,
                   BeadMotor_Dir,
                   BeadMotorTimeout_time,
                   BeadMotorReverse_Time,
                   BeadMotor1Retry_Times,
                   true);

    Ctrl_BeadMotor(&BeadMotor2,
                   BeadMotor_Speed,
                   BeadMotor_Dir,
                   BeadMotorTimeout_time,
                   BeadMotorReverse_Time,
                   BeadMotor2Retry_Times,
                   false);

    Ctrl_Lock(&Lock, LockOpen_Time);
}
