#ifndef __LIGHTTASK_H__
#define __LIGHTTASK_H__

#include "port_lighteffect.h"
#include "port_light.h"

#define Light1_RGBbuffer_SIZE 66
#define Light1_CRRbuffer_SIZE ((Light1_RGBbuffer_SIZE + 7) * 24)

#define Light2_RGBbuffer_SIZE 16
#define Light2_CRRbuffer_SIZE ((Light2_RGBbuffer_SIZE + 7) * 24)

#define LightTime 300

void Light_Init(void);
void Light_Task(void);

#endif
