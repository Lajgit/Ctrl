#include "MainTask.h"
#include "FlashTask.h"
#include "MainTask.h"
#include "port_event.h"

Flash_HandleTypeDef hflash;
Setting_TypeDef Setting;
extern Event_Handle_t Event;
void ResumeSetting(void)
{
    Setting.Board_Lightness = 5;
    Setting.LightBelt_Lightness = 5;
}

void FlashTask_Init(void)
{
    Flash_InitTypeDef init;
    init.DataSize = sizeof(Setting_TypeDef);
    init.pData = &Setting;
    init.StartAddress = Setting_Addr;

    Flash_Init(&hflash, init);
    hflash.f_Read(&hflash);
    // 如果Flash未写入过数据，默认设置
    for (uint32_t i = 0; i < hflash.DataSize / sizeof(uint32_t); i++)
    {
        if (((uint32_t *)hflash.pData)[i] == 0xFFFFFFFF)
        {
            ResumeSetting();
            hflash.f_Write(&hflash);
            break;
        }
    }
}

void FlashTask(void)
{
    if (EventGroupCheckBits(&Event, Event_SaveSetting) == true)
    {
        hflash.f_Write(&hflash);
        EventGroupClearBits(&Event, Event_SaveSetting);
    }
}