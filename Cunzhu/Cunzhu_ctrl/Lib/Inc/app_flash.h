#ifndef __APP_FLASH_H__
#define __APP_FLASH_H__ 

#include "main.h"

int Flash_WriteData(uint32_t StartAddress, uint32_t *Data, uint32_t size);
int Flash_ReadData(uint32_t StartAddress, uint32_t *Data, uint32_t size);
int Flash_CleanData(uint32_t StartAddress);

#endif
