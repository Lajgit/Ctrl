#include "MainTask.h"
#include "CtrlTask.h"
#include "MesgTask.h"
#include "KeyTask.h"
#include "CommunicateTask.h"
#include "gpio.h"
#include "iwdg.h"

#define SYSLIGHT_BLINK_TIME 500U

extern Event_Handle_t Mesg_event;

Scene_t Scene = SCENE_IDLE;
Event_Handle_t Event;

static void System_Task(void)
{
    static uint32_t time = 0U;

    if (HAL_GetTick() - time > SYSLIGHT_BLINK_TIME)
    {
        HAL_GPIO_TogglePin(LED_GPIO_Port, LED_Pin);
        time = HAL_GetTick();
    }
}

void MainTaskInit(void)
{
    EventGroupCreate(&Mesg_event);
    EventGroupCreate(&Event);
    Device_Init();
    KeyAll_Init();
    Communicate_Init();
}

void MainTask(void)
{
    Communicate_Task();
    HAL_IWDG_Refresh(&hiwdg);

    Key_Task();
    HAL_IWDG_Refresh(&hiwdg);

    CtrlTask();
    HAL_IWDG_Refresh(&hiwdg);

    Mesg_Task();
    HAL_IWDG_Refresh(&hiwdg);

    System_Task();
    HAL_IWDG_Refresh(&hiwdg);
}
