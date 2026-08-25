#include "port_key.h"

static inline uint32_t Key_Frequency2Cycle(uint32_t frequency)
{
    if (frequency == 0)
        return UINT32_MAX; // 防止除零错误
    return 1000 / frequency;
}

static inline uint32_t Get_Systime(void)
{
    return HAL_GetTick();
}
void Key_Init(Key_HandleTypeDef *key, Key_InitTypeDef KeyInit)
{
    if (key == NULL)
        return; // 检查指针有效性

    key->key_id = KeyInit.key_id;
    key->port = KeyInit.port;
    key->pin = KeyInit.pin;
    key->trigger_level = KeyInit.trigger_level;
    key->last_state = !key->trigger_level;
    key->current_state = key->last_state;
    key->debounce_time = KeyInit.debounce_time;
    key->long_press_time = KeyInit.longpress_time;
    key->press_tick = Get_Systime();
    key->release_tick = Get_Systime();
    key->hold_time = 0;
    key->key_state = KEY_STATE_IDLE;
    key->trigger_count = Get_Systime();
    key->trigger_frequnecy = KeyInit.trigger_frequnecy;
    key->short_callback = KeyInit.short_callback;
    key->long_callback = KeyInit.long_callback;
    key->release_callback = KeyInit.release_callback;
}

void Key_Scan(Key_HandleTypeDef **key, uint16_t key_num)
{
    if (key == NULL)
        return; // 检查指针有效性

    for (uint16_t i = 0; i < key_num; i++)
    {
        if (key[i] == NULL)
            continue; // 检查单个按键指针有效性

        key[i]->current_state = HAL_GPIO_ReadPin(key[i]->port, key[i]->pin);
        uint32_t current_tick = Get_Systime(); // 减少多次调用

        // 按键按下
        if (key[i]->current_state == key[i]->trigger_level && key[i]->last_state != key[i]->trigger_level)
        {
            key[i]->press_tick = current_tick;
        }
        // 按键弹起
        else if (key[i]->current_state != key[i]->trigger_level && key[i]->last_state == key[i]->trigger_level)
        {
            key[i]->release_tick = current_tick;
            // 溢出安全的时间差计算
            if (key[i]->release_tick >= key[i]->press_tick)
            {
                key[i]->hold_time = key[i]->release_tick - key[i]->press_tick;
            }
            else
            {
                // 处理 32 位 tick 溢出情况
                key[i]->hold_time = (UINT32_MAX - key[i]->press_tick) + key[i]->release_tick + 1;
            }
            if (key[i]->hold_time >= key[i]->debounce_time)
            {
                if (key[i]->key_state == KEY_STATE_IDLE)
                {
                    key[i]->key_state = KEY_STATE_SHORT_PRESS;
                    if (key[i]->short_callback)
                        key[i]->short_callback(key[i]->key_id);

                    // 立即清理/重置，避免旧的 press_tick 导致下次误判
                    key[i]->key_state = KEY_STATE_IDLE;
                    key[i]->press_tick = key[i]->release_tick;
                    key[i]->trigger_count = key[i]->release_tick;
                }
                else if (key[i]->key_state == KEY_STATE_LONG_PRESS)
                {
                    // 长按释放
                    key[i]->key_state = KEY_STATE_IDLE;
                    if (key[i]->release_callback)
                        key[i]->release_callback(key[i]->key_id);

                    // 同样重置时间参考
                    key[i]->press_tick = key[i]->release_tick;
                    key[i]->trigger_count = key[i]->release_tick;
                }
            }
            else
            {
                // 未达到消抖时长，也重置 press_tick 避免残留
                key[i]->press_tick = key[i]->release_tick;
            }
        }
        // 按键长按
        else if (key[i]->current_state == key[i]->trigger_level)
        {
            key[i]->hold_time = current_tick - key[i]->press_tick;

            if (key[i]->hold_time >= key[i]->long_press_time)
            {
                if (key[i]->key_state == KEY_STATE_IDLE)
                {
                    key[i]->key_state = KEY_STATE_LONG_PRESS;
                    if (key[i]->long_callback)
                        key[i]->long_callback(key[i]->key_id);
                    key[i]->trigger_count = current_tick;
                }
                else if (key[i]->key_state == KEY_STATE_LONG_PRESS &&
                         current_tick - key[i]->trigger_count >= Key_Frequency2Cycle(key[i]->trigger_frequnecy))
                {
                    key[i]->trigger_count = current_tick;
                    if (key[i]->long_callback)
                        key[i]->long_callback(key[i]->key_id);
                }
            }
        }
        else
        {
            key[i]->key_state = KEY_STATE_IDLE;
        }
        key[i]->last_state = key[i]->current_state; // 提取公共逻辑
    }
}