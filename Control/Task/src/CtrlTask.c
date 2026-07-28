#include "CtrlTask.h"
#include "MesgTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "tim.h"

BeadMotor_t BeadMotor1;
BeadMotor_t BeadMotor2;
Lock_t Lock;

extern Event_Handle_t Mesg_event;
extern Setting_TypeDef Setting;

/* 固件购买状态：无珠锁定、补珠后延时吐珠和低库存通知状态。 */
static bool PurchaseNoBead = false;
static bool PurchaseResumeWaiting = false;
static bool PurchaseLowStockNotified = false;
static uint32_t PurchaseResumeTick = 0U;
static uint8_t PurchasePriceSetResult = PURCHASE_PRICE_SET_OK;

static void Purchase_Save(void)
{
    /* 价格、库存、余额和未吐数量统一追加写入 Flash 日志。 */
    FlashTask_RequestSave();
}

static uint32_t Purchase_BillTypeToFen(uint8_t bill_type)
{
    /* 人民币版本：0x40~0x45 分别为 1、5、10、20、50、100 元。 */
    switch (bill_type)
    {
    case 0x40U:
        return 100U;
    case 0x41U:
        return 500U;
    case 0x42U:
        return 1000U;
    case 0x43U:
        return 2000U;
    case 0x44U:
        return 5000U;
    case 0x45U:
        return 10000U;
    default:
        return 0U;
    }
}

static void Purchase_ApplyCreditToPending(void)
{
    uint32_t new_beads;

    if (Setting.BeadPriceFen == 0U)
    {
        return;
    }

    new_beads = Setting.PurchaseCreditFen / Setting.BeadPriceFen;
    Setting.PurchaseCreditFen %= Setting.BeadPriceFen;

    /* 实际金额范围很小；仍做饱和保护，避免异常累计导致 32 位回绕。 */
    if (new_beads > (0xFFFFFFFFUL - Setting.PendingBeads))
    {
        Setting.PendingBeads = 0xFFFFFFFFUL;
    }
    else
    {
        Setting.PendingBeads += new_beads;
    }
}

static void Purchase_AddPayment(uint32_t amount_fen)
{
    if ((amount_fen == 0U) || (Setting.BeadPriceFen == 0U))
    {
        return;
    }

    /* 先按“分”累计金额，再按安卓设置的单颗价格换算为待吐珠数量。 */
    if (amount_fen > (0xFFFFFFFFUL - Setting.PurchaseCreditFen))
    {
        Setting.PurchaseCreditFen = 0xFFFFFFFFUL;
    }
    else
    {
        Setting.PurchaseCreditFen += amount_fen;
    }

    Purchase_ApplyCreditToPending();
    Purchase_Save();

    /* 安卓可同步显示当前待吐数量和未满一颗的余额。 */
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchaseCreditStatus);
}

static void Purchase_TryStartDispense(void)
{
    uint32_t output_count;

    if ((PurchaseNoBead == true) ||
        (PurchaseResumeWaiting == true) ||
        (Setting.PendingBeads == 0U))
    {
        return;
    }

    if (BeadMotor1.motor.state != DEVICE_STATE_IDLE)
    {
        return;
    }

    /*
     * 超时后 BeadMotor1.remain_num 会保留，补珠后直接恢复原任务；
     * 掉电启动时 remain_num 为 0，再从 Flash 中的 PendingBeads 重新排队。
     */
    if (BeadMotor1.remain_num > 0U)
    {
        BeadMotor_Resume(&BeadMotor1);
        return;
    }

    output_count = Setting.PendingBeads;
    if (output_count > 0xFFFFU)
    {
        output_count = 0xFFFFU;
    }

    BeadMotor_Output(&BeadMotor1, (uint16_t)output_count);
    EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
}

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

    /* 第一次超时后反转清障，达到反转时间后再恢复正转。 */
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
            /* 首次未出珠：先断电，再反转清障。 */
            bead_motor->motor.state = DEVICE_STATE_TIMEOUT;
            bead_motor->motor.LosePower(&bead_motor->motor);
            HAL_Delay(1U);
            bead_motor->motor.SetSpeed(&bead_motor->motor, speed, !dir);
        }
        else
        {
            /* 再次正转仍无反馈：保留未完成数量并进入无珠处理。 */
            bead_motor->motor.Stop(&bead_motor->motor);
            bead_motor->motor.state = DEVICE_STATE_IDLE;
            EventGroupSetBits(&Mesg_event, timeout_event);

            if (timeout_event == MesgEvent_BeadMotor1Timeout)
            {
                Purchase_OnDispenseTimeout();
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

void BeadMotor_Resume(BeadMotor_t *bead_motor)
{
    if ((bead_motor == NULL) || (bead_motor->remain_num == 0U))
    {
        return;
    }

    if ((bead_motor->motor.state == DEVICE_STATE_IDLE) ||
        (bead_motor->motor.state == DEVICE_STATE_STOP))
    {
        /* 补珠后恢复欠吐任务时，不再追加数量，只重新启动现有剩余任务。 */
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

void Purchase_Init(void)
{
    uint32_t pending_before;
    uint32_t credit_before;

    PurchaseNoBead = (Setting.PurchaseFlags & PURCHASE_FLAG_NO_BEAD) != 0U;
    PurchaseLowStockNotified = Setting.BeadStock <= PURCHASE_LOW_STOCK_THRESHOLD;
    PurchaseResumeWaiting = false;
    PurchasePriceSetResult = PURCHASE_PRICE_SET_OK;

    /*
     * 上电时重新按“分”归一化余额。
     * 这同时用于把旧版元单位记录迁移后可能形成的可购买金额转成欠吐数量。
     */
    pending_before = Setting.PendingBeads;
    credit_before = Setting.PurchaseCreditFen;
    Purchase_ApplyCreditToPending();
    if ((pending_before != Setting.PendingBeads) ||
        (credit_before != Setting.PurchaseCreditFen))
    {
        Purchase_Save();
        EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
        EventGroupSetBits(&Mesg_event, MesgEvent_PurchaseCreditStatus);
    }

    if (PurchaseNoBead == true)
    {
        /* 上次掉电前已经判定无珠，上电后继续禁用纸钞机。 */
        BillAcceptor_SetEnable(false);
    }
    else if (Setting.PendingBeads > 0U)
    {
        /* 掉电后恢复欠吐任务也预留 10 秒，避免设备门尚未关闭就启动电机。 */
        PurchaseResumeWaiting = true;
        PurchaseResumeTick = HAL_GetTick();
    }
}

void Purchase_AddCoinPayment(void)
{
    /* 硬币机只能投入 1 元，每个有效脉冲计入 100 分。 */
    Purchase_AddPayment(100U);
}

void Purchase_AddBillPayment(uint8_t bill_type)
{
    uint32_t amount_fen = Purchase_BillTypeToFen(bill_type);

    /* 当前仅实现人民币 0x40~0x45；其他类型仍上报安卓，但不换算吐珠。 */
    if (amount_fen > 0U)
    {
        Purchase_AddPayment(amount_fen);
    }
}

void Purchase_SetBeadPrice(uint32_t price_fen)
{
    if ((price_fen < PURCHASE_MIN_PRICE_FEN) ||
        (price_fen > PURCHASE_MAX_PRICE_FEN))
    {
        PurchasePriceSetResult = PURCHASE_PRICE_SET_INVALID;
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadPriceStatus);
        return;
    }

    /* 新价格立即应用于当前尚未换算成珠子的累计余额。 */
    Setting.BeadPriceFen = price_fen;
    PurchasePriceSetResult = PURCHASE_PRICE_SET_OK;
    Purchase_ApplyCreditToPending();
    Purchase_Save();

    EventGroupSetBits(&Mesg_event, MesgEvent_BeadPriceStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchaseCreditStatus);
}

void Purchase_RequestStatus(void)
{
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadPriceStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchaseCreditStatus);
}

void Purchase_OnBeadDispensed(void)
{
    /* 只有 PD3 吐珠光眼确认后，才扣减库存和已付款欠吐数量。 */
    if (Setting.BeadStock > 0U)
    {
        Setting.BeadStock--;
    }

    if (Setting.PendingBeads > 0U)
    {
        Setting.PendingBeads--;
    }

    Purchase_Save();
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);

    if ((PurchaseLowStockNotified == false) &&
        (Setting.BeadStock <= PURCHASE_LOW_STOCK_THRESHOLD))
    {
        /* 库存首次降到 3000 或以下时通知安卓库存不足。 */
        PurchaseLowStockNotified = true;
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadLowStock);
    }
}

void Purchase_OnDispenseTimeout(void)
{
    /*
     * 保留未完成数量：固件购买数量与电机剩余量取较大值，
     * 即使异常掉电，补珠后仍能继续兑现未完成吐珠。
     */
    if ((uint32_t)BeadMotor1.remain_num > Setting.PendingBeads)
    {
        Setting.PendingBeads = BeadMotor1.remain_num;
    }

    /* 物理吐珠失败时用实际结果校准库存为 0，并锁定购买。 */
    Setting.BeadStock = 0U;
    Setting.PurchaseFlags |= PURCHASE_FLAG_NO_BEAD;
    PurchaseNoBead = true;
    PurchaseResumeWaiting = false;
    PurchaseLowStockNotified = true;

    Purchase_Save();
    BillAcceptor_SetEnable(false);

    EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
}

void Purchase_Refill(void)
{
    /* K1 编码器按键确认补珠后，库存暂定重置为 10000。 */
    Setting.BeadStock = PURCHASE_DEFAULT_STOCK;
    Setting.PurchaseFlags &= ~PURCHASE_FLAG_NO_BEAD;
    PurchaseNoBead = false;
    PurchaseLowStockNotified = false;

    BillAcceptor_SetEnable(true);

    if (Setting.PendingBeads > 0U)
    {
        /* 留出 10 秒关门时间，然后自动补吐尚未完成的珠子。 */
        PurchaseResumeWaiting = true;
        PurchaseResumeTick = HAL_GetTick();
    }
    else
    {
        PurchaseResumeWaiting = false;
    }

    Purchase_Save();
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadRefilled);
    EventGroupSetBits(&Mesg_event, MesgEvent_BeadStockStatus);
    EventGroupSetBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
}

void Purchase_PauseDispense(void)
{
    if (Setting.PendingBeads > 0U)
    {
        /* 安卓紧急停止后保留欠吐数量，10 秒后才允许重新启动。 */
        PurchaseResumeWaiting = true;
        PurchaseResumeTick = HAL_GetTick();
    }
}

void Purchase_Task(void)
{
    if (PurchaseNoBead == true)
    {
        return;
    }

    if (PurchaseResumeWaiting == true)
    {
        if ((HAL_GetTick() - PurchaseResumeTick) < PURCHASE_REFILL_DELAY)
        {
            return;
        }

        PurchaseResumeWaiting = false;
    }

    Purchase_TryStartDispense();
}

uint32_t Purchase_GetBeadPriceFen(void)
{
    return Setting.BeadPriceFen;
}

uint32_t Purchase_GetBeadStock(void)
{
    return Setting.BeadStock;
}

uint32_t Purchase_GetPendingBeads(void)
{
    return Setting.PendingBeads;
}

uint32_t Purchase_GetCreditFen(void)
{
    return Setting.PurchaseCreditFen;
}

uint8_t Purchase_GetPriceSetResult(void)
{
    return PurchasePriceSetResult;
}

void CtrlTask(void)
{
    /* 吐珠电机：一次反转清障和一次再次正转，仍失败则判定无珠。 */
    Ctrl_BeadMotor(&BeadMotor1,
                   BeadMotor_Speed,
                   BeadMotor_Dir,
                   BeadMotorTimeout_time,
                   BeadMotorReverse_Time,
                   BeadMotor1Retry_Times,
                   MesgEvent_BeadMotor1Timeout);

    /* 存珠电机保持原来的三次重试策略。 */
    Ctrl_BeadMotor(&BeadMotor2,
                   BeadMotor_Speed,
                   BeadMotor_Dir,
                   BeadMotorTimeout_time,
                   BeadMotorReverse_Time,
                   BeadMotor2Retry_Times,
                   MesgEvent_BeadMotor2Timeout);

    Ctrl_Lock(&Lock, LockOpen_Time);
}
