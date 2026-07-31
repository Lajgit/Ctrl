#include "InterruptTask.h"
#include "CtrlTask.h"
#include "MesgTask.h"
#include "CommunicateTask.h"
#include "KeyTask.h"
#include "usart.h"

extern Event_Handle_t Mesg_event;
extern BeadMotor_t BeadMotor1;
extern BeadMotor_t BeadMotor2;
extern Rx_HandleTypeDef Rx1;
extern UART_HandleTypeDef huart3;

/*
 * 吐珠末端控制参数：
 * 1. 正常阶段保持 85% 高速；
 * 2. 倒数第二颗进入光眼时立即制动；
 * 3. 等待机械机构稳定后，如仍差最后一颗，以 30% 低速补出。
 *
 * 稳定时间和末颗速度属于机械标定参数，后续可根据整机实测微调。
 */
#define DISPENSE_FINAL_SPEED 30U
#define DISPENSE_SETTLE_TIME 120U

#define DISPENSE_STAGE_NORMAL 0U
#define DISPENSE_STAGE_SETTLING 1U
#define DISPENSE_STAGE_FINAL_LOW_SPEED 2U

/*
 * 光眼脉冲始终完整进入待处理队列；预测停机只控制电机，不在中断中扣减
 * remain_num、库存或欠吐数量，避免再次出现“光眼两次但库存只减一次”。
 */
static volatile uint16_t BeadMotor1FeedbackPending = 0U;
static volatile uint16_t BeadMotor2FeedbackPending = 0U;
static volatile uint8_t DispenseStage = DISPENSE_STAGE_NORMAL;
static volatile uint32_t DispenseSettleTick = 0U;

/*
 * 计算当前光眼对应的真实剩余数量。
 * remain_num 尚未扣除队列中未处理的脉冲，因此需要先减去 pending_before。
 */
static uint16_t Dispense_GetEffectiveRemain(uint16_t pending_before)
{
    uint16_t remain = BeadMotor1.remain_num;

    if (remain > pending_before)
    {
        return (uint16_t)(remain - pending_before);
    }

    return 0U;
}

static void BeadMotor1Feedback_IRQ(void)
{
    uint16_t pending_before = BeadMotor1FeedbackPending;
    uint16_t effective_remain = Dispense_GetEffectiveRemain(pending_before);

    if (BeadMotor1.motor.state == DEVICE_STATE_BUSY)
    {
        if ((DispenseStage == DISPENSE_STAGE_NORMAL) &&
            (effective_remain == 2U))
        {
            /*
             * 当前下降沿是倒数第二颗刚进入光眼：立即主动制动，
             * 给机构中的最后一颗自然通过机会，然后等待稳定。
             */
            BeadMotor1.motor.Stop(&BeadMotor1.motor);
            BeadMotor1.motor.state = DEVICE_STATE_PAUSE;
            DispenseStage = DISPENSE_STAGE_SETTLING;
            DispenseSettleTick = HAL_GetTick();
        }
        else if ((DispenseStage == DISPENSE_STAGE_FINAL_LOW_SPEED) &&
                 (effective_remain == 1U))
        {
            /* 最后一颗刚进入光眼，立即主动制动。 */
            BeadMotor1.motor.Stop(&BeadMotor1.motor);
        }
    }

    /* 无论电机处于哪个末端阶段，实际光眼脉冲都必须完整登记。 */
    if (BeadMotor1FeedbackPending < 0xFFFFU)
    {
        BeadMotor1FeedbackPending++;
    }
}

static void BeadMotor2Feedback_IRQ(void)
{
    if (BeadMotor2FeedbackPending < 0xFFFFU)
    {
        BeadMotor2FeedbackPending++;
    }
}

/*
 * 吐珠末端状态机在主循环执行：
 * - 单颗任务直接低速启动；
 * - 倒数第二颗制动后等待机械稳定；
 * - 根据光眼实际扣减后的 remain_num 判断是否还差最后一颗；
 * - 若有新任务追加导致剩余数量大于 1，则恢复正常高速。
 */
static void DispenseFinalStage_Task(void)
{
    uint32_t now;

    if ((BeadMotor1.motor.state == DEVICE_STATE_IDLE) ||
        ((BeadMotor1.motor.state == DEVICE_STATE_STOP) &&
         (BeadMotor1.remain_num == 0U)))
    {
        DispenseStage = DISPENSE_STAGE_NORMAL;
        return;
    }

    /* 单颗任务以及反转清障后仍只差一颗时，直接使用末颗低速。 */
    if (BeadMotor1.motor.state == DEVICE_STATE_START)
    {
        if (BeadMotor1.remain_num == 1U)
        {
            BeadMotor1.motor.ResetRuntime(&BeadMotor1.motor);
            BeadMotor1.motor.SetSpeed(&BeadMotor1.motor,
                                      DISPENSE_FINAL_SPEED,
                                      BeadMotor_Dir);
            BeadMotor1.motor.state = DEVICE_STATE_BUSY;
            DispenseStage = DISPENSE_STAGE_FINAL_LOW_SPEED;
        }
        else
        {
            DispenseStage = DISPENSE_STAGE_NORMAL;
        }
        return;
    }

    if ((DispenseStage != DISPENSE_STAGE_SETTLING) ||
        (BeadMotor1.motor.state != DEVICE_STATE_PAUSE))
    {
        return;
    }

    /* 等待期间若机械余量已经带出最后一颗，反馈处理会把状态改为 STOP。 */
    if (BeadMotor1.remain_num == 0U)
    {
        DispenseStage = DISPENSE_STAGE_NORMAL;
        return;
    }

    now = HAL_GetTick();
    if ((now - DispenseSettleTick) < DISPENSE_SETTLE_TIME)
    {
        return;
    }

    BeadMotor1.motor.ResetRuntime(&BeadMotor1.motor);

    if (BeadMotor1.remain_num == 1U)
    {
        /* 机构稳定后仍差一颗，以低速补出最后一颗。 */
        BeadMotor1.motor.SetSpeed(&BeadMotor1.motor,
                                  DISPENSE_FINAL_SPEED,
                                  BeadMotor_Dir);
        DispenseStage = DISPENSE_STAGE_FINAL_LOW_SPEED;
    }
    else
    {
        /* 等待期间追加了新任务，剩余多于一颗时恢复正常高速。 */
        BeadMotor1.motor.SetSpeed(&BeadMotor1.motor,
                                  BeadMotor_Speed,
                                  BeadMotor_Dir);
        DispenseStage = DISPENSE_STAGE_NORMAL;
    }

    BeadMotor1.motor.state = DEVICE_STATE_BUSY;
}

void InterruptTask_Process(void)
{
    uint32_t primask;
    bool process_motor1 = false;
    bool process_motor2 = false;

    /* 临界区内只取出一个待处理脉冲，缩短关中断时间。 */
    primask = __get_PRIMASK();
    __disable_irq();

    if (BeadMotor1FeedbackPending > 0U)
    {
        BeadMotor1FeedbackPending--;
        process_motor1 = true;
    }

    if (BeadMotor2FeedbackPending > 0U)
    {
        BeadMotor2FeedbackPending--;
        process_motor2 = true;
    }

    if (primask == 0U)
    {
        __enable_irq();
    }

    if (process_motor1 == true)
    {
        /* PD3：吐珠光眼确认一颗后，电机剩余量、库存和欠吐量同步减一。 */
        BeadMotor_Feedback(&BeadMotor1);
        Purchase_OnBeadDispensed();
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadMotor1Feedback);
        EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
    }

    if (process_motor2 == true)
    {
        /* PD4：存珠电机光眼反馈。 */
        BeadMotor_Feedback(&BeadMotor2);
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadMotor2Feedback);
        EventGroupSetBits(&Mesg_event, MesgEvent_RemainingBead);
    }

    /* 必须在 CtrlTask 前运行，确保单颗任务不会先以正常高速启动。 */
    DispenseFinalStage_Task();
}

void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)
{
    switch (GPIO_Pin)
    {
    case HoolleOutput_Pin:
        BeadMotor1Feedback_IRQ();
        break;

    case CardFeedback_Pin:
        BeadMotor2Feedback_IRQ();
        break;

    default:
        break;
    }
}

void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart)
{
    if (huart == Rx1.Handle.huart)
    {
        Rx1.Handle.RingBuf.f_WriteByte(&Rx1.Handle.RingBuf, Rx1.Handle.temp_data);
        HAL_UART_Receive_IT(huart, &Rx1.Handle.temp_data, 1U);
    }
    else if (huart == &huart3)
    {
        /* 量产纸钞机仅使用 USART3 TTL 接口。 */
        BillAcceptor_RxCpltCallback();
    }
}
