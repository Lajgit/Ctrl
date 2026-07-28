#ifndef __PORT_LIGHTEFFECT_H__
#define __PORT_LIGHTEFFECT_H__

#include "main.h"
#include "app_ws2812.h"
#include "stdbool.h"

#define MAX_COLORLIGHT_NUM 12
#define MAX_BREATHLIGHT_NUM 12
#define MAX_NORMALLIGHT_NUM 12

typedef enum
{
    ColorLight = 0,
    BreathLight = 1,
    NormalLight = 2
} LightType_Typedef;

typedef struct
{
    TIM_HandleTypeDef *htim; // 定时器句柄
    uint32_t Channel;        // 定时器通道
    uint16_t ReloadCounter;  // 定时器重装计数值
    uint16_t CCR;            // 定时器CCR值
    GPIO_TypeDef *GPIOPort;  // GPIO端口
    uint16_t GPIO_Pin;       // GPIO引脚
    uint16_t TimerCount;     // 定时器计数值
    uint16_t StepCount;      // 步进数
    bool Init;               // 初始化状态
} BreathLight_t;

typedef struct
{
    GPIO_TypeDef *GPIOPort; // GPIO端口
    uint16_t GPIO_Pin;      // GPIO引脚
    uint16_t TimerCount;    // 定时器计数值
    bool Init;              // 初始化状态
    bool Configured;        // 配置状态
} NormalLight_t;

typedef struct
{
    uint8_t colorlight_num;          // 已注册彩灯数量
    uint8_t breathlight_num;         // 已注册呼吸灯数量
    uint8_t normallight_num;         // 已注册普通灯数量
    Light_t **colorlightlist;        // 已注册彩灯列表
    BreathLight_t **breathlightlist; // 已注册呼吸灯列表
    NormalLight_t **normallightlist; // 已注册普通灯列表
} LightRegister_t;

uint8_t RegisterLight(LightType_Typedef LightType, void *light);
void LightEffectTimer_ISR(void);

void LightEffect_Unblock_SetColor(Light_t *light, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness, bool circle);
void LightEffect_Unblock_SetNone(Light_t *light);
void LightEffect_Unblock_Blink(Light_t *light, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time);
void LightEffect_Unblock_Flow(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint16_t Holdtime, uint8_t dir);
void LightEffect_Unblock_AlternateFill(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t step, uint8_t dir);
void LightEffect_Unblock_AlternateFill_ChangeColor(Light_t *light, uint16_t start, uint16_t end, RGB_t *color, uint16_t num, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t step, uint8_t dir);
void LightEffect_Unblock_AlternatePointRun(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t step, uint8_t dir);
void LightEffect_Unblock_SpreadPointRun(Light_t *light, uint16_t center, uint16_t radius, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint16_t step, uint8_t dir);
void LightEffect_Unblock_SpreadPointHold(Light_t *light, uint16_t center, uint16_t radius, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir);
void LightEffect_Unblock_Breath(Light_t *light, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint16_t time, uint8_t step, uint8_t (*Function)(uint16_t), uint8_t circle);
void LightEffect_Unblock_Breath_ChangeColor(Light_t *light, uint16_t start, uint16_t end, RGB_t *color, uint8_t color_num, uint8_t AbsoluteLightness, uint16_t time, uint16_t HoldTime);
void LightEffect_Unblock_DoubleFlow(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir);
void LightEffect_Unblock_DoubleFlow_ChangeColor(Light_t *light, uint16_t start, uint16_t end, RGB_t *color, uint8_t color_num, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint16_t HoldTime, uint8_t dir);
void LightEffect_Unblock_SetRand(Light_t *light, uint16_t start, uint16_t end, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time);
void LightEffect_Unblock_InitList(Light_t *lightlist[], uint8_t num);
void LightEffect_Unblock_Spread(Light_t *light, uint16_t center, uint16_t radius, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir, uint8_t circle);
void LightEffect_Unblock_SetDifferentColor(Light_t *light, uint16_t *area, RGB_t *color, uint16_t num, uint8_t AbsoluteLightness, uint8_t RelativeLightness);
void LightEffect_Unblock_DifferentFlow(Light_t *light, uint16_t *area, RGB_t *color, uint16_t num, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir, uint8_t circle);

// 呼吸曲线
uint8_t Breath_LinearlyIncrease(uint16_t x);  // 线性增加
uint8_t Breath_LinearlyDiminish(uint16_t x);  // 线性减少
uint8_t Breath_LinearlyEpirelief(uint16_t x); // 线性上凸;
uint8_t Breath_LinearlyFovea(uint16_t x);     // 线性下凹
uint8_t Breath_QuadraticIncrease(uint16_t x); // 二次函数下凹
uint8_t Breath_QuadraticDiminish(uint16_t x); // 二次函数上凸
// 呼吸灯控制
void BreathLight_SetLightEffect(BreathLight_t *Light, uint16_t Time, uint16_t Step, uint8_t AbsoluteLightness, uint16_t DelayTime, uint16_t HoldonTime, uint8_t (*Function)(uint16_t), uint8_t Circle);
void BreathLight_SetLightKeep(BreathLight_t *Light, uint16_t DelayTime, uint8_t AbsoluteLightness, uint8_t RelativeLightness);
void BreathLight_SetBlink(BreathLight_t *light, uint16_t time, uint8_t AbsoluteLightness, uint16_t minLightness, uint16_t maxLightness, uint16_t DelayTime);
void BreathLight_RefreshState(BreathLight_t *lightlist[], uint8_t num);
void BreathLight_Init(BreathLight_t *light, TIM_HandleTypeDef *htim, uint32_t Channel, GPIO_TypeDef *GPIOPort, uint16_t GPIO_Pin);
// 普通灯控制
void NormalLight_SetLight(NormalLight_t *light, GPIO_PinState state, uint16_t delaytime); // 设置灯状态
void NormalLight_SetBlink(NormalLight_t *light, uint16_t time, uint16_t delaytime);       // 设置灯闪烁
void NormalLight_RefreshState(NormalLight_t *lightlist[], uint8_t num);
void NormalLight_Init(NormalLight_t *light, GPIO_TypeDef *GPIOPort, uint16_t GPIO_Pin);
#endif