#ifndef __PORT_FLASH_H__
#define __PORT_FLASH_H__

#include "app_flash.h"
#include "stdint.h"

typedef struct
{
    uint32_t StartAddress; // 起始地址
    uint32_t DataSize;     // 数据大小
    void *pData;           // 数据指针
} Flash_InitTypeDef;

typedef struct
{
    uint32_t StartAddress; // 起始地址
    uint32_t DataSize;     // 数据大小
    void *pData;           // 数据指针
    int (*f_Write)(void *self); // 写入函数指针
    int (*f_Read)(void *self);  // 读取函数指针
    int (*f_Clean)(void *self);
} Flash_HandleTypeDef;


void Flash_Init(Flash_HandleTypeDef *hflash, Flash_InitTypeDef flash_init);

#endif
