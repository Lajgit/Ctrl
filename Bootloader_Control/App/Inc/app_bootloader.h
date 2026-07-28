#ifndef __APP_BOOTLOADER_H__
#define __APP_BOOTLOADER_H__

#include "main.h"
#include <stdbool.h>

#define BOOTLOADER_ADDR        0x08000000U
#define BOOTLOADER_MAX_SIZE    0x00008000U
#define SETTINGS_ADDR          0x08008000U
#define APP_ADDR               0x0800C000U
#define APP_END_ADDR           0x080A0000U
#define APP_MAX_SIZE           (APP_END_ADDR - APP_ADDR)
#define OTA_CACHE_ADDR         0x080A0000U
#define OTA_META_ADDR          0x080FFF00U

#define OTA_META_VALID_ADDR              OTA_META_ADDR
#define OTA_META_INSTALLING_MARKER_ADDR  (OTA_META_ADDR + 0x20U)
#define OTA_META_INSTALLED_MARKER_ADDR   (OTA_META_ADDR + 0x24U)

#define OTA_META_MARKER_EMPTY            0xFFFFFFFFU
#define OTA_META_MARKER_SET              0x00000000U

#define OTA_CACHE_MAX_SIZE     (OTA_META_ADDR - OTA_CACHE_ADDR)

#define OTA_REQUEST_MAGIC      0x424F5441U /* 数据字节42 4F 54 41，即“BOTA” */
#define OTA_METADATA_MAGIC     0x4D41544FU

#define OTA_META_EMPTY         0xFFFFFFFFU
#define OTA_META_VALID         0xFFFFFFFEU
#define OTA_META_INSTALLING    0xFFFFFFFCU
#define OTA_META_INSTALLED     0xFFFFFFF8U

typedef struct
{
    uint32_t magic;
    uint32_t state;
    uint32_t version_code;
    uint32_t image_size;
    uint32_t image_crc32;
    uint32_t header_crc32;
} OtaMetadata_t;

HAL_StatusTypeDef Boot_FlashWrite(uint32_t start_address, const uint8_t *data, uint32_t size);
HAL_StatusTypeDef Boot_EraseCache(void);
bool Boot_FlashCompare(uint32_t address, const uint8_t *data, uint32_t size);
bool Boot_AppIsValid(void);
bool Boot_CachedImageVectorIsValid(uint32_t image_size);
bool Boot_ConsumeOtaRequest(void);
bool Boot_ReadMetadata(OtaMetadata_t *metadata);
HAL_StatusTypeDef Boot_WriteMetadata(uint32_t version_code, uint32_t image_size, uint32_t image_crc32);
HAL_StatusTypeDef Boot_SetMetadataState(uint32_t state);
HAL_StatusTypeDef Boot_InstallCachedImage(void);
void JumpToApplication(void);
void App_Bootloader(void);
void Boot_LightWaiting(void);
void Boot_LightSuccess(void);
void Boot_LightError(void);

#endif
