#ifndef __MESGTASK_H__
#define __MESGTASK_H__

#include "main.h"
#include "port_event.h"
#include "CommunicateTask.h"

#define Android_USART USART1

#define VERSION_MAJOR 1U
#define VERSION_MINOR 3U
#define VERSION_PATCH 0U
#define VERSION_BUILD 1U
#define VERSION ((VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | \
                 (VERSION_PATCH << 8) | VERSION_BUILD)

#define Board_to_Android 0x00U
#define Android_to_Board 0x01U

/* 主板发给安卓的功能码。 */
#define VersionRequest 0x00U
#define BeadMotor1Feedback 0x01U
#define CoinInput 0x02U
#define BeadMotor2Feedback 0x03U
#define BillAccepted 0x04U
#define RemainingBead 0x05U
#define BeadMotor1Timeout 0x07U
#define BeadMotor2Timeout 0x08U
#define AlreadyUnlock 0x0DU
#define Encoder 0x0FU
#define BillStatus 0x12U
#define BillCurrencyModeStatus 0x13U
#define BeadPriceStatus 0x20U
#define BeadStockStatus 0x21U
#define PurchasePendingStatus 0x22U
#define PurchaseCreditStatus 0x23U
#define BeadLowStock 0x24U
#define BeadEmpty 0x25U
#define BeadRefilled 0x26U
#define BackendSettingsRequest 0x27U

/*
 * 明确现金金额事件：Data1=现金介质，Data2:Data4=整数人民币元。
 * 介质：0=硬币，1=纸币。
 */
#define CashAcceptedAmount 0x28U
#define CashReturnedAmount 0x29U
#define CashReturnFailed 0x2AU

/* 安卓发给主板的功能码。 */
#define BeadMotor1Output 0x01U
#define BeadMotor2Output 0x02U
#define BeadMotor2Stop 0x03U
#define Unlock 0x10U
#define BillCurrencyModeSet 0x19U
#define BillEnable 0x1AU
#define BillDisable 0x1BU
#define BillReset 0x1CU
#define CoinEnable 0x1DU
#define CoinDisable 0x1EU
/* Data1=现金介质，Data2:Data4=整数人民币元。 */
#define CashReturnRequest 0x1FU
#define BeadPriceSet 0x20U
#define PurchaseStatusRequest 0x21U
/* 平台 MQTT dispense_marbles 经安卓转发，Data1:Data4 为吐珠数量。 */
#define PaidPurchaseOutput 0x27U
#define BoardRestart 0xF0U
#define StopAllDevice 0xFFU

#define CASH_MEDIUM_COIN 0x00U
#define CASH_MEDIUM_BANKNOTE 0x01U

#define BILL_CURRENCY_RMB 0x00U
#define BILL_CURRENCY_FOREIGN 0x01U

#define OTA_REQUEST_MAGIC 0x424F5441U

/* 消息事件位。 */
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
#define MesgEvent_BackendSettingsRequest (1u << 18)
#define MesgEvent_CashAcceptedAmount (1u << 19)
#define MesgEvent_CashReturnedAmount (1u << 20)
#define MesgEvent_CashReturnFailed (1u << 21)

#define ResendTrigger_Time 1000U
#define MesgDeal_Time 5000U
#define Max_Resend_Times 3U

void Mesg_Task(void);

#endif
