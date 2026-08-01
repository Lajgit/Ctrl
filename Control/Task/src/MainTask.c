#include "MainTask.h"
#include "CtrlTask.h"
#include "MesgTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "InterruptTask.h"
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

    FlashTask_Init();
    Device_Init();
    Communicate_Init();
    KeyAll_Init();
    Hardware_Init();

    /* 新协议上电默认关闭现金；恢复尚未被 Android 确认的同一笔现金事实。 */
    CashEvent_RestorePending();
    Hardware_RequestStatus();
    EventGroupSetBits(&Mesg_event, MesgEvent_VersionRequest);

    if (Hardware_GetBeadStock() == 0U || Hardware_IsNoBead())
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
    }
    else if (Hardware_GetBeadStock() <= HARDWARE_LOW_STOCK_THRESHOLD)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadLowStock);
    }
}

void MainTask(void)
{
    Communicate_Task();
    HAL_IWDG_Refresh(&hiwdg);

    Key_Task();
    HAL_IWDG_Refresh(&hiwdg);

    InterruptTask_Process();
    HAL_IWDG_Refresh(&hiwdg);

    /* 现金事实和真实光眼库存先落 Flash，再允许通过串口对外报告。 */
    FlashTask();
    HAL_IWDG_Refresh(&hiwdg);

    CtrlTask();
    HAL_IWDG_Refresh(&hiwdg);

    FlashTask();
    HAL_IWDG_Refresh(&hiwdg);

    Mesg_Task();
    HAL_IWDG_Refresh(&hiwdg);

    System_Task();
    HAL_IWDG_Refresh(&hiwdg);
}
