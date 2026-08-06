#ifndef __CTRLTASK_H__
#define __CTRLTASK_H__

/*
 * 控制板硬件执行层接口。
 * 负责吐珠电机、存珠电机、电子锁以及单订单出珠状态机，不处理平台业务计价。
 */
#include "main.h"
#include "port_device.h"
#include "stdbool.h"

/* 电机与电子锁的基础运行参数。 */
#define BeadMotorTimeout_time 3000U
#define BeadMotorReverse_Time 300U
#define BeadMotor1Retry_Times 1U
#define BeadMotor2Retry_Times 3U
#define BeadMotor_Speed 85U
#define BeadMotor_Dir 1U
#define LockOpen_Time 800U

/* 库存默认值、低库存阈值和单次动作允许的最大数量。 */
#define HARDWARE_DEFAULT_STOCK 10000U
#define HARDWARE_LOW_STOCK_THRESHOLD 3000U
#define HARDWARE_MAX_OPERATION_QUANTITY 0xFFFFU

/* 出珠终态结果码，通过协议帧 ExpandCode 上报 Android。 */
#define HW_RESULT_OK 0x00U
#define HW_RESULT_BUSY 0x01U
#define HW_RESULT_NO_BEAD 0x02U
#define HW_RESULT_INVALID_QUANTITY 0x03U
#define HW_RESULT_SENSOR_TIMEOUT 0x04U
#define HW_RESULT_ABORTED 0x05U
#define HW_RESULT_NOT_ACTIVE 0x06U
#define HW_RESULT_BLOCKED 0x07U
#define HW_RESULT_ORDER_SEQUENCE_MISMATCH 0x08U

/* 单路珠子电机的剩余数量、重试次数和底层电机对象。 */
typedef struct
{
    uint16_t remain_num;
    uint8_t retry_count;
    motor_t motor;
} BeadMotor_t;

/* 电子锁开关对象。 */
typedef struct
{
    switch_t sw;
} Lock_t;

/* 单订单出珠状态：空闲、执行、等待终态确认和故障阻塞。 */
typedef enum
{
    DISPENSE_STATE_IDLE = 0,
    DISPENSE_STATE_RUNNING,
    DISPENSE_STATE_WAIT_TERMINAL_ACK,
    DISPENSE_STATE_BLOCKED
} DispenseState_t;

/* 当前出珠订单的控制板侧真实执行状态。 */
typedef struct
{
    DispenseState_t state;
    uint16_t orderSequence;
    uint16_t requestedQuantity;
    uint16_t actualQuantity;
    uint8_t resultCode;
    uint8_t terminalFrameId;
    bool terminalPending;
} DispenseOrder_t;

/* 最近一次已完成确认的出珠结果，用于防止订单序号被重复执行。 */
typedef struct
{
    bool valid;
    uint16_t orderSequence;
    uint16_t requestedQuantity;
    uint16_t actualQuantity;
    uint8_t resultCode;
    uint8_t terminalFrameId;
} LastDispenseResult_t;

/* 初始化和直接控制底层电机、电子锁。 */
void Device_Init(void);
void BeadMotor_Output(BeadMotor_t *bead_motor, uint16_t num);
void BeadMotor_Feedback(BeadMotor_t *bead_motor);
void Device_StopAllImmediately(void);

/* 平台授权后的硬件动作及其真实反馈处理。 */
void Hardware_Init(void);
bool Hardware_StartDispenseOrder(uint16_t order_sequence, uint16_t requested_quantity);
void Hardware_ConfirmDispenseTerminal(uint16_t order_sequence, uint8_t terminal_frame_id);
void Hardware_MarkDispenseTerminalQueued(uint8_t terminal_frame_id);
bool Hardware_StartCollect(uint32_t maximum_quantity);
void Hardware_StopCollect(void);
void Hardware_AbortAll(void);
void Hardware_OnDispensePulse(void);
void Hardware_OnCollectPulse(void);
void Hardware_OnDispenseTimeout(void);
void Hardware_OnCollectTimeout(void);
void Hardware_Refill(void);
void Hardware_RequestStatus(void);

/* 查询库存、动作状态和当前出珠订单。 */
uint32_t Hardware_GetBeadStock(void);
bool Hardware_IsNoBead(void);
bool Hardware_IsDispenseActive(void);
bool Hardware_IsCollectActive(void);
bool Hardware_CanEnableCashAcceptance(void);
const DispenseOrder_t *Hardware_GetDispenseOrder(void);

/* 周期驱动电机和电子锁状态机。 */
void CtrlTask(void);

#endif
