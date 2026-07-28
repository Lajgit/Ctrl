#include "app_crc32.h"

static uint32_t CRC32_UpdateByte(uint32_t crc, uint8_t data)
{
    crc ^= data;
    for (uint8_t i = 0; i < 8; i++)
    {
        if ((crc & 1U) != 0U)
            crc = (crc >> 1U) ^ 0xEDB88320U;
        else
            crc >>= 1U;
    }
    return crc;
}

uint32_t CRC32_Calculate(const uint8_t *data, uint32_t length)
{
    uint32_t crc = 0xFFFFFFFFU;

    if (data == NULL && length != 0U)
        return 0U;

    for (uint32_t i = 0; i < length; i++)
        crc = CRC32_UpdateByte(crc, data[i]);

    return crc ^ 0xFFFFFFFFU;
}

uint32_t CRC32_CalculateFlash(uint32_t address, uint32_t length)
{
    uint32_t crc = 0xFFFFFFFFU;
    const uint8_t *data = (const uint8_t *)address;

    for (uint32_t i = 0; i < length; i++)
        crc = CRC32_UpdateByte(crc, data[i]);

    return crc ^ 0xFFFFFFFFU;
}
