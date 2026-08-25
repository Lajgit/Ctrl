#ifndef __PORT_EVENT_H__
#define __PORT_EVENT_H__

#include "main.h"
#include "stdbool.h"

#if EVENT_BIT_WIDTH == 8
typedef uint8_t event_bits_t;
#elif EVENT_BIT_WIDTH == 16
typedef uint16_t event_bits_t;
#elif EVENT_BIT_WIDTH == 32
typedef uint32_t event_bits_t;
#else
typedef uint32_t event_bits_t;
#endif

typedef struct
{
    event_bits_t bits;
} Event_Handle_t;

void EventGroupCreate(Event_Handle_t *event);
void EventGroupSetBits(Event_Handle_t *event, event_bits_t bits);
void EventGroupClearBits(Event_Handle_t *event, event_bits_t bits);
bool EventGroupCheckBits(Event_Handle_t *event, event_bits_t bits);


#endif