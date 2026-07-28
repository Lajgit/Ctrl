#ifndef __KEYTASK_H__
#define __KEYTASK_H__

#include "main.h"

#define KEY_DEBOUNCE_TIME 7         // 按键消抖时间
#define KEY_LONG_PRESS_TIME 800      // 长按判定时间
#define KEY_LONG_TRIGGER_FREQUENCY 3 // 长按触发频率

#define HOLE_LONG_PRESS_TIME 500       // 洞口长按判定时间
#define HOLE_LONG_TRIGGER_FREQUENCY 1 // 洞口长按触发频率

#define Event_SettingButtonPress (1u << 0)      
#define Event_AllHoleSwitchTrigger (1u << 1)
#define Event_Encoder_K_Push (1u << 2)

void KeyAll_Init(void);
void Key_Task(void);

#endif