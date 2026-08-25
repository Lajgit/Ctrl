#ifndef __PORT_KEY_H__
#define __PORT_KEY_H__

#include "main.h"

typedef enum
{
    KEY_STATE_IDLE = 0,
    KEY_STATE_SHORT_PRESS,
    KEY_STATE_LONG_PRESS,
} Key_State;

typedef struct
{
    uint16_t key_id;                    // 按键ID
    GPIO_TypeDef *port;                 // 按键对应的GPIO端口
    uint16_t pin;                       // 按键对应的GPIO引脚
    uint32_t debounce_time;             // 按键去抖时间
    uint32_t longpress_time;           // 按键长按时间
    uint32_t trigger_frequnecy;         // 按键触发频率
    void (*short_callback)(uint16_t);   // 按键短按回调函数
    void (*long_callback)(uint16_t);    // 按键长按回调函数
    void (*release_callback)(uint16_t); // 按键释放回调函数
    GPIO_PinState trigger_level;        // 按键触发电平
} Key_InitTypeDef;

// 按键状态结构体
typedef struct
{
    uint16_t key_id;                           // 按键ID
    GPIO_TypeDef *port;                        // 按键对应的GPIO端口
    uint16_t pin;                              // 按键对应的GPIO引脚
    GPIO_PinState last_state;                  // 按键上一次状态
    GPIO_PinState current_state;               // 按键当前状态
    uint32_t debounce_time;                    // 按键去抖时间
    uint32_t long_press_time;                  // 按键长按时间
    uint32_t press_tick;                       // 按键按下时刻
    uint32_t release_tick;                     // 按键释放时刻
    uint32_t hold_time;                        // 按键保持时间
    Key_State key_state;                       // 按键状态
    uint32_t trigger_count;                    // 按键触发计时
    uint32_t trigger_frequnecy;                // 按键触发频率
    void (*short_callback)(uint16_t key_id);   // 按键短按回调函数
    void (*long_callback)(uint16_t key_id);    // 按键长按回调函数
    void (*release_callback)(uint16_t key_id); // 按键释放回调函数
    GPIO_PinState trigger_level;               // 按键触election
} Key_HandleTypeDef;

void Key_Init(Key_HandleTypeDef *key, Key_InitTypeDef KeyInit);
void Key_Scan(Key_HandleTypeDef **key, uint16_t key_num);

#endif
