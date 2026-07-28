#ifndef __FLASHTASK_H__
#define __FLASHTASK_H__

#include "port_flash.h"
#include "stdbool.h"

/*
 * STM32F407 的 Sector 2：0x08008000~0x0800BFFF。
 * APP 从 0x0800C000 启动，因此该扇区专用于参数和购买状态掉电保存。
 */
#define Setting_Addr 0x08008000U
#define Setting_End_Addr 0x0800C000U

/* 购买状态标志：检测到无珠后置位，补珠按钮按下后清除。 */
#define PURCHASE_FLAG_NO_BEAD (1UL << 0)

typedef struct
{
    uint32_t Board_Lightness;
    uint32_t LightBelt_Lightness;
    uint32_t Ctrl_Lightness;

    /* 安卓设置的单颗珠子价格，单位：人民币元。 */
    uint32_t BeadPriceYuan;

    /* 当前珠子库存，每成功吐出一颗减一。 */
    uint32_t BeadStock;

    /* 已收款但尚未成功吐出的珠子数量，掉电后继续保留。 */
    uint32_t PendingBeads;

    /* 尚不足以购买一颗珠子的累计余额，单位：人民币元。 */
    uint32_t PurchaseCreditYuan;

    /* 购买状态标志，当前使用 PURCHASE_FLAG_NO_BEAD。 */
    uint32_t PurchaseFlags;
} Setting_TypeDef;

void ResumeSetting(void);
void FlashTask_Init(void);
void FlashTask_RequestSave(void);
void FlashTask(void);

#endif
