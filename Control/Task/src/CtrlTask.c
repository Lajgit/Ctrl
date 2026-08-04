#include "CtrlTask.h"
#include "MesgTask.h"
#include "CommunicateTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "tim.h"
#include "string.h"

typedef struct
{
    bool active;
    uint16_t requestedQuantity;
    uint16_t actualQuantity;
} CollectMaintenance_t;

BeadMotor_t BeadMotor1;
BeadMotor_t BeadMotor2;
Lock_t Lock;

extern Event_Handle_t Mesg_event;
extern Setting_TypeDef Setting;

static DispenseOrder_t DispenseOrder;
static LastDispenseResult_t LastDispenseResult;
static CollectMaintenance_t CollectMaintenance;
static bool LowStockNotified = false;

static void Hardware_ResetDispenseOrder(void)
{
    memset(&DispenseOrder, 0, sizeof(DispenseOrder));
    DispenseOrder.state = DISPENSE_STATE_IDLE;
    DispenseOrder.resultCode = HW_RESULT_OK;
}

static bool Hardware_IsDispenseSuccess(const DispenseOrder_t *order)
{
    return (order != NULL) &&
           (order->resultCode == HW_RESULT_OK) &&
           (order->actualQuantity == order->requestedQuantity) &&
           (order->actualQuantity > 0U);
}

static void Hardware_SaveLastDispenseResult(void)
{
    LastDispenseResult.valid = true;
    LastDispenseResult.orderSequence = DispenseOrder.orderSequence;
    LastDispenseResult.requestedQuantity = DispenseOrder.requestedQuantity;
    LastDispenseResult.actualQuantity = DispenseOrder.actualQuantity;
    LastDispenseResult.resultCode = DispenseOrder.resultCode;
    LastDispenseResult.terminalFrameId = DispenseOrder.terminalFrameId;
}

static bool Hardware_LastMatches(uint16_t order_sequence,
                                 uint16_t requested_quantity)
{
    return LastDispenseResult.valid &&
           (LastDispenseResult.orderSequence == order_sequence) &&
           (LastDispenseResult.requestedQuantity == requested_quantity);
}

static void Hardware_SendDispenseReject(uint16_t order_sequence,
                                        uint16_t actual_quantity,
                                        uint8_t result_code)
{
    (void)Comm_SendDispenseTerminalOnce(order_sequence,
                                        actual_quantity,
                                        result_code);
}

static void Hardware_StopDispenseMotor(void)
{
    BeadMotor1.motor.Stop(&BeadMotor1.motor);
    BeadMotor1.motor.state = DEVICE_STATE_IDLE;
    BeadMotor1.remain_num = 0U;
    BeadMotor1.retry_count = 0U;
}

static void Hardware_StopCollectMotor(void)
{
    BeadMotor2.motor.Stop(&BeadMotor2.motor);
    BeadMotor2.motor.state = DEVICE_STATE_IDLE;
    BeadMotor2.remain_num = 0U;
    BeadMotor2.retry_count = 0U;
}

static bool Hardware_HasNoBead(void)
{
    return (Setting.BeadStock == 0U) ||
           ((Setting.HardwareFlags & HARDWARE_FLAG_NO_BEAD) != 0U);
}

static void Hardware_SetDispenseTerminal(uint8_t result_code)
{
    if (DispenseOrder.state != DISPENSE_STATE_RUNNING)
    {
        return;
    }

    Hardware_StopDispenseMotor();
    DispenseOrder.resultCode = result_code;
    DispenseOrder.terminalFrameId = 0U;
    DispenseOrder.terminalPending = true;
    DispenseOrder.state = DISPENSE_STATE_WAIT_TERMINAL_ACK;
    EventGroupSetBits(&Mesg_event, MesgEvent_DispenseTerminal);
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
    Hardware_ResetDispenseOrder();
    memset(&LastDispenseResult, 0, sizeof(LastDispenseResult));
    memset(&CollectMaintenance, 0, sizeof(CollectMaintenance));
    LowStockNotified = Setting.BeadStock <= HARDWARE_LOW_STOCK_THRESHOLD;
    CashAcceptance_Disable();
}

bool Hardware_StartDispenseOrder(uint16_t order_sequence,
                                 uint16_t requested_quantity)
{
    if (DispenseOrder.state == DISPENSE_STATE_RUNNING)
    {
        if ((DispenseOrder.orderSequence == order_sequence) &&
            (DispenseOrder.requestedQuantity == requested_quantity))
        {
            return true;
        }
        Hardware_SendDispenseReject(order_sequence,
                                    0U,
                                    DispenseOrder.orderSequence == order_sequence
                                        ? HW_RESULT_ORDER_SEQUENCE_MISMATCH
                                        : HW_RESULT_BUSY);
        return false;
    }

    if (DispenseOrder.state == DISPENSE_STATE_WAIT_TERMINAL_ACK)
    {
        if ((DispenseOrder.orderSequence == order_sequence) &&
            (DispenseOrder.requestedQuantity == requested_quantity))
        {
            EventGroupSetBits(&Mesg_event, MesgEvent_DispenseTerminal);
            return true;
        }
        Hardware_SendDispenseReject(order_sequence,
                                    0U,
                                    DispenseOrder.orderSequence == order_sequence
                                        ? HW_RESULT_ORDER_SEQUENCE_MISMATCH
                                        : HW_RESULT_BUSY);
        return false;
    }

    if (DispenseOrder.state == DISPENSE_STATE_BLOCKED)
    {
        if (Hardware_LastMatches(order_sequence, requested_quantity))
        {
            Hardware_SendDispenseReject(order_sequence,
                                        LastDispenseResult.actualQuantity,
                                        LastDispenseResult.resultCode);
        }
        else
        {
            Hardware_SendDispenseReject(order_sequence,
                                        0U,
                                        LastDispenseResult.valid &&
                                                (LastDispenseResult.orderSequence == order_sequence)
                                            ? HW_RESULT_ORDER_SEQUENCE_MISMATCH
                                            : HW_RESULT_BLOCKED);
        }
        return false;
    }

    if (Hardware_LastMatches(order_sequence, requested_quantity))
    {
        Hardware_SendDispenseReject(order_sequence,
                                    LastDispenseResult.actualQuantity,
                                    LastDispenseResult.resultCode);
        return false;
    }
    if (LastDispenseResult.valid &&
        (LastDispenseResult.orderSequence == order_sequence))
    {
        Hardware_SendDispenseReject(order_sequence,
                                    0U,
                                    HW_RESULT_ORDER_SEQUENCE_MISMATCH);
        return false;
    }

    if ((order_sequence == 0U) || (requested_quantity == 0U))
    {
        Hardware_SendDispenseReject(order_sequence,
                                    0U,
                                    HW_RESULT_INVALID_QUANTITY);
        return false;
    }
    if (CollectMaintenance.active ||
        (BeadMotor1.motor.state != DEVICE_STATE_IDLE) ||
        (BeadMotor2.motor.state != DEVICE_STATE_IDLE))
    {
        Hardware_SendDispenseReject(order_sequence, 0U, HW_RESULT_BUSY);
        return false;
    }

    memset(&DispenseOrder, 0, sizeof(DispenseOrder));
    DispenseOrder.state = DISPENSE_STATE_RUNNING;
    DispenseOrder.orderSequence = order_sequence;
    DispenseOrder.requestedQuantity = requested_quantity;
    DispenseOrder.resultCode = HW_RESULT_OK;

    CashAcceptance_Disable();

    if (Hardware_HasNoBead())
    {
        Setting.BeadStock = 0U;
        Setting.HardwareFlags |= HARDWARE_FLAG_NO_BEAD;
        LowStockNotified = true;
        FlashTask_RequestSave();
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
        Hardware_SetDispenseTerminal(HW_RESULT_NO_BEAD);
        return false;
    }

    BeadMotor_Output(&BeadMotor1, requested_quantity);
    return true;
}

bool Hardware_StartCollect(uint32_t maximum_quantity)
{
    if ((maximum_quantity == 0U) ||
        (maximum_quantity > HARDWARE_MAX_OPERATION_QUANTITY) ||
        (DispenseOrder.state != DISPENSE_STATE_IDLE) ||
        CollectMaintenance.active ||
        (BeadMotor1.motor.state != DEVICE_STATE_IDLE) ||
        (BeadMotor2.motor.state != DEVICE_STATE_IDLE))
    {
        return false;
    }

    CollectMaintenance.active = true;
    CollectMaintenance.requestedQuantity = (uint16_t)maximum_quantity;
    CollectMaintenance.actualQuantity = 0U;
    CashAcceptance_Disable();
    BeadMotor_Output(&BeadMotor2, (uint16_t)maximum_quantity);
    return true;
}

void Hardware_StopCollect(void)
{
    if (!CollectMaintenance.active)
    {
        return;
    }

    Hardware_StopCollectMotor();
    CollectMaintenance.active = false;
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
}

void Hardware_AbortAll(void)
{
    bool dispense_running = DispenseOrder.state == DISPENSE_STATE_RUNNING;

    Device_StopAllImmediately();
    if (dispense_running)
    {
        Hardware_SetDispenseTerminal(HW_RESULT_ABORTED);
    }
    CollectMaintenance.active = false;
}

void Hardware_OnDispensePulse(void)
{
    if (DispenseOrder.state != DISPENSE_STATE_RUNNING)
    {
        return;
    }

    BeadMotor_Feedback(&BeadMotor1);
    if (DispenseOrder.actualQuantity < DispenseOrder.requestedQuantity)
    {
        DispenseOrder.actualQuantity++;
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

    if (DispenseOrder.actualQuantity >= DispenseOrder.requestedQuantity)
    {
        Hardware_SetDispenseTerminal(HW_RESULT_OK);
    }
}

void Hardware_OnCollectPulse(void)
{
    if (!CollectMaintenance.active)
    {
        return;
    }

    BeadMotor_Feedback(&BeadMotor2);
    if (CollectMaintenance.actualQuantity < CollectMaintenance.requestedQuantity)
    {
        CollectMaintenance.actualQuantity++;
        if (Setting.BeadStock < 0xFFFFU)
        {
            Setting.BeadStock++;
            Setting.HardwareFlags &= ~HARDWARE_FLAG_NO_BEAD;
            LowStockNotified = Setting.BeadStock <= HARDWARE_LOW_STOCK_THRESHOLD;
            FlashTask_RequestSave();
            EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
        }
    }
    if (CollectMaintenance.actualQuantity >= CollectMaintenance.requestedQuantity)
    {
        Hardware_StopCollect();
    }
}

void Hardware_OnDispenseTimeout(void)
{
    if (DispenseOrder.state != DISPENSE_STATE_RUNNING)
    {
        return;
    }

    Setting.BeadStock = 0U;
    Setting.HardwareFlags |= HARDWARE_FLAG_NO_BEAD;
    LowStockNotified = true;
    CashAcceptance_Disable();
    FlashTask_RequestSave();

    Hardware_SetDispenseTerminal(HW_RESULT_SENSOR_TIMEOUT);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
}

void Hardware_OnCollectTimeout(void)
{
    if (!CollectMaintenance.active)
    {
        return;
    }

    Hardware_StopCollect();
}

void Hardware_ConfirmDispenseTerminal(uint16_t order_sequence,
                                      uint8_t terminal_frame_id)
{
    bool success;

    if (LastDispenseResult.valid &&
        (LastDispenseResult.orderSequence == order_sequence) &&
        (LastDispenseResult.terminalFrameId == terminal_frame_id))
    {
        return;
    }

    if ((DispenseOrder.state != DISPENSE_STATE_WAIT_TERMINAL_ACK) ||
        !DispenseOrder.terminalPending ||
        (DispenseOrder.orderSequence != order_sequence) ||
        (DispenseOrder.terminalFrameId == 0U) ||
        (DispenseOrder.terminalFrameId != terminal_frame_id))
    {
        return;
    }

    success = Hardware_IsDispenseSuccess(&DispenseOrder);
    Hardware_SaveLastDispenseResult();
    Comm_RemoveDispenseTerminal(order_sequence, terminal_frame_id);
    DispenseOrder.terminalPending = false;

    if (success)
    {
        Hardware_ResetDispenseOrder();
    }
    else
    {
        DispenseOrder.state = DISPENSE_STATE_BLOCKED;
        CashAcceptance_Disable();
    }
}

void Hardware_MarkDispenseTerminalQueued(uint8_t terminal_frame_id)
{
    if ((DispenseOrder.state == DISPENSE_STATE_WAIT_TERMINAL_ACK) &&
        DispenseOrder.terminalPending &&
        (terminal_frame_id != 0U))
    {
        DispenseOrder.terminalFrameId = terminal_frame_id;
    }
}

void Hardware_Refill(void)
{
    Setting.BeadStock = HARDWARE_DEFAULT_STOCK;
    Setting.HardwareFlags &= ~HARDWARE_FLAG_NO_BEAD;
    LowStockNotified = false;
    FlashTask_RequestSave();

    if (DispenseOrder.state == DISPENSE_STATE_BLOCKED)
    {
        Hardware_ResetDispenseOrder();
    }

    EventGroupSetBits(&Mesg_event, MesgEvent_BeadRefilled);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
}

void Hardware_RequestStatus(void)
{
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
    CashAcceptance_RequestStatus();
    if (DispenseOrder.state == DISPENSE_STATE_RUNNING)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseProgress);
    }
    if (DispenseOrder.state == DISPENSE_STATE_WAIT_TERMINAL_ACK)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_DispenseTerminal);
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
    return DispenseOrder.state == DISPENSE_STATE_RUNNING;
}

bool Hardware_IsCollectActive(void)
{
    return CollectMaintenance.active;
}

bool Hardware_CanEnableCashAcceptance(void)
{
    return (DispenseOrder.state == DISPENSE_STATE_IDLE) &&
           !CollectMaintenance.active &&
           (BeadMotor1.motor.state == DEVICE_STATE_IDLE) &&
           (BeadMotor2.motor.state == DEVICE_STATE_IDLE) &&
           !Hardware_HasNoBead();
}

const DispenseOrder_t *Hardware_GetDispenseOrder(void)
{
    return &DispenseOrder;
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
