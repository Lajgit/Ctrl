#ifndef __SOFTSPI_H__
#define __SOFTSPI_H__

#include "main.h"

typedef struct
{
    GPIO_TypeDef *SPI_SDA_GPIOPORT;
    GPIO_TypeDef *SPI_CLK_GPIOPORT;
    GPIO_TypeDef *SPI_CS_GPIOPORT;
    uint16_t SPI_SDA_GPIOPIN;
    uint16_t SPI_CLK_GPIOPIN;
    uint16_t SPI_CS_GPIOPIN;
    uint16_t DelayTick;
    uint8_t CLK_CPOL;       // Clock polarity
    GPIO_PinState CS_Level; // Chip select level
} SoftwareSPI_HandleTypeDef;

typedef struct
{
    GPIO_TypeDef *SDA_Port;
    GPIO_TypeDef *CLK_Port;
    GPIO_TypeDef *CS_Port;
    uint16_t SDA_Pin;
    uint16_t CLK_Pin;
    uint16_t CS_Pin;
    uint16_t DelayTick;
    uint8_t CLK_CPOL;       // Clock polarity
    GPIO_PinState CS_Level; // Chip select level
} SoftwareSPI_InitTypeDef;

void SoftwareSPI_TransmitByte(SoftwareSPI_HandleTypeDef *hspi, uint8_t data);
void SoftwareSPI_Transmit(SoftwareSPI_HandleTypeDef *hspi, uint8_t *pData, uint16_t Size);
void SoftwareSPI_Init(SoftwareSPI_HandleTypeDef *hspi, SoftwareSPI_InitTypeDef init);

#endif
