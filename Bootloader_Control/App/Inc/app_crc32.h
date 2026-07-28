#ifndef __APP_CRC32_H__
#define __APP_CRC32_H__

#include "main.h"

uint32_t CRC32_Calculate(const uint8_t *data, uint32_t length);
uint32_t CRC32_CalculateFlash(uint32_t address, uint32_t length);

#endif
