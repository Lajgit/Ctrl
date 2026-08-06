/*
 * 硬件控制任务：驱动吐珠电机、存珠电机和电子锁，并维护单订单出珠状态机。
 * 控制板只执行 Android 已授权的数量，所有 actualQuantity 均来自真实光眼脉冲。
 */
#include "CtrlTask.h"
#include "MesgTask.h"
#include "CommunicateTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "tim.h"
#include "string.h"

/* 本地维护存珠动作，只记录本次最大数量和真实光眼计数。 */
typedef struct
{
    bool active;
    uint16_t requestedQuantity;
    uint16_t actualQuantity;
} CollectMaintenance_t;

/* 两路电机和电子锁的全局硬件对象。 */
BeadMotor_t BeadMotor1;
BeadMotor_t BeadMotor2;
Lock_t Lock;

extern Event_Handle_t Mesg_event;
extern Setting_TypeDef Setting;

/* 当前出珠订单、最近确认结果和存珠维护状态。 */
static DispenseOrder_t DispenseOrder;
static LastDispenseResult_t LastDispenseResult;
static CollectMaintenance_t CollectMaintenance;
static bool LowStockNotified = false;

/* 清空当前出珠订单并恢复为空闲状态。 */
static void Hardware_ResetDispenseOrder(void)
{
    memset(&DispenseOrder, 0, sizeof(DispenseOrder));
    DispenseOrder.state = DISPENSE_STATE_IDLE;
    DispenseOrder.resultCode = HW_RESULT_OK;
}

/* 只有结果码成功、实际数量等于应出数量且数量大于零才算完整成功。 */
static bool Hardware_IsDispenseSuccess(const DispenseOrder_t *order)
{
    return (order != NULL) &&
           (order->resultCode == HW_RESULT_OK) &&
           (order->actualQuantity == order->requestedQuantity) &&
           (order->actualQuantity > 0U);
}

/* 保存刚刚完成确认的订单结果，用于阻止相同订单序号再次驱动电机。 */
static void Hardware_SaveLastDispenseResult(void)
{
    LastDispenseResult.valid = true;
    LastDispenseResult.orderSequence = DispenseOrder.orderSequence;
    LastDispenseResult.requestedQuantity = DispenseOrder.requestedQuantity;
    LastDispenseResult.actualQuantity = DispenseOrder.actualQuantity;
    LastDispenseResult.resultCode = DispenseOrder.resultCode;
    LastDispenseResult.terminalFrameId = DispenseOrder.terminalFrameId;
}

/* 判断新命令是否与最近一次已确认订单完全相同。 */
static bool Hardware_LastMatches(uint16_t order_sequence,
                                 uint16_t requested_quantity)
{
    return LastDispenseResult.valid &&
           (LastDispenseResult.orderSequence == order_sequence) &&
           (LastDispenseResult.requestedQuantity == requested_quantity);
}

/* 发送一次性拒绝终态，不创建新的持久出珠订单。 */
static void Hardware_SendDispenseReject(uint16_t order_sequence,
                                        uint16_t actual_quantity,
                                        uint8_t result_code)
{
    (void)Comm_SendDispenseTerminalOnce(order_sequence,
                                        actual_quantity,
                                        result_code);
}

/* 停止吐珠电机并清空电机内部剩余数量和重试计数。 */
static void Hardware_StopDispenseMotor(void)
{
    BeadMotor1.motor.Stop(&BeadMotor1.motor);
    BeadMotor1.motor.state = DEVICE_STATE_IDLE;
    BeadMotor1.remain_num = 0U;
    BeadMotor1.retry_count = 0U;
}

/* 停止存珠电机并清空电机内部剩余数量和重试计数。 */
static void Hardware_StopCollectMotor(void)
{
    BeadMotor2.motor.Stop(&BeadMotor2.motor);
    BeadMotor2.motor.state = DEVICE_STATE_IDLE;
    BeadMotor2.remain_num = 0U;
    BeadMotor2.retry_count = 0U;
}

/* 库存为零或掉电标志记录无珠时，均禁止开始新的出珠动作。 */
static bool Hardware_HasNoBead(void)
{
    return (Setting.BeadStock == 0U) ||
           ((Setting.HardwareFlags & HARDWARE_FLAG_NO_BEAD) != 0U);
}

/* 停止电机并将当前订单切换为等待 Android 确认终态。 */
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

/*
 * 通用电机状态机：启动正转，超时后断电并短暂反转清障，再按配置次数重试。
 * 重试耗尽后回调对应的吐珠或存珠超时处理函数。
 */
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

/* 电子锁状态机：启动后吸合，达到持续时间后转入关闭状态。 */
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

/* 在电机空闲且数量有效时启动指定数量的电机动作。 */
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

/* 一个有效光眼脉冲到达后，重置超时并减少电机剩余数量。 */
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

/* 绑定两路 TIM1 PWM 电机通道和电子锁 GPIO。 */
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

/* 紧急关闭两路电机和电子锁，不在此函数中修改业务终态。 */
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

/* 初始化业务状态机，并在上电时默认关闭现金入口。 */
void Hardware_Init(void)
{
    Hardware_ResetDispenseOrder();
    memset(&LastDispenseResult, 0, sizeof(LastDispenseResult));
    memset(&CollectMaintenance, 0, sizeof(CollectMaintenance));
    LowStockNotified = Setting.BeadStock <= HARDWARE_LOW_STOCK_THRESHOLD;
    CashAcceptance_Disable();
}

/*
 * 接收一个新的平台授权出珠订单。
 * 相同运行中订单只返回已有状态；不同订单在忙、等待确认或阻塞状态下直接拒绝。
 */
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

    /* 出珠期间禁止纸币和硬币继续进入。 */
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

/* 仅在设备完全空闲时启动本地存珠维护动作。 */
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

/* 停止当前存珠维护动作，并请求上报最终库存。 */
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

/* 紧急停止全部机构；若出珠正在运行，则生成中止终态。 */
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

/* 处理一个真实吐珠光眼脉冲，同步实际数量、库存和低库存事件。 */
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

/* 处理一个真实存珠光眼脉冲，增加库存并在达到目标后停止电机。 */
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

/* 吐珠重试耗尽后锁定无珠状态、关闭现金并生成传感器超时终态。 */
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

/* 存珠超时只停止本地维护动作，不修改出珠订单终态。 */
void Hardware_OnCollectTimeout(void)
{
    if (!CollectMaintenance.active)
    {
        return;
    }

    Hardware_StopCollect();
}

/*
 * Android 确认已安全保存出珠终态后，移除重发帧并保存最近结果。
 * 完整成功回到空闲；失败结果进入阻塞状态并保持现金关闭。
 */
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

/* 终态成功进入可靠发送队列后，记录其帧 ID 供业务确认匹配。 */
void Hardware_MarkDispenseTerminalQueued(uint8_t terminal_frame_id)
{
    if ((DispenseOrder.state == DISPENSE_STATE_WAIT_TERMINAL_ACK) &&
        DispenseOrder.terminalPending &&
        (terminal_frame_id != 0U))
    {
        DispenseOrder.terminalFrameId = terminal_frame_id;
    }
}

/* K1 补珠：恢复默认库存、清除无珠标志并解除失败订单的本地阻塞。 */
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

/* 请求重新上报库存、现金状态及当前订单过程或终态。 */
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

/* 以下接口只读取当前硬件状态，不产生副作用。 */
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

/* 只有订单、电机和存珠维护均空闲且库存正常时，才允许重新开启现金入口。 */
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

/* 主循环中依次推进吐珠电机、存珠电机和电子锁状态机。 */
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
