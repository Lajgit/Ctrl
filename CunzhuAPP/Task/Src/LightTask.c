#include "LightTask.h"
#include "MainTask.h"
#include "FlashTask.h"
#include "app_list.h"
#include "CommTask.h"
#include "tim.h"
#include "stdlib.h"

static RGB_t Light1_RGBbuffer[Light1_RGBbuffer_SIZE];
static uint8_t Light1_CRRbuffer[Light1_CRRbuffer_SIZE];
static RGB_t Light2_RGBbuffer[Light2_RGBbuffer_SIZE];
static uint8_t Light2_CRRbuffer[Light2_CRRbuffer_SIZE];

uint8_t LightBelt_Lightness = 5;
uint8_t LightBoard_Lightness = 5;
Semaphore_t Light1_Semaphore, Light2_Semaphore = {1};
Light_t Light1, Light2;
BreathLight_t J1, J2;
BreathLight_t *BreathList[] = {&J1, &J2};

extern Tx_HandleTypeDef Tx1;
extern Scene_t Scene;

void Light_Init(void)
{
    RGB_Init(&Light1, &htim3, TIM_CHANNEL_1, Light1_RGBbuffer_SIZE, Light1_RGBbuffer, Light1_CRRbuffer, &Light1_Semaphore, RGB);
    RGB_Init(&Light2, &htim3, TIM_CHANNEL_4, Light2_RGBbuffer_SIZE, Light2_RGBbuffer, Light2_CRRbuffer, &Light2_Semaphore, RGB);
    BreathLight_Init(&J1, &htim1, TIM_CHANNEL_1, GPIOA, GPIO_PIN_8);
    BreathLight_Init(&J2, &htim1, TIM_CHANNEL_2, GPIOA, GPIO_PIN_9);
    RegisterLight(ColorLight, &Light1);
    RegisterLight(ColorLight, &Light2);
    RegisterLight(BreathLight, &J1);
    RegisterLight(BreathLight, &J2);

    RGB_SetAllColor(&Light1, NONE, 10, 255);
    RGB_SetAllColor(&Light2, NONE, 10, 255);
    RGB_Flush(&Light1);
    RGB_Flush(&Light2);
}

static void LesslightSceneLight(void)
{
    static uint8_t i, j, dir = 0;
    static uint32_t time;
    if (HAL_GetTick() - time > 10)
    {
        RGB_SetMoreColor(&Light1, 0, 17, Color_table[j], 0, 0);
        RGB_SetMoreColor(&Light1, 18, Light1.LED_NUM, Color_table[j], LightBoard_Lightness, i);
        RGB_SetAllColor(&Light2, Color_table[j], LightBoard_Lightness, i);
        RGB_Flush(&Light1);
        RGB_Flush(&Light2);
        time = HAL_GetTick();
        if (dir == 0)
            i++;
        else
            i--;
    }
    if (i >= 255)
        dir = 1;
    else if (i <= 0)
    {
        dir = 0;
        j++;
        if (j >= 9)
            j = 0;
    }

    // LightEffect_Unblock_Breath_ChangeColor(&Light1, 18, Light1.LED_NUM, Color_table, 9, LightBoard_Lightness, 5, 1000);
    // LightEffect_Unblock_Breath_ChangeColor(&Light2, 0, Light2.LED_NUM, Color_table, 9, LightBoard_Lightness, 5, 1000);
    for (uint8_t i = 0; i < 2; i++)
        BreathLight_SetLightKeep(BreathList[i], 0, LightBelt_Lightness, 255);
}

static void IdleSceneLight(void)
{
    LightEffect_Unblock_Breath_ChangeColor(&Light1, 0, Light1.LED_NUM, Color_table, 9, LightBoard_Lightness, 5, 1000);
    LightEffect_Unblock_Breath_ChangeColor(&Light2, 0, Light2.LED_NUM, Color_table, 9, LightBoard_Lightness, 5, 1000);
    for (uint8_t i = 0; i < 2; i++)
        BreathLight_SetLightKeep(BreathList[i], 0, LightBelt_Lightness, 255);
}

static void PlayingSceneLight(void)
{
}

static void VictorySceneLight(void)
{
}

void Light_Task(void)
{
    if (Scene == SCENE_LESSLIGHT)
        LesslightSceneLight();
    else if (Scene == SCENE_IDLE)
        IdleSceneLight();
    else if (Scene == SCENE_PLAYING)
        PlayingSceneLight();
    else if (Scene == SCENE_VICTORY)
        VictorySceneLight();
}