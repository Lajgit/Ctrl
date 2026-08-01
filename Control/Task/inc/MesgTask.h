#ifndef __MESGTASK_H__
#define __MESGTASK_H__

#include "main.h"
#include "port_event.h"
#include "CommunicateTask.h"

#define Android_USART USART1

#define VERSION_MAJOR 2U
#define VERSION_MINOR 0U
#define VERSION_PATCH 0U
#define VERSION_BUILD 0U
#define VERSION ((VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | \
                 (VERSION_PATCH << 8) | VERSION_BUILD)

#define Board_to_Android 0x00U
#define Android_to_Board 0x01U

/* Android -> 控制板。旧的本地价格、余额、欠吐和退币命令已删除。 */
#define VersionRequest 0x00U
#define DispenseStart 0x01U
#define CollectStart 0x02U
#define CollectStop 0x03U
#define Unlock 0x10U
#define CashAcceptanceApply 0x18U
#define BillReset 0x19U
/* Android 已持久化现金事实：Data3:Data4=16位现金序号。 */
#define CashEventStored 0x1AU
/* Android 已持久化关键硬件事件：Data1=原事件Code2，Data2=操作token。 */
#define BoardEventStored 0x1BU
#define HardwareStatusRequest 0x20U
#define BoardRestart 0xF0U
#define EmergencyStop 0xFFU

/* 控制板 -> Android。 */
#define VersionReport 0x00U
#define DispenseStarted 0x01U
#define DispenseProgress 0x02U
#define DispenseCompleted 0x03U
#define DispenseFailed 0x04U
#define CollectStarted 0x05U
#define CollectProgress 0x06U
#define CollectCompleted 0x07U
#define CollectFailed 0x08U
#define CashAccepted 0x10U
#define CashAcceptanceStatus 0x11U
#define CashDeviceStatus 0x12U
#define BeadStockStatus 0x20U
#define BeadLowStock 0x21U
#define BeadEmpty 0x22U
#define BeadRefilled 0x23U
#define BackendSettingsRequest 0x27U
#define AlreadyUnlock 0x0DU

#define OTA_REQUEST_MAGIC 0x424F5441U

/* 操作帧：Data1=本次操作 token，Data2:Data4=数量或真实计数。 */
#define OPERATION_DATA_TOKEN_SHIFT 24U
#define OPERATION_DATA_VALUE_MASK 0x00FFFFFFUL

/* 现金事实：Data1=介质，Data2:Data3=面额（分），Data4/Expand=16位现金序号。 */

#define MesgEvent_DispenseStarted (1u << 0)
#define MesgEvent_DispenseProgress (1u << 1)
#define MesgEvent_DispenseCompleted (1u << 2)
#define MesgEvent_DispenseFailed (1u << 3)
#define MesgEvent_CollectStarted (1u << 4)
#define MesgEvent_CollectProgress (1u << 5)
#define MesgEvent_CollectCompleted (1u << 6)
#define MesgEvent_CollectFailed (1u << 7)
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

#define ResendTrigger_Time 1000U
#define MesgDeal_Time 5000U
#define Max_Resend_Times 3U

void Mesg_Task(void);

#endif
