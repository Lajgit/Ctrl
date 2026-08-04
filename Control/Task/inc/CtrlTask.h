#ifndef __CTRLTASK_H__
#define __CTRLTASK_H__

#include "main.h"
#include "port_device.h"
#include "stdbool.h"

#define BeadMotorTimeout_time 3000U
#define BeadMotorReverse_Time 300U
#define BeadMotor1Retry_Times 1U
#define BeadMotor2Retry_Times 3U
#define BeadMotor_Speed 85U
#define BeadMotor_Dir 1U
#define LockOpen_Time 800U

#define HARDWARE_DEFAULT_STOCK 10000U
#define HARDWARE_LOW_STOCK_THRESHOLD 3000U
#define HARDWARE_MAX_OPERATION_QUANTITY 0xFFFFU

#define HW_RESULT_OK 0x00U
#define HW_RESULT_BUSY 0x01U
#define HW_RESULT_NO_BEAD 0x02U
#define HW_RESULT_INVALID_QUANTITY 0x03U
#define HW_RESULT_SENSOR_TIMEOUT 0x04U
#define HW_RESULT_ABORTED 0x05U
#define HW_RESULT_NOT_ACTIVE 0x06U
#define HW_RESULT_BLOCKED 0x07U
#define HW_RESULT_ORDER_SEQUENCE_MISMATCH 0x08U

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

typedef enum
{
    DISPENSE_STATE_IDLE = 0,
    DISPENSE_STATE_RUNNING,
    DISPENSE_STATE_WAIT_TERMINAL_ACK,
    DISPENSE_STATE_BLOCKED
} DispenseState_t;

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

typedef struct
{
    bool valid;
    uint16_t orderSequence;
    uint16_t requestedQuantity;
    uint16_t actualQuantity;
    uint8_t resultCode;
    uint8_t terminalFrameId;
} LastDispenseResult_t;

void Device_Init(void);
void BeadMotor_Output(BeadMotor_t *bead_motor, uint16_t num);
void BeadMotor_Feedback(BeadMotor_t *bead_motor);
void Device_StopAllImmediately(void);

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

uint32_t Hardware_GetBeadStock(void);
bool Hardware_IsNoBead(void);
bool Hardware_IsDispenseActive(void);
bool Hardware_IsCollectActive(void);
bool Hardware_CanEnableCashAcceptance(void);
const DispenseOrder_t *Hardware_GetDispenseOrder(void);

void CtrlTask(void);

#endif
