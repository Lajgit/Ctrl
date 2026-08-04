#include "MesgTask.h"
#include "CtrlTask.h"
#include "BackendKeyTask.h"
#include "KeyTask.h"

Event_Handle_t Mesg_event;

extern Tx_HandleTypeDef Tx1;

static uint32_t MakeOrderData(uint16_t order_sequence, uint16_t value)
{
    return ((uint32_t)order_sequence << ORDER_DATA_SEQUENCE_SHIFT) |
           ((uint32_t)value & ORDER_DATA_VALUE_MASK);
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
    const DispenseOrder_t *order;
    uint8_t frame_id;

    BackendKey_Task();

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_DispenseProgress))
    {
        order = Hardware_GetDispenseOrder();
        if (order->state == DISPENSE_STATE_RUNNING)
        {
            Comm_SendMesg_FillData(&Tx1,
                                   Board_to_Android,
                                   DispenseProgress,
                                   MakeOrderData(order->orderSequence,
                                                 order->actualQuantity),
                                   HW_RESULT_OK);
        }
        EventGroupClearBits(&Mesg_event, MesgEvent_DispenseProgress);
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_DispenseTerminal))
    {
        order = Hardware_GetDispenseOrder();
        if ((order->state == DISPENSE_STATE_WAIT_TERMINAL_ACK) &&
            order->terminalPending)
        {
            frame_id = Comm_SendDispenseTerminal(order->orderSequence,
                                                 order->actualQuantity,
                                                 order->resultCode);
            if (frame_id != 0U)
            {
                Hardware_MarkDispenseTerminalQueued(frame_id);
                EventGroupClearBits(&Mesg_event, MesgEvent_DispenseTerminal);
            }
        }
        else
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_DispenseTerminal);
        }
    }

    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CashAccepted))
    {
        if (CashEvent_HasPending())
        {
            /*
             * A physically accepted cash event owns the device immediately. Disable both
             * cash inputs before reporting the durable event so a second note/coin cannot
             * enter while Android and the platform are still creating the corresponding
             * dispense operation. Android will explicitly reapply the configured mask after
             * the transaction reaches a safe terminal state.
             */
            CashAcceptance_Disable();
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
