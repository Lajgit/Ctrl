#ifndef __PORT_DIGITALTUBE_H__
#define __PORT_DIGITALTUBE_H__

#include "main.h"
#include "stdbool.h"

typedef struct
{
    SPI_HandleTypeDef *hspi;
    GPIO_TypeDef *LE_GPIO;
    uint16_t LE_Pin;
    GPIO_TypeDef *OE_GPIO;
    uint16_t OE_Pin;
    uint8_t *Buffer;
    uint8_t bit_num;
    const uint8_t *CODE_CA;

    void (*Set_Num)(void *self, uint8_t position, uint32_t data, uint8_t datasize);
    void (*Refresh)(void *self);
} DigitalTube_t;

typedef struct
{
    SPI_HandleTypeDef *hspi;
    GPIO_TypeDef *LE_GPIO;
    uint16_t LE_Pin;
    GPIO_TypeDef *OE_GPIO;
    uint16_t OE_Pin;
    uint8_t *Buffer;
    uint8_t bit_num;
    const uint8_t *CODE_CA;

} DigitalTube_Init_t;

extern const uint8_t SEGMENT_CODE_CA[];
extern const uint8_t DIGITAL3BIT_CODE_CA[];
extern const uint8_t TOWER_CODE_CA[];
extern const uint8_t TEST_CODE_CA[];

void DigitalTube_Init(DigitalTube_t *DigitalTube,DigitalTube_Init_t Init);
#endif
