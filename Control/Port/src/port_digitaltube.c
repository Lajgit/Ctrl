#include "port_digitaltube.h"

/* 共阳极数码管段码表（0~9）*/
const uint8_t SEGMENT_CODE_CA[] = {
    0xC0, // 0 (共阳极取反：原0x3F -> 0xC0)
    0xF9, // 1
    0xA4, // 2
    0xB0, // 3
    0x99, // 4
    0x92, // 5
    0x82, // 6
    0xF8, // 7
    0x80, // 8
    0x90, // 9
    0xFF, // 10 (空白)
};

const uint8_t DIGITAL3BIT_CODE_CA[] = {
    0x82, // 0 (
    0xAF, // 1
    0x91, // 2
    0x85, // 3
    0xAC, // 4
    0xC4, // 5
    0xC0, // 6
    0x8F, // 7
    0x80, // 8
    0x84, // 9
    0xFF, // 10 (空白)
};

const uint8_t TOWER_CODE_CA[] = {
    0x82, // 0
    0xAF, // 1
    0x91, // 2
    0x85, // 3
    0xAC, // 4
    0xC4, // 5
    0xC0, // 6
    0x8F, // 7
    0x80, // 8
    0x84, // 9
    0xFF, // 10 (空白)
};

const uint8_t TEST_CODE_CA[] = {
    0x00, // 0
    (0x01 << 0),
    (0x01 << 1),
    (0x01 << 2),
    (0x01 << 3),
    (0x01 << 4),
    (0x01 << 5),
    (0x01 << 6),
    (0x01 << 7),

};

/*
 * @brief  设置数码管 的某一位
 * @param  data: 数码管 的某一位数据
 * @param  position: 数码管 的某一位位置 第0位开始
 * @param  buffer: 数码管 的数据缓存
 * @param  size: 数码管 的数据缓存大小
 * @retval 位置非法返回false ，正确返回true
 */
static bool DigitalTube_Setbit(uint8_t position, uint8_t data, uint8_t *buffer, uint8_t buffersize, const uint8_t *code)
{
    if (data > 10)
        return false;
    if (position > buffersize - 1)
        return false;
    else
        buffer[buffersize - position - 1] = code[data];
    return true;
}

/*
 * @brief  设置数码管 的某位数字
 * @param  positon: 数码管 的某位位置 第0位开始
 * @param  data: 数码管 的某位数字
 * @param  datasize: 数码管 的数字位数
 * @param  buffer:数码管的数据缓存
 * @param  buffersize: 数码管 的数据缓存大小
 * @param  code: 码表
 */
static bool DigitalTube_SetNum(uint8_t positon, uint32_t data, uint8_t datasize, uint8_t *buffer, uint8_t buffersize, const uint8_t *code)
{
    if (positon + datasize > buffersize)
        return false;
    for (uint8_t i = 1; i <= datasize; i++)
    {
        uint8_t num = data % 10;
        if (data == 0)
        {
            if (i == 1)
                DigitalTube_Setbit(positon + datasize - i, 0, buffer, buffersize, code);
            else
                DigitalTube_Setbit(positon + datasize - i, 10, buffer, buffersize, code);
        }
        else
            DigitalTube_Setbit(positon + datasize - i, num, buffer, buffersize, code);
        data /= 10;
    }
}

/*
 *===============================数码管对象方法实现=============================================
 */

/*
 * @brief  设置数码管 的某位数字
 * @param  self: 数码管对象指针
 * @param  position: 数码管 的某位位置 第0位开始
 * @param  data: 数码管 的某位数字
 * @param  datasize: 数码管 的数字位数
 */
static void Set_Num(void *self, uint8_t position, uint32_t data, uint8_t datasize)
{
    DigitalTube_t *DigitalTube = (DigitalTube_t *)self;
    DigitalTube_SetNum(position, data, datasize, DigitalTube->Buffer, DigitalTube->bit_num, DigitalTube->CODE_CA);
}

/*
 * @brief  刷新数码管，将数码管缓存中的数据刷新上数码管
 * @param  self: 数码管对象指针
 */
static void Refresh(void *self)
{
    DigitalTube_t *DigitalTube = (DigitalTube_t *)self;
    HAL_GPIO_WritePin(DigitalTube->LE_GPIO, DigitalTube->LE_Pin, GPIO_PIN_SET);
    HAL_GPIO_WritePin(DigitalTube->OE_GPIO, DigitalTube->OE_Pin, GPIO_PIN_SET);
    HAL_SPI_Transmit(DigitalTube->hspi, DigitalTube->Buffer, DigitalTube->bit_num, 100);
    HAL_GPIO_WritePin(DigitalTube->LE_GPIO, DigitalTube->LE_Pin, GPIO_PIN_RESET);
    HAL_GPIO_WritePin(DigitalTube->OE_GPIO, DigitalTube->OE_Pin, GPIO_PIN_RESET);
}

/*
 * @brief  初始化数码管对象
 * @param  DigitalTube: 数码管对象指针
 * @param  SPI: SPI句柄
 * @param  Buffer: 数码管缓存
 * @param  bit_num: 数码管缓存大小
 * @param  CODE_CA: 码表
 */
void DigitalTube_Init(DigitalTube_t *DigitalTube, DigitalTube_Init_t Init)
{
    DigitalTube->hspi = Init.hspi;
    DigitalTube->Buffer = Init.Buffer;
    DigitalTube->bit_num = Init.bit_num;
    DigitalTube->CODE_CA = Init.CODE_CA;
    DigitalTube->LE_GPIO = Init.LE_GPIO;
    DigitalTube->LE_Pin = Init.LE_Pin;
    DigitalTube->OE_GPIO = Init.OE_GPIO;
    DigitalTube->OE_Pin = Init.OE_Pin;

    DigitalTube->Set_Num = Set_Num;
    DigitalTube->Refresh = Refresh;
}
