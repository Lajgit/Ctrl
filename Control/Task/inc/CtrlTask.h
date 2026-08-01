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

/* 控制板执行结果码，放入终态帧 ExpandCode。 */
#define HW_RESULT_OK 0x00U
#define HW_RESULT_BUSY 0x01U
#define HW_RESULT_NO_BEAD 0x02U
#define HW_RESULT_INVALID_QUANTITY 0x03U
#define HW_RESULT_SENSOR_TIMEOUT 0x04U
#define HW_RESULT_ABORTED 0x05U
#define HW_RESULT_NOT_ACTIVE 0x06U

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

typedef struct
{
    bool active;
    uint8_t token;
    uint32_t requested;
    uint32_t actual;
    uint8_t result;
} HardwareOperation_t;

void Device_Init(void);
void BeadMotor_Output(BeadMotor_t *bead_motor, uint16_t num);
void BeadMotor_Feedback(BeadMotor_t *bead_motor);
void Device_StopAllImmediately(void);

/* 平台授权的硬件动作。控制板不再按现金金额计算珠数。 */
void Hardware_Init(void);
bool Hardware_StartDispense(uint8_t token, uint32_t quantity);
bool Hardware_StartCollect(uint8_t token, uint32_t maximum_quantity);
void Hardware_StopCollect(uint8_t token);
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
const HardwareOperation_t *Hardware_GetDispenseReport(void);
const HardwareOperation_t *Hardware_GetCollectReport(void);

void CtrlTask(void);

#endif
