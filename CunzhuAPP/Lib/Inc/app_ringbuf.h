#ifndef __APP_RINGBUF_H__
#define __APP_RINGBUF_H__

#include "main.h"
#include "stdint.h"
#include "stdbool.h"

typedef struct
{
    uint8_t *Buffer;
    uint16_t Size;
    uint16_t Read_index;
    uint16_t Write_index;
    uint16_t Count;
    bool (*f_IsEmpty)(void *self);
    bool (*f_IsFull)(void *self);
    bool (*f_WriteByte)(void *self, uint8_t data);
    bool (*f_ReadByte)(void *self, uint8_t *data);
    uint16_t (*f_Write)(void *self, uint8_t *data, uint16_t len);
    uint16_t (*f_Read)(void *self, uint8_t *data, uint16_t len);
    uint16_t (*f_GetSpace)(void *self);
    uint16_t (*f_GetDatalen)(void *self);
    void (*f_Clear)(void *self);
} RingBuffer_t;

void RingBuffer_Init(RingBuffer_t *rb, uint8_t *buffer, uint16_t size);

#endif
