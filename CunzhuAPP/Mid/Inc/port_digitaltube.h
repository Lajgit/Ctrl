#ifndef __PORT_DIGITALTUBE_H__
#define __PORT_DIGITALTUBE_H__

#include "main.h"
#include "softspi.h"
#include "stdbool.h"
#include "string.h"

typedef struct
{
    GPIO_TypeDef *LE_GPIO;
    uint16_t LE_Pin;
    uint8_t *Buffer;
    uint8_t bit_num;
    const uint8_t *CODE_CA;

    void (*spi_transmit)(uint8_t *data, uint16_t size);
    void (*Set_Num)(void *self, uint8_t position, uint32_t data, uint8_t datasize);
    void (*Refresh)(void *self);
} DigitalTube_t;

typedef struct
{
    GPIO_TypeDef *LE_GPIO;
    uint16_t LE_Pin;
    uint8_t *Buffer;
    uint8_t bit_num;
    const uint8_t *CODE_CA;
    void (*spi_transmit)(uint8_t *data, uint16_t size);
} DigitalTube_Init_t;

extern const uint8_t SEGMENT_CODE_CA[];
extern const uint8_t DIGITAL3BIT_CODE_CA[];
extern const uint8_t TOWER_CODE_CA[];
extern const uint8_t TEST_CODE_CA[];

void DigitalTube_Init(DigitalTube_t *DigitalTube, DigitalTube_Init_t Init);
#endif
