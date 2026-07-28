#ifndef __INTERRUPTTASK_H__
#define __INTERRUPTTASK_H__

#include "main.h"

#define HOOLLE_SHAKE_TIME 10

void USART1_RxInit(void);
void USART1_IRQ(void);

/* 在主循环中处理由外部中断登记的吐珠、存珠光眼脉冲。 */
void InterruptTask_Process(void);

#endif