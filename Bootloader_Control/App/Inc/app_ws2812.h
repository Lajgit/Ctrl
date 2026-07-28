#ifndef __APP_WS2812_H__
#define __APP_WS2812_H__

#include "main.h"

#define Light_RGBbuffer_SIZE 159
#define Light_CRRbuffer_SIZE ((Light_RGBbuffer_SIZE + 7) * 24)

#define code1 10 // WS2812逻辑1对应占空比
#define code0 3  // WS2812逻辑0对应占空比

#define RED Color_table[0]     // 红色
#define GREEN Color_table[1]   // 绿色
#define BLUE Color_table[2]    // 深蓝色
#define SKYBLUE Color_table[3] // 天蓝色
#define PINK Color_table[4]    // 粉色
#define YELLOW Color_table[5]  // 黄色
#define ORANGE Color_table[6]  // 橘色
#define WHITE Color_table[7]   // 白色
#define NONE Color_table[8]    // 无颜色
#define PURPLE Color_table[9]  // 紫色

// 颜色结构体
typedef struct
{
    uint8_t R;
    uint8_t G;
    uint8_t B;
} RGB_t;
extern RGB_t Color_table[];
extern uint16_t RGB_buffer[Light_CRRbuffer_SIZE];

void Light_SetAllColor(RGB_t color, uint16_t *buffer);

#endif
