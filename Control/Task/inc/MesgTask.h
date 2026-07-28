#ifndef __MESGTASK_H__
#define __MESGTASK_H__

#include "main.h"
#include "port_event.h"
#include "CommunicateTask.h"

#define Android_USART USART1

//#define VERSION 0x01000100U // V1.0.1

/* ========== 版本号分段定义，升级版本仅需修改此处数值 ========== */
#define VERSION_MAJOR    1U    // 主版本号：重大架构迭代时递增
#define VERSION_MINOR    0U    // 次版本号：功能新增时递增
#define VERSION_PATCH    0U    // 修订版本号：Bug修复时递增
#define VERSION_BUILD    0U    // 构建版本号：编译序号/保留位，按需使用

/* ========== 自动组合为32位无符号版本号（格式：0xMM NN PP BB） ==========
   高字节 → 低字节：主版本(M) → 次版本(N) → 修订版(P) → 构建版(B)
   原数值 0x01000100U 等价于 1.0.1.0
========================================================================= */
#define VERSION  ((VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | \
                  (VERSION_PATCH << 8)  | VERSION_BUILD)

#define Board_to_Android 0x00 // 主板->安卓
#define Android_to_Board 0x01 // 安卓->主板
#define Board_to_Ctrl 0x02    // 主板->控制器
#define Ctrl_to_Board 0x03    // 控制器->主板

// 球盘发送给安卓的消息功能码
#define VersionRequest 0x00
#define HoolleInput 0x01         // 投入弹珠
#define CoinInput 0x02           // 投入硬币
#define Button 0x03              // 拍拍按键
#define SettingButton 0x04       // 设置按键
#define RemainingHoolle 0x05     // 剩余珠子数
#define WinOrLoss 0x06           // 游戏结果
#define HoolleOutputTimeOut 0x07 // 珠子输出超时
#define CardOutputTimeOut 0x08   // 卡片输出超时
#define RequestDigitalTube 0x09     // NFC进入后台
#define UnlockCardStatus 0x0A    // 解锁卡片状态
#define BackStageCardStatus 0x0B // 后台卡片状态
#define CardID 0x0C              // 绑定卡片ID
#define AlreadyUnlock 0x0D       // 已开锁
#define LightEye 0x0E            // 光眼
#define Encoder 0x0F             // 编码器
#define ChannelRequest 0x10      // 击中通道位置反馈
#define ClearRemainMesg 0x11     // 清除剩余数量窗口消息
#define IntoHigherStage 0x12     // 进入高级后台

// 球盘接收到安卓的消息功能码
#define HoolleOutput 0x01           // 珠子输出
#define CardOutput 0x02             // 卡片输出
#define ValveTrigger 0x03           // 触发电磁阀
#define BoardLightness 0x04         // 球盘亮度
#define LightBoardLightness 0x05    // 灯板亮度
#define LightBeltLightness 0x06     // 灯带亮度
#define SceneChange 0x07            // 切换场景
#define WinChannel 0x08             // 中奖通道
#define LittleGameResult 0x09       // 小游戏输赢结果
#define ButtonLight 0x0A            // 按键灯
#define OutputAllHoolle 0x0B        // 清珠
#define OutputRemainingItem 0x0C    // 吐出剩余物品
#define ResumeDefultSetting 0x0D    // 恢复默认设置
#define SaveSetting 0x0E            // 保存设置
#define HoleValveTrigger 0x0F       // 洞内电磁阀触发
#define Unlock 0x10                 // 已开锁
#define ResumeBoundCard 0x11        // 重新绑卡
#define WirelessMasterSetting 0x12  // 无线通信主从设置
#define WirelessChannelSetting 0x13 // 无线通信信道设置
#define ServoControl 0x14           // 舵机控制
#define LightControl 0x15           // 灯控制
#define DigitalTubeData 0x16        // 数字数据
#define CtrlTubeLight 0x17        // 控台按键灯
#define CtrlLightness 0x18     // 控台亮度
#define r_ServoReset 0x20              // 舵机归零
#define BoardRestart 0xF0            // 球盘重启；数据为PANT时进入Bootloader
#define StopAllDevice 0xFF           // 停止所有输出

#define OTA_REQUEST_MAGIC 0x424F5441U // Data1~4 = 42 4F 54 41，ASCII“BOTA”

extern Mesg_TypeDef Mesg_HoolleInput;
extern Mesg_TypeDef Mesg_CoinInput;

extern Mesg_TypeDef Mesg_Button1Press;
extern Mesg_TypeDef Mesg_Button2Press;
extern Mesg_TypeDef Mesg_Button3Press;
extern Mesg_TypeDef Mesg_Button4Press;
extern Mesg_TypeDef Mesg_Button5Press;
extern Mesg_TypeDef Mesg_Button6Press;

extern Mesg_TypeDef Mesg_Button1Hold;
extern Mesg_TypeDef Mesg_Button2Hold;
extern Mesg_TypeDef Mesg_Button3Hold;
extern Mesg_TypeDef Mesg_Button4Hold;
extern Mesg_TypeDef Mesg_Button5Hold;
extern Mesg_TypeDef Mesg_Button6Hold;

extern Mesg_TypeDef Mesg_Encoder_L;
extern Mesg_TypeDef Mesg_Encoder_R;

extern Mesg_TypeDef Mesg_ButtonEnterBackstage;
extern Mesg_TypeDef Mesg_NFCEnterSetting;
extern Mesg_TypeDef Mesg_UnboundUnlockCard;
extern Mesg_TypeDef Mesg_BoundUnlockCard;
extern Mesg_TypeDef Mesg_UnboundBackStageCard;
extern Mesg_TypeDef Mesg_BoundBackStageCard;
extern Mesg_TypeDef Mesg_AlreadyUnlock;

extern Mesg_TypeDef Mesg_Victory;
extern Mesg_TypeDef Mesg_Defeat;

extern uint8_t handclasp_transmit[9];
extern uint8_t comfirm_transmit[9];
extern uint8_t uid_transmit[19];
extern uint8_t NFCUnlock_mesg[11];

/*
 * 消息事件定义
 */
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
#define MesgEvent_RemainingCard (1u << 13)      // 剩余卡片

#define MesgEvent_DigitalTubeRefresh (1u << 13)
#define MesgEvent_VersionRequest (1u << 14)
#define MesgEvent_ClearRemainMesg (1u << 15)
/*
 * 消息处理参数
 */

#define ResendTrigger_Time 1000 // 重新发送触发时间ms
#define MesgDeal_Time 250       // 消息处理时间
#define Max_Resend_Times 3      // 最大重新发送次数

void Mesg_Task(void);

#endif
