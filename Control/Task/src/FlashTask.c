#include "FlashTask.h"
#include "CtrlTask.h"
#include "MainTask.h"
#include "port_event.h"
#include "iwdg.h"
#include "string.h"

/*
 * 采用追加日志而不是每次都擦除扇区：
 * 1. 每次库存、欠吐数量或价格变化时追加一条 32 字节记录；
 * 2. 校验字最后写入，掉电产生的半条记录不会覆盖上一条有效记录；
 * 3. Sector 2 写满后才擦除并从头写入，降低 Flash 擦写次数。
 */
#define SETTING_RECORD_MAGIC_V1 0x475A0101UL /* V1：价格和余额单位为元 */
#define SETTING_RECORD_MAGIC_V2 0x475A0102UL /* V2：价格和余额单位为分 */
#define SETTING_RECORD_WORDS 8U
#define SETTING_RECORD_SIZE (SETTING_RECORD_WORDS * sizeof(uint32_t))
#define SETTING_CHECK_SEED 0xA55A5AA5UL

/* 默认购买参数：1.00 元一颗，库存暂定 10000 颗。 */
#define DEFAULT_BEAD_PRICE_FEN 100U
#define DEFAULT_BEAD_STOCK 10000U

typedef struct
{
    uint32_t Magic;
    uint32_t Sequence;

    /* V1 中表示元，V2 中表示分，读取时根据 Magic 转换。 */
    uint32_t BeadPriceValue;

    uint32_t BeadStock;
    uint32_t PendingBeads;

    /* V1 中表示元，V2 中表示分，读取时根据 Magic 转换。 */
    uint32_t PurchaseCreditValue;

    uint32_t PackedConfig;
    uint32_t Checksum;
} SettingRecord_t;

Setting_TypeDef Setting;
extern Event_Handle_t Event;

/* 下一条日志写入地址和当前有效记录序号。 */
static uint32_t SettingNextWriteAddress = Setting_Addr;
static uint32_t SettingSequence = 0U;

static uint32_t FlashTask_PackConfig(const Setting_TypeDef *setting)
{
    /* 高 8 位保存购买标志，低 24 位保存三组亮度值。 */
    return ((setting->PurchaseFlags & 0xFFU) << 24U) |
           ((setting->Ctrl_Lightness & 0xFFU) << 16U) |
           ((setting->LightBelt_Lightness & 0xFFU) << 8U) |
           (setting->Board_Lightness & 0xFFU);
}

static void FlashTask_UnpackConfig(Setting_TypeDef *setting, uint32_t packed)
{
    setting->Board_Lightness = packed & 0xFFU;
    setting->LightBelt_Lightness = (packed >> 8U) & 0xFFU;
    setting->Ctrl_Lightness = (packed >> 16U) & 0xFFU;
    setting->PurchaseFlags = (packed >> 24U) & 0xFFU;
}

static uint32_t FlashTask_CalculateChecksum(const SettingRecord_t *record)
{
    const uint32_t *word = (const uint32_t *)record;
    uint32_t checksum = SETTING_CHECK_SEED;
    uint32_t i;

    /* 不包含最后一个 Checksum 字段。 */
    for (i = 0U; i < (SETTING_RECORD_WORDS - 1U); i++)
    {
        checksum = (checksum << 5U) | (checksum >> 27U);
        checksum ^= word[i];
    }

    return checksum;
}

static bool FlashTask_IsKnownMagic(uint32_t magic)
{
    return (magic == SETTING_RECORD_MAGIC_V1) ||
           (magic == SETTING_RECORD_MAGIC_V2);
}

static bool FlashTask_IsRecordValid(const SettingRecord_t *record)
{
    return FlashTask_IsKnownMagic(record->Magic) &&
           (record->Checksum == FlashTask_CalculateChecksum(record));
}

static uint32_t FlashTask_YuanToFen(uint32_t value_yuan)
{
    if (value_yuan > (0xFFFFFFFFUL / 100U))
    {
        return 0xFFFFFFFFUL;
    }

    return value_yuan * 100U;
}

static void FlashTask_RecordToSetting(const SettingRecord_t *record)
{
    if (record->Magic == SETTING_RECORD_MAGIC_V1)
    {
        /* 兼容上一版：原来保存的是整数元，升级后转换成分。 */
        Setting.BeadPriceFen = FlashTask_YuanToFen(record->BeadPriceValue);
        Setting.PurchaseCreditFen = FlashTask_YuanToFen(record->PurchaseCreditValue);
    }
    else
    {
        Setting.BeadPriceFen = record->BeadPriceValue;
        Setting.PurchaseCreditFen = record->PurchaseCreditValue;
    }

    Setting.BeadStock = record->BeadStock;
    Setting.PendingBeads = record->PendingBeads;
    FlashTask_UnpackConfig(&Setting, record->PackedConfig);
}

static void FlashTask_SettingToRecord(SettingRecord_t *record)
{
    memset(record, 0, sizeof(*record));
    record->Magic = SETTING_RECORD_MAGIC_V2;
    record->Sequence = SettingSequence + 1U;
    record->BeadPriceValue = Setting.BeadPriceFen;
    record->BeadStock = Setting.BeadStock;
    record->PendingBeads = Setting.PendingBeads;
    record->PurchaseCreditValue = Setting.PurchaseCreditFen;
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

    /* Checksum 是最后一个字段，因此最后写入；半条记录不会成为有效状态。 */
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
        /* 写满后仅擦除一次，并立即写入最新完整状态。 */
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

    /* 首次使用默认 1.00 元一颗，协议和 Flash 均保存为 100 分。 */
    Setting.BeadPriceFen = DEFAULT_BEAD_PRICE_FEN;
    Setting.BeadStock = DEFAULT_BEAD_STOCK;
    Setting.PendingBeads = 0U;
    Setting.PurchaseCreditFen = 0U;
    Setting.PurchaseFlags = 0U;
}

void FlashTask_Init(void)
{
    SettingRecord_t latest_record;
    bool found_valid = false;
    bool found_blank = false;
    bool found_non_blank = false;
    bool need_upgrade_save = false;
    uint32_t first_blank = Setting_End_Addr;
    uint32_t address;

    memset(&latest_record, 0, sizeof(latest_record));

    /* 扫描整个日志区，忽略掉电留下的无效半条记录。 */
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
        need_upgrade_save = latest_record.Magic == SETTING_RECORD_MAGIC_V1;

        /* 将异常或旧版超范围价格限制到新的 0.01~100.00 元范围。 */
        if (Setting.BeadPriceFen < PURCHASE_MIN_PRICE_FEN)
        {
            Setting.BeadPriceFen = DEFAULT_BEAD_PRICE_FEN;
            need_upgrade_save = true;
        }
        else if (Setting.BeadPriceFen > PURCHASE_MAX_PRICE_FEN)
        {
            Setting.BeadPriceFen = PURCHASE_MAX_PRICE_FEN;
            need_upgrade_save = true;
        }

        if (need_upgrade_save == true)
        {
            /* 下一轮主循环追加一条 V2 记录，不立即擦除原有有效记录。 */
            FlashTask_RequestSave();
        }
    }
    else
    {
        ResumeSetting();
        SettingSequence = 0U;
        SettingNextWriteAddress = Setting_Addr;

        /* 兼容旧版固定地址数据或无效半条记录，先清空再写入 V2 日志。 */
        if (found_non_blank == true)
        {
            (void)FlashTask_EraseSector();
        }

        (void)FlashTask_WriteRecord();
    }
}

void FlashTask_RequestSave(void)
{
    /* 事件位会合并同一主循环内的多次变化，最终写入最新完整状态。 */
    EventGroupSetBits(&Event, Event_SaveSetting);
}

void FlashTask(void)
{
    if (EventGroupCheckBits(&Event, Event_SaveSetting) == true)
    {
        /* 写入失败时保留事件位，下一轮主循环继续重试。 */
        if (FlashTask_WriteRecord() == 0)
        {
            EventGroupClearBits(&Event, Event_SaveSetting);
        }
    }
}
