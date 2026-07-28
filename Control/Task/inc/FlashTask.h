#ifndef __FLASHTASK_H__
#define __FLASHTASK_H__ 

#include "port_flash.h"

#define Setting_Addr 0x08008000

typedef struct
{
    uint32_t Board_Lightness;
    uint32_t LightBelt_Lightness;
    uint32_t Ctrl_Lightness;
}Setting_TypeDef;

void ResumeSetting(void);
void FlashTask_Init(void);
void FlashTask(void);

#endif
