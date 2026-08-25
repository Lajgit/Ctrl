#ifndef __FLASHTASK_H__
#define __FLASHTASK_H__

#include "port_flash.h"

#define Setting_Addr 0x0800F800

typedef struct
{
    uint32_t Board_Lightness;
    uint32_t LightBelt_Lightness;
} Setting_TypeDef;

extern Flash_HandleTypeDef hflash;
extern Setting_TypeDef Setting;

void ResumeSetting(void);
void FlashTask_Init(void);
void FlashTask(void);

#endif
