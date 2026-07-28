#include "MesgTask.h"
#include "CtrlTask.h"

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
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadMotor1Feedback))
    {
        Comm_SendMesg_FillData(&Tx1, Board_to_Android, BeadMotor1Feedback, 1U, 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadMotor1Feedback);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadMotor2Feedback))
    {
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
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BillCurrencyModeStatus,
                               BillAcceptor_CurrencyMode,
                               BillAcceptor_CurrencyMode);
        EventGroupClearBits(&Mesg_event, MesgEvent_BillCurrencyMode);
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
