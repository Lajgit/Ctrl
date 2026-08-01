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

/*
 * 三线硬币器只有12V/GND/脉冲，没有物理inhibit，控制板始终接收并上报硬币。
 * enable_mask 只决定纸钞机是否允许收钞；状态中硬币位始终为1。
 */
bool CashAcceptance_Apply(uint8_t enable_mask, uint32_t config_version);
void CashAcceptance_Disable(void);
void CashAcceptance_RequestStatus(void);
uint8_t CashAcceptance_GetEnableMask(void);
uint32_t CashAcceptance_GetConfigVersion(void);
uint32_t CashDevice_GetStatusData(void);

/* 多笔现金按独立序号排队，Android每持久化一笔后确认并推进队列。 */
bool CashEvent_HasPending(void);
bool CashEvent_HasCapacity(void);
uint8_t CashEvent_GetPendingMedium(void);
uint16_t CashEvent_GetPendingAmountFen(void);
uint16_t CashEvent_GetPendingSequence(void);
void CashEvent_ConfirmTransport(uint16_t sequence);
void CashEvent_RestorePending(void);

/* 三线硬币器无使能引脚，保留接口仅兼容未来硬件版本。 */
bool CashHardware_SetCoinEnable(bool enable);

#endif
