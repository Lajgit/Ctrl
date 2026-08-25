#ifndef __APP_WS2812_H__
#define __APP_WS2812_H__

#include "main.h"
#include "stdbool.h"


#if defined(STM32F405xx) || defined(STM32F415xx) || defined(STM32F407xx) || defined(STM32F417xx) || defined(STM32F412Zx) || \
    defined(STM32F412Vx) || defined(STM32F412Rx) || defined(STM32F412Cx)

#define code1 10 // WS2812逻辑1对应占空比
#define code0 3  // WS2812逻辑0对应占空比

#endif

#if defined(STM32F103xB) || defined(STM32F103xE) || defined(STM32F103xG)
#define code1 50 // WS2812逻辑1对应占空比
#define code0 23 // WS2812逻辑0对应占空比

#endif

#define RED Color_table[0]     // 红色
#define GREEN Color_table[1]   // 绿色
#define BLUE Color_table[2]    // 深蓝色
#define SKYBLUE Color_table[3] // 天蓝色
#define PINK Color_table[4]    // 粉色
#define YELLOW Color_table[5]  // 黄色
#define ORANGE Color_table[6]  // 橘色
#define WHITE Color_table[7]   // 白色
#define PURPLE Color_table[8]  // 紫色
#define NONE Color_table[9]    // 无颜色

typedef enum
{
    RGB = 1,
    RBG = 2,
    GRB = 3,
    BRG = 4,
} RGB_Order;

// 颜色结构体
typedef struct
{
    uint8_t R;
    uint8_t G;
    uint8_t B;
} RGB_t;

typedef struct
{
    uint16_t num;
} Semaphore_t;

// 灯光结构体，用于将CRR缓冲区的数据分配至不同灯光区域
typedef struct
{
    TIM_HandleTypeDef *htim;  // 定时器句柄
    uint32_t Channel;         // 定时器通道
    uint16_t LED_NUM;         // LED数量
    RGB_t *RGB_buffer;        // RGB缓冲区
    uint8_t *CRR_buffer;     // CRR缓冲区
    uint16_t TimerCount;      // 计数器
    uint16_t FirstStepCount;  // 一级步进计数
    uint16_t SecondStepCount; // 二级步进计数
    uint16_t ThirdStepCount;  // 三级步进计数
    Semaphore_t *Semaphore;   // 信号量
    bool Init;                // 初始化标志
    bool Finish;              // 完成标志
    RGB_Order Order;          // RGB顺序
} Light_t;

extern uint8_t LightnessLevel;
extern RGB_t Color_table[];

void RGB_Init(Light_t *light, TIM_HandleTypeDef *htim, uint32_t Channel, uint16_t LED_NUM, RGB_t *RGB_Buffer, uint8_t *CRR_Buffer, Semaphore_t *Semaphore, RGB_Order Order);
RGB_t RGB_Color(uint8_t R, uint8_t G, uint8_t B);
void RGB_SetOneColor(Light_t *light, uint16_t LED_ID, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness);
void RGB_SetMoreColor(Light_t *lihgt, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness);
void RGB_SetAllColor(Light_t *light, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness);

void RGB_Flush(Light_t *light);
void RGB_LocalRefresh(Light_t *light, uint16_t start, uint16_t end);

void RGB_Flush(Light_t *light);
void RGB_LocalRefresh(Light_t *light, uint16_t start, uint16_t end);
void RGB_CleanAll(Light_t *light);

bool SemaphoreTake(Semaphore_t *Semaphore);
bool SemaphoreGive(Semaphore_t *Semaphore);
void RGB_FinishCallback(Light_t *light, DMA_HandleTypeDef *hdma);
#endif
