#include "MainTask.h"
#include "CtrlTask.h"
#include "MesgTask.h"
#include "KeyTask.h"
#include "FlashTask.h"
#include "LightTask.h"
#include "InterruptTask.h"
#include "CommunicateTask.h"
#include "FlashTask.h"
#include "port_digitaltube.h"
#include "gpio.h"
#include "iwdg.h"

#define SYSLIGHT_BLINK_TIME 500

//测试全部数码管显示8
#define DIGITAL_TUBE_ALL_8_TEST 0

extern Event_Handle_t Mesg_event;
extern Event_Handle_t Key_event;
extern Motor_Hoolle Motor_Hoolle1;
extern Switch_Valve Lock_Valve, Valve;
extern ListHandle_t ResendList, DealList;
extern Tx_HandleTypeDef Tx;
extern Rx_HandleTypeDef Rx;
extern Setting_TypeDef Setting;
extern SPI_HandleTypeDef hspi2, hspi3;
extern Tx_HandleTypeDef Tx3, Tx1;

static uint8_t DigitalBuffer[14] = {0x82};
static uint8_t DigitalBufferOutside[4] = {0x82};

DigitalTube_t DigitalTube;
DigitalTube_t DigitalTubeOutside;

Scene_t Scene = SCENE_PLAYING;
Event_Handle_t Event;
uint32_t ValveRestartTime = 0;

static void System_Task(void)
{
    static uint32_t time = 0;
    if (HAL_GetTick() - time > SYSLIGHT_BLINK_TIME)
    {
        HAL_GPIO_TogglePin(LED_GPIO_Port, LED_Pin);
        time = HAL_GetTick();
    }
}

static void DigitalTubeTask_Init(void)
{
    DigitalTube_Init_t Init;
    /* 装载有效数据前禁止数码管输出 */
    HAL_GPIO_WritePin(
        SPI2_OE_GPIO_Port,
        SPI2_OE_Pin,
        GPIO_PIN_SET
    );

    HAL_GPIO_WritePin(
        SPI3_OE_GPIO_Port,
        SPI3_OE_Pin,
        GPIO_PIN_SET
    );

    Init.hspi = &hspi2;
    Init.Buffer = DigitalBuffer;
    Init.bit_num = 14;
    Init.OE_GPIO = SPI2_OE_GPIO_Port;
    Init.OE_Pin = SPI2_OE_Pin;
    Init.LE_GPIO = SPI2_CS_GPIO_Port;
    Init.LE_Pin = SPI2_CS_Pin;
    Init.CODE_CA = DIGITAL3BIT_CODE_CA;
    DigitalTube_Init(&DigitalTube, Init);
    DigitalTube.Set_Num(&DigitalTube, 0, (uint32_t)15, 2);
    DigitalTube.Set_Num(&DigitalTube, 2, 1, 1);
    DigitalTube.Set_Num(&DigitalTube, 3, 2, 1);
    DigitalTube.Set_Num(&DigitalTube, 4, 3, 1);
    DigitalTube.Set_Num(&DigitalTube, 5, 4, 1);
    DigitalTube.Set_Num(&DigitalTube, 6, 5, 1);
    DigitalTube.Set_Num(&DigitalTube, 7, 6, 1);
    DigitalTube.Set_Num(&DigitalTube, 8, 7, 1);
    DigitalTube.Set_Num(&DigitalTube, 9, 8, 1);
    DigitalTube.Set_Num(&DigitalTube, 10, 9, 1);
    DigitalTube.Set_Num(&DigitalTube, 11, 9, 1);
    DigitalTube.Set_Num(&DigitalTube, 12, 9, 1);
    DigitalTube.Set_Num(&DigitalTube, 13, 9, 1);
    DigitalTube.Refresh(&DigitalTube);

    Init.hspi = &hspi3;
    Init.Buffer = DigitalBufferOutside;
    Init.bit_num = 4;
    Init.OE_GPIO = SPI3_OE_GPIO_Port;
    Init.OE_Pin = SPI3_OE_Pin;
    Init.LE_GPIO = SPI3_CS_GPIO_Port;
    Init.LE_Pin = SPI3_CS_Pin;
    Init.CODE_CA = DIGITAL3BIT_CODE_CA;
    DigitalTube_Init(&DigitalTubeOutside, Init);
    DigitalTubeOutside.Set_Num(&DigitalTubeOutside, 0, 0, 4);
    DigitalTubeOutside.Refresh(&DigitalTubeOutside);
}

// static void DigitalTube_Task(void)
// {
//     static uint32_t time = 0;
//     if (HAL_GetTick() - time > 10)
//     {
//         DigitalTubeOutside.Refresh(&DigitalTubeOutside);
//         DigitalTube.Refresh(&DigitalTube);
//         time = HAL_GetTick();
//     }
// }

static void DigitalTube_Task(void)
{
    static uint32_t time = 0;

    if (HAL_GetTick() - time > 10)
    {
#if DIGITAL_TUBE_ALL_8_TEST

        /* SPI2：球盘上的14位数码管全部显示8 */
        for (uint8_t i = 0; i < 14; i++)
        {
            DigitalTube.Set_Num(&DigitalTube, i, 8, 1);
        }

        /* SPI3：外部4位数码管全部显示8 */
        for (uint8_t i = 0; i < 4; i++)
        {
            DigitalTubeOutside.Set_Num(&DigitalTubeOutside, i, 8, 1);
        }

#endif

        DigitalTubeOutside.Refresh(&DigitalTubeOutside);
        DigitalTube.Refresh(&DigitalTube);

        time = HAL_GetTick();
    }
}

static void Detect_Task(void)
{
    if (EventGroupCheckBits(&Key_event, Event_AllHoleSwitchTrigger) == true)
    {
        if (HAL_GetTick() - ValveRestartTime > 4000)
        {
            // EventGroupClearBits(&Key_event, Event_AllHoleSwitchTrigger);
            Valve.Switch.state = DEVICE_STATE_START;
            ValveRestartTime = HAL_GetTick();
        }
    }
}

void MainTaskInit(void)
{
    FlashTask_Init();
    KeyAll_Init();
    Device_Init();
    Light_Init();
    DigitalTubeTask_Init();
    Communicate_Init();
    Comm_SendMesg_FillData(&Tx3, Board_to_Ctrl, 0x04, Setting.Ctrl_Lightness, 0x00); // 控台亮度
    Comm_SendMesg_FillData(&Tx3, Board_to_Ctrl, 0x03, 0x00, 0x00);                   // 控台灯效
    Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, RequestDigitalTube, 0x00, 0x00, &ResendList);
}
void MainTask(void)
{
    // HAL_GPIO_WritePin(SPI3_OE_GPIO_Port, SPI3_OE_Pin, GPIO_PIN_RESET);
    Communicate_Task();
    HAL_IWDG_Refresh(&hiwdg);
    Mesg_Task();
    HAL_IWDG_Refresh(&hiwdg);
    Key_Task();
    HAL_IWDG_Refresh(&hiwdg);
    CtrlTask();
    HAL_IWDG_Refresh(&hiwdg);
    System_Task();
    HAL_IWDG_Refresh(&hiwdg);
    Light_Task();
    HAL_IWDG_Refresh(&hiwdg);
    DigitalTube_Task();
    HAL_IWDG_Refresh(&hiwdg);
    Detect_Task();
    HAL_IWDG_Refresh(&hiwdg);
    FlashTask();
    HAL_IWDG_Refresh(&hiwdg);
    // HAL_GPIO_WritePin(SPI3_OE_GPIO_Port, SPI3_OE_Pin, GPIO_PIN_SET);
}