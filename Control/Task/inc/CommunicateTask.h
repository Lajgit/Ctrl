#ifndef __COMMUNICATETASK_H__
#define __COMMUNICATETASK_H__

#include "port_communicate.h"
#include "app_list.h"


#define Mesg_Head 0xAA
#define Mesg_Tail 0x55

/// 接收消息结构体(新球盘)
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

///
uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode);
uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode, ListHandle_t *List);

void Resend_Task(void);
void MesgDeal_Task(void);
///
void Communicate_Init(void);
void Communicate_Task(void);

#endif