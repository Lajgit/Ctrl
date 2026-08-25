#include "port_light.h"

static void Light_SetColor(void *self, RGB_t Color, uint16_t Lightness)
{
    Light_Handle_t *Light = (Light_Handle_t *)self;
    if(SemaphoreTake(Light->light->Semaphore) == true)
    {
        RGB_SetMoreColor(Light->light, Light->start, Light->end, Color, *(Light->Lightness), Lightness);
        RGB_LocalRefresh(Light->light, Light->start, Light->end);
    }
}

void LightDerive_Init(Light_Handle_t *LightDerive, Light_t *light, uint16_t start, uint16_t end, uint8_t *Lightness)
{
    LightDerive->light = light;
    LightDerive->start = start;
    LightDerive->end = end;
    LightDerive->Lightness = Lightness;
    LightDerive->state = 0;
    LightDerive->SetColor = Light_SetColor;
}