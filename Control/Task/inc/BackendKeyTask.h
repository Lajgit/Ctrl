#ifndef __BACKENDKEYTASK_H__
#define __BACKENDKEYTASK_H__

/*
 * 后台设置按键扫描任务。
 * 实现放在头文件内，保持现有 Keil 工程源文件列表不变，由 Mesg_Task 周期调用。
 */
#include "MesgTask.h"

extern Event_Handle_t Mesg_event;

/*
 * K2 对应 KeyBoard2（PD13），低电平按下。
 * 该函数由 Mesg_Task 周期调用，完成 15 ms 消抖并只在一次按下沿上报一次。
 *
 * 使用头文件内 static 函数，是为了不改变当前 Keil 工程的源文件列表。
 */
static void BackendKey_Task(void)
{
    /* raw_state 跟踪即时电平，stable_state 保存通过消抖后的稳定电平。 */
    static GPIO_PinState raw_state = GPIO_PIN_SET;
    static GPIO_PinState stable_state = GPIO_PIN_SET;
    static uint32_t change_tick = 0U;
    GPIO_PinState current_state;
    uint32_t now;

    current_state = HAL_GPIO_ReadPin(KeyBoard2_GPIO_Port, KeyBoard2_Pin);
    now = HAL_GetTick();

    /* 电平刚变化时只记录时间，等待保持稳定后再确认。 */
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

        /* 仅在稳定按下沿请求 Android 重新同步后台设置。 */
        if (stable_state == GPIO_PIN_RESET)
        {
            EventGroupSetBits(&Mesg_event, MesgEvent_BackendSettingsRequest);
        }
    }
}

#endif
