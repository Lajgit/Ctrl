#include "DigitalTubeTask.h"
#include "spi.h"

#define DigitalTube_Refresh_Time 50

static uint8_t DigitalBuffer[4] = {0x82};
DigitalTube_t DigitalTube;

static void DigitalTube_SPItransmit(uint8_t *pData, uint16_t Size)
{
    HAL_SPI_Transmit(&hspi2, pData, Size, 100);
}

void DigitalTubeTask_Init(void)
{
    DigitalTube_Init_t Init;

    Init.Buffer = DigitalBuffer;
    Init.bit_num = sizeof(DigitalBuffer);
    Init.LE_GPIO = SPI2_LE_GPIO_Port;
    Init.LE_Pin = SPI2_LE_Pin;
    Init.CODE_CA = DIGITAL3BIT_CODE_CA;
    Init.spi_transmit = DigitalTube_SPItransmit;
    DigitalTube_Init(&DigitalTube, Init);
    HAL_GPIO_WritePin(SPI2_OE_GPIO_Port, SPI2_OE_Pin, GPIO_PIN_RESET);
    DigitalTube.Set_Num(&DigitalTube, 0, 0, 4);
    DigitalTube.Refresh(&DigitalTube);
}

void DigitalTube_Task(void)
{
    static uint32_t time = 0;
    if (HAL_GetTick() - time > DigitalTube_Refresh_Time)
    {
        DigitalTube.Refresh(&DigitalTube);
        time = HAL_GetTick();
    }
}