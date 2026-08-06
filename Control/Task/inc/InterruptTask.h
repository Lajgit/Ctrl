#ifndef __INTERRUPTTASK_H__
#define __INTERRUPTTASK_H__

/* 串口接收中断和光眼外部中断的任务接口。 */
#include "main.h"

/* 光眼脉冲的基础消抖时间，单位为毫秒。 */
#define HOOLLE_SHAKE_TIME 10

/* 初始化 Android 通信串口的中断接收。 */
void USART1_RxInit(void);
/* USART1 中断入口兼容接口。 */
void USART1_IRQ(void);

/* 在主循环中处理由外部中断登记的吐珠、存珠光眼脉冲。 */
void InterruptTask_Process(void);

#endif