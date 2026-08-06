#ifndef __KEYTASK_H__
#define __KEYTASK_H__

/*
 * 按键、硬币脉冲和 ICT 纸钞机任务接口。
 * 现金输入只形成可持久化的硬件事实，珠数和订单由 Android/平台决定。
 */
#include "main.h"
#include "stdbool.h"

/* 按键和硬币脉冲的软件消抖时间，单位为毫秒。 */
#define KEY_DEBOUNCE_TIME 15U
#define PULSE_DEBOUNCE_TIME 10U

/* 现金介质编码。 */
#define CASH_MEDIUM_COIN 0U
#define CASH_MEDIUM_BANKNOTE 1U

/* 现金受理使能掩码：bit0 纸钞机，bit1 硬币器。 */
#define CASH_ACCEPT_BANKNOTE_MASK (1U << 0)
#define CASH_ACCEPT_COIN_MASK (1U << 1)

/* 初始化并周期扫描按键、硬币器和纸钞机。 */
void KeyAll_Init(void);
void Key_Task(void);
/* USART3 收到一个纸钞机字节后的中断回调。 */
void BillAcceptor_RxCpltCallback(void);
/* 向 ICT 纸钞机发送复位命令。 */
void BillAcceptor_Reset(void);

/* 应用现金设备配置并查询当前实际状态。 */
bool CashAcceptance_Apply(uint8_t enable_mask, uint32_t config_version);
bool CashAcceptance_ApplyV22(uint8_t enable_mask, uint32_t config_version);
void CashAcceptance_Disable(void);
void CashAcceptance_RequestStatus(void);
uint8_t CashAcceptance_GetEnableMask(void);
uint32_t CashAcceptance_GetConfigVersion(void);
uint32_t CashDevice_GetStatusData(void);

/* 掉电现金事实队列的查询、确认和恢复接口。 */
bool CashEvent_HasPending(void);
bool CashEvent_HasCapacity(void);
uint8_t CashEvent_GetPendingMedium(void);
uint16_t CashEvent_GetPendingAmountFen(void);
uint16_t CashEvent_GetPendingSequence(void);
void CashEvent_ConfirmTransport(uint16_t sequence);
void CashEvent_RestorePending(void);

/* 通过 PB13 控制硬币器 12V 电源，并读取输出状态进行确认。 */
bool CashHardware_SetCoinEnable(bool enable);

#endif
