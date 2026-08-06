#ifndef __MESGTASK_H__
#define __MESGTASK_H__

/* 控制板与 Android 协议命令、事件位及固件版本定义。 */
#include "main.h"
#include "port_event.h"
#include "CommunicateTask.h"

/* Android 主通信串口。 */
#define Android_USART USART1

/* 固件版本按 0xMMNNPPBB 组合。 */
#define VERSION_MAJOR 2U
#define VERSION_MINOR 2U
#define VERSION_PATCH 0U
#define VERSION_BUILD 2U
#define VERSION ((VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | \
                 (VERSION_PATCH << 8) | VERSION_BUILD)

/* Code1 表示协议帧方向。 */
#define Board_to_Android 0x00U
#define Android_to_Board 0x01U

/* Android -> 控制板命令码。 */
#define VersionRequest 0x00U
#define CollectStart 0x02U
#define CollectStop 0x03U
#define Unlock 0x10U
#define CashAcceptanceApply 0x18U
#define BillReset 0x19U
#define CashEventStored 0x1AU
#define HardwareStatusRequest 0x20U
#define DispenseStartOrder 0x30U
#define DispenseTerminalAck 0x31U
#define CashAcceptanceApplyV22 0x33U
#define BoardRestart 0xF0U
#define EmergencyStop 0xFFU

/* 控制板 -> Android 事件码。 */
#define VersionReport 0x00U
#define CashAccepted 0x10U
#define CashAcceptanceStatus 0x11U
#define CashDeviceStatus 0x12U
#define BeadStockStatus 0x20U
#define BeadLowStock 0x21U
#define BeadEmpty 0x22U
#define BeadRefilled 0x23U
#define BackendSettingsRequest 0x27U
#define AlreadyUnlock 0x0DU
#define DispenseProgress 0x40U
#define DispenseTerminal 0x41U

/* 写入 RTC 备份寄存器后，复位进入 Bootloader 的识别码。 */
#define OTA_REQUEST_MAGIC 0x424F5441U

/* 出珠订单帧中：高 16 位为订单序号，低 16 位为数量。 */
#define ORDER_DATA_SEQUENCE_SHIFT 16U
#define ORDER_DATA_VALUE_MASK 0x0000FFFFUL

/* 消息事件组各位定义。 */
#define MesgEvent_DispenseProgress (1u << 0)
#define MesgEvent_DispenseTerminal (1u << 1)
#define MesgEvent_CashAccepted (1u << 8)
#define MesgEvent_CashAcceptanceStatus (1u << 9)
#define MesgEvent_CashDeviceStatus (1u << 10)
#define MesgEvent_BeadStockStatus (1u << 11)
#define MesgEvent_BeadLowStock (1u << 12)
#define MesgEvent_BeadEmpty (1u << 13)
#define MesgEvent_BeadRefilled (1u << 14)
#define MesgEvent_BackendSettingsRequest (1u << 15)
#define MesgEvent_Unlock (1u << 16)
#define MesgEvent_VersionRequest (1u << 17)

/* 重发周期、接收消息去重窗口和普通消息最大重发次数。 */
#define ResendTrigger_Time 1000U
#define MesgDeal_Time 5000U
#define Max_Resend_Times 3U

/* 将事件组中的待上报事件编码并发送给 Android。 */
void Mesg_Task(void);

#endif
