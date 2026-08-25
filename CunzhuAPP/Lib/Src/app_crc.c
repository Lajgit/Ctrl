#include "app_crc.h"

// 生成CRC32表（多项式0x04C11DB7）
static void generate_crc32_table(uint32_t *table)
{
    for (uint32_t i = 0; i < 256; i++)
    {
        uint32_t crc = i;
        for (int j = 0; j < 8; j++)
        {
            crc = (crc >> 1) ^ ((crc & 1) ? 0xEDB88320UL : 0);
        }
        table[i] = crc;
    }
}

/**
 * @brief  CRC32校验
 * @param  data: 待校验数据
 * @param  length: 待校验数据长度
 * @retval CRC32校验结果
 */
uint32_t CRC32_calculate(uint8_t *data, size_t length)
{
    static uint32_t table[256];
    static int table_generated = 0;

    if (!table_generated)
    {
        generate_crc32_table(table);
        table_generated = 1;
    }

    uint32_t crc = 0xFFFFFFFFUL; // 初始值
    const uint8_t *bytes = (const uint8_t *)data;

    for (size_t i = 0; i < length; i++)
    {
        crc = (crc >> 8) ^ table[(crc ^ bytes[i]) & 0xFF];
    }

    return crc ^ 0xFFFFFFFFUL; // 最终异或值
}

/**
 * @brief  CRC16校验
 * @param  data: 待校验数据
 * @param  length: 待校验数据长度
 * @retval CRC16校验结果
 */
uint16_t CRC16_calculate(uint8_t *data, uint16_t length)
{
    uint16_t crc = 0xFFFF; // CRC初始值
    uint8_t i;

    if (length == 0)
        return 0;
    while (length--)
    {
        crc ^= *data++; // 每次处理一个字节
        for (i = 0; i < 8; i++)
        {
            if (crc & 1)
            {
                crc >>= 1;     // 如果最低位为1
                crc ^= 0xA001; // 多项式 0xA001 (0x8005的反转)
            }
            else
                crc >>= 1;
        }
    }
    return crc;
}