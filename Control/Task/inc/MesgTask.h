#ifndef __MESGTASK_H__
#define __MESGTASK_H__

#include "main.h"
#include "port_event.h"
#include "CommunicateTask.h"

#define Android_USART USART1

#define VERSION_MAJOR 1U
#define VERSION_MINOR 1U
#define VERSION_PATCH 1U
#define VERSION_BUILD 0U
#define VERSION ((VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | \
                 (VERSION_PATCH << 8) | VERSION_BUILD)

#define Board_to_Android 0x00U
#define Android_to_Board 0x01U

/* 主板发给安卓的功能码 */
#define VersionRequest 0x00U
#define BeadMotor1Feedback 0x01U   /* 吐珠电机 PD3 光眼反馈 */
#define CoinInput 0x02U
#define BeadMotor2Feedback 0x03U   /* 存珠电机 PD4 光眼反馈 */
#define BillAccepted 0x04U
#define RemainingBead 0x05U
#define BeadMotor1Timeout 0x07U    /* 吐珠电机超时 */
#define BeadMotor2Timeout 0x08U    /* 存珠电机超时 */
#define AlreadyUnlock 0x0DU
#define Encoder 0x0FU
#define BillStatus 0x12U
#define BillCurrencyModeStatus 0x13U

/* 固件购买、库存和补珠状态。 */
#define BeadPriceStatus 0x20U
#define BeadStockStatus 0x21U
#define PurchasePendingStatus 0x22U
#define PurchaseCreditStatus 0x23U
#define BeadLowStock 0x24U
#define BeadEmpty 0x25U
#define BeadRefilled 0x26U

/* 安卓发给主板的功能码 */
#define BeadMotor1Output 0x01U     /* 启动吐珠电机 */
#define BeadMotor2Output 0x02U     /* 启动存珠电机 */
#define Unlock 0x10U
#define BillCurrencyModeSet 0x19U
#define BillEnable 0x1AU
#define BillDisable 0x1BU
#define BillReset 0x1CU
#define BeadPriceSet 0x20U         /* Data1:Data4 为单颗价格，单位：人民币分 */
#define PurchaseStatusRequest 0x21U
#define BoardRestart 0xF0U
#define StopAllDevice 0xFFU

#define BILL_CURRENCY_RMB 0x00U
#define BILL_CURRENCY_FOREIGN 0x01U

#define OTA_REQUEST_MAGIC 0x424F5441U

/* 消息事件位 */
#define MesgEvent_BeadMotor1Feedback (1u << 0)
#define MesgEvent_CoinInput (1u << 1)
#define MesgEvent_BeadMotor2Feedback (1u << 2)
#define MesgEvent_BillAccepted (1u << 3)
#define MesgEvent_BeadMotor1Timeout (1u << 4)
#define MesgEvent_BeadMotor2Timeout (1u << 5)
#define MesgEvent_RemainingBead (1u << 6)
#define MesgEvent_Unlock (1u << 7)
#define MesgEvent_VersionRequest (1u << 8)
#define MesgEvent_BillStatus (1u << 9)
#define MesgEvent_BillCurrencyMode (1u << 10)
#define MesgEvent_BeadPriceStatus (1u << 11)
#define MesgEvent_BeadStockStatus (1u << 12)
#define MesgEvent_PurchasePendingStatus (1u << 13)
#define MesgEvent_PurchaseCreditStatus (1u << 14)
#define MesgEvent_BeadLowStock (1u << 15)
#define MesgEvent_BeadEmpty (1u << 16)
#define MesgEvent_BeadRefilled (1u << 17)

#define ResendTrigger_Time 1000U
/* 覆盖安卓 1 秒重发和最多 3 次重发，防止同一 ID 重复执行电机命令。 */
#define MesgDeal_Time 5000U
#define Max_Resend_Times 3U

void Mesg_Task(void);

#endif
