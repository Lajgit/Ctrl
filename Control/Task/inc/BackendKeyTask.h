#ifndef __BACKENDKEYTASK_H__
#define __BACKENDKEYTASK_H__

#include "MesgTask.h"

extern Event_Handle_t Mesg_event;

/*
 * K2 使用原理图和 CubeMX 中的 SettingButton（PD11），低电平按下。
 * 该函数由 Mesg_Task 周期调用，完成 15 ms 消抖并只在一次按下沿上报一次。
 *
 * 使用头文件内 static inline，是为了不改变当前 Keil 工程的源文件列表。
 */
static void BackendKey_Task(void)
{
    static GPIO_PinState raw_state = GPIO_PIN_SET;
    static GPIO_PinState stable_state = GPIO_PIN_SET;
    static uint32_t change_tick = 0U;
    GPIO_PinState current_state;
    uint32_t now;

    current_state = HAL_GPIO_ReadPin(SettingButton_GPIO_Port, SettingButton_Pin);
    now = HAL_GetTick();

    if (current_state != raw_state)
    {
        raw_state = current_state;
        change_tick = now;
        return;
    }

    if ((current_state != stable_state) &&
        ((now - change_tick) >= 15U))
    {
        stable_state = current_state;

        if (stable_state == GPIO_PIN_RESET)
        {
            EventGroupSetBits(&Mesg_event, MesgEvent_BackendSettingsRequest);
        }
    }
}

#endif
