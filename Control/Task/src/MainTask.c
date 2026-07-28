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

    /* 先恢复价格、库存、余额和欠吐数量，再初始化购买相关外设。 */
    FlashTask_Init();
    Device_Init();
    Communicate_Init();
    KeyAll_Init();
    Purchase_Init();
}

void MainTask(void)
{
    Communicate_Task();
    HAL_IWDG_Refresh(&hiwdg);

    Key_Task();
    HAL_IWDG_Refresh(&hiwdg);

    /* 在主循环上下文处理 PD3 吐珠、PD4 存珠光眼反馈。 */
    InterruptTask_Process();
    HAL_IWDG_Refresh(&hiwdg);

    /* 根据纸币、硬币、补珠延时和掉电恢复状态安排吐珠。 */
    Purchase_Task();
    CtrlTask();
    HAL_IWDG_Refresh(&hiwdg);

    Mesg_Task();
    HAL_IWDG_Refresh(&hiwdg);

    /* 将最新价格、库存、余额和欠吐数量追加保存到 Sector 2。 */
    FlashTask();
    HAL_IWDG_Refresh(&hiwdg);

    System_Task();
    HAL_IWDG_Refresh(&hiwdg);
}
