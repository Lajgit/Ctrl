#include "app_ringbuf.h"
#include <string.h>

/*
 * @brief 环形缓冲区是否为空
 * @param RB 环形缓冲区句柄指针
 * @return true: 空，false: 非空
 */
static bool RingBuffer_IsEmpty(void *RB)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    if (rb == NULL)
        return true;
    return (rb->Count == 0);
}

/*
 * @brief 环形缓冲区是否已满
 * @param RB 环形缓冲区句柄指针
 * @return true: 满，false: 非满
 */
static bool RingBuffer_IsFull(void *RB)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    if (rb == NULL)
        return true;
    return (rb->Count == rb->Size);
}

/*
 * @brief 获取环形缓冲区剩余空间
 * @param RB 环形缓冲区句柄指针
 * @return 环形缓冲区剩余空间
 */
static uint16_t RingBuffer_GetSpace(void *RB)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    return rb->Size - rb->Count;
}

/*
 * @brief 获取环形缓冲区数据长度
 * @param RB 环形缓冲区句柄指针
 * @return 环形缓冲区数据长度
 */
static uint16_t RingBuffer_GetDatalen(void *RB)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    if (rb == NULL)
        return 0;
    if (rb->Write_index >= rb->Read_index)
        rb->Count = rb->Write_index - rb->Read_index;
    else
        rb->Count = rb->Write_index + rb->Size - rb->Read_index;
    return rb->Count;
}

/*
 *===================读写操作=====================
 */

/*
 * @brief 向环形缓冲区写入一个字节
 * @param RB 环形缓冲区句柄指针
 * @param data 要写入的数据字节
 * @return true: 写入成功，false: 写入失败（如缓冲区已满）
 */
static bool RingBuffer_WriteByte(void *RB, uint8_t data)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    if (rb == NULL)
        return false;
    if (RingBuffer_IsFull(rb))
        return false;
    rb->Buffer[rb->Write_index] = data;
    rb->Write_index = (rb->Write_index + 1) % rb->Size;
    rb->Count++;
    return true;
}

/*
 * @brief 从环形缓冲区读取一个字节
 * @param RB 环形缓冲区句柄指针
 * @param data 读取到的数据字节
 * @return true: 读取成功，false: 读取失败（如缓冲区已空）
 */
static bool RingBuffer_ReadByte(void *RB, uint8_t *data)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    uint32_t primask;

    if (rb == NULL || data == NULL)
        return false;

    /*
     * RingBuffer_WriteByte() 会在 UART 接收中断中修改
     * Write_index 和 Count。
     *
     * 读取端必须保证“判空、读取、更新 Read_index、Count--”
     * 是一个不可被 UART 中断打断的完整操作。
     */
    primask = __get_PRIMASK();
    __disable_irq();

    if (rb->Count == 0)
    {
        __set_PRIMASK(primask);
        return false;
    }

    *data = rb->Buffer[rb->Read_index];
    rb->Read_index = (rb->Read_index + 1U) % rb->Size;
    rb->Count--;

    __set_PRIMASK(primask);

    return true;
}
/*
 * @brief 向环形缓冲区写入多个字节
 * @param RB 环形缓冲区句柄指针
 * @param data 要写入的数据字节数组指针
 * @param len 要写入的数据字节数
 * @return 实际写入的数据字节数
 */
static uint16_t RingBuffer_Write(void *RB, uint8_t *data, uint16_t len)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    uint16_t space = RingBuffer_GetSpace(RB);
    if (len > space)
        len = space;
    uint16_t W_FirstPart = rb->Size - rb->Write_index;
    if (W_FirstPart >= len)
    {
        memcpy(&rb->Buffer[rb->Write_index], data, len);
        rb->Write_index = (rb->Write_index + len) % rb->Size;
    }
    else
    {
        memcpy(&rb->Buffer[rb->Write_index], data, W_FirstPart);
        memcpy(&rb->Buffer[0], &data[W_FirstPart], len - W_FirstPart);
        rb->Write_index = (len - W_FirstPart) % rb->Size;
    }
    rb->Count += len;
    return len;
}

/*
 * @brief 从环形缓冲区读取多个字节
 * @param RB 环形缓冲区句柄指针
 * @param data 读取到的数据字节数组指针
 * @param len 要读取的数据字节数
 * @return 实际读取的数据字节数
 */
static uint16_t RingBuffer_Read(void *RB, uint8_t *data, uint16_t len)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    uint16_t datalen = RingBuffer_GetDatalen(RB);
    if (len > datalen)
        len = datalen;
    uint16_t R_FirstPart = rb->Size - rb->Read_index;
    if (R_FirstPart >= len)
    {
        memcpy(data, &rb->Buffer[rb->Read_index], len);
        rb->Read_index = (rb->Read_index + len) % rb->Size;
    }
    else
    {
        memcpy(data, &rb->Buffer[rb->Read_index], R_FirstPart);
        memcpy(&data[R_FirstPart], &rb->Buffer[0], len - R_FirstPart);
        rb->Read_index = (len - R_FirstPart) % rb->Size;
    }
    rb->Count -= len;
    return len;
}

/*
 * @brief 清空环形缓冲区
 * @param RB 环形缓冲区句柄指针
 */
void RingBuffer_Clear(void *RB)
{
    RingBuffer_t *rb = (RingBuffer_t *)RB;
    if (rb == NULL)
        return;
    rb->Read_index = 0;
    rb->Write_index = 0;
    rb->Count = 0;
}

/*
 *===================初始化操作=====================
 */

/*
 * @brief 初始化环形缓冲区
 * @param rb 环形缓冲区句柄指针
 * @param Buffer 环形缓冲区数据缓冲区指针
 * @param Size 环形缓冲区数据缓冲区大小
 */
void RingBuffer_Init(RingBuffer_t *rb, uint8_t *Buffer, uint16_t Size)
{
    if (rb == NULL)
        return;
    rb->Buffer = Buffer;
    rb->Size = Size;
    rb->Read_index = 0;
    rb->Write_index = 0;
    rb->f_IsEmpty = RingBuffer_IsEmpty;
    rb->f_IsFull = RingBuffer_IsFull;
    rb->f_WriteByte = RingBuffer_WriteByte;
    rb->f_ReadByte = RingBuffer_ReadByte;
    rb->f_Write = RingBuffer_Write;
    rb->f_Read = RingBuffer_Read;
    rb->f_GetSpace = RingBuffer_GetSpace;
    rb->f_GetDatalen = RingBuffer_GetDatalen;
    rb->f_Clear = RingBuffer_Clear;
    rb->Count = 0;
}