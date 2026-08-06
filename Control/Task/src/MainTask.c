/*
 * 控制板主任务：完成各事件组和硬件模块初始化，并在主循环中按固定顺序调度。
 * 各阶段之间刷新独立看门狗，防止某个外设任务长时间阻塞导致整机失控。
 */
#include "MainTask.h"
#include "CtrlTask.h"
#include "MesgTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "InterruptTask.h"
#include "CommunicateTask.h"
#include "gpio.h"
#include "iwdg.h"

/* 系统运行指示灯翻转周期，单位为毫秒。 */
#define SYSLIGHT_BLINK_TIME 500U

extern Event_Handle_t Mesg_event;

/* 当前场景和通用任务事件组。 */
Scene_t Scene = SCENE_IDLE;
Event_Handle_t Event;

/* 周期翻转系统 LED，作为主循环仍在运行的可视化心跳。 */
static void System_Task(void)
{
    static uint32_t time = 0U;

    if (HAL_GetTick() - time > SYSLIGHT_BLINK_TIME)
    {
        HAL_GPIO_TogglePin(LED_GPIO_Port, LED_Pin);
        time = HAL_GetTick();
    }
}

/* 按依赖顺序初始化持久化、硬件、通信、现金输入和业务状态。 */
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

    /* 根据掉电恢复后的库存生成首个库存告警事件。 */
    if (Hardware_GetBeadStock() == 0U || Hardware_IsNoBead())
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadEmpty);
    }
    else if (Hardware_GetBeadStock() <= HARDWARE_LOW_STOCK_THRESHOLD)
    {
        EventGroupSetBits(&Mesg_event, MesgEvent_BeadLowStock);
    }
}

/*
 * 主循环调度顺序：通信接收→现金/按键→中断事实→Flash→硬件状态机→
 * 再次保存→消息上报→系统心跳。该顺序保证硬件事实先持久化再对外报告。
 */
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

    /* 保存硬件状态机本轮产生的库存或标志变化。 */
    FlashTask();
    HAL_IWDG_Refresh(&hiwdg);

    Mesg_Task();
    HAL_IWDG_Refresh(&hiwdg);

    System_Task();
    HAL_IWDG_Refresh(&hiwdg);
}
