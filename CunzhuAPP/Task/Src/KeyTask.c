#include "KeyTask.h"
#include "MesgTask.h"
#include "MainTask.h"
#include "CommTask.h"
#include "LightTask.h"
#include "port_key.h"
#include "port_event.h"

static GPIO_TypeDef *Button_GPIO[7] = {Button1_GPIO_Port, Button2_GPIO_Port, Button3_GPIO_Port, Button4_GPIO_Port, Button5_GPIO_Port, Button6_GPIO_Port, Encoder_K_GPIO_Port};
static uint16_t Button_Pin[7] = {Button1_Pin, Button2_Pin, Button3_Pin, Button4_Pin, Button5_Pin, Button6_Pin, Encoder_K_Pin};

static GPIO_TypeDef *Keyboard_GPIO[4] = {KeyBoard1_GPIO_Port, KeyBoard2_GPIO_Port, KeyBoard3_GPIO_Port, KeyBoard4_GPIO_Port};
static uint16_t Keyboard_Pin[4] = {KeyBoard1_Pin, KeyBoard2_Pin, KeyBoard3_Pin, KeyBoard4_Pin};

static Key_HandleTypeDef Button_Key[7];
static Key_HandleTypeDef *Button_List[7];

static Key_HandleTypeDef Keyboard_Key[4];
static Key_HandleTypeDef *Keyboard_List[4];

extern Event_Handle_t Mesg_event;
extern Event_Handle_t Event;

extern Tx_HandleTypeDef Tx;

/// ----------按键初始化----------
static void Button_ShortCallback(uint16_t id)
{
    Comm_SendMesg_FillData(&Tx, Ctrl_to_Board, 0x01, id + 1, 0x01);
}

static void Button_LongCallback(uint16_t id)
{
    Comm_SendMesg_FillData(&Tx, Ctrl_to_Board, 0x01, id + 1, 0x02);
}

static void Button_Init(void)
{
    Key_InitTypeDef Key_InitStruct;
    Key_InitStruct.debounce_time = KEY_DEBOUNCE_TIME;
    Key_InitStruct.longpress_time = 800;
    Key_InitStruct.trigger_frequnecy = KEY_LONG_TRIGGER_FREQUENCY;
    Key_InitStruct.short_callback = Button_ShortCallback;
    Key_InitStruct.long_callback = Button_LongCallback;
    Key_InitStruct.release_callback = NULL;
    Key_InitStruct.trigger_level = GPIO_PIN_RESET;

    for (uint8_t i = 0; i < 7; i++)
    {
        Key_InitStruct.key_id = i;
        Key_InitStruct.port = Button_GPIO[i];
        Key_InitStruct.pin = Button_Pin[i];
        Key_Init(&Button_Key[i], Key_InitStruct);
        Button_List[i] = &Button_Key[i];
    }
}
/// ----------键盘初始化----------

static void Keyboard_ShortCallback(uint16_t id)
{
    Comm_SendMesg_FillData(&Tx, Ctrl_to_Board, 0x02, id + 1, 0x01);
}

static void Keyboard_Init(void)
{
    Key_InitTypeDef Key_InitStruct;
    Key_InitStruct.debounce_time = KEY_DEBOUNCE_TIME;
    Key_InitStruct.longpress_time = 1000;
    Key_InitStruct.trigger_frequnecy = 1;
    Key_InitStruct.short_callback = Keyboard_ShortCallback;
    Key_InitStruct.long_callback = NULL;
    Key_InitStruct.release_callback = NULL;
    Key_InitStruct.trigger_level = GPIO_PIN_RESET;

    for (uint8_t i = 0; i < 4; i++)
    {
        Key_InitStruct.key_id = i;
        Key_InitStruct.port = Keyboard_GPIO[i];
        Key_InitStruct.pin = Keyboard_Pin[i];
        Key_Init(&Keyboard_Key[i], Key_InitStruct);
        Keyboard_List[i] = &Keyboard_Key[i];
    }
}
void KeyAll_Init(void)
{
    Button_Init();
    Keyboard_Init();
}

void Key_Task(void)
{
    Key_Scan(Button_List, 7);
    Key_Scan(Keyboard_List, 4);
}
