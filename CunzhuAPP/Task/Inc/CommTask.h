#ifndef __COMM_TASK_H__
#define __COMM_TASK_H__

#include "stdint.h"
#include "port_communicate.h"
#include "app_list.h"

/// 版本号
#define VERSION 20260820

/// 原球盘协议设备类型
#define Board_to_Android 0x00 // 主板->安卓
#define Android_to_Board 0x01 // 安卓->主板
#define Board_to_Ctrl 0x02    // 主板->控台（兼容旧代码）
#define Ctrl_to_Board 0x03    // 控台->主板（兼容旧代码）

/// UART转发板新增设备类型
#define Relay_to_Android 0x04 // 转发板->安卓
#define Android_to_Relay 0x05 // 安卓->转发板

/// 第二组吐珠电机协议功能码
#define Motor2Output 0x01    // 电机2按数量吐珠
#define Motor2Stop 0x02      // 停止电机2
#define Motor2Remaining 0x01 // 电机2剩余珠数
#define Motor2Timeout 0x02   // 电机2吐珠超时

/// 原球盘中需要旁路监听的功能码
#define BoardRestart 0xF0
#define StopAllDevice 0xFF
#define OTA_REQUEST_MAGIC 0x424F5441U // Data1~4 = 42 4F 54 41，ASCII“BOTA”

#define ResendTrigger_Time 1000 // 保留旧接口定义
#define MesgDeal_Time 250
#define Max_Resend_Times 3

/// 固定14字节球盘消息
typedef struct
{
    uint8_t Head;
    uint8_t ResendID;
    uint8_t ID;
    uint8_t Code1;
    uint8_t Code2;
    uint8_t Data1;
    uint8_t Data2;
    uint8_t Data3;
    uint8_t Data4;
    uint8_t ACKbyte;
    uint8_t ExpandCode;
    uint8_t CRC16_H;
    uint8_t CRC16_L;
    uint8_t Tail;
} Mesg_TypeDef;

/// 兼容旧控台文件保留的发送接口
uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode);
uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode, ListHandle_t *List);
void Resend_Task(void);
void MesgDeal_Task(void);

void CommInit(void);
void CommTask(void);

/// 中断回调由InterruptTask统一转入通信模块
void Comm_UART_RxCpltCallback(UART_HandleTypeDef *huart);
void Comm_UART_ErrorCallback(UART_HandleTypeDef *huart);
void Comm_HoolleOutput2IRQ(void);

#endif
