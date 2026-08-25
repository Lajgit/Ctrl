#ifndef __MESGTASK_H__
#define __MESGTASK_H__

#include "port_event.h"

#define MesgEvent_HoolleInput (1u << 0)          // 投入珠子
#define MesgEvent_CoinInput (1u << 1)            // 投入硬币
#define MesgEvent_RemainingHoolle (1u << 2)      // 发送剩余珠子
#define MesgEvent_HoolleOutputTimeout (1u << 3)  // 发送吐珠超时
#define MesgEvent_CardOutputTimeout (1u << 4)    // 发送吐卡超时
#define MesgEvent_NFCEnterSetting (1u << 5)      // 发送NFC进入设置
#define MesgEvent_UnboundUnlockCard (1u << 6)    // 发送未绑定开锁卡
#define MesgEvent_UnboundBackStageCard (1u << 7) // 发送未绑定后台卡
#define MesgEvent_BoundUnlockCard (1u << 8)      // 发送已绑定开锁卡
#define MesgEvent_BoundBackStageCard (1u << 9)   // 发送已绑定后台卡
#define MesgEvent_BoundAllCards (1u << 10)       // 发送已绑定所有卡，即发送绑卡ID
#define MesgEvent_Unlock (1u << 11)              // 发送开锁
#define MesgEvent_ButtonEnterSetting (1u << 12)  // 发送按键进入设置
#define MesgEvent_NFCUnlock (1u << 13)           // 发送NFC开锁
#define MesgEvent_VersionRequest (1u << 14)      // 发送版本请求
#define MesgEvent_CardOutputOnce (1u << 15)      // 吐卡一次
#define MesgEvent_CardOutputFinish (1u << 16)    // 吐卡完成
#define MesgEvent_HoleDetected (1u << 17)         // 检测到洞

void Mesg_Task(void);

#endif
