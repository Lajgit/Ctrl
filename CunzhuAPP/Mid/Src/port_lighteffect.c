#include "port_lighteffect.h"
#include "string.h"
#include "stdlib.h"

Light_t *ColorLightList[MAX_COLORLIGHT_NUM];
BreathLight_t *BreathLightList[MAX_BREATHLIGHT_NUM];
NormalLight_t *NormalLightList[MAX_NORMALLIGHT_NUM];
LightRegister_t LightTable = {0, 0, 0, ColorLightList, BreathLightList, NormalLightList};

// 新增：检查指针是否已在列表中，避免重复注册
static bool IsRegistered(void *list[], uint8_t num, void *item)
{
    // 空指针或数量为0直接返回未注册
    if (item == NULL || num == 0)
        return false;
    for (uint8_t i = 0; i < num; i++)
        if (list[i] == item)
            return true;
    return false;
}

/*
 * @brief 灯光注册
 * @param LightType 注册的灯光类型
 * @param light 灯光结构体地址
 */
uint8_t RegisterLight(LightType_Typedef LightType, void *light)
{
    // 空指针保护
    if (light == NULL)
        return 1;

    switch (LightType)
    {
    case ColorLight:
        // 避免重复注册与越界
        if (IsRegistered((void **)LightTable.colorlightlist, LightTable.colorlight_num, light))
            return 2;
        if (LightTable.colorlight_num >= MAX_COLORLIGHT_NUM)
            return 3;
        LightTable.colorlightlist[LightTable.colorlight_num] = (Light_t *)light;
        LightTable.colorlightlist[LightTable.colorlight_num]->Init = true;
        SemaphoreGive(LightTable.colorlightlist[LightTable.colorlight_num]->Semaphore);
        LightTable.colorlight_num++;
        return 0;
    case BreathLight:
        if (IsRegistered((void **)LightTable.breathlightlist, LightTable.breathlight_num, light))
            return 2;
        if (LightTable.breathlight_num >= MAX_BREATHLIGHT_NUM)
            return 3;
        LightTable.breathlightlist[LightTable.breathlight_num] = (BreathLight_t *)light;
        LightTable.breathlight_num++;
        return 0;
    case NormalLight:
        if (IsRegistered((void **)LightTable.normallightlist, LightTable.normallight_num, light))
            return 2;
        if (LightTable.normallight_num >= MAX_NORMALLIGHT_NUM)
            return 3;
        LightTable.normallightlist[LightTable.normallight_num] = (NormalLight_t *)light;
        LightTable.normallight_num++;
        return 0;
    default:
        // 未知类型不处理
        return 4;
    }
}

/// @brief 定时器中断服务函数，用于给注册的定时器递增计数
void LightEffectTimer_ISR(void)
{
    for (uint8_t i = 0; i < LightTable.colorlight_num; i++)
        LightTable.colorlightlist[i]->TimerCount++;
    for (uint8_t i = 0; i < LightTable.breathlight_num; i++)
        LightTable.breathlightlist[i]->TimerCount++;
    for (uint8_t i = 0; i < LightTable.normallight_num; i++)
        LightTable.normallightlist[i]->TimerCount++;
}

// 彩灯灯效非阻塞函数
static int min(uint16_t a, uint16_t b)
{
    if (a <= b)
        return a;
    else
        return b;
}
void LightEffect_Unblock_SetColor(Light_t *light, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness, bool circle)
{
    if (light->Init == true)
    {
        light->Init = false;
        // if (SemaphoreTake(light->Semaphore) == true)
        {
            // RGB_SetMoreColor(light, start, end, NONE, AbsoluteLightness, 0);
            RGB_SetMoreColor(light, start, end, color, AbsoluteLightness, RelativeLightness);
            RGB_LocalRefresh(light, start, end);
        }
    }
    if (circle == true)
        light->Init = true;
}
void LightEffect_Unblock_Blink(Light_t *light, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->FirstStepCount = 0;
    }
    if (light->TimerCount > time && light->FirstStepCount == 0)
    {
        if (SemaphoreTake(light->Semaphore) == true)
        {
            RGB_SetMoreColor(light, start, end, color, AbsoluteLightness, RelativeLightness);
            RGB_LocalRefresh(light, start, end);
            light->FirstStepCount = 1;
            light->TimerCount = 0;
        }
    }
    if (light->TimerCount > time && light->FirstStepCount == 1)
    {
        if (SemaphoreTake(light->Semaphore) == true)
        {
            RGB_SetMoreColor(light, start, end, NONE, 0, 0);
            RGB_LocalRefresh(light, start, end);
            light->FirstStepCount = 0;
            light->TimerCount = 0;
        }
    }
}

void LightEffect_Unblock_Flow(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint16_t Holdtime, uint8_t dir)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = start;
            light->Finish = false;
        }
        if (light->TimerCount > time)
        {
            if (light->FirstStepCount < end)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    RGB_SetAllColor(light, backgroundcolor, AbsoluteLightness, RelativeLightness);
                    RGB_SetMoreColor(light, start, light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                    RGB_Flush(light);
                    light->TimerCount = 0;
                    light->FirstStepCount++;
                }
            }
            if (light->FirstStepCount >= end && light->TimerCount >= Holdtime + time)
            {
                light->Finish = true;
                light->Init = true;
            }
        }
    }
    else if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = end;
            light->Finish = false;
        }
        if (light->TimerCount > time)
        {
            if (light->FirstStepCount > start)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    RGB_SetAllColor(light, backgroundcolor, AbsoluteLightness, RelativeLightness);
                    RGB_SetMoreColor(light, light->FirstStepCount, end, frontcolor, AbsoluteLightness, RelativeLightness);
                    RGB_Flush(light);
                    light->TimerCount = 0;
                    light->FirstStepCount--;
                }
                if (light->FirstStepCount <= start && light->TimerCount >= Holdtime + time)
                {
                    light->Finish = true;
                    light->Init = true;
                }
            }
        }
    }
}

void LightEffect_Unblock_AlternateFill(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t step, uint8_t dir)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
            light->SecondStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (light->SecondStepCount == 0)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, j, min(j + light->FirstStepCount, end), frontcolor, AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                    light->TimerCount = 0;
                    light->FirstStepCount++;
                }
                if (light->FirstStepCount > step && light->SecondStepCount == 0)
                {
                    light->FirstStepCount = 0;
                    light->SecondStepCount = 1;
                }
            }
            if (light->SecondStepCount == 1)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, min(j + light->FirstStepCount, end), min(j + step, end), frontcolor, AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                    light->TimerCount = 0;
                    light->FirstStepCount++;
                }
                if (light->FirstStepCount > step && light->SecondStepCount == 1)
                {
                    light->FirstStepCount = 0;
                    light->SecondStepCount = 0;
                    light->Finish = true;
                }
            }
        }
    }
    if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = step;
            light->SecondStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (light->SecondStepCount == 0)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, min(j + light->FirstStepCount, end), min(j + step, end), frontcolor, AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                    light->TimerCount = 0;
                    light->FirstStepCount--;
                }
                if (light->FirstStepCount == 0 && light->SecondStepCount == 0)
                {
                    light->FirstStepCount = step;
                    light->SecondStepCount = 1;
                }
            }
            if (light->SecondStepCount == 1)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, j, min(j + light->FirstStepCount, end), frontcolor, AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                    light->TimerCount = 0;
                    light->FirstStepCount--;
                }
                if (light->FirstStepCount == 0 && light->SecondStepCount == 1)
                {
                    light->FirstStepCount = step;
                    light->SecondStepCount = 0;
                    light->Finish = true;
                }
            }
        }
    }
}

void LightEffect_Unblock_AlternateFill_ChangeColor(Light_t *light, uint16_t start, uint16_t end, RGB_t *color, uint16_t num, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t step, uint8_t dir)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
            light->SecondStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                if (light->SecondStepCount == num - 1)
                {
                    RGB_SetMoreColor(light, start, end, color[num - 1], AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, j, min(j + light->FirstStepCount, end), color[0], AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                }
                else
                {
                    RGB_SetMoreColor(light, start, end, color[light->SecondStepCount], AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, j, min(j + light->FirstStepCount, end), color[light->SecondStepCount + 1], AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                }
                light->TimerCount = 0;
                light->FirstStepCount++;
            }
            if (light->FirstStepCount > step)
            {
                light->FirstStepCount = 0;
                if (++light->SecondStepCount == num)
                {
                    light->Finish = true;
                    light->SecondStepCount = 0;
                }
            }
        }
    }
    if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = step;
            light->SecondStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                if (light->SecondStepCount == num - 1)
                {
                    RGB_SetMoreColor(light, start, end, color[num - 1], AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, min(j + light->FirstStepCount, end), min(j + step, end), color[0], AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                }
                else
                {
                    RGB_SetMoreColor(light, start, end, color[light->SecondStepCount], AbsoluteLightness, RelativeLightness);
                    for (uint16_t j = start; j < end; j += step)
                        RGB_SetMoreColor(light, min(j + light->FirstStepCount, end), min(j + step, end), color[light->SecondStepCount + 1], AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                }
                light->TimerCount = 0;
                light->FirstStepCount--;
            }
            if (light->FirstStepCount == 0)
            {
                light->FirstStepCount = step;
                if (++light->SecondStepCount == num)
                {
                    light->Finish = true;
                    light->SecondStepCount = 0;
                }
            }
        }
    }
}

void LightEffect_Unblock_AlternatePointRun(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t step, uint8_t dir)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                for (uint16_t j = start; j + light->FirstStepCount < end + step; j += step)
                    if (j + light->FirstStepCount <= end)
                        RGB_SetOneColor(light, j + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, start, end);
                light->FirstStepCount++;
                light->TimerCount = 0;
            }
            if (light->FirstStepCount > step)
            {
                light->Finish = true;
                light->FirstStepCount = 0;
            }
        }
    }
    if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = step;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                for (uint16_t j = start; j <= end + step; j += step)
                    if (j + light->FirstStepCount <= end)
                        RGB_SetOneColor(light, j + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, start, end);
                light->FirstStepCount--;
                light->TimerCount = 0;
            }
            if (light->FirstStepCount <= 0)
            {
                light->Finish = true;
                light->FirstStepCount = step;
            }
        }
    }
}

void LightEffect_Unblock_SpreadPointRun(Light_t *light, uint16_t center, uint16_t radius, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint16_t step, uint8_t dir)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, center - radius, center + radius, backgroundcolor, AbsoluteLightness, RelativeLightness);
                for (uint16_t j = center - radius; j + light->FirstStepCount <= center; j += step)
                    RGB_SetOneColor(light, j + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                for (uint16_t j = center + radius; j - light->FirstStepCount >= center; j -= step)
                    RGB_SetOneColor(light, j - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, center - radius, center + radius);
                light->FirstStepCount++;
                light->TimerCount = 0;
            }
            if (light->FirstStepCount > step)
            {
                light->Finish = true;
                light->FirstStepCount = 0;
            }
        }
    }
    else if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, center - radius, center + radius, backgroundcolor, AbsoluteLightness, RelativeLightness);
                for (uint16_t j = center; j + light->FirstStepCount <= center + radius; j += step)
                    RGB_SetOneColor(light, j + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                for (uint16_t j = center; j - light->FirstStepCount > center - radius; j -= step)
                    RGB_SetOneColor(light, j - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, center - radius, center + radius);
                light->FirstStepCount++;
                light->TimerCount = 0;
            }
            if (light->FirstStepCount > step)
            {
                light->Finish = true;
                light->FirstStepCount = 0;
            }
        }
    }
}
void LightEffect_Unblock_SpreadPointHold(Light_t *light, uint16_t center, uint16_t radius, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = radius;
            light->SecondStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, center - radius, center - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, center + light->FirstStepCount, center + radius, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, center - light->FirstStepCount, center + light->FirstStepCount, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetOneColor(light, center - light->SecondStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetOneColor(light, center + light->SecondStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, center - radius, center + radius);
                light->SecondStepCount++;
                light->TimerCount = 0;
            }
            if (light->SecondStepCount == light->FirstStepCount)
            {
                light->SecondStepCount = 0;
                light->FirstStepCount--;
            }
            if (light->FirstStepCount == 0)
            {
                light->Init = true;
                light->Finish = true;
            }
        }
    }
    if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
            light->SecondStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, center - radius, center - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, center + light->FirstStepCount, center + radius, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, center - light->FirstStepCount, center + light->FirstStepCount, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetOneColor(light, center - light->SecondStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetOneColor(light, center + light->SecondStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, center - radius, center + radius);
                light->TimerCount = 0;
            }
            if (light->SecondStepCount == 0)
            {
                light->FirstStepCount++;
                light->SecondStepCount = light->FirstStepCount;
            }
            else
                light->SecondStepCount--;
            if (light->FirstStepCount == radius)
            {
                light->Init = true;
                light->Finish = true;
            }
        }
    }
}
void LightEffect_Unblock_SetNone(Light_t *light)
{
    if (light->Init == true)
    {
        light->Init = false;
        if (SemaphoreTake(light->Semaphore) == true)
        {
            RGB_CleanAll(light);
            RGB_Flush(light);
        }
    }
}

void LightEffect_Unblock_Breath(Light_t *light, uint16_t start, uint16_t end, RGB_t color, uint8_t AbsoluteLightness, uint16_t time, uint8_t step, uint8_t (*Function)(uint16_t), uint8_t circle)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->TimerCount = 0;
        light->FirstStepCount = 0;
    }
    if (light->TimerCount > time)
    {
        // if (SemaphoreTake(light->Semaphore) == true)
        {
            // RGB_CleanAll(light);
            // RGB_SetMoreColor(light, start, end, NONE, 0, 0);
            // RGB_SetMoreColor(light, start, end, NONE, AbsoluteLightness, 0);
            RGB_SetMoreColor(light, start, end, color, AbsoluteLightness, min(Function(light->FirstStepCount), 255));
            // RGB_Flush(light);
            RGB_LocalRefresh(light, start, end);
            light->TimerCount = 0;
            light->FirstStepCount += step;
        }
        if (light->FirstStepCount > 255 && circle == 1)
        {
            light->Finish = true;
            light->Init = true;
        }
    }
}

void LightEffect_Unblock_Breath_ChangeColor(Light_t *light, uint16_t start, uint16_t end, RGB_t *color, uint8_t color_num, uint8_t AbsoluteLightness, uint16_t time, uint16_t HoldTime)
{
    // 输入保护
    if (light == NULL || color == NULL || color_num == 0)
        return;

    // 初始化阶段
    if (light->Init == true)
    {
        light->Init = false;
        light->TimerCount = 0;
        light->FirstStepCount = 0;  // 0..255 rising -> hold -> falling
        light->SecondStepCount = 0; // color index
        light->Finish = false;
    }

    // 当计时达到或超过间隔时执行一次状态推进
    if (light->TimerCount >= time)
    {
        // 上升阶段：FirstStepCount 从 0 增加到 254，然后到 255 时进入 hold
        if (light->FirstStepCount < 255)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, color[light->SecondStepCount], AbsoluteLightness, min(Breath_LinearlyIncrease(light->FirstStepCount), 255));
                RGB_LocalRefresh(light, start, end);
                light->TimerCount = 0;
                light->FirstStepCount++; // 每次事件 +1
            }
            // 若 SemaphoreTake 失败，保留 TimerCount 以便下一次重试
            else
                return;
        }

        // 保持阶段：在 255 到 255 + HoldTime 之间停留
        if (light->FirstStepCount >= 255 && light->FirstStepCount < (uint32_t)255 + HoldTime)
        {
            // 这里只按步进计数推进保持时间（每次达到 time 增加 1）
            light->TimerCount = 0;
            light->FirstStepCount++;
            return;
        }

        // 下降阶段：从 (255 + HoldTime) 增加到 (255 + HoldTime + 255 - 1)
        if (light->FirstStepCount >= (uint32_t)255 + HoldTime && light->FirstStepCount < (uint32_t)255 + HoldTime + 255)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                uint16_t fallingIndex = light->FirstStepCount - HoldTime - 255;
                RGB_SetMoreColor(light, start, end, color[light->SecondStepCount], AbsoluteLightness, Breath_LinearlyDiminish(fallingIndex));
                RGB_LocalRefresh(light, start, end);
                light->TimerCount = 0;
                light->FirstStepCount++;
            }
            else
            {
                // 如果拿不到信号量，下次再试
                return;
            }
        }

        // 完成一个颜色的完整呼吸周期，切换到下一个颜色或标记完成
        if (light->FirstStepCount >= (uint32_t)255 + HoldTime + 255)
        {
            light->TimerCount = 0;
            light->FirstStepCount = 0;
            light->SecondStepCount++;
            if (light->SecondStepCount >= color_num)
            {
                light->Finish = true;
                light->SecondStepCount = 0;
            }
            return;
        }
    }
}

void LightEffect_Unblock_DoubleFlow(Light_t *light, uint16_t start, uint16_t end, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->TimerCount = 0;
        light->FirstStepCount = 0;
        light->SecondStepCount = 0;
    }
    if (dir == 0)
    {
        if (light->TimerCount > time && light->SecondStepCount == 0)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start, start + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start + (end - start) / 2 + 1, start + (end - start) / 2 + 1 + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, start, end);
                light->TimerCount = 0;
                light->FirstStepCount++;
            }
        }
        if (light->FirstStepCount >= (end - start) / 2 && light->SecondStepCount == 0)
        {
            light->FirstStepCount = 0;
            light->SecondStepCount = 1;
        }
        if (light->TimerCount > time && light->SecondStepCount == 1)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start + light->FirstStepCount, start + (end - start) / 2, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start + (end - start) / 2 + 1 + light->FirstStepCount, end, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, start, end);
                light->TimerCount = 0;
                light->FirstStepCount++;
            }
        }
        if (light->FirstStepCount >= (end - start) / 2 && light->SecondStepCount == 1)
        {
            light->Finish = true;
            light->Init = true;
        }
    }
    else if (dir == 1)
    {
        if (light->TimerCount > time && light->SecondStepCount == 0)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start + (end - start) / 2 - light->FirstStepCount, start + (end - start) / 2, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, end - light->FirstStepCount, end, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, start, end);
                light->TimerCount = 0;
                light->FirstStepCount++;
            }
        }
        if (light->FirstStepCount >= (end - start) / 2 && light->SecondStepCount == 0)
        {
            light->FirstStepCount = 0;
            light->SecondStepCount = 1;
        }
        if (light->TimerCount > time && light->SecondStepCount == 1)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                RGB_SetMoreColor(light, start, end, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start, start + (end - start) / 2 - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, start + (end - start) / 2 + 1, end - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, start, end);
                light->TimerCount = 0;
                light->FirstStepCount++;
            }
        }
        if (light->FirstStepCount >= (end - start) / 2 && light->SecondStepCount == 1)
        {
            light->Finish = true;
            light->Init = true;
        }
    }
}

void LightEffect_Unblock_DoubleFlow_ChangeColor(Light_t *light, uint16_t start, uint16_t end, RGB_t *color, uint8_t color_num, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint16_t HoldTime, uint8_t dir)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->TimerCount = 0;
        light->FirstStepCount = 0;
        light->SecondStepCount = 0;
    }
    if (dir == 0)
    {
        if (light->TimerCount > time)
        {
            if (light->FirstStepCount <= (end - start) / 2)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    if (light->SecondStepCount == 0)
                        RGB_SetMoreColor(light, start, end, color[color_num - 1], AbsoluteLightness, RelativeLightness);
                    else
                        RGB_SetMoreColor(light, start, end, color[light->SecondStepCount - 1], AbsoluteLightness, RelativeLightness);
                    RGB_SetMoreColor(light, start, start + light->FirstStepCount, color[light->SecondStepCount], AbsoluteLightness, RelativeLightness);
                    RGB_SetMoreColor(light, start + (end - start) / 2 + 1, start + (end - start) / 2 + 1 + light->FirstStepCount, color[light->SecondStepCount], AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                    light->TimerCount = 0;
                    light->FirstStepCount++;
                }
            }
            if (light->FirstStepCount > (end - start) / 2 && light->FirstStepCount < (end - start) / 2 + HoldTime)
            {
                light->TimerCount = 0;
                light->FirstStepCount += time;
            }
            if (light->FirstStepCount > (end - start) / 2 + HoldTime)
            {
                light->TimerCount = 0;
                light->FirstStepCount = 0;
                light->SecondStepCount++;
                if (light->SecondStepCount >= color_num)
                {
                    light->Finish = true;
                    light->SecondStepCount = 0;
                }
            }
        }
    }
    if (dir == 1)
    {
        if (light->TimerCount > time)
        {
            if (light->FirstStepCount <= (end - start) / 2)
            {
                if (SemaphoreTake(light->Semaphore) == true)
                {
                    if (light->SecondStepCount == 0)
                        RGB_SetMoreColor(light, start, end, color[color_num - 1], AbsoluteLightness, RelativeLightness);
                    else
                        RGB_SetMoreColor(light, start, end, color[light->SecondStepCount - 1], AbsoluteLightness, RelativeLightness);
                    RGB_SetMoreColor(light, start + (end - start) / 2 - light->FirstStepCount, start + (end - start) / 2, color[light->SecondStepCount], AbsoluteLightness, RelativeLightness);
                    RGB_SetMoreColor(light, end - light->FirstStepCount, end, color[light->SecondStepCount], AbsoluteLightness, RelativeLightness);
                    RGB_LocalRefresh(light, start, end);
                    light->TimerCount = 0;
                    light->FirstStepCount++;
                }
            }
            if (light->FirstStepCount > (end - start) / 2 && light->FirstStepCount < (end - start) / 2 + HoldTime)
            {
                light->TimerCount = 0;
                light->FirstStepCount += time;
            }
            if (light->FirstStepCount > (end - start) / 2 + HoldTime)
            {
                light->TimerCount = 0;
                light->FirstStepCount = 0;
                light->SecondStepCount++;
                if (light->SecondStepCount >= color_num)
                {
                    light->Finish = true;
                    light->SecondStepCount = 0;
                }
            }
        }
    }
}

void LightEffect_Unblock_Spread(Light_t *light, uint16_t center, uint16_t radius, RGB_t backgroundcolor, RGB_t frontcolor, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir, uint8_t circle)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                light->TimerCount = 0;
                RGB_SetMoreColor(light, center - radius, center + radius, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, center - light->FirstStepCount, center + light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, center - radius, center + radius);
                light->FirstStepCount++;
            }
            if (light->FirstStepCount >= radius)
            {
                light->Finish = true;
                if (circle == 1)
                    light->Init = true;
                else
                {
                    light->TimerCount = 0;
                    light->FirstStepCount = radius;
                }
            }
        }
    }
    if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = 0;
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true)
            {
                light->TimerCount = 0;
                RGB_SetMoreColor(light, center - radius, center + radius, backgroundcolor, AbsoluteLightness, RelativeLightness);
                RGB_SetMoreColor(light, center - radius + light->FirstStepCount, center + radius - light->FirstStepCount, frontcolor, AbsoluteLightness, RelativeLightness);
                RGB_LocalRefresh(light, center - radius, center + radius);
                light->FirstStepCount++;
            }
            if (light->FirstStepCount >= radius)
            {
                light->Finish = true;
                if (circle == 1)
                    light->Init = true;
                else
                {
                    light->TimerCount = 0;
                    light->FirstStepCount = radius;
                }
            }
        }
    }
}

void LightEffect_Unblock_SetDifferentColor(Light_t *light, uint16_t *area, RGB_t *color, uint16_t num, uint8_t AbsoluteLightness, uint8_t RelativeLightness)
{
    if (light->Init == true)
    {
        light->Init = false;
        if (SemaphoreTake(light->Semaphore) == true)
        {
            for (uint16_t i = 0; i < num; i++)
                RGB_SetMoreColor(light, area[i], area[i + 1], color[i], AbsoluteLightness, RelativeLightness);
            RGB_LocalRefresh(light, area[0], area[num]);
        }
    }
}

void LightEffect_Unblock_DifferentFlow(Light_t *light, uint16_t *area, RGB_t *color, uint16_t num, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time, uint8_t dir, uint8_t circle)
{
    if (dir == 0)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = area[0];
            RGB_SetMoreColor(light, area[0], area[num], NONE, 0, 0);
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true && light->FirstStepCount <= area[num])
            {
                light->TimerCount = 0;
                uint16_t i = 0;
                do
                {
                    RGB_SetMoreColor(light, area[i], min(light->FirstStepCount, area[i + 1]), color[i], AbsoluteLightness, RelativeLightness);
                    i++;
                } while (area[i] <= light->FirstStepCount);
                RGB_LocalRefresh(light, area[0], area[num]);
                light->FirstStepCount++;
            }
            if (light->FirstStepCount > area[num] && circle == 1)
            {
                light->Finish = true;
                light->Init = true;
            }
        }
    }
    if (dir == 1)
    {
        if (light->Init == true)
        {
            light->Init = false;
            light->TimerCount = 0;
            light->FirstStepCount = area[num];
            RGB_SetMoreColor(light, area[0], area[num], NONE, 0, 0);
        }
        if (light->TimerCount > time)
        {
            if (SemaphoreTake(light->Semaphore) == true && light->FirstStepCount > area[0])
            {
                light->TimerCount = 0;
                uint16_t i = num - 1;
                do
                {
                    RGB_SetMoreColor(light, light->FirstStepCount, area[i + 1], color[i], AbsoluteLightness, RelativeLightness);
                    if (i > 0)
                        i--;
                    else
                        break;
                } while (light->FirstStepCount < area[i + 1]);
                RGB_LocalRefresh(light, area[0], area[num]);
                light->FirstStepCount--;
            }
            if (light->FirstStepCount <= area[0] && circle == 1)
            {
                light->Finish = true;
                light->Init = true;
            }
        }
    }
}

void LightEffect_Unblock_SetRand(Light_t *light, uint16_t start, uint16_t end, uint8_t AbsoluteLightness, uint8_t RelativeLightness, uint16_t time)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->TimerCount = time;
    }
    if (light->TimerCount > time)
    {
        if (SemaphoreTake(light->Semaphore) == true)
        {
            for (uint16_t i = start; i <= end; i++)
                RGB_SetOneColor(light, i, RGB_Color(rand() % 256, rand() % 256, rand() % 256), AbsoluteLightness, RelativeLightness);
            RGB_LocalRefresh(light, start, end);
            light->TimerCount = 0;
        }
    }
}

void LightEffect_Unblock_InitList(Light_t *lightlist[], uint8_t num)
{
    for (uint8_t i = 0; i < num; i++)
        lightlist[i]->Init = true;
}
/*--------------- NormalLight_LightEffect --------------*/
uint8_t Breath_LinearlyIncrease(uint16_t x) // 线性增加
{
    int y;
    if (x > 255)
        x = 255;
    y = x;
    return (uint8_t)y;
}

uint8_t Breath_LinearlyDiminish(uint16_t x) // 线性减少
{
    int y;
    if (x > 255)
        x = 255;
    y = 255 - x;
    return (uint8_t)y;
}

uint8_t Breath_QuadraticIncrease(uint16_t x) // 二次函数下凹
{
    int y;
    if (x >= 255)
        x = 255;
    y = (2 / 127) * (x - 127) * (x - 127);
    return (uint8_t)y;
}

uint8_t Breath_QuadraticDiminish(uint16_t x) // 二次函数上凸
{
    int y;
    if (x >= 255)
        x = 255;
    y = 254 - (2 / 127) * (x - 127) * (x - 127);
    return (uint8_t)y;
}

uint8_t Breath_LinearlyEpirelief(uint16_t x) // 线性上凸
{
    int y;
    if (x <= 127)
        y = 2 * x;
    if (x > 127 && x <= 254)
        y = 508 - 2 * x;
    else if (x > 254)
        y = 0;
    return (uint8_t)y;
}

uint8_t Breath_LinearlyFovea(uint16_t x) // 线性下凹
{
    int y;
    if (x <= 127)
        y = 254 - 2 * x;
    if (x > 127 && x <= 254)
        y = 2 * x - 254;
    else if (x > 254)
        y = 254;
    return (uint8_t)y;
}

void BreathLight_SetLightEffect(BreathLight_t *Light, uint16_t Time, uint16_t Step, uint8_t AbsoluteLightness, uint16_t DelayTime, uint16_t HoldonTime, uint8_t (*Function)(uint16_t), uint8_t Circle)
{
    if (Light->Init == true)
    {
        Light->Init = false;
        Light->TimerCount = 0;
        Light->StepCount = 0;
        // Light->CCR = 0;
        //__HAL_TIM_SetCompare(Light->htim, Light->Channel, Light->CCR);
    }
    if (Light->TimerCount >= Time + DelayTime) // 若计时时间超过间隔时间
    {
        if (Light->StepCount <= 255)
        {
            Light->TimerCount = DelayTime;
            Light->StepCount += Step; // 步进一步
            Light->CCR = min(Function(Light->StepCount) * Light->ReloadCounter / 255, Light->ReloadCounter);
            __HAL_TIM_SetCompare(Light->htim, Light->Channel, Light->CCR * AbsoluteLightness / 10);
        }
        if (Light->StepCount > 255 && Light->TimerCount >= Time + DelayTime + HoldonTime) // 若步进数超过100且超出保持时间
        {
            if (Circle == 0) // 若不是循环则停止步进
            {
                Light->StepCount = 255;
                Light->TimerCount = Time + DelayTime + HoldonTime;
                return;
            }
            else
            {
                Light->StepCount = 0;
                Light->TimerCount = DelayTime;
            }
        }
    }
}

void BreathLight_SetLightKeep(BreathLight_t *Light, uint16_t DelayTime, uint8_t AbsoluteLightness, uint8_t RelativeLightness)
{
    if (Light->Init == true)
    {
        Light->TimerCount = 0;
    }
    if (Light->TimerCount >= DelayTime && Light->Init == true)
    {
        Light->CCR = RelativeLightness * Light->ReloadCounter / 255;
        __HAL_TIM_SetCompare(Light->htim, Light->Channel, Light->CCR * AbsoluteLightness / 10);
        Light->Init = false;
    }
}

void BreathLight_SetBlink(BreathLight_t *light, uint16_t time, uint8_t AbsoluteLightness, uint16_t minLightness, uint16_t maxLightness, uint16_t DelayTime)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->TimerCount = 0;
        light->StepCount = 0;
        light->CCR = 0;
    }
    if (light->TimerCount > DelayTime + time)
    {
        light->TimerCount = DelayTime;
        if (light->StepCount == 0)
        {
            light->StepCount = 1;
            light->CCR = minLightness * light->ReloadCounter / 255;
            __HAL_TIM_SetCompare(light->htim, light->Channel, light->CCR * AbsoluteLightness / 10);
        }
        else if (light->StepCount == 1)
        {
            light->StepCount = 0;
            light->CCR = maxLightness * light->ReloadCounter / 255;
            __HAL_TIM_SetCompare(light->htim, light->Channel, light->CCR * AbsoluteLightness / 10);
        }
    }
}

void BreathLight_Init(BreathLight_t *light, TIM_HandleTypeDef *htim, uint32_t Channel, GPIO_TypeDef *GPIOPort, uint16_t GPIO_Pin)
{
    light->htim = htim;
    light->Channel = Channel;
    light->ReloadCounter = htim->Init.Period;
    light->CCR = 0;
    light->GPIOPort = GPIOPort;
    light->GPIO_Pin = GPIO_Pin;
    light->TimerCount = 0;
    light->StepCount = 0;
    light->Init = true;
    HAL_TIM_PWM_Start(light->htim, light->Channel);
}

void BreathLight_RefreshState(BreathLight_t *lihgtlist[], uint8_t num)
{
    for (uint8_t i = 0; i < num; i++)
        lihgtlist[i]->Init = true;
}

/*---------- NormalLight_LightEffect ----------*/
void NormalLight_SetLight(NormalLight_t *light, GPIO_PinState state, uint16_t delaytime)
{
    if (light->Init == true)
    {
        light->Init = false;
        light->Configured = false;
        light->TimerCount = 0;
    }
    if (light->TimerCount >= delaytime && light->Configured == false)
    {
        HAL_GPIO_WritePin(light->GPIOPort, light->GPIO_Pin, state);
        light->Configured = true;
    }
}

void NormalLight_SetBlink(NormalLight_t *light, uint16_t time, uint16_t delaytime)
{
    if (light->Init == true)
    {
        light->TimerCount = 0;
        light->Init = false;
        HAL_GPIO_WritePin(light->GPIOPort, light->GPIO_Pin, GPIO_PIN_RESET);
    }
    if (light->TimerCount > delaytime + time)
    {
        light->TimerCount = delaytime;
        HAL_GPIO_TogglePin(light->GPIOPort, light->GPIO_Pin);
    }
}

void NormalLight_Init(NormalLight_t *light, GPIO_TypeDef *GPIOPort, uint16_t GPIO_Pin)
{
    light->GPIOPort = GPIOPort;
    light->GPIO_Pin = GPIO_Pin;
    light->Init = true;
    light->Configured = false;
}
void NormalLight_RefreshState(NormalLight_t *lihgtlist[], uint8_t num)
{
    for (uint8_t i = 0; i < num; i++)
    {
        lihgtlist[i]->Init = true;
        lihgtlist[i]->Configured = false;
    }
}