#include "MainTask.h"
#include "MesgTask.h"
#include "CommTask.h"
#include "port_communicate.h"
#include "port_event.h"

extern Tx_HandleTypeDef Tx;
extern Rx_HandleTypeDef Rx;

Event_Handle_t Mesg_event;
// 发送给NFC开锁消息
static uint8_t NFCUnlock_mesg[11] = {0xaa, 0x0f, 0x01, 0x01, 0x01, 0x01, 0x5c, 0x77, 0x08, 0x7f, 0x55};

void Mesg_Task(void)
{
    // 版本请求
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_VersionRequest) == true)
    {
        Comm_SendMesg_FillData(&Tx, Ctrl_to_Board, 0x00, (uint32_t)VERSION, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_VersionRequest);
    }
}