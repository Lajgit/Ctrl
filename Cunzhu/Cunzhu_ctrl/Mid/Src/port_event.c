#include "port_event.h"

void EventGroupCreate(Event_Handle_t *event)
{
    event->bits = 0;
}
void EventGroupSetBits(Event_Handle_t *event, event_bits_t bits)
{
    event->bits |= bits;
}
void EventGroupClearBits(Event_Handle_t *event, event_bits_t bits)
{
    event->bits &= ~bits;
}
bool EventGroupCheckBits(Event_Handle_t *event, event_bits_t bits)
{
    return (event->bits & bits) == bits;
}