#include "port_communicate.h"
#include <string.h>

/*
 * ================================TX=========================================
 */

/*
 * @brief 发送数据函数
 * @param handle Tx_HandleTypeDef 结构体指针
 * @param data 待发送数据指针
 * @param len 待发送数据长度
 */
static void Communicate_Tx_Transmit(void *handle, uint8_t *data, uint16_t len)
{
    Tx_HandleTypeDef *Handle = (Tx_HandleTypeDef *)handle;
    if (Handle->huart == NULL || data == NULL || len == 0)
        return;
    HAL_UART_Transmit(Handle->huart, data, len, 100);
}

/*
 * @brief 初始化串口发送模块
 * @param handle Tx_HandleTypeDef 句柄指针
 * @param Tx_Init Tx_InitTypeDef 初始化参数
 */
void Communicate_Tx_Init(Tx_HandleTypeDef *Handle, Tx_InitTypeDef Tx_Init)
{
    Handle->huart = Tx_Init.huart;
    Handle->hdma = Tx_Init.hdma;
    Handle->TxBuf = Tx_Init.TxBuf;
    Handle->TxBuf_Size = Tx_Init.TxBuf_Size;
    Handle->Transimit = Communicate_Tx_Transmit;

    /* UART gState 由 HAL_UART_Init/Transmit/IRQ 维护，不在业务层强制改写。 */
}

/*
 * ================================RX=========================================
 */

void Rx_Receive(void *self, void *mesg, uint8_t mesg_len)
{
    Rx_HandleTypeDef *rx = (Rx_HandleTypeDef *)self;

    if (rx == NULL || mesg == NULL || mesg_len == 0 || mesg_len > rx->Queue.Buf_Size)
        return;

    while (rx->Handle.RingBuf.f_IsEmpty(&rx->Handle.RingBuf) == false)
    {
        /// 从环形缓冲区中读取一个字节，并根据当前状态进行处理
        if (rx->Handle.RingBuf.f_ReadByte(&rx->Handle.RingBuf, &rx->CurrData) == true)
        {
            switch (rx->State)
            {
            /// 等待帧头阶段，只有在接收到正确的帧头时才开始接收数据
            case WAIT_HEAD:
                if (rx->CurrData == rx->Frame_Head)
                {
                    rx->Queue.Index = 0;
                    rx->Queue.Buf[rx->Queue.Index] = rx->CurrData;
                    rx->State = RECEIVE_DATA;
                    rx->Queue.Index++;
                }
                break;
            /// 接收固定长度数据帧
            case RECEIVE_DATA:
                // 索引超过固定帧长或接收队列容量时，立即丢弃当前帧
                if (rx->Queue.Index >= mesg_len || rx->Queue.Index >= rx->Queue.Buf_Size)
                {
                    rx->State = WAIT_HEAD;
                    rx->Queue.Index = 0;
                    break;
                }

                rx->Queue.Buf[rx->Queue.Index] = rx->CurrData;

                // 只允许固定帧长的最后一个字节作为帧尾
                if (rx->Queue.Index == mesg_len - 1U)
                {
                    rx->State = WAIT_HEAD;
                    rx->Queue.Index = 0;

                    if (rx->CurrData != rx->Frame_Tail)
                        break;

                    memcpy(mesg, rx->Queue.Buf, mesg_len);
                    /// 解包并处理
                    if (rx->Verify != NULL)
                    {
                        if (rx->Verify(rx, mesg) == true)
                            if (rx->Deal != NULL)
                                rx->Deal(mesg);
                    }
                    else
                    {
                        if (rx->Deal != NULL)
                            rx->Deal(mesg);
                    }
                    break;
                }

                rx->Queue.Index++;
                break;
            }
        }
    }
}

/*
 * @brief 初始化串口接收模块
 * @param handle Rx_HandleTypeDef 句柄指针
 * @param Rx_Init Rx_InitTypeDef 初始化参数
 */
void Communicate_Rx_Init(Rx_HandleTypeDef *rx, Rx_InitTypeDef Rx_Init)
{
    rx->Handle.huart = Rx_Init.huart;
    RingBuffer_Init(&rx->Handle.RingBuf, Rx_Init.RingBuf, Rx_Init.RingBuf_Size);
    rx->CurrData = 0;
    rx->State = WAIT_HEAD;
    rx->Queue.Buf_Size = Queue_Size;
    rx->Frame_Head = Rx_Init.Frame_Head;
    rx->Frame_Tail = Rx_Init.Frame_Tail;
    rx->Mesg_Len = Rx_Init.Mesg_Len;
    if (Rx_Init.Receive == NULL)
        rx->Receive = Rx_Receive;
    else
        rx->Receive = Rx_Init.Receive;
    rx->Verify = Rx_Init.Verify;
    rx->Deal = Rx_Init.Deal;

    HAL_UART_Receive_IT(rx->Handle.huart, &rx->Handle.temp_data, 1);
}