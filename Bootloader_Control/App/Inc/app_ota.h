#ifndef __APP_OTA_H__
#define __APP_OTA_H__

#include "main.h"
#include <stdbool.h>

#define OTA_SOF1               0xAAU
#define OTA_SOF2               0x5AU
#define OTA_PROTOCOL_VERSION   0x01U
#define OTA_MAX_DATA_SIZE      1024U
#define OTA_MAX_PAYLOAD_SIZE   (OTA_MAX_DATA_SIZE + 6U)
#define OTA_BOOT_VERSION       0x00010000U

/* BEGIN负载前4字节，按字节发送为 42 4F 54 41，即ASCII“BOTA”。 */   
#define OTA_TARGET_MAGIC       0x41544F42U

/* Bootloader命令 */
#define OTA_CMD_HELLO          0x01U
#define OTA_CMD_BEGIN          0x02U
#define OTA_CMD_DATA           0x03U
#define OTA_CMD_END            0x04U
#define OTA_CMD_INSTALL        0x05U
#define OTA_CMD_STATUS         0x06U
#define OTA_CMD_ABORT          0x07U
#define OTA_CMD_REBOOT         0x08U
#define OTA_CMD_ACK            0x80U
#define OTA_CMD_NACK           0x81U

/* ACK/NACK结果码 */
#define OTA_RESULT_OK                  0x00U
#define OTA_RESULT_BAD_PROTOCOL        0x01U
#define OTA_RESULT_BAD_FRAME_CRC       0x02U
#define OTA_RESULT_BAD_LENGTH          0x03U
#define OTA_RESULT_BAD_TARGET          0x04U
#define OTA_RESULT_BAD_IMAGE_SIZE      0x05U
#define OTA_RESULT_FLASH_ERASE_FAILED  0x06U
#define OTA_RESULT_FLASH_WRITE_FAILED  0x07U
#define OTA_RESULT_OFFSET_MISMATCH     0x08U
#define OTA_RESULT_IMAGE_CRC_FAILED    0x09U
#define OTA_RESULT_IMAGE_INVALID       0x0AU
#define OTA_RESULT_NO_VALID_IMAGE      0x0BU
#define OTA_RESULT_INSTALL_FAILED      0x0CU
#define OTA_RESULT_NOT_STARTED         0x0DU
#define OTA_RESULT_UNKNOWN_COMMAND     0x0EU

/* STATUS/HELLO返回的状态 */
#define OTA_SESSION_IDLE        0U
#define OTA_SESSION_RECEIVING   1U
#define OTA_SESSION_VERIFIED    2U
#define OTA_SESSION_INSTALLING  3U
#define OTA_SESSION_INSTALLED   4U
#define OTA_SESSION_ERROR       5U

void OTA_Run(void);

#endif
