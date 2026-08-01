#ifndef __FLASHTASK_H__
#define __FLASHTASK_H__

#include "port_flash.h"
#include "stdbool.h"

/*
 * STM32F407 的 Sector 2：0x08008000~0x0800BFFF。
 * APP 从 0x0800C000 启动，因此该扇区专用于控制板硬件状态掉电保存。
 */
#define Setting_Addr 0x08008000U
#define Setting_End_Addr 0x0800C000U

#define HARDWARE_FLAG_NO_BEAD (1UL << 0)

/*
 * Android 正常会在几十毫秒内确认一笔现金；16 项队列用于覆盖连续投币、
 * 连续入钞以及 Android 短时重启。控制板只保存现金事实，不计算购买余额。
 */
#define CASH_EVENT_QUEUE_CAPACITY 16U
#define CASH_EVENT_PACK(medium, amount_fen) \
    ((((uint32_t)(medium) & 0xFFU) << 16U) | ((uint32_t)(amount_fen) & 0xFFFFU))
#define CASH_EVENT_PACKED_MEDIUM(value) (((value) >> 16U) & 0xFFU)
#define CASH_EVENT_PACKED_AMOUNT(value) ((value) & 0xFFFFU)

typedef struct
{
    uint32_t Board_Lightness;
    uint32_t LightBelt_Lightness;
    uint32_t Ctrl_Lightness;

    /* 仅由真实 PD3 光眼和 K1 补珠动作修改。 */
    uint32_t BeadStock;
    uint32_t HardwareFlags;

    /* 每笔现金使用独立16位序号，队列头按顺序等待 Android 持久化确认。 */
    uint32_t CashSequenceCounter;
    uint32_t CashQueueHead;
    uint32_t CashQueueCount;
    uint32_t CashQueueSequence[CASH_EVENT_QUEUE_CAPACITY];
    uint32_t CashQueuePacked[CASH_EVENT_QUEUE_CAPACITY];
} Setting_TypeDef;

void ResumeSetting(void);
void FlashTask_Init(void);
void FlashTask_RequestSave(void);
void FlashTask(void);

#endif
