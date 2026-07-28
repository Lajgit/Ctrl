#include "app_ws2812.h"
#include "tim.h"
#include "dma.h"
#include <string.h>
#include <stdlib.h>

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
/**
 * @brief  将没有RGB信息的CRR缓冲区部分设置为0，用于将灯珠复位
 * @param  led_num 最大灯珠数量
 * @retval none
 */
static void RGB_Reset(Light_t *light)
{
    for (uint16_t i = light->LED_NUM * 24; i < (light->LED_NUM + 7) * 24; i++)
    {
        light->CRR_buffer[i] = 0;
    }
}

void RGB_Init(Light_t *light, TIM_HandleTypeDef *htim, uint32_t Channel, uint16_t LED_NUM, RGB_t *RGB_Buffer, uint16_t *CRR_Buffer, Semaphore_t *Semaphore, RGB_Order Order)
{
    light->htim = htim;
    light->Channel = Channel;
    light->LED_NUM = LED_NUM;
    light->RGB_buffer = RGB_Buffer;
    light->CRR_buffer = CRR_Buffer;
    light->TimerCount = 0;
    light->FirstStepCount = 0;
    light->SecondStepCount = 0;
    light->ThirdStepCount = 0;
    light->Semaphore = Semaphore;
    light->Init = true;
    light->Finish = false;
    light->Order = Order;
    SemaphoreGive(light->Semaphore);
    RGB_Reset(light);
}

/**
 * @brief  BRG刷新
 * @param  light 灯光结构体
 * @retval none
 */
void RGB_Flush(Light_t *light)
{
    uint16_t i, j;
    // RGB_Reset(light);
    if (light->Order == RGB)
    {
        for (i = 0; i < light->LED_NUM; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].R >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].G >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].B >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    else if (light->Order == RBG)
    {
        for (i = 0; i < light->LED_NUM; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].R >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].B >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].G >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    else if (light->Order == BRG)
    {
        for (i = 0; i < light->LED_NUM; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].B >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].R >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].G >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    else if (light->Order == GRB)
    {
        for (i = 0; i < light->LED_NUM; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].G >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].R >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].B >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    HAL_TIM_PWM_Start_DMA(light->htim, light->Channel, (uint32_t *)light->CRR_buffer, (light->LED_NUM + 7) * 24);
}

void RGB_LocalRefresh(Light_t *light, uint16_t start, uint16_t end)
{
    uint16_t i, j;
    RGB_Reset(light);
    if (light->Order == RGB)
    {
        for (i = start; i <= end; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].R >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].G >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].B >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    else if (light->Order == RBG)
    {
        for (i = start; i <= end; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].R >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].B >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].G >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    else if (light->Order == BRG)
    {
        for (i = start; i <= end; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].B >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].R >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].G >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    else if (light->Order == GRB)
    {
        for (i = start; i <= end; i++)
        {
            for (j = 0; j < 8; j++)
            {
                if ((light->RGB_buffer[i].G >> (7 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 8; j < 16; j++)
            {
                if ((light->RGB_buffer[i].R >> (15 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
            for (j = 16; j < 24; j++)
            {
                if ((light->RGB_buffer[i].B >> (23 - j)) & 0x01 == 1)
                    light->CRR_buffer[i * 24 + j] = code1;
                else
                    light->CRR_buffer[i * 24 + j] = code0;
            }
        }
    }
    HAL_TIM_PWM_Start_DMA(light->htim, light->Channel, (uint32_t *)light->CRR_buffer, (light->LED_NUM + 7) * 24);
}
/**
 * @brief  将RGB颜色转换为RGB结构体
 * @param  R 红色通道数值0-255
 * @param  G 绿色通道数值0-255
 * @param  B 蓝色通道数值0-255
 * @retval RGB颜色结构体
 */
RGB_t RGB_Color(uint8_t R, uint8_t G, uint8_t B)
{
    RGB_t color;
    color.R = R;
    color.G = G;
    color.B = B;
    return color;
}

/**
 * @brief  设置单个灯珠的RGB颜色
 * @param  LED_ID:灯珠序号id
 * @param  color:RGB颜色结构体
 * @param  lightness:亮度0~255
 * @retval none
 */
void RGB_SetOneColor(Light_t *light, uint16_t LED_ID, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness)
{
    if (LED_ID >= light->LED_NUM)
        return;
    light->RGB_buffer[LED_ID].R = color.R * RelativeLightness * AbsoluteLightness / 2550;
    light->RGB_buffer[LED_ID].G = color.G * RelativeLightness * AbsoluteLightness / 2550;
    light->RGB_buffer[LED_ID].B = color.B * RelativeLightness * AbsoluteLightness / 2550;
}

/**
 * @brief  设置指定范围的灯珠颜色
 * @param  start:起始位置
 * @param  end:结束位置
 * @param  color:RGB颜色结构体
 * @param  lightness:亮度0~255
 * @retval none
 */
void RGB_SetMoreColor(Light_t *lihgt, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness)
{
    for (uint16_t i = start; i <= end; i++)
    {
        RGB_SetOneColor(lihgt, i, color, AbsoluteLightness, RelativeLightness);
    }
}

/**
 * @brief  设置指定灯光区域内的所有灯珠颜色
 * @param  color:颜色结构体
 * @param  lightness:亮度0~255
 * @retval none
 */
void RGB_SetAllColor(Light_t *light, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness)
{
    RGB_SetMoreColor(light, 0, light->LED_NUM, color, AbsoluteLightness, RelativeLightness);
}
void RGB_CleanAll(Light_t *light)
{
    RGB_SetAllColor(light, NONE, 0, 0);
    memset(light->CRR_buffer, 0, (light->LED_NUM + 7) * 24);
}

bool SemaphoreTake(Semaphore_t *Semaphore)
{
    if (Semaphore->num == 0)
        return false;
    else
        Semaphore->num = 0;
    return true;
}

bool SemaphoreGive(Semaphore_t *Semaphore)
{
    if (Semaphore->num == 1)
    {
        return false;
    }
    else
    {
        Semaphore->num = 1;
        return true;
    }
}

void RGB_FinishCallback(Light_t *light, DMA_HandleTypeDef *hdma)
{
    if (__HAL_DMA_GET_IT_SOURCE(hdma, DMA_IT_TC) == 0)
    {
        HAL_TIM_PWM_Stop_DMA(light->htim, light->Channel);
        SemaphoreGive(light->Semaphore);
    }
}