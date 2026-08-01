#include "FlashTask.h"
#include "CtrlTask.h"
#include "MainTask.h"
#include "port_event.h"
#include "iwdg.h"
#include "string.h"

/*
 * V3 是服务器统一控制出珠后的全新记录格式。
 * 旧 V1/V2 中的本地价格、余额和欠吐数据不再迁移，发现旧记录时直接擦除并
 * 写入 V3 默认硬件状态，避免旧现金逻辑在新版固件中复活。
 */
#define SETTING_RECORD_MAGIC_V3 0x475A0200UL
#define SETTING_RECORD_WORDS 8U
#define SETTING_RECORD_SIZE (SETTING_RECORD_WORDS * sizeof(uint32_t))
#define SETTING_CHECK_SEED 0xA55A5AA5UL

#define DEFAULT_BEAD_STOCK 10000U

typedef struct
{
    uint32_t Magic;
    uint32_t Sequence;
    uint32_t BeadStock;
    uint32_t PendingCashAmountFen;
    uint32_t PendingCashSequence;
    uint32_t PendingCashMedium;
    uint32_t PackedConfig;
    uint32_t Checksum;
} SettingRecord_t;

Setting_TypeDef Setting;
extern Event_Handle_t Event;

static uint32_t SettingNextWriteAddress = Setting_Addr;
static uint32_t SettingSequence = 0U;

static uint32_t FlashTask_PackConfig(const Setting_TypeDef *setting)
{
    return ((setting->HardwareFlags & 0xFFU) << 24U) |
           ((setting->Ctrl_Lightness & 0xFFU) << 16U) |
           ((setting->LightBelt_Lightness & 0xFFU) << 8U) |
           (setting->Board_Lightness & 0xFFU);
}

static void FlashTask_UnpackConfig(Setting_TypeDef *setting, uint32_t packed)
{
    setting->Board_Lightness = packed & 0xFFU;
    setting->LightBelt_Lightness = (packed >> 8U) & 0xFFU;
    setting->Ctrl_Lightness = (packed >> 16U) & 0xFFU;
    setting->HardwareFlags = (packed >> 24U) & 0xFFU;
}

static uint32_t FlashTask_CalculateChecksum(const SettingRecord_t *record)
{
    const uint32_t *word = (const uint32_t *)record;
    uint32_t checksum = SETTING_CHECK_SEED;
    uint32_t i;

    for (i = 0U; i < (SETTING_RECORD_WORDS - 1U); i++)
    {
        checksum = (checksum << 5U) | (checksum >> 27U);
        checksum ^= word[i];
    }
    return checksum;
}

static bool FlashTask_IsRecordValid(const SettingRecord_t *record)
{
    return (record->Magic == SETTING_RECORD_MAGIC_V3) &&
           (record->Checksum == FlashTask_CalculateChecksum(record));
}

static void FlashTask_RecordToSetting(const SettingRecord_t *record)
{
    Setting.BeadStock = record->BeadStock;
    Setting.PendingCashAmountFen = record->PendingCashAmountFen;
    Setting.PendingCashSequence = record->PendingCashSequence;
    Setting.PendingCashMedium = record->PendingCashMedium;
    FlashTask_UnpackConfig(&Setting, record->PackedConfig);

    if (((Setting.HardwareFlags & HARDWARE_FLAG_PENDING_CASH) != 0U) &&
        ((Setting.PendingCashAmountFen == 0U) ||
         (Setting.PendingCashAmountFen > 0xFFFFU) ||
         (Setting.PendingCashMedium > 1U)))
    {
        Setting.HardwareFlags &= ~HARDWARE_FLAG_PENDING_CASH;
        Setting.PendingCashAmountFen = 0U;
        Setting.PendingCashMedium = 0U;
        FlashTask_RequestSave();
    }
}

static void FlashTask_SettingToRecord(SettingRecord_t *record)
{
    memset(record, 0, sizeof(*record));
    record->Magic = SETTING_RECORD_MAGIC_V3;
    record->Sequence = SettingSequence + 1U;
    record->BeadStock = Setting.BeadStock;
    record->PendingCashAmountFen = Setting.PendingCashAmountFen;
    record->PendingCashSequence = Setting.PendingCashSequence;
    record->PendingCashMedium = Setting.PendingCashMedium;
    record->PackedConfig = FlashTask_PackConfig(&Setting);
    record->Checksum = FlashTask_CalculateChecksum(record);
}

static int FlashTask_EraseSector(void)
{
    FLASH_EraseInitTypeDef erase = {0};
    uint32_t error_sector = 0U;

    erase.TypeErase = FLASH_TYPEERASE_SECTORS;
    erase.Sector = FLASH_SECTOR_2;
    erase.NbSectors = 1U;
    erase.VoltageRange = FLASH_VOLTAGE_RANGE_3;

    HAL_IWDG_Refresh(&hiwdg);
    if (HAL_FLASH_Unlock() != HAL_OK)
    {
        return 1;
    }
    if (HAL_FLASHEx_Erase(&erase, &error_sector) != HAL_OK)
    {
        HAL_FLASH_Lock();
        return 2;
    }
    HAL_FLASH_Lock();
    HAL_IWDG_Refresh(&hiwdg);
    return 0;
}

static int FlashTask_ProgramRecord(uint32_t address, const SettingRecord_t *record)
{
    const uint32_t *word = (const uint32_t *)record;
    uint32_t i;

    if (HAL_FLASH_Unlock() != HAL_OK)
    {
        return 1;
    }
    for (i = 0U; i < SETTING_RECORD_WORDS; i++)
    {
        if (HAL_FLASH_Program(FLASH_TYPEPROGRAM_WORD,
                              address + i * sizeof(uint32_t),
                              word[i]) != HAL_OK)
        {
            HAL_FLASH_Lock();
            return 2;
        }
    }
    HAL_FLASH_Lock();

    if (memcmp((const void *)address, record, sizeof(*record)) != 0)
    {
        return 3;
    }
    return 0;
}

static int FlashTask_WriteRecord(void)
{
    SettingRecord_t record;
    int result;

    if ((SettingNextWriteAddress + SETTING_RECORD_SIZE) > Setting_End_Addr)
    {
        result = FlashTask_EraseSector();
        if (result != 0)
        {
            return result;
        }
        SettingNextWriteAddress = Setting_Addr;
    }

    FlashTask_SettingToRecord(&record);
    result = FlashTask_ProgramRecord(SettingNextWriteAddress, &record);
    if (result == 0)
    {
        SettingSequence = record.Sequence;
        SettingNextWriteAddress += SETTING_RECORD_SIZE;
    }
    return result;
}

void ResumeSetting(void)
{
    Setting.Board_Lightness = 5U;
    Setting.LightBelt_Lightness = 5U;
    Setting.Ctrl_Lightness = 5U;
    Setting.BeadStock = DEFAULT_BEAD_STOCK;
    Setting.HardwareFlags = 0U;
    Setting.PendingCashAmountFen = 0U;
    Setting.PendingCashSequence = 0U;
    Setting.PendingCashMedium = 0U;
}

void FlashTask_Init(void)
{
    SettingRecord_t latest_record;
    bool found_valid = false;
    bool found_blank = false;
    bool found_non_blank = false;
    uint32_t first_blank = Setting_End_Addr;
    uint32_t address;

    memset(&latest_record, 0, sizeof(latest_record));
    for (address = Setting_Addr;
         (address + SETTING_RECORD_SIZE) <= Setting_End_Addr;
         address += SETTING_RECORD_SIZE)
    {
        const SettingRecord_t *record = (const SettingRecord_t *)address;
        if (record->Magic == 0xFFFFFFFFUL)
        {
            if (found_blank == false)
            {
                first_blank = address;
                found_blank = true;
            }
            continue;
        }

        found_non_blank = true;
        if (FlashTask_IsRecordValid(record) &&
            ((found_valid == false) || (record->Sequence >= latest_record.Sequence)))
        {
            latest_record = *record;
            found_valid = true;
        }
    }

    if (found_valid == true)
    {
        FlashTask_RecordToSetting(&latest_record);
        SettingSequence = latest_record.Sequence;
        SettingNextWriteAddress = found_blank ? first_blank : Setting_End_Addr;
        return;
    }

    /* 不兼容旧现金购买记录：统一清空并初始化 V3。 */
    ResumeSetting();
    SettingSequence = 0U;
    SettingNextWriteAddress = Setting_Addr;
    if (found_non_blank == true)
    {
        (void)FlashTask_EraseSector();
    }
    (void)FlashTask_WriteRecord();
}

void FlashTask_RequestSave(void)
{
    EventGroupSetBits(&Event, Event_SaveSetting);
}

void FlashTask(void)
{
    if (EventGroupCheckBits(&Event, Event_SaveSetting) == true)
    {
        if (FlashTask_WriteRecord() == 0)
        {
            EventGroupClearBits(&Event, Event_SaveSetting);
        }
    }
}
