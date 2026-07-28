#include "app_ws2812.h"
#include "tim.h"
// 预设颜色RGB表
RGB_t Color_table[] =
    {
        {255, 0, 0},        // 红色
        {0, 255, 0},        // 绿色
        {0, 0, 255},        // 蓝色
        {0, 255, 255},      // 天蓝色
        {0xFF, 0x40, 0x81}, // 粉色
        {128, 216, 0},      // 黄色
        {127, 106, 0},      // 橘色
        {255, 255, 255},    // 白色
        {0, 0, 0},          // 无颜色
        {213, 0, 249},      // 紫色
};
void Light_SetAllColor(RGB_t color, uint16_t *buffer)
{
    uint8_t i, j;
    for (i = 0; i < Light_RGBbuffer_SIZE; i++)
    {
        for (j = 0; j < 8; j++)
        {
            if (color.G & 0x01 == 1)
                buffer[i * 24 + j] = code1;
            else
                buffer[i * 24 + j] = code0;
        }
        for (j = 8; j < 16; j++)
        {
            if (color.R & 0x01 == 1)
                buffer[i * 24 + j] = code1;
            else
                buffer[i * 24 + j] = code0;
        }
        for (j = 16; j < 24; j++)
        {
            if (color.B & 0x01 == 1)
                buffer[i * 24 + j] = code1;
            else
                buffer[i * 24 + j] = code0;
        }
    }
    HAL_TIM_PWM_Start_DMA(&htim3, TIM_CHANNEL_2, (uint32_t *)buffer, Light_CRRbuffer_SIZE);
}