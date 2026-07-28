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

#endif
