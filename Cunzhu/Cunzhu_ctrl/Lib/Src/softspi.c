#include "softspi.h"
static inline void SPI_Delay(uint16_t count)
{
    for (uint16_t i = 0; i < count; i++)
        __NOP();
}

static void gpio_write(GPIO_TypeDef *Port, uint16_t Pin, GPIO_PinState State)
{
    HAL_GPIO_WritePin(Port, Pin, State);
}

/*
 * @brief  Transmit a byte of data through the SPI peripheral
 * @param  hspi: SPI handle
 * @param  data: The data to transmit
 * @retval None
 */
void SoftwareSPI_TransmitByte(SoftwareSPI_HandleTypeDef *hspi, uint8_t data)
{
    // gpio_write(hspi->SPI_CS_GPIOPORT, hspi->SPI_CS_GPIOPIN, hspi->CS_Level);
    SPI_Delay(hspi->DelayTick);
    for (uint8_t i = 0; i < 8; i++)
    {
        gpio_write(hspi->SPI_SDA_GPIOPORT, hspi->SPI_SDA_GPIOPIN, (data & (0x80 >> i)));
        SPI_Delay(hspi->DelayTick);
        gpio_write(hspi->SPI_CLK_GPIOPORT, hspi->SPI_CLK_GPIOPIN, (hspi->CLK_CPOL == 0) ? GPIO_PIN_SET : GPIO_PIN_RESET);
        SPI_Delay(hspi->DelayTick);
        gpio_write(hspi->SPI_CLK_GPIOPORT, hspi->SPI_CLK_GPIOPIN, (hspi->CLK_CPOL == 0) ? GPIO_PIN_RESET : GPIO_PIN_SET);
    }
    // gpio_write(hspi->SPI_CS_GPIOPORT, hspi->SPI_CS_GPIOPIN, !hspi->CS_Level);
    // gpio_write(hspi->SPI_SDA_GPIOPORT, hspi->SPI_SDA_GPIOPIN, GPIO_PIN_RESET);
}

/*
 * @brief  Transmit an array of bytes through the SPI peripheral
 * @param  hspi: SPI handle
 * @param  pData: The data to transmit
 * @param  Size: The size of the data to transmit
 * @retval None
 */
void SoftwareSPI_Transmit(SoftwareSPI_HandleTypeDef *hspi, uint8_t *pData, uint16_t Size)
{
    if (pData == NULL || Size == 0)
        return;
    if (hspi->SPI_CS_GPIOPORT != NULL && hspi->SPI_CS_GPIOPIN != 0)
        gpio_write(hspi->SPI_CS_GPIOPORT, hspi->SPI_CS_GPIOPIN, hspi->CS_Level);
    SPI_Delay(hspi->DelayTick);
    for (uint8_t i = 0; i < Size; i++)
    {
        for (uint8_t j = 0; j < 8; j++)
        {
            gpio_write(hspi->SPI_SDA_GPIOPORT, hspi->SPI_SDA_GPIOPIN, (pData[i] & (0x80 >> j)));
            SPI_Delay(hspi->DelayTick);
            gpio_write(hspi->SPI_CLK_GPIOPORT, hspi->SPI_CLK_GPIOPIN, (hspi->CLK_CPOL == 0) ? GPIO_PIN_SET : GPIO_PIN_RESET);
            SPI_Delay(hspi->DelayTick);
            gpio_write(hspi->SPI_CLK_GPIOPORT, hspi->SPI_CLK_GPIOPIN, (hspi->CLK_CPOL == 0) ? GPIO_PIN_RESET : GPIO_PIN_SET);
        }
    }
    if (hspi->SPI_CS_GPIOPORT != NULL && hspi->SPI_CS_GPIOPIN != 0)
        gpio_write(hspi->SPI_CS_GPIOPORT, hspi->SPI_CS_GPIOPIN, !hspi->CS_Level);
    gpio_write(hspi->SPI_SDA_GPIOPORT, hspi->SPI_SDA_GPIOPIN, GPIO_PIN_RESET);
}

void SoftwareSPI_Init(SoftwareSPI_HandleTypeDef *hspi, SoftwareSPI_InitTypeDef init)
{
    hspi->SPI_SDA_GPIOPORT = init.SDA_Port;
    hspi->SPI_SDA_GPIOPIN = init.SDA_Pin;
    hspi->SPI_CLK_GPIOPORT = init.CLK_Port;
    hspi->SPI_CLK_GPIOPIN = init.CLK_Pin;
    hspi->SPI_CS_GPIOPORT = init.CS_Port;
    hspi->SPI_CS_GPIOPIN = init.CS_Pin;
    hspi->CLK_CPOL = init.CLK_CPOL;
    hspi->CS_Level = init.CS_Level;
    hspi->DelayTick = init.DelayTick;
}