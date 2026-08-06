#ifndef __LIGHTTASK_H__
#define __LIGHTTASK_H__

/* 控制板 WS2812 孔洞灯和六路呼吸灯的任务接口。 */
#include "main.h"
#include "port_lighteffect.h"
#include "port_light.h"

/* 32 颗 RGB 灯珠及对应 PWM 编码缓冲区大小。 */
#define Light_RGBbuffer_SIZE 32
#define Light_CRRbuffer_SIZE ((Light_RGBbuffer_SIZE + 7) * 24)

/* 初始化灯光对象、定时器通道和分区映射。 */
void Light_Init(void);
/* 根据当前场景周期刷新对应灯效。 */
void Light_Task(void);

#endif
