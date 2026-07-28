#ifndef __CTRLTASK_H__
#define __CTRLTASK_H__

#include "main.h"
#include "port_device.h"

#define BeadMotorTimeout_time 3000U
#define BeadMotorReverse_Time 300U

/* 吐珠电机只做一次“反转清障→再次正转”，第二次仍超时即判定无珠。 */
#define BeadMotor1Retry_Times 1U
#define BeadMotor2Retry_Times 3U

#define BeadMotor_Speed 85U
#define BeadMotor_Dir 1U
#define LockOpen_Time 800U

/* 购买与库存暂定参数。 */
#define PURCHASE_DEFAULT_STOCK 10000U
#define PURCHASE_LOW_STOCK_THRESHOLD 3000U
#define PURCHASE_REFILL_DELAY 10000U
#define PURCHASE_MIN_PRICE_YUAN 1U
#define PURCHASE_MAX_PRICE_YUAN 10000U

/* 安卓设置价格结果，放在 BeadPriceStatus 的 ExpandCode。 */
#define PURCHASE_PRICE_SET_OK 0x00U
#define PURCHASE_PRICE_SET_INVALID 0x01U

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
void BeadMotor_Resume(BeadMotor_t *bead_motor);
void BeadMotor_Feedback(BeadMotor_t *bead_motor);
void Device_StopAllImmediately(void);

/* 固件购买、欠吐和库存管理接口。 */
void Purchase_Init(void);
void Purchase_AddCoinPayment(void);
void Purchase_AddBillPayment(uint8_t bill_type);
void Purchase_SetBeadPrice(uint32_t price_yuan);
void Purchase_RequestStatus(void);
void Purchase_OnBeadDispensed(void);
void Purchase_OnDispenseTimeout(void);
void Purchase_Refill(void);
void Purchase_PauseDispense(void);
void Purchase_Task(void);
bool Purchase_IsNoBead(void);
uint32_t Purchase_GetBeadPrice(void);
uint32_t Purchase_GetBeadStock(void);
uint32_t Purchase_GetPendingBeads(void);
uint32_t Purchase_GetCreditYuan(void);
uint8_t Purchase_GetPriceSetResult(void);

void CtrlTask(void);

#endif
