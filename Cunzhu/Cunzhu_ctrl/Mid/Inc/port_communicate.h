#ifndef __PORT_COMMUNICATE_H__
#define __PORT_COMMUNICATE_H__

#include "main.h"
#include "app_ringbuf.h"
#include "stdbool.h"
/*
 * ================================TX=========================================
 */
typedef struct
{
    UART_HandleTypeDef *huart;
    DMA_HandleTypeDef *hdma;
    uint8_t *TxBuf;
    uint16_t TxBuf_Size;
} Tx_InitTypeDef;

typedef struct
{
    UART_HandleTypeDef *huart;
    DMA_HandleTypeDef *hdma;
    uint8_t *TxBuf;
    uint16_t TxBuf_Size;
    void (*Transimit)(void *self, uint8_t *data, uint16_t len);
} Tx_HandleTypeDef;
/*
 * ================================RX=========================================
 */
/// 解包数据队列大小
#define Queue_Size 32

/// 串口接收状态
typedef enum
{
    WAIT_HEAD = 0,
    RECEIVE_DATA,
    WAIT_TAIL,
} RxFSM_State;

/// @brief 串口接收模块句柄结构体
typedef struct
{
    RxFSM_State State;
    uint8_t Frame_Head;
    uint8_t Frame_Tail;
    uint8_t Mesg_Len;
    uint8_t CurrData;
    struct
    {
        UART_HandleTypeDef *huart;
        RingBuffer_t RingBuf;
        uint8_t temp_data;
    } Handle;
    struct
    {
        uint8_t Buf[Queue_Size];
        uint8_t Buf_Size;
        uint8_t Index;
    } Queue;
    void (*Receive)(void *self, void *mesg, uint8_t mesg_len);
    bool (*Verify)(void *self, void *mesg);
    void (*Deal)(void *mesg);
} Rx_HandleTypeDef;

typedef struct
{
    UART_HandleTypeDef *huart;
    uint8_t *RingBuf;
    uint16_t RingBuf_Size;
    uint8_t Frame_Head;
    uint8_t Frame_Tail;
    uint8_t Mesg_Len;
    void (*Receive)(void *self, void *mesg, uint8_t mesg_len);
    bool (*Verify)(void *self, void *mesg);
    void (*Deal)(void *mesg);
} Rx_InitTypeDef;

void Communicate_Tx_Init(Tx_HandleTypeDef *Handle, Tx_InitTypeDef Tx_Init);

void Communicate_Rx_Init(Rx_HandleTypeDef *Handle, Rx_InitTypeDef Rx_Init);

void Rx_Receive(void *self, void *mesg, uint8_t mesg_len);
#endif