/*
 * Flash 任务：使用追加写记录保存库存、硬件标志和现金事实队列。
 * 上电扫描整个保留扇区，校验记录后恢复序号最大的有效快照；空间用尽时整扇区擦除。
 */
#include "FlashTask.h"
#include "CtrlTask.h"
#include "MainTask.h"
#include "port_event.h"
#include "iwdg.h"
#include "string.h"

/*
 * V4 保存多笔待确认现金事实。旧记录不迁移，避免旧的单笔阻塞或本地现金
 * 购买状态进入新协议。
 */
#define SETTING_RECORD_MAGIC_V4 0x475A0400UL
#define SETTING_CHECK_SEED 0xA55A5AA5UL
#define DEFAULT_BEAD_STOCK 10000U

/* 单条 Flash 快照：版本标识、递增序号、业务数据和完整性校验值。 */
typedef struct
{
    uint32_t Magic;
    uint32_t Sequence;
    Setting_TypeDef SettingData;
    uint32_t Checksum;
} SettingRecord_t;

/* 记录按 32 位字写入，大小用于计算下一条追加地址。 */
#define SETTING_RECORD_WORDS ((uint32_t)(sizeof(SettingRecord_t) / sizeof(uint32_t)))
#define SETTING_RECORD_SIZE ((uint32_t)sizeof(SettingRecord_t))

/* 当前 RAM 中的控制板持久化状态。 */
Setting_TypeDef Setting;
extern Event_Handle_t Event;

/* 下一条记录的写入地址及当前最新记录序号。 */
static uint32_t SettingNextWriteAddress = Setting_Addr;
static uint32_t SettingSequence = 0U;

/* 对校验字段之前的全部 32 位字进行旋转异或校验。 */
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

/* 检查现金环形队列的索引、数量、介质、金额和序号是否在合法范围内。 */
static bool FlashTask_IsQueueValid(const Setting_TypeDef *setting)
{
    uint32_t offset;

    if ((setting->CashQueueHead >= CASH_EVENT_QUEUE_CAPACITY) ||
        (setting->CashQueueCount > CASH_EVENT_QUEUE_CAPACITY))
    {
        return false;
    }

    for (offset = 0U; offset < setting->CashQueueCount; offset++)
    {
        uint32_t index = (setting->CashQueueHead + offset) % CASH_EVENT_QUEUE_CAPACITY;
        uint32_t packed = setting->CashQueuePacked[index];
        uint32_t sequence = setting->CashQueueSequence[index];
        uint32_t medium = CASH_EVENT_PACKED_MEDIUM(packed);
        uint32_t amount = CASH_EVENT_PACKED_AMOUNT(packed);
        if ((sequence == 0U) || (sequence > 0xFFFFU) ||
            (medium > 1U) || (amount == 0U))
        {
            return false;
        }
    }
    return true;
}

/* 同时验证记录版本、校验和及队列数据。 */
static bool FlashTask_IsRecordValid(const SettingRecord_t *record)
{
    return (record->Magic == SETTING_RECORD_MAGIC_V4) &&
           (record->Checksum == FlashTask_CalculateChecksum(record)) &&
           FlashTask_IsQueueValid(&record->SettingData);
}

/* 将选中的有效 Flash 记录恢复到 RAM。 */
static void FlashTask_RecordToSetting(const SettingRecord_t *record)
{
    Setting = record->SettingData;
}

/* 根据当前 RAM 状态构造下一条待写入记录。 */
static void FlashTask_SettingToRecord(SettingRecord_t *record)
{
    memset(record, 0, sizeof(*record));
    record->Magic = SETTING_RECORD_MAGIC_V4;
    record->Sequence = SettingSequence + 1U;
    record->SettingData = Setting;
    record->Checksum = FlashTask_CalculateChecksum(record);
}

/* 擦除专用 Sector 2，擦除前后刷新独立看门狗。 */
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

/* 按 32 位字写入一条记录，并通过内存比较确认写入内容。 */
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

/* 追加写入最新快照；剩余空间不足时先擦除扇区再从起始地址写入。 */
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

/* 恢复无有效记录时使用的默认亮度、库存和空队列。 */
void ResumeSetting(void)
{
    memset(&Setting, 0, sizeof(Setting));
    Setting.Board_Lightness = 5U;
    Setting.LightBelt_Lightness = 5U;
    Setting.Ctrl_Lightness = 5U;
    Setting.BeadStock = DEFAULT_BEAD_STOCK;
}

/* 扫描整个保留扇区，恢复序号最大的有效记录并确定下一写入位置。 */
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

    /* 扇区存在无效内容时先擦除，再写入一条默认记录。 */
    ResumeSetting();
    SettingSequence = 0U;
    SettingNextWriteAddress = Setting_Addr;
    if (found_non_blank == true)
    {
        (void)FlashTask_EraseSector();
    }
    (void)FlashTask_WriteRecord();
}

/* 仅设置事件位，避免在中断或业务处理函数中直接进行耗时 Flash 操作。 */
void FlashTask_RequestSave(void)
{
    EventGroupSetBits(&Event, Event_SaveSetting);
}

/* 主循环检测保存请求，写入成功后才清除事件位。 */
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
