#ifndef __KEYTASK_H__
#define __KEYTASK_H__

#include "main.h"
#include "stdbool.h"

#define KEY_DEBOUNCE_TIME 15U
#define PULSE_DEBOUNCE_TIME 10U

#define CASH_MEDIUM_COIN 0U
#define CASH_MEDIUM_BANKNOTE 1U

#define CASH_ACCEPT_BANKNOTE_MASK (1U << 0)
#define CASH_ACCEPT_COIN_MASK (1U << 1)

void KeyAll_Init(void);
void Key_Task(void);
void BillAcceptor_RxCpltCallback(void);
void BillAcceptor_Reset(void);

/* 平台完整现金配置应用成功后才能启用；上电默认禁用。 */
bool CashAcceptance_Apply(uint8_t enable_mask, uint32_t config_version);
void CashAcceptance_Disable(void);
void CashAcceptance_RequestStatus(void);
uint8_t CashAcceptance_GetEnableMask(void);
uint32_t CashAcceptance_GetConfigVersion(void);
uint32_t CashDevice_GetStatusData(void);

/* 尚未被 Android 可靠接收确认的现金事实。 */
bool CashEvent_HasPending(void);
uint8_t CashEvent_GetPendingMedium(void);
uint16_t CashEvent_GetPendingAmountFen(void);
uint16_t CashEvent_GetPendingSequence(void);
void CashEvent_ConfirmTransport(uint16_t sequence);
void CashEvent_RestorePending(void);

/* 量产硬件适配函数：其他源文件可提供同名强实现覆盖弱实现。 */
bool CashHardware_SetCoinEnable(bool enable);

#endif
