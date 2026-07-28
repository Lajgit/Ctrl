#include "MesgTask.h"
#include "CtrlTask.h"
#include "CommunicateTask.h"
#include "port_event.h"
// 投珠
Mesg_TypeDef Mesg_HoolleInput = {0x00, 0x00, Board_to_Android, HoolleInput, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
// 投币
Mesg_TypeDef Mesg_CoinInput = {0x00, 0x00, Board_to_Android, CoinInput, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
// 按键短按
Mesg_TypeDef Mesg_Button1Press = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_Button2Press = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_Button3Press = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_Button4Press = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_Button5Press = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_Button6Press = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x0000};
// 按键长按
Mesg_TypeDef Mesg_Button1Hold = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x01, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_Button2Hold = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_Button3Hold = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x03, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_Button4Hold = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x04, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_Button5Hold = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x05, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_Button6Hold = {0x00, 0x00, Board_to_Android, Button, 0x00, 0x00, 0x00, 0x06, 0x00, 0x02, 0x0000};
// 蓝色光眼
Mesg_TypeDef Mesg_LightEye_B1 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B2 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B3 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B4 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B5 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B6 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B7 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_LightEye_B8 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x0000};
// 黄色光眼
Mesg_TypeDef Mesg_LightEye_Y1 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y2 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y3 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y4 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y5 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y6 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y7 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x07, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_LightEye_Y8 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x08, 0x00, 0x01, 0x0000};
// 粉色光眼
Mesg_TypeDef Mesg_LightEye_P1 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x01, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_LightEye_P2 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x0000};
Mesg_TypeDef Mesg_LightEye_P3 = {0x00, 0x00, Board_to_Android, LightEye, 0x00, 0x00, 0x00, 0x03, 0x00, 0x02, 0x0000};
// 编码器
Mesg_TypeDef Mesg_Encoder_L = {0x00, 0x00, Board_to_Android, Encoder, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_Encoder_R = {0x00, 0x00, Board_to_Android, Encoder, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0000};

// 进入设置
Mesg_TypeDef Mesg_ButtonEnterBackstage = {0x00, 0x00, Board_to_Android, SettingButton, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x0000};
// 未绑卡
Mesg_TypeDef Mesg_UnboundUnlockCard = {0x00, 0x00, Board_to_Android, UnlockCardStatus, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
Mesg_TypeDef Mesg_UnboundBackStageCard = {0x00, 0x00, Board_to_Android, BackStageCardStatus, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
// 已绑卡
Mesg_TypeDef Mesg_BoundUnlockCard = {0x00, 0x00, Board_to_Android, UnlockCardStatus, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_BoundBackStageCard = {0x00, 0x00, Board_to_Android, BackStageCardStatus, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0000};
// 已解锁
Mesg_TypeDef Mesg_AlreadyUnlock = {0x00, 0x00, Board_to_Android, AlreadyUnlock, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
// 中奖
Mesg_TypeDef Mesg_Victory = {0x00, 0x00, Board_to_Android, WinOrLoss, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0000};
Mesg_TypeDef Mesg_Defeat = {0x00, 0x00, Board_to_Android, WinOrLoss, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0000};
// 加密消息
uint8_t handclasp_transmit[9] = {0xaa, 0xaa, 0xcc, 0xcc, 0x22, 0xe0, 0xe8, 0x12, 0x55};
uint8_t comfirm_transmit[9] = {0xAA, 0xFF, 0xAA, 0xAA, 0xDF, 0xE1, 0x0C, 0x05, 0x55};
uint8_t uid_transmit[19];
// NFC解锁消息
uint8_t NFCUnlock_mesg[11] = {0xaa, 0x0f, 0x01, 0x01, 0x01, 0x01, 0x5c, 0x77, 0x08, 0x7f, 0x55};

Event_Handle_t Mesg_event;
extern Motor_Hoolle Motor_Hoolle1;
extern Motor_Card Card;
extern Tx_HandleTypeDef Tx1;
extern Rx_HandleTypeDef Rx1;
extern ListHandle_t ResendList, DealList;

void Mesg_Task(void)
{
    // 按键进入设置
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_ButtonEnterSetting))
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, SettingButton, (uint32_t)0x03, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_ButtonEnterSetting);
    }
    // 开锁
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_Unlock) == true)
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, AlreadyUnlock, (uint32_t)0x00, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_Unlock);
    }
    // 投珠
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_HoolleInput) == true)
    {
        // Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, HoolleInput, 0x00, 0x00, 0x00, &ResendList);
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, HoolleInput, 0x00, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_HoolleInput);
    }
    // 投币
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CoinInput) == true)
    {
        // Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, CoinInput, 0x00, 0x00, 0x00, &ResendList);
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, CoinInput, 0x00, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_CoinInput);
    }
    // 吐珠超时
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_HoolleOutputTimeout))
    {
        // Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, HoolleOutputTimeOut, (uint32_t)Motor_Hoolle1.Hoolle_num, 0x00, 0x00, &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_HoolleOutputTimeout);
    }
    // 吐卡超时
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CardOutputTimeout))
    {
        Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, CardOutputTimeOut, (uint32_t)Card.Card_num, 0x00, &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_CardOutputTimeout);
    }
    // 剩余珠子数
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_RemainingHoolle) == true)
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, RemainingHoolle, (uint32_t)Motor_Hoolle1.Hoolle_num, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_RemainingHoolle);
    }
    // 剩余卡数
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_RemainingCard) == true)
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, RemainingHoolle, (uint32_t)Card.Card_num, 0x01);
        EventGroupClearBits(&Mesg_event, MesgEvent_RemainingCard);
    }

    // 版本请求
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_VersionRequest) == true)
    {
        Comm_SendMesg_FillData_withResend(&Tx1, Board_to_Android, VersionRequest, (uint32_t)VERSION, 0x00, &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_VersionRequest);
    }
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_ClearRemainMesg) == true)
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, ClearRemainMesg, 0x00, 0x00);
        EventGroupClearBits(&Mesg_event, MesgEvent_ClearRemainMesg);
    }
    Resend_Task();
    MesgDeal_Task();
}