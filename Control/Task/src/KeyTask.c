#include "KeyTask.h"
#include "port_key.h"
#include "MesgTask.h"
#include "MainTask.h"
#include "LightTask.h"
#include "CtrlTask.h"
#include "port_digitaltube.h"

static GPIO_TypeDef *Hole_GPIOPort[] = {Hole1_GPIO_Port, Hole2_GPIO_Port, Hole3_GPIO_Port,
                                        Hole4_GPIO_Port};
static uint16_t Hole_Pin[] = {Hole1_Pin, Hole2_Pin, Hole3_Pin, Hole4_Pin};

static GPIO_TypeDef *Button_GPIOPort[] = {KeyBoard1_GPIO_Port, KeyBoard2_GPIO_Port, KeyBoard3_GPIO_Port};
static uint16_t Button_Pin[] = {KeyBoard1_Pin, KeyBoard2_Pin, KeyBoard3_Pin};

static GPIO_TypeDef *Switch_GPIOPort[] = {Switch0_GPIO_Port, Switch1_GPIO_Port, Switch2_GPIO_Port, Switch3_GPIO_Port, Switch4_GPIO_Port,
                                          Switch5_GPIO_Port, Switch6_GPIO_Port, Switch7_GPIO_Port, Switch8_GPIO_Port, Switch9_GPIO_Port,
                                          Switch10_GPIO_Port, Switch11_GPIO_Port};
static uint16_t Switch_Pin[] = {Switch0_Pin, Switch1_Pin, Switch2_Pin, Switch3_Pin, Switch4_Pin, Switch5_Pin, Switch6_Pin,
                                Switch7_Pin, Switch8_Pin, Switch9_Pin, Switch10_Pin, Switch11_Pin};

static Key_HandleTypeDef Hole[4];
static Key_HandleTypeDef *Hole_list[4];
// static Key_HandleTypeDef Key[5];
// static Key_HandleTypeDef *Key_list[5];
static Key_HandleTypeDef Key[4];
static Key_HandleTypeDef *Key_list[4];
static Key_HandleTypeDef Switch[12];
static Key_HandleTypeDef *Switch_list[12];
uint8_t KeyState[4] = {0};

extern Event_Handle_t Mesg_event;
extern ListHandle_t ResendList, DealList;
extern servo_t Servo;
extern Scene_t Scene;
extern Light_Handle_t *HoleLightList[4];
extern Motor_Hoolle Motor_Hoolle1;
extern Motor_Card Card;
extern Tx_HandleTypeDef Tx1;
extern Rx_HandleTypeDef Rx1;
extern uint32_t ValveRestartTime;
extern DigitalTube_t DigitalTube;

Event_Handle_t Key_event;


#define COIN_INPUT_DEBOUNCE_TIME 10U

typedef enum
{
    COIN_INPUT_IDLE = 0,
    COIN_INPUT_LOW_FILTER,
    COIN_INPUT_WAIT_RELEASE,
    COIN_INPUT_HIGH_FILTER,
} CoinInput_State_t;

static CoinInput_State_t CoinInputState = COIN_INPUT_IDLE;
static uint32_t CoinInputTick = 0;

/*
 * ----------微动初始化----------
 */
static void Switch_ShortCallback(uint16_t id)
{
    if (id >= 12)
        return;
    // Comm_SendMesg_FillData(&Tx1, Board_to_Android, ChannelRequest, id, 0x00);
    Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, ChannelRequest, id, 0x00, &ResendList);
}

static void Switch_ReleaseCallback(uint16_t id)
{
}

static void Switch_Init(void)
{
    for (int i = 0; i < 12; i++)
    {
        Key_Init(&Switch[i], i, Switch_GPIOPort[i], Switch_Pin[i], KEY_DEBOUNCE_TIME, KEY_LONG_PRESS_TIME, 1, Switch_ShortCallback, NULL, Switch_ReleaseCallback, GPIO_PIN_SET);
        Switch_list[i] = &Switch[i];
    }
}

/*
 * ----------洞口初始化----------
 */
static void Hole_ShortCallback(uint16_t id)
{
    if (id >= 4)
        return;
    if (HoleLightList[id]->state == 0)
    {
        HoleLightList[id]->state = 1;
        HoleLightList[id]->SetColor(HoleLightList[id], GREEN, 255);
    }
    if (KeyState[id] == 0)
    {
        KeyState[id] = 1;
        EventGroupClearBits(&Key_event, Event_AllHoleSwitchTrigger);
        // if (Scene == SCENE_PLAYING)
        {
            Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, LightEye, (uint32_t)id + 1, 0x00, &ResendList);
        }
    }
}

static void Hole_LongCallback(uint16_t id)
{
}

static void Hole_ReleaseCallback(uint16_t id)
{
    // if (id >= 4)
    //     return;
    // HoleLightList[id]->state = 0;
    // HoleLightList[id]->SetColor(HoleLightList[id], NONE, 0);
}

static void Hole_Init(void)
{
    for (int i = 0; i < 4; i++)
    {
        Key_Init(&Hole[i], i, Hole_GPIOPort[i], Hole_Pin[i], KEY_DEBOUNCE_TIME, HOLE_LONG_PRESS_TIME, HOLE_LONG_TRIGGER_FREQUENCY, Hole_ShortCallback, Hole_LongCallback, Hole_ReleaseCallback, GPIO_PIN_SET);
        Hole_list[i] = &Hole[i];
    }
}

/*
 * ----------按键初始化----------
 */

static void SettingButtonScan(void)
{
    for (uint8_t i = 0; i < 3; i++)
    {
        if (HAL_GPIO_ReadPin(Button_GPIOPort[i], Button_Pin[i]) == GPIO_PIN_SET)
            return;
    }
    Comm_SendMesg_FillData(&Tx1, Board_to_Android, IntoHigherStage, 0x00, 0x00);
}

static void Key_ShortCallback(uint16_t id)
{
    // if (id >= 5)
    //     return;
    // if (id <= 2)
    // {
    //     Comm_SendMesg_FillData(&Tx1, Board_to_Android, SettingButton, id + 1, 0x01);
    // }
    // if (id == 3)
    //     EventGroupSetBits(&Mesg_event, MesgEvent_HoolleInput);
    // if (id == 4)
    //     EventGroupSetBits(&Mesg_event, MesgEvent_CoinInput);
    if (id >= 4)
        return;
    
    if (id <= 2)
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android,SettingButton, id + 1, 0x01);
    }
    
    if (id == 3)
        EventGroupSetBits(&Mesg_event, MesgEvent_HoolleInput);
}

static void Key_LongCallback(uint16_t id)
{
    if (id >= 3)
        return;
    if (id <= 2)
    {
        SettingButtonScan();
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, SettingButton, id + 1, 0x02);
    }
}

static void Key_ReleaseCallback(uint16_t id)
{
}

static void CoinInput_Scan(void)
{
    GPIO_PinState pin_state;
    uint32_t current_tick;

    pin_state = HAL_GPIO_ReadPin(CoinInput_GPIO_Port, CoinInput_Pin);
    current_tick = HAL_GetTick();

    switch (CoinInputState)
    {
    case COIN_INPUT_IDLE:
        if (pin_state == GPIO_PIN_RESET)
        {
            CoinInputTick = current_tick;
            CoinInputState = COIN_INPUT_LOW_FILTER;
        }
        break;

    case COIN_INPUT_LOW_FILTER:
        if (pin_state == GPIO_PIN_SET)
        {
            /* 低电平不足10ms，判定为毛刺 */
            CoinInputState = COIN_INPUT_IDLE;
        }
        else if (current_tick - CoinInputTick >=
                 COIN_INPUT_DEBOUNCE_TIME)
        {
            /* 低电平有效，等待投币脉冲释放 */
            CoinInputState = COIN_INPUT_WAIT_RELEASE;
        }
        break;

    case COIN_INPUT_WAIT_RELEASE:
        if (pin_state == GPIO_PIN_SET)
        {
            CoinInputTick = current_tick;
            CoinInputState = COIN_INPUT_HIGH_FILTER;
        }
        break;

    case COIN_INPUT_HIGH_FILTER:
        if (pin_state == GPIO_PIN_RESET)
        {
            /* 高电平未稳定，继续等待释放 */
            CoinInputState = COIN_INPUT_WAIT_RELEASE;
        }
        else if (current_tick - CoinInputTick >=
                 COIN_INPUT_DEBOUNCE_TIME)
        {
            /* 完整有效脉冲，只上报一次投币 */
            EventGroupSetBits(&Mesg_event, MesgEvent_CoinInput);
            CoinInputState = COIN_INPUT_IDLE;
        }
        break;

    default:
        CoinInputState = COIN_INPUT_IDLE;
        break;
    }
}

static void Button_Init(void)
{
    for (int i = 0; i < 3; i++)
    {
        Key_Init(&Key[i], i, Button_GPIOPort[i], Button_Pin[i], 15, KEY_LONG_PRESS_TIME, 1, Key_ShortCallback, Key_LongCallback, Key_ReleaseCallback, GPIO_PIN_RESET);
        Key_list[i] = &Key[i];
    }
    // 投珠光眼
    Key_Init(&Key[3], 3, HoolleInput_GPIO_Port, HoolleInput_Pin, 1, 2000, 1, Key_ShortCallback, Key_LongCallback, Key_ReleaseCallback, GPIO_PIN_RESET);
    Key_list[3] = &Key[3];
    // 投币器
    // Key_Init(&Key[4], 4, CoinInput_GPIO_Port, CoinInput_Pin, 1, 2000, 1, Key_ShortCallback, Key_LongCallback, Key_ReleaseCallback, GPIO_PIN_RESET);
    // Key_list[4] = &Key[4];
}

void KeyAll_Init(void)
{
    Hole_Init();
    Button_Init();
    Switch_Init();
    EventGroupClearBits(&Mesg_event, Event_SettingButtonPress);
    EventGroupClearBits(&Key_event, Event_AllHoleSwitchTrigger);
}
void Key_Task(void)
{
    // SettingButtonScan();
    if (HAL_GetTick() > 10000)
        Key_Scan(Hole_list, 4);
    // Key_Scan(Key_list, 5);
    Key_Scan(Key_list, 4);
    CoinInput_Scan();
    Key_Scan(Switch_list, 12);
    if (EventGroupCheckBits(&Key_event, Event_AllHoleSwitchTrigger) == false)
    {
        uint8_t i;
        for (i = 0; i < 4; i++)
        {
            if (KeyState[i] == 0)
                break;
        }
        if (i == 4)
        {
            Motor_Hoolle1.Motor.state = DEVICE_STATE_PAUSE;
            Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, LightEye, 0x00, 0xFF, &ResendList);
            ValveRestartTime = HAL_GetTick();
            EventGroupSetBits(&Key_event, Event_AllHoleSwitchTrigger);
        }
    }
}