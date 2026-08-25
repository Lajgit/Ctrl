#ifndef __APP_CRC_H__
#define __APP_CRC_H__

#include "main.h"

uint32_t CRC32_calculate(uint8_t* data, size_t length);
uint16_t CRC16_calculate(uint8_t *data, uint16_t length);

#endif