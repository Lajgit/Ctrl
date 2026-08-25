#include "port_flash.h"

static int Flash_Write(void *hflash)
{
    Flash_HandleTypeDef *Handle = (Flash_HandleTypeDef *)hflash;
    uint32_t *pData = (uint32_t *)Handle->pData;
    if (Handle == NULL || Handle->pData == NULL || Handle->DataSize == 0)
        return -1; // 参数无效
    return Flash_WriteData(Handle->StartAddress, pData, Handle->DataSize);
}

static int Flash_Read(void *hflash)
{
    Flash_HandleTypeDef *Handle = (Flash_HandleTypeDef *)hflash;
    if (Handle == NULL || Handle->pData == NULL || Handle->DataSize == 0)
        return -1; // 参数无效
    return Flash_ReadData(Handle->StartAddress, (uint32_t *)Handle->pData, Handle->DataSize);
}

static int Flash_Clean(void *hflash)
{
    Flash_HandleTypeDef *Handle = (Flash_HandleTypeDef *)hflash;
    if (Handle == NULL || Handle->pData == NULL || Handle->DataSize == 0)
        return -1; // 参数无效
    return Flash_CleanData(Handle->StartAddress);
}

void Flash_Init(Flash_HandleTypeDef *hflash, Flash_InitTypeDef flash_init)
{
    hflash->StartAddress = flash_init.StartAddress;
    hflash->DataSize = flash_init.DataSize;
    hflash->pData = flash_init.pData;
    hflash->f_Write = Flash_Write;
    hflash->f_Read = Flash_Read;
    hflash->f_Clean = Flash_Clean;
}