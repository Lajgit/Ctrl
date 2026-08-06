#ifndef __COMMUNICATETASK_H__
#define __COMMUNICATETASK_H__

/*
 * 控制板与 Android 之间的串口通信任务接口。
 * 协议帧固定为 14 字节，负责普通发送、可靠重发、出珠终态确认及接收消息处理。
 */
#include "port_communicate.h"

/* 协议帧头和帧尾，用于接收端定位完整数据帧。 */
#define Mesg_Head 0xAA
#define Mesg_Tail 0x55

/* Android 通信协议的 14 字节帧结构。 */
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

/* 发送无需可靠确认的普通消息。 */
uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode);
/* 发送需要进入重发队列并等待确认的消息。 */
uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode);
/* 发送可持久重发的出珠终态，重复调用时复用原终态帧。 */
uint8_t Comm_SendDispenseTerminal(uint16_t order_sequence, uint16_t actual_quantity, uint8_t result_code);
/* 发送一次性出珠拒绝终态，不进入持久重发流程。 */
uint8_t Comm_SendDispenseTerminalOnce(uint16_t order_sequence, uint16_t actual_quantity, uint8_t result_code);
/* Android 完成业务确认后，从重发队列删除对应出珠终态。 */
void Comm_RemoveDispenseTerminal(uint16_t order_sequence, uint8_t terminal_frame_id);

/* 周期处理待确认消息重发。 */
void Resend_Task(void);
/* 清理已超过去重窗口的接收消息 ID。 */
void MesgDeal_Task(void);
/* 初始化串口收发对象、去重列表及重发队列。 */
void Communicate_Init(void);
/* 从串口环形缓冲区提取并处理协议帧。 */
void Communicate_Task(void);

#endif
