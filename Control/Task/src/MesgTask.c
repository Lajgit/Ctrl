#include "MesgTask.h"
#include "CtrlTask.h"
#include "BackendKeyTask.h"

Event_Handle_t Mesg_event;

extern BeadMotor_t BeadMotor1;
extern BeadMotor_t BeadMotor2;
extern Tx_HandleTypeDef Tx1;
extern ListHandle_t ResendList;
extern uint8_t BillAcceptor_LastType;
extern uint8_t BillAcceptor_LastStatus;
extern uint8_t BillAcceptor_CurrencyMode;

static uint32_t MakeRemainData(void)
{
    /* Data1:Data2 为吐珠剩余数量，Data3:Data4 为存珠剩余数量。 */
    return ((uint32_t)BeadMotor1.remain_num << 16U) |
           (uint32_t)BeadMotor2.remain_num;
}

static uint8_t MakeBillIndex(uint8_t bill_type)
{
    if ((bill_type >= 0x40U) && (bill_type <= 0x4FU))
    {
        return (uint8_t)(bill_type - 0x3FU);
    }

    return 0U;
}

static uint32_t MakeBillData(void)
{
    return ((uint32_t)BillAcceptor_CurrencyMode << 24U) |
           ((uint32_t)BillAcceptor_LastType << 16U) |
           ((uint32_t)MakeBillIndex(BillAcceptor_LastType) << 8U) |
           (uint32_t)BillAcceptor_LastStatus;
}

void Mesg_Task(void)
{
    /* K2（SettingButton/PD11）短按后请求安卓进入后台设置。 */
    BackendKey_Task();

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadMotor1Feedback))
    {
        /* PD3：吐珠电机光眼反馈。 */
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, BeadMotor1Feedback, 1U, 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadMotor1Feedback);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadMotor2Feedback))
    {
        /* PD4：存珠电机光眼反馈。 */
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, BeadMotor2Feedback, 1U, 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadMotor2Feedback);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CoinInput))
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, CoinInput, 1U, 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_CoinInput);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BillAccepted))
    {
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BillAccepted,
                                          MakeBillData(),
                                          BillAcceptor_CurrencyMode,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BillAccepted);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BillStatus))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BillStatus,
                               MakeBillData(),
                               BillAcceptor_CurrencyMode);
        EventGroupClearBits(&Mesg_event, MesgEvent_BillStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BillCurrencyMode))
    {
        /* 币种模式只放在 Data4，ExpandCode 保持 0。 */
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BillCurrencyModeStatus,
                               BillAcceptor_CurrencyMode,
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BillCurrencyMode);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadPriceStatus))
    {
        /* Data1:Data4 为单颗价格（分），ExpandCode 为设置结果。 */
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BeadPriceStatus,
                               Purchase_GetBeadPriceFen(),
                               Purchase_GetPriceSetResult());
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadPriceStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadStockStatus))
    {
        /* 每成功吐出一颗后同步最新库存。 */
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BeadStockStatus,
                               Purchase_GetBeadStock(),
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadStockStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_PurchasePendingStatus))
    {
        /* 已收款但尚未吐出的珠子数量。 */
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               PurchasePendingStatus,
                               Purchase_GetPendingBeads(),
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_PurchasePendingStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_PurchaseCreditStatus))
    {
        /* 尚不足以购买一颗珠子的累计人民币余额，单位：分。 */
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               PurchaseCreditStatus,
                               Purchase_GetCreditFen(),
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_PurchaseCreditStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadLowStock))
    {
        /* 库存首次降到 3000 或以下，要求安卓确认接收。 */
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BeadLowStock,
                                          Purchase_GetBeadStock(),
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadLowStock);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadEmpty))
    {
        /* 无珠时 Data1:Data4 保存仍需补吐的珠子数量。 */
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BeadEmpty,
                                          Purchase_GetPendingBeads(),
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadEmpty);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadRefilled))
    {
        /* K1 补珠确认后上报新库存，安卓据此恢复购买界面。 */
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BeadRefilled,
                                          Purchase_GetBeadStock(),
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadRefilled);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BackendSettingsRequest))
    {
        /* K2 请求进入后台设置，需要安卓原样确认。 */
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BackendSettingsRequest,
                                          1U,
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BackendSettingsRequest);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_RemainingBead))
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, RemainingBead, MakeRemainData(), 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_RemainingBead);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadMotor1Timeout))
    {
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BeadMotor1Timeout,
                                          BeadMotor1.remain_num,
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadMotor1Timeout);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadMotor2Timeout))
    {
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          BeadMotor2Timeout,
                                          BeadMotor2.remain_num,
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadMotor2Timeout);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_Unlock))
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, AlreadyUnlock, 0U, 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_Unlock);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_VersionRequest))
    {
        Comm_SendMesg_FillData_withResend(&Tx1,
                                          Board_to_Android,
                                          VersionRequest,
                                          VERSION,
                                          0x00U,
                                          &ResendList);
        EventGroupClearBits(&Mesg_event, MesgEvent_VersionRequest);
    }

    Resend_Task();
    MesgDeal_Task();
}
