#include "app_flash.h"
#include "string.h"

#if defined(STM32F103xE) || defined(STM32F103xB)

static int Flash_Erase(uint32_t StartAddress)
{
    uint32_t errorCode = 0;
    FLASH_EraseInitTypeDef EraseInitStruct;
    EraseInitStruct.TypeErase = FLASH_TYPEERASE_PAGES;
    EraseInitStruct.PageAddress = StartAddress;
    EraseInitStruct.NbPages = 1;

    if (HAL_FLASH_Unlock() != HAL_OK)
        return 1; // 解锁失败
    if (HAL_FLASHEx_Erase(&EraseInitStruct, &errorCode) != HAL_OK)
        return 2;     // 擦除失败
    HAL_FLASH_Lock(); // 锁定闪存
    return 0;         // 擦除成功
}

static int Flash_Write(uint32_t StartAddress, uint32_t *Data, uint32_t size)
{
    if (Data == NULL || size == 0)
        return -1; // 参数无效
    uint32_t *pData = (uint32_t *)Data;
    uint32_t address = StartAddress;
    uint32_t words = (size + 3) / 4; // 字节转字(向上取整)
    HAL_FLASH_Unlock();
    for (uint32_t i = 0; i < words; i++)
    {
        if (HAL_FLASH_Program(FLASH_TYPEPROGRAM_WORD, address, *pData) != HAL_OK)
        {
            HAL_FLASH_Lock();
            return 1;
        }
        address += 4;
        pData++;
    }
    HAL_FLASH_Lock();
    return 0;
}

#endif

#if defined(STM32F405xx) || defined(STM32F415xx) || defined(STM32F407xx) || defined(STM32F417xx) || defined(STM32F412Zx) || \
    defined(STM32F412Vx) || defined(STM32F412Rx) || defined(STM32F412Cx)

/**
 * @brief  根据地址获取所属的扇区编号
 * @param  Address: Flash地址
 * @retval 扇区编号 (FLASH_SECTOR_0 到 FLASH_SECTOR_11)
 */
static uint32_t GetSector(uint32_t Address)
{
    uint32_t sector = 0;

    if ((Address < 0x08003FFF) && (Address >= 0x08000000))
        sector = FLASH_SECTOR_0;
    else if ((Address < 0x08007FFF) && (Address >= 0x08004000))
        sector = FLASH_SECTOR_1;
    else if ((Address < 0x0800BFFF) && (Address >= 0x08008000))
        sector = FLASH_SECTOR_2;
    else if ((Address < 0x0800FFFF) && (Address >= 0x0800C000))
        sector = FLASH_SECTOR_3;
    else if ((Address < 0x0801FFFF) && (Address >= 0x08010000))
        sector = FLASH_SECTOR_4;
    else if ((Address < 0x0803FFFF) && (Address >= 0x08020000))
        sector = FLASH_SECTOR_5;
    else if ((Address < 0x0805FFFF) && (Address >= 0x08040000))
        sector = FLASH_SECTOR_6;
    else if ((Address < 0x0807FFFF) && (Address >= 0x08060000))
        sector = FLASH_SECTOR_7;
    else if ((Address < 0x0809FFFF) && (Address >= 0x08080000))
        sector = FLASH_SECTOR_8;
    else if ((Address < 0x080BFFFF) && (Address >= 0x080A0000))
        sector = FLASH_SECTOR_9;
    else if ((Address < 0x080DFFFF) && (Address >= 0x080C0000))
        sector = FLASH_SECTOR_10;
    else if ((Address < 0x080FFFFF) && (Address >= 0x080E0000))
        sector = FLASH_SECTOR_11;
    else
        // 地址超出范围
        sector = 0xFFFFFFFF;

    return sector;
}
static int Flash_Erase(uint32_t StartAddress)
{
    uint32_t errorCode = 0;
    FLASH_EraseInitTypeDef EraseInitStruct;
    EraseInitStruct.TypeErase = FLASH_TYPEERASE_SECTORS;
    EraseInitStruct.Sector = GetSector(StartAddress);
    EraseInitStruct.NbSectors = 1;
    EraseInitStruct.VoltageRange = FLASH_VOLTAGE_RANGE_3;

    if (HAL_FLASH_Unlock() != HAL_OK)
        return 1; // 解锁失败
    if (HAL_FLASHEx_Erase(&EraseInitStruct, &errorCode) != HAL_OK)
        return 2;     // 擦除失败
    HAL_FLASH_Lock(); // 锁定闪存
    return 0;         // 擦除成功
}

static int Flash_Write(uint32_t StartAddress, uint32_t *Data, uint32_t size)
{
    uint32_t *pData = (uint32_t *)Data;
    uint32_t address = StartAddress;
    uint32_t words = (size + 3) / 4; // 字节转字(向上取整)
    HAL_FLASH_Unlock();
    for (uint32_t i = 0; i < words; i++)
    {
        if (HAL_FLASH_Program(FLASH_TYPEPROGRAM_WORD, address, *pData) != HAL_OK)
        {
            HAL_FLASH_Lock();
            return 1;
        }
        address += 4;
        pData++;
    }
    HAL_FLASH_Lock();
    return 0;
}

#endif

/*
 * ================================ Flash 操作 ===============================
 */

/*
 * @brief  擦除数据
 * @param  StartAddress: 起始地址
 * @retval 0: 成功
 *         -1: 参数无效
 *         1: 解锁失败
 *         2: 擦除失败
 */
int Flash_CleanData(uint32_t StartAddress)
{
    return Flash_Erase(StartAddress);
}

/*
 * @brief  写入数据
 * @param  StartAddress: 起始地址
 * @param  Data: 数据
 * @param  size: 数据大小
 * @retval 0: 成功
 *         1: 擦除失败
 *         2: 写入失败
 */
int Flash_WriteData(uint32_t StartAddress, uint32_t *Data, uint32_t size)
{
    if (Data == NULL || size == 0)
        return -1; // 参数无效
    if (Flash_Erase(StartAddress) != 0)
        return 1; // 擦除失败
    if (Flash_Write(StartAddress, Data, size) != 0)
        return 2; // 写入失败
    return 0;     // 写入成功
}

/*
 * @brief  读取数据
 * @param  StartAddress: 起始地址
 * @param  Data: 数据
 * @param  size: 数据大小
 * @retval 0: 读取成功
 */
int Flash_ReadData(uint32_t StartAddress, uint32_t *Data, uint32_t size)
{
    if (Data == NULL || size == 0)
        return -1; // 参数无效
    memcpy(Data, (uint32_t *)StartAddress, size);
    return 0; // 读取成功
}