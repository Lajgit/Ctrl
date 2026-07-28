#include "app_ota.h"
#include "app_bootloader.h"
#include "app_crc32.h"
#include "usart.h"
#include <string.h>

typedef struct
{
    uint8_t command;
    uint16_t sequence;
    uint16_t payload_length;
    uint8_t payload[OTA_MAX_PAYLOAD_SIZE];
} OtaPacket_t;

typedef struct
{
    uint8_t state;
    uint32_t version_code;
    uint32_t image_size;
    uint32_t image_crc32;
    uint32_t expected_offset;
} OtaSession_t;

static OtaSession_t Session;
static uint8_t RxFrame[2U + 6U + OTA_MAX_PAYLOAD_SIZE + 4U];
static uint8_t TxFrame[2U + 6U + 10U + 4U];

static uint16_t ReadU16LE(const uint8_t *data)
{
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8U);
}

static uint32_t ReadU32LE(const uint8_t *data)
{
    return (uint32_t)data[0] |
           ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) |
           ((uint32_t)data[3] << 24U);
}

static void WriteU16LE(uint8_t *data, uint16_t value)
{
    data[0] = (uint8_t)value;
    data[1] = (uint8_t)(value >> 8U);
}

static void WriteU32LE(uint8_t *data, uint32_t value)
{
    data[0] = (uint8_t)value;
    data[1] = (uint8_t)(value >> 8U);
    data[2] = (uint8_t)(value >> 16U);
    data[3] = (uint8_t)(value >> 24U);
}

static HAL_StatusTypeDef OTA_SendPacket(uint8_t command,
                                         uint16_t sequence,
                                         const uint8_t *payload,
                                         uint16_t payload_length)
{
    uint32_t crc;
    uint16_t total_length;

    if (payload_length > 10U)
        return HAL_ERROR;

    TxFrame[0] = OTA_SOF1;
    TxFrame[1] = OTA_SOF2;
    TxFrame[2] = OTA_PROTOCOL_VERSION;
    TxFrame[3] = command;
    WriteU16LE(&TxFrame[4], sequence);
    WriteU16LE(&TxFrame[6], payload_length);

    if (payload_length > 0U && payload != NULL)
        memcpy(&TxFrame[8], payload, payload_length);

    crc = CRC32_Calculate(&TxFrame[2], 6U + payload_length);
    WriteU32LE(&TxFrame[8U + payload_length], crc);
    total_length = (uint16_t)(12U + payload_length);

    return HAL_UART_Transmit(&huart1, TxFrame, total_length, 1000U);
}

/*
 * ACK/NACK负载，共10字节：
 * [0] 原命令
 * [1] 结果码
 * [2] 当前状态
 * [3] 保留
 * [4..7] value，小端；DATA时表示nextOffset
 * [8..9] 最大数据块长度，小端
 */
static void OTA_SendReply(uint16_t sequence,
                          uint8_t request_command,
                          uint8_t result,
                          uint32_t value)
{
    uint8_t payload[10];

    payload[0] = request_command;
    payload[1] = result;
    payload[2] = Session.state;
    payload[3] = 0U;
    WriteU32LE(&payload[4], value);
    WriteU16LE(&payload[8], OTA_MAX_DATA_SIZE);

    OTA_SendPacket(result == OTA_RESULT_OK ? OTA_CMD_ACK : OTA_CMD_NACK,
                   sequence,
                   payload,
                   sizeof(payload));
}

static HAL_StatusTypeDef OTA_ReceivePacket(OtaPacket_t *packet)
{
    uint8_t byte;
    uint16_t payload_length;
    uint32_t receive_crc;
    uint32_t calculate_crc;

    if (packet == NULL)
        return HAL_ERROR;

    while (1)
    {
        if (HAL_UART_Receive(&huart1, &byte, 1U, 1000U) != HAL_OK)
            return HAL_TIMEOUT;

        if (byte != OTA_SOF1)
            continue;

        RxFrame[0] = byte;

        if (HAL_UART_Receive(&huart1, &RxFrame[1], 1U, 100U) != HAL_OK)
            continue;

        if (RxFrame[1] != OTA_SOF2)
            continue;

        if (HAL_UART_Receive(&huart1, &RxFrame[2], 6U, 1000U) != HAL_OK)
            return HAL_ERROR;

        packet->command = RxFrame[3];
        packet->sequence = ReadU16LE(&RxFrame[4]);
        payload_length = ReadU16LE(&RxFrame[6]);
        packet->payload_length = payload_length;

        if (RxFrame[2] != OTA_PROTOCOL_VERSION)
        {
            OTA_SendReply(packet->sequence,
                          packet->command,
                          OTA_RESULT_BAD_PROTOCOL,
                          Session.expected_offset);
            return HAL_ERROR;
        }

        if (payload_length > OTA_MAX_PAYLOAD_SIZE)
        {
            OTA_SendReply(packet->sequence,
                          packet->command,
                          OTA_RESULT_BAD_LENGTH,
                          Session.expected_offset);
            return HAL_ERROR;
        }

        if (HAL_UART_Receive(&huart1,
                             &RxFrame[8],
                             (uint16_t)(payload_length + 4U),
                             3000U) != HAL_OK)
            return HAL_ERROR;

        receive_crc = ReadU32LE(&RxFrame[8U + payload_length]);
        calculate_crc = CRC32_Calculate(&RxFrame[2], 6U + payload_length);

        if (receive_crc != calculate_crc)
        {
            OTA_SendReply(packet->sequence,
                          packet->command,
                          OTA_RESULT_BAD_FRAME_CRC,
                          Session.expected_offset);
            return HAL_ERROR;
        }

        if (payload_length > 0U)
            memcpy(packet->payload, &RxFrame[8], payload_length);

        return HAL_OK;
    }
}

static void OTA_HandleHello(const OtaPacket_t *packet)
{
    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OK,
                  OTA_BOOT_VERSION);
}

static void OTA_HandleBegin(const OtaPacket_t *packet)
{
    uint32_t target_magic;
    uint32_t version_code;
    uint32_t image_size;
    uint32_t image_crc32;

    /* BEGIN负载：targetMagic、versionCode、imageSize、imageCRC32，共16字节。 */
    if (packet->payload_length != 16U)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_LENGTH,
                      0U);
        return;
    }

    target_magic = ReadU32LE(&packet->payload[0]);
    version_code = ReadU32LE(&packet->payload[4]);
    image_size = ReadU32LE(&packet->payload[8]);
    image_crc32 = ReadU32LE(&packet->payload[12]);

    if (target_magic != OTA_TARGET_MAGIC)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_TARGET,
                      target_magic);
        return;
    }

    if (image_size == 0U ||
        image_size > OTA_CACHE_MAX_SIZE ||
        image_size > APP_MAX_SIZE)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_IMAGE_SIZE,
                      image_size);
        return;
    }

    Session.state = OTA_SESSION_IDLE;
    Session.expected_offset = 0U;

    if (Boot_EraseCache() != HAL_OK)
    {
        Session.state = OTA_SESSION_ERROR;
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_FLASH_ERASE_FAILED,
                      0U);
        return;
    }

    Session.version_code = version_code;
    Session.image_size = image_size;
    Session.image_crc32 = image_crc32;
    Session.expected_offset = 0U;
    Session.state = OTA_SESSION_RECEIVING;

    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OK,
                  Session.expected_offset);
}

static void OTA_HandleData(const OtaPacket_t *packet)
{
    uint32_t offset;
    uint16_t data_length;
    const uint8_t *data;

    if (Session.state != OTA_SESSION_RECEIVING)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_NOT_STARTED,
                      Session.expected_offset);
        return;
    }

    if (packet->payload_length < 7U)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_LENGTH,
                      Session.expected_offset);
        return;
    }

    offset = ReadU32LE(&packet->payload[0]);
    data_length = ReadU16LE(&packet->payload[4]);
    data = &packet->payload[6];

    if (data_length == 0U ||
        data_length > OTA_MAX_DATA_SIZE ||
        packet->payload_length != (uint16_t)(6U + data_length) ||
        offset > Session.image_size ||
        data_length > Session.image_size - offset ||
        (offset & 3U) != 0U ||
        ((offset + data_length) < Session.image_size && (data_length & 3U) != 0U))
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_LENGTH,
                      Session.expected_offset);
        return;
    }

    if (offset == Session.expected_offset)
    {
        if (Boot_FlashWrite(OTA_CACHE_ADDR + offset, data, data_length) != HAL_OK ||
            !Boot_FlashCompare(OTA_CACHE_ADDR + offset, data, data_length))
        {
            Session.state = OTA_SESSION_ERROR;
            OTA_SendReply(packet->sequence,
                          packet->command,
                          OTA_RESULT_FLASH_WRITE_FAILED,
                          Session.expected_offset);
            return;
        }

        Session.expected_offset += data_length;
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_OK,
                      Session.expected_offset);
        return;
    }

    /* ACK丢失后Unity会重复发送上一包；内容一致时直接再次确认。 */
    if (offset < Session.expected_offset &&
        offset + data_length <= Session.expected_offset &&
        Boot_FlashCompare(OTA_CACHE_ADDR + offset, data, data_length))
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_OK,
                      Session.expected_offset);
        return;
    }

    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OFFSET_MISMATCH,
                  Session.expected_offset);
}

static void OTA_HandleEnd(const OtaPacket_t *packet)
{
    uint32_t calculate_crc;

    if (packet->payload_length != 0U)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_LENGTH,
                      Session.expected_offset);
        return;
    }

    if (Session.state != OTA_SESSION_RECEIVING ||
        Session.expected_offset != Session.image_size)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_OFFSET_MISMATCH,
                      Session.expected_offset);
        return;
    }

    calculate_crc = CRC32_CalculateFlash(OTA_CACHE_ADDR, Session.image_size);
    if (calculate_crc != Session.image_crc32)
    {
        Session.state = OTA_SESSION_ERROR;
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_IMAGE_CRC_FAILED,
                      calculate_crc);
        return;
    }

    if (!Boot_CachedImageVectorIsValid(Session.image_size))
    {
        Session.state = OTA_SESSION_ERROR;
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_IMAGE_INVALID,
                      0U);
        return;
    }

    if (Boot_WriteMetadata(Session.version_code,
                           Session.image_size,
                           Session.image_crc32) != HAL_OK)
    {
        Session.state = OTA_SESSION_ERROR;
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_FLASH_WRITE_FAILED,
                      0U);
        return;
    }

    Session.state = OTA_SESSION_VERIFIED;
    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OK,
                  Session.image_size);
}

static void OTA_HandleInstall(const OtaPacket_t *packet)
{
    OtaMetadata_t metadata;

    if (packet->payload_length != 0U)
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_BAD_LENGTH,
                      Session.expected_offset);
        return;
    }

    if (!Boot_ReadMetadata(&metadata))
    {
        OTA_SendReply(packet->sequence,
                      packet->command,
                      OTA_RESULT_NO_VALID_IMAGE,
                      0U);
        return;
    }

    Session.state = OTA_SESSION_INSTALLING;

    if (Boot_InstallCachedImage() != HAL_OK)
    {
        Session.state = OTA_SESSION_ERROR;

        Boot_LightError();

        OTA_SendReply(
                packet->sequence,
                packet->command,
                OTA_RESULT_INSTALL_FAILED,
                0U
        );

        return;
    }

    Session.state = OTA_SESSION_INSTALLED;

    OTA_SendReply(
            packet->sequence,
            packet->command,
            OTA_RESULT_OK,
            metadata.version_code
    );

    Boot_LightSuccess();

    HAL_Delay(1000U);

    NVIC_SystemReset();
}

static void OTA_HandleStatus(const OtaPacket_t *packet)
{
    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OK,
                  Session.expected_offset);
}

static void OTA_HandleAbort(const OtaPacket_t *packet)
{
    memset(&Session, 0, sizeof(Session));
    Session.state = OTA_SESSION_IDLE;

    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OK,
                  0U);
}

static void OTA_HandleReboot(const OtaPacket_t *packet)
{
    OTA_SendReply(packet->sequence,
                  packet->command,
                  OTA_RESULT_OK,
                  0U);
    HAL_Delay(100U);

    NVIC_SystemReset();
}

void OTA_Run(void)
{
    OtaPacket_t packet;
    OtaMetadata_t metadata;

    memset(&Session, 0, sizeof(Session));
    Session.state = OTA_SESSION_IDLE;
    if (Boot_ReadMetadata(&metadata) && metadata.state == OTA_META_INSTALLED)
        Session.state = OTA_SESSION_INSTALLED;

    while (1)
    {
        if (OTA_ReceivePacket(&packet) != HAL_OK)
            continue;

        switch (packet.command)
        {
        case OTA_CMD_HELLO:
            OTA_HandleHello(&packet);
            break;

        case OTA_CMD_BEGIN:
            OTA_HandleBegin(&packet);
            break;

        case OTA_CMD_DATA:
            OTA_HandleData(&packet);
            break;

        case OTA_CMD_END:
            OTA_HandleEnd(&packet);
            break;

        case OTA_CMD_INSTALL:
            OTA_HandleInstall(&packet);
            break;

        case OTA_CMD_STATUS:
            OTA_HandleStatus(&packet);
            break;

        case OTA_CMD_ABORT:
            OTA_HandleAbort(&packet);
            break;

        case OTA_CMD_REBOOT:
            OTA_HandleReboot(&packet);
            break;

        default:
            OTA_SendReply(packet.sequence,
                          packet.command,
                          OTA_RESULT_UNKNOWN_COMMAND,
                          Session.expected_offset);
            break;
        }
    }
}
