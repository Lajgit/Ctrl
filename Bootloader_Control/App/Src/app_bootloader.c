#include "app_bootloader.h"
#include "app_crc32.h"
#include "app_ota.h"
#include "app_ws2812.h"
#include "usart.h"
#include <stddef.h>
#include <string.h>

uint16_t RGB_buffer[Light_CRRbuffer_SIZE];
#define BOOT_OTA_RESCUE_WINDOW_MS 2000U

static bool Boot_WaitForOtaHelloPrefix(uint32_t timeout_ms)
{
    static const uint8_t hello_prefix[] =
    {
        OTA_SOF1,
        OTA_SOF2,
        OTA_PROTOCOL_VERSION,
        OTA_CMD_HELLO
    };

    uint32_t start_tick;
    uint8_t match_index;
    uint8_t byte;

    start_tick = HAL_GetTick();
    match_index = 0U;

    while ((HAL_GetTick() - start_tick) < timeout_ms)
    {
        if (HAL_UART_Receive(&huart1,
                             &byte,
                             1U,
                             10U) != HAL_OK)
        {
            continue;
        }

        if (byte == hello_prefix[match_index])
        {
            match_index++;

            if (match_index >= sizeof(hello_prefix))
            {
                return true;
            }
        }
        else
        {
            /*
             * 当前字节本身又是0xAA时，作为下一次匹配的开头。
             */
            match_index = (byte == OTA_SOF1) ? 1U : 0U;
        }
    }

    return false;
}

static void Flash_ClearFlags(void)
{
    __HAL_FLASH_CLEAR_FLAG(FLASH_FLAG_EOP | FLASH_FLAG_OPERR | FLASH_FLAG_WRPERR |
                           FLASH_FLAG_PGAERR | FLASH_FLAG_PGPERR | FLASH_FLAG_PGSERR);
}

static uint32_t Flash_GetSector(uint32_t address)
{
    if (address < 0x08004000U) return FLASH_SECTOR_0;
    if (address < 0x08008000U) return FLASH_SECTOR_1;
    if (address < 0x0800C000U) return FLASH_SECTOR_2;
    if (address < 0x08010000U) return FLASH_SECTOR_3;
    if (address < 0x08020000U) return FLASH_SECTOR_4;
    if (address < 0x08040000U) return FLASH_SECTOR_5;
    if (address < 0x08060000U) return FLASH_SECTOR_6;
    if (address < 0x08080000U) return FLASH_SECTOR_7;
    if (address < 0x080A0000U) return FLASH_SECTOR_8;
    if (address < 0x080C0000U) return FLASH_SECTOR_9;
    if (address < 0x080E0000U) return FLASH_SECTOR_10;
    return FLASH_SECTOR_11;
}

static HAL_StatusTypeDef Flash_WriteUnlocked(uint32_t start_address,
                                              const uint8_t *data,
                                              uint32_t size)
{
    uint32_t address = start_address;
    uint32_t offset = 0U;

    if ((start_address & 3U) != 0U || data == NULL || size == 0U)
        return HAL_ERROR;

    while (offset < size)
    {
        uint32_t word = 0xFFFFFFFFU;
        uint32_t remain = size - offset;
        uint32_t copy_size = remain >= 4U ? 4U : remain;

        memcpy(&word, &data[offset], copy_size);

        if (HAL_FLASH_Program(FLASH_TYPEPROGRAM_WORD, address, word) != HAL_OK)
            return HAL_ERROR;

        address += 4U;
        offset += copy_size;
    }

    return HAL_OK;
}

void Boot_LightWaiting(void)
{
    Light_SetAllColor(SKYBLUE, RGB_buffer);
}

void Boot_LightSuccess(void)
{
    Light_SetAllColor(GREEN, RGB_buffer);
}

void Boot_LightError(void)
{
    Light_SetAllColor(RED, RGB_buffer);
}

HAL_StatusTypeDef Boot_FlashWrite(uint32_t start_address,
                                  const uint8_t *data,
                                  uint32_t size)
{
    HAL_StatusTypeDef status;

    if ((start_address & 3U) != 0U || data == NULL || size == 0U)
        return HAL_ERROR;

    if (HAL_FLASH_Unlock() != HAL_OK)
        return HAL_ERROR;

    Flash_ClearFlags();
    status = Flash_WriteUnlocked(start_address, data, size);
    HAL_FLASH_Lock();
    return status;
}

HAL_StatusTypeDef Boot_EraseCache(void)
{
    FLASH_EraseInitTypeDef erase = {0};
    uint32_t sector_error = 0U;
    HAL_StatusTypeDef status;

    if (HAL_FLASH_Unlock() != HAL_OK)
        return HAL_ERROR;

    Flash_ClearFlags();
    erase.TypeErase = FLASH_TYPEERASE_SECTORS;
    erase.VoltageRange = FLASH_VOLTAGE_RANGE_3;
    erase.Sector = FLASH_SECTOR_9;
    erase.NbSectors = 3U;

    status = HAL_FLASHEx_Erase(&erase, &sector_error);
    HAL_FLASH_Lock();
    return status;
}

bool Boot_FlashCompare(uint32_t address, const uint8_t *data, uint32_t size)
{
    if (data == NULL)
        return false;

    return memcmp((const void *)address, data, size) == 0;
}

static bool StackPointerIsValid(uint32_t stack_pointer)
{
    return stack_pointer >= 0x20000000U && stack_pointer < 0x20020000U;
}

static bool ResetHandlerIsValid(uint32_t reset_handler)
{
    uint32_t address;

    if ((reset_handler & 1U) == 0U)
        return false;

    address = reset_handler & ~1U;
    return address >= APP_ADDR && address < APP_END_ADDR;
}

bool Boot_AppIsValid(void)
{
    uint32_t stack_pointer = *(volatile uint32_t *)APP_ADDR;
    uint32_t reset_handler = *(volatile uint32_t *)(APP_ADDR + 4U);

    return StackPointerIsValid(stack_pointer) && ResetHandlerIsValid(reset_handler);
}

bool Boot_CachedImageVectorIsValid(uint32_t image_size)
{
    uint32_t stack_pointer;
    uint32_t reset_handler;

    if (image_size < 8U || image_size > OTA_CACHE_MAX_SIZE || image_size > APP_MAX_SIZE)
        return false;

    stack_pointer = *(volatile uint32_t *)OTA_CACHE_ADDR;
    reset_handler = *(volatile uint32_t *)(OTA_CACHE_ADDR + 4U);

    return StackPointerIsValid(stack_pointer) && ResetHandlerIsValid(reset_handler);
}

bool Boot_ConsumeOtaRequest(void)
{
    uint32_t magic;
    uint32_t timeout;
    uint32_t retry;
    bool ota_requested;

    /*
     * 使能PWR时钟，并回读确认。
     */
    __HAL_RCC_PWR_CLK_ENABLE();
    __DSB();
    (void)RCC->APB1ENR;

    /*
     * 打开备份域写权限。
     */
    HAL_PWR_EnableBkUpAccess();

    timeout = 100000U;

    while (((PWR->CR & PWR_CR_DBP) == 0U) &&
           (timeout > 0U))
    {
        timeout--;
    }

    if (timeout == 0U)
    {
        return false;
    }

    /*
     * 使能RTC接口。
     */
    __HAL_RCC_RTC_ENABLE();
    __DSB();
    (void)RCC->BDCR;

    /*
     * 先读取，再判断是不是OTA请求。
     */
    magic = RTC->BKP0R;
    ota_requested = (magic == OTA_REQUEST_MAGIC);

    /*
     * 清除请求并读回确认，防止一次PANT请求在下次复位时再次生效。
     */
    for (retry = 0U; retry < 3U; retry++)
    {
        RTC->BKP0R = 0U;

        __DSB();
        __ISB();

        if (RTC->BKP0R == 0U)
        {
            break;
        }
    }

    /*
     * Bootloader完成读取和清除后，可以重新关闭备份域写权限。
     * APP端已经能够可靠地重新打开DBP。
     */
    HAL_PWR_DisableBkUpAccess();

    return ota_requested;
}

static uint32_t Metadata_CalculateCrc(const OtaMetadata_t *metadata)
{
    uint32_t fields[4];

    fields[0] = metadata->magic;
    fields[1] = metadata->version_code;
    fields[2] = metadata->image_size;
    fields[3] = metadata->image_crc32;
    return CRC32_Calculate((const uint8_t *)fields, sizeof(fields));
}

bool Boot_ReadMetadata(OtaMetadata_t *metadata)
{
    uint32_t installing_marker;
    uint32_t installed_marker;

    if (metadata == NULL)
    {
        return false;
    }

    memcpy(
        metadata,
        (const void *)OTA_META_VALID_ADDR,
        sizeof(*metadata)
    );

    if (metadata->magic != OTA_METADATA_MAGIC)
    {
        return false;
    }

    /*
     * 基础元数据只在END时写一次，固定为VALID。
     */
    if (metadata->state != OTA_META_VALID)
    {
        return false;
    }

    if (metadata->image_size == 0U ||
        metadata->image_size > OTA_CACHE_MAX_SIZE ||
        metadata->image_size > APP_MAX_SIZE)
    {
        return false;
    }

    if (metadata->header_crc32 != Metadata_CalculateCrc(metadata))
    {
        return false;
    }

    installing_marker =
        *(volatile uint32_t *)OTA_META_INSTALLING_MARKER_ADDR;

    installed_marker =
        *(volatile uint32_t *)OTA_META_INSTALLED_MARKER_ADDR;

    /*
     * INSTALLED优先级最高。
     */
    if (installed_marker == OTA_META_MARKER_SET)
    {
        metadata->state = OTA_META_INSTALLED;
    }
    else if (installing_marker == OTA_META_MARKER_SET)
    {
        metadata->state = OTA_META_INSTALLING;
    }
    else
    {
        metadata->state = OTA_META_VALID;
    }

    return true;
}

HAL_StatusTypeDef Boot_WriteMetadata(uint32_t version_code,
                                     uint32_t image_size,
                                     uint32_t image_crc32)
{
    OtaMetadata_t metadata;

    metadata.magic = OTA_METADATA_MAGIC;
    metadata.state = OTA_META_VALID;
    metadata.version_code = version_code;
    metadata.image_size = image_size;
    metadata.image_crc32 = image_crc32;
    metadata.header_crc32 = Metadata_CalculateCrc(&metadata);

    /*
     * BEGIN已经擦除了Sector 9～11。
     * 两个状态标记地址此时必须为空。
     */
    if (*(volatile uint32_t *)OTA_META_INSTALLING_MARKER_ADDR !=
            OTA_META_MARKER_EMPTY ||
        *(volatile uint32_t *)OTA_META_INSTALLED_MARKER_ADDR !=
            OTA_META_MARKER_EMPTY)
    {
        return HAL_ERROR;
    }

    return Boot_FlashWrite(
        OTA_META_VALID_ADDR,
        (const uint8_t *)&metadata,
        sizeof(metadata)
    );
}

HAL_StatusTypeDef Boot_SetMetadataState(uint32_t state)
{
    uint32_t address;
    uint32_t marker;
    HAL_StatusTypeDef status;

    marker = OTA_META_MARKER_SET;

    if (state == OTA_META_INSTALLING)
    {
        address = OTA_META_INSTALLING_MARKER_ADDR;
    }
    else if (state == OTA_META_INSTALLED)
    {
        address = OTA_META_INSTALLED_MARKER_ADDR;
    }
    else
    {
        return HAL_ERROR;
    }

    /*
     * 支持重复调用：
     * 标记已经写入时，直接视为成功。
     */
    if (*(volatile uint32_t *)address == OTA_META_MARKER_SET)
    {
        return HAL_OK;
    }

    /*
     * 地址既不是空白也不是有效标记，说明元数据损坏。
     */
    if (*(volatile uint32_t *)address != OTA_META_MARKER_EMPTY)
    {
        return HAL_ERROR;
    }

    status = Boot_FlashWrite(
        address,
        (const uint8_t *)&marker,
        sizeof(marker)
    );

    if (status != HAL_OK)
    {
        return status;
    }

    __DSB();
    __ISB();

    if (*(volatile uint32_t *)address != OTA_META_MARKER_SET)
    {
        return HAL_ERROR;
    }

    return HAL_OK;
}

HAL_StatusTypeDef Boot_InstallCachedImage(void)
{
    OtaMetadata_t metadata;
    FLASH_EraseInitTypeDef erase = {0};
    uint32_t sector_error = 0U;
    uint32_t last_address;
    uint32_t first_sector;
    uint32_t last_sector;
    uint32_t offset = 0U;
    HAL_StatusTypeDef status = HAL_ERROR;

    if (!Boot_ReadMetadata(&metadata))
        return HAL_ERROR;

    if (CRC32_CalculateFlash(OTA_CACHE_ADDR, metadata.image_size) != metadata.image_crc32)
        return HAL_ERROR;

    if (!Boot_CachedImageVectorIsValid(metadata.image_size))
        return HAL_ERROR;

    if (metadata.state != OTA_META_INSTALLING)
    {
        if (Boot_SetMetadataState(OTA_META_INSTALLING) != HAL_OK)
            return HAL_ERROR;
        metadata.state = OTA_META_INSTALLING;
    }

    last_address = APP_ADDR + metadata.image_size - 1U;
    first_sector = Flash_GetSector(APP_ADDR);
    last_sector = Flash_GetSector(last_address);

    if (HAL_FLASH_Unlock() != HAL_OK)
        return HAL_ERROR;

    Flash_ClearFlags();
    erase.TypeErase = FLASH_TYPEERASE_SECTORS;
    erase.VoltageRange = FLASH_VOLTAGE_RANGE_3;
    erase.Sector = first_sector;
    erase.NbSectors = last_sector - first_sector + 1U;

    if (HAL_FLASHEx_Erase(&erase, &sector_error) != HAL_OK)
        goto finish;

    while (offset < metadata.image_size)
    {
        uint32_t remain = metadata.image_size - offset;
        uint32_t block_size = remain > 1024U ? 1024U : remain;

        if (Flash_WriteUnlocked(APP_ADDR + offset,
                                (const uint8_t *)(OTA_CACHE_ADDR + offset),
                                block_size) != HAL_OK)
            goto finish;

        offset += block_size;
    }

    if (CRC32_CalculateFlash(APP_ADDR, metadata.image_size) != metadata.image_crc32)
        goto finish;

    if (!Boot_AppIsValid())
        goto finish;

    status = HAL_OK;

finish:
    HAL_FLASH_Lock();

    if (status == HAL_OK)
    {
        if (Boot_SetMetadataState(OTA_META_INSTALLED) != HAL_OK)
            return HAL_ERROR;

        /*
        * 再读取一次元数据，确认Flash中实际状态已经是INSTALLED。
        */
        if (!Boot_ReadMetadata(&metadata))
            return HAL_ERROR;

        if (metadata.state != OTA_META_INSTALLED)
            return HAL_ERROR;
    }

    return status;
}

void JumpToApplication(void)
{
    typedef void (*AppEntry_t)(void);
    uint32_t app_stack;
    uint32_t app_reset;
    AppEntry_t entry;

    if (!Boot_AppIsValid())
        return;

    app_stack = *(volatile uint32_t *)APP_ADDR;
    app_reset = *(volatile uint32_t *)(APP_ADDR + 4U);
    entry = (AppEntry_t)app_reset;

    __disable_irq();

    HAL_UART_DeInit(&huart1);
    HAL_DeInit();

    SysTick->CTRL = 0U;
    SysTick->LOAD = 0U;
    SysTick->VAL = 0U;

    for (uint32_t i = 0U; i < 8U; i++)
    {
        NVIC->ICER[i] = 0xFFFFFFFFU;
        NVIC->ICPR[i] = 0xFFFFFFFFU;
    }

    SCB->VTOR = APP_ADDR;
    __set_CONTROL(0U);
    __set_MSP(app_stack);
    __DSB();
    __ISB();
    __enable_irq();

    entry();

    while (1)
    {
    }
}

// void App_Bootloader(void)
// {
//     OtaMetadata_t metadata;
//     bool ota_requested ;
//     bool recovery_pending = false;

//     ota_requested = Boot_ConsumeOtaRequest();

//     /*
//      * APP明确请求进入OTA时，优先进入OTA_Run。
//      * 即使上次元数据仍为VALID或INSTALLING，也不能先执行自动恢复并跳回APP。
//      */
//     if (ota_requested)
//     {
//         Boot_LightWaiting();
//         OTA_Run();
//     }

//     if (Boot_ReadMetadata(&metadata) &&
//         (metadata.state == OTA_META_VALID ||
//          metadata.state == OTA_META_INSTALLING))
//     {
//         recovery_pending = true;

//         Boot_LightWaiting();

//         if (Boot_InstallCachedImage() == HAL_OK)
//         {
//             Boot_LightSuccess();
//             HAL_Delay(1000U);
//             JumpToApplication();
//         }

//         Boot_LightError();
//     }

//     /*
//      * 保留原Bootloader行为：
//      * 普通开机时孔洞灯天蓝色亮1秒，然后进入App。
//      */
//     if (!ota_requested &&
//     !recovery_pending &&
//     Boot_AppIsValid())
//     {
//         Boot_LightWaiting();

//         /*
//         * 普通启动时保留2秒串口救援窗口。
//         *
//         * 如果上位机持续发送OTA HELLO前缀：
//         * AA 5A 01 01
//         * 则不依赖APP，直接进入OTA_Run()。
//         */
//         if (Boot_WaitForOtaHelloPrefix(
//                 BOOT_OTA_RESCUE_WINDOW_MS))
//         {
//             OTA_Run();
//         }

//         JumpToApplication();
//     }

//     /*
//      * App请求进入升级、App损坏或没有有效App时，
//      * 孔洞灯保持天蓝色，等待Unity发送固件。
//      */
//     if (!recovery_pending)
//     {
//         Boot_LightWaiting();
//     }

//     OTA_Run();
// }
void App_Bootloader(void)
{
    OtaMetadata_t metadata;
    bool ota_requested;
    bool recovery_pending = false;

    ota_requested = Boot_ConsumeOtaRequest();

    /*
     * 明确的串口OTA请求最高优先级。
     */
    if (ota_requested)
    {
        Boot_LightWaiting();
        OTA_Run();
    }

    /*
     * 没有明确OTA请求时，才执行断电恢复。
     */
    if (Boot_ReadMetadata(&metadata) &&
        (metadata.state == OTA_META_VALID ||
         metadata.state == OTA_META_INSTALLING))
    {
        recovery_pending = true;

        Boot_LightWaiting();

        if (Boot_InstallCachedImage() == HAL_OK)
        {
            Boot_LightSuccess();
            HAL_Delay(1000U);
            JumpToApplication();
        }

        Boot_LightError();
    }

    if (!recovery_pending && Boot_AppIsValid())
    {
        Boot_LightWaiting();
        HAL_Delay(1000U);
        JumpToApplication();
    }

    Boot_LightWaiting();
    OTA_Run();
}