#ifndef __PORT_LIGHT_H__
#define __APP_LIGHT_H__

#include "app_ws2812.h"

typedef struct
{
    Light_t *light;
    uint16_t start;
    uint16_t end;
    uint8_t *Lightness;
    uint8_t state;
    void (*SetColor)(void *self, RGB_t Color, uint16_t Lightness);
} Light_Handle_t;

void LightDerive_Init(Light_Handle_t *LightDerive, Light_t *light, uint16_t start, uint16_t end, uint8_t *Lightness);

#endif
