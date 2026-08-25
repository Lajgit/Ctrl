#include "MainTask.h"
#include "CommTask.h"
#include "port_event.h"
#include "iwdg.h"

#define SYSLIGHT_BLINK_TIME 500U

/*
 * 旧控台的灯光/按键模块仍在工程中保留源码，但新转发板不再执行这些业务。
 * 保留Scene和Event全局符号，避免旧文件参与编译时产生链接错误。
 */
Scene_t Scene = SCENE_LESSLIGHT;
Event_Handle_t Event;

static void SystemLight_Task(void)
{
    static uint32_t time = 0U;

    if ((HAL_GetTick() - time) > SYSLIGHT_BLINK_TIME)
    {
        HAL_GPIO_TogglePin(LED_GPIO_Port, LED_Pin);
        time = HAL_GetTick();
    }
}

void System_Reset(void)
{
    __disable_irq();
    HAL_NVIC_SystemReset();
}

void Main_Init(void)
{
    /* 新板只初始化UART透明转发和第二组吐珠电机控制。 */
    CommInit();
}

void Main_Task(void)
{
    CommTask();
    HAL_IWDG_Refresh(&hiwdg);
    SystemLight_Task();
}
