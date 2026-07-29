#ifndef __KEYTASK_H__
#define __KEYTASK_H__

#include "main.h"
#include "stdbool.h"

#define KEY_DEBOUNCE_TIME 15U
#define PULSE_DEBOUNCE_TIME 10U

void KeyAll_Init(void);
void Key_Task(void);
void BillAcceptor_RxCpltCallback(void);
void BillAcceptor_SetCurrencyMode(uint8_t mode);
void BillAcceptor_SetEnable(bool enable);
void BillAcceptor_Reset(void);

/* 投币器接收开关。硬件 inhibit 输出由 CashHardware_SetCoinEnable 适配。 */
void CoinAcceptor_SetEnable(bool enable);
bool CoinAcceptor_IsEnabled(void);

/* 量产硬件适配函数：其他源文件可提供同名强实现覆盖 KeyTask.c 的弱实现。 */
bool CashHardware_SetCoinEnable(bool enable);
bool CashHardware_DoReturn(uint8_t medium, uint32_t amount_yuan);

/* 请求真实退币；成功后由固件上报 CashReturnedAmount。 */
bool CashHardware_RequestReturn(uint8_t medium, uint32_t amount_yuan);

/* 最近一次现金金额事件，供 MesgTask 组帧。 */
uint32_t CashEvent_GetPackedData(void);

#endif
