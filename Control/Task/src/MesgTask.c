#include "MesgTask.h"
#include "CtrlTask.h"
#include "BackendKeyTask.h"
#include "KeyTask.h"

Event_Handle_t Mesg_event;

extern Tx_HandleTypeDef Tx1;

static uint32_t MakeOperationData(const HardwareOperation_t *operation,
                                  uint32_t value)
{
    uint8_t token = operation == NULL ? 0U : operation->token;
    return ((uint32_t)token << OPERATION_DATA_TOKEN_SHIFT) |
           (value & OPERATION_DATA_VALUE_MASK);
}

static uint32_t MakeCashAcceptedData(void)
{
    uint16_t amount_fen = CashEvent_GetPendingAmountFen();
    uint16_t sequence = CashEvent_GetPendingSequence();
    return ((uint32_t)CashEvent_GetPendingMedium() << 24U) |
           ((uint32_t)amount_fen << 8U) |
           ((uint32_t)sequence >> 8U);
}

static uint32_t MakeCashAcceptanceData(void)
{
    return ((uint32_t)CashAcceptance_GetEnableMask() << 24U) |
           (CashAcceptance_GetConfigVersion() & 0x00FFFFFFUL);
}

static bool SendMesgWithResend(uint8_t code_2, uint32_t data, uint8_t expandCode)
{
    return Comm_SendMesg_FillData_withResend(&Tx1,
                                             Board_to_Android,
                                             code_2,
                                             data,
                                             expandCode) != 0U;
}

void Mesg_Task(void)
{
    const HardwareOperation_t *operation;

    /* K2（PD13）短按后请求 Android 进入后台设置。 */
    BackendKey_Task();

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_DispenseStarted))
    {
        operation = Hardware_GetDispenseReport();
        if (SendMesgWithResend(DispenseStarted,
                               MakeOperationData(operation, operation->requested),
                               HW_RESULT_OK))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_DispenseStarted);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_DispenseProgress))
    {
        operation = Hardware_GetDispenseReport();
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               DispenseProgress,
                               MakeOperationData(operation, operation->actual),
                               HW_RESULT_OK);
        EventGroupClearBits(&Mesg_event, MesgEvent_DispenseProgress);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_DispenseCompleted))
    {
        operation = Hardware_GetDispenseReport();
        if (SendMesgWithResend(DispenseCompleted,
                               MakeOperationData(operation, operation->actual),
                               operation->result))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_DispenseCompleted);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_DispenseFailed))
    {
        operation = Hardware_GetDispenseReport();
        if (SendMesgWithResend(DispenseFailed,
                               MakeOperationData(operation, operation->actual),
                               operation->result))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_DispenseFailed);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CollectStarted))
    {
        operation = Hardware_GetCollectReport();
        if (SendMesgWithResend(CollectStarted,
                               MakeOperationData(operation, operation->requested),
                               HW_RESULT_OK))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_CollectStarted);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CollectProgress))
    {
        operation = Hardware_GetCollectReport();
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               CollectProgress,
                               MakeOperationData(operation, operation->actual),
                               HW_RESULT_OK);
        EventGroupClearBits(&Mesg_event, MesgEvent_CollectProgress);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CollectCompleted))
    {
        operation = Hardware_GetCollectReport();
        if (SendMesgWithResend(CollectCompleted,
                               MakeOperationData(operation, operation->actual),
                               operation->result))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_CollectCompleted);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CollectFailed))
    {
        operation = Hardware_GetCollectReport();
        if (SendMesgWithResend(CollectFailed,
                               MakeOperationData(operation, operation->actual),
                               operation->result))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_CollectFailed);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CashAccepted))
    {
        if (CashEvent_HasPending())
        {
            if (SendMesgWithResend(CashAccepted,
                                   MakeCashAcceptedData(),
                                   (uint8_t)CashEvent_GetPendingSequence()))
            {
                EventGroupClearBits(&Mesg_event, MesgEvent_CashAccepted);
            }
        }
        else
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_CashAccepted);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CashAcceptanceStatus))
    {
        if (SendMesgWithResend(CashAcceptanceStatus,
                               MakeCashAcceptanceData(),
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_CashAcceptanceStatus);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CashDeviceStatus))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               CashDeviceStatus,
                               CashDevice_GetStatusData(),
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_CashDeviceStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadStockStatus))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BeadStockStatus,
                               Hardware_GetBeadStock(),
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadStockStatus);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadLowStock))
    {
        if (SendMesgWithResend(BeadLowStock,
                               Hardware_GetBeadStock(),
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_BeadLowStock);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadEmpty))
    {
        if (SendMesgWithResend(BeadEmpty,
                               Hardware_GetBeadStock(),
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_BeadEmpty);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadRefilled))
    {
        if (SendMesgWithResend(BeadRefilled,
                               Hardware_GetBeadStock(),
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_BeadRefilled);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BackendSettingsRequest))
    {
        if (SendMesgWithResend(BackendSettingsRequest,
                               1U,
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_BackendSettingsRequest);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_Unlock))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               AlreadyUnlock,
                               0U,
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_Unlock);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_VersionRequest))
    {
        if (SendMesgWithResend(VersionReport,
                               VERSION,
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_VersionRequest);
        }
    }

    Resend_Task();
    MesgDeal_Task();
}
