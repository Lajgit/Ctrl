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

/* 仅保存硬件事实；控制板不再保存价格、余额、欠吐或本地订单。 */
#define HARDWARE_FLAG_NO_BEAD (1UL << 0)
#define HARDWARE_FLAG_PENDING_CASH (1UL << 1)

typedef struct
{
    uint32_t Board_Lightness;
    uint32_t LightBelt_Lightness;
    uint32_t Ctrl_Lightness;

    /* 维护库存估计值，仅由真实 PD3 光眼和 K1 补珠动作修改。 */
    uint32_t BeadStock;

    /* HARDWARE_FLAG_*。 */
    uint32_t HardwareFlags;

    /*
     * 尚未被 Android 持久化确认的现金事实。
     * 金额单位为人民币分；介质 0=硬币、1=纸币。
     */
    uint32_t PendingCashAmountFen;
    uint32_t PendingCashSequence;
    uint32_t PendingCashMedium;
} Setting_TypeDef;

void ResumeSetting(void);
void FlashTask_Init(void);
void FlashTask_RequestSave(void);
void FlashTask(void);

#endif
