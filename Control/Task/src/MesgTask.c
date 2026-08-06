/*
 * 消息任务：读取各模块设置的事件位，将真实硬件状态编码为协议帧上报 Android。
 * 普通状态发送一次，现金、库存告警和出珠终态进入可靠重发流程等待明确确认。
 */
#include "MesgTask.h"
#include "CtrlTask.h"
#include "BackendKeyTask.h"
#include "KeyTask.h"

/* 全局消息事件组，由硬件、现金和按键任务设置待上报事件。 */
Event_Handle_t Mesg_event;

extern Tx_HandleTypeDef Tx1;

/* 将订单序号放入高 16 位，将本次数量放入低 16 位。 */
static uint32_t MakeOrderData(uint16_t order_sequence, uint16_t value)
{
    return ((uint32_t)order_sequence << ORDER_DATA_SEQUENCE_SHIFT) |
           ((uint32_t)value & ORDER_DATA_VALUE_MASK);
}

/* 组装现金事实：介质、金额和 16 位现金序号的高字节。 */
static uint32_t MakeCashAcceptedData(void)
{
    uint16_t amount_fen = CashEvent_GetPendingAmountFen();
    uint16_t sequence = CashEvent_GetPendingSequence();
    return ((uint32_t)CashEvent_GetPendingMedium() << 24U) |
           ((uint32_t)amount_fen << 8U) |
           ((uint32_t)sequence >> 8U);
}

/* 组装现金受理实际掩码和已提交的 24 位配置版本。 */
static uint32_t MakeCashAcceptanceData(void)
{
    return ((uint32_t)CashAcceptance_GetEnableMask() << 24U) |
           (CashAcceptance_GetConfigVersion() & 0x00FFFFFFUL);
}

/* 发送需要可靠确认的控制板事件。 */
static bool SendMesgWithResend(uint8_t code_2, uint32_t data, uint8_t expandCode)
{
    return Comm_SendMesg_FillData_withResend(&Tx1,
                                             Board_to_Android,
                                             code_2,
                                             data,
                                             expandCode) != 0U;
}

/* 按事件位逐项生成上报帧，并在本轮末尾执行重发和接收去重维护。 */
void Mesg_Task(void)
{
    const DispenseOrder_t *order;
    uint8_t frame_id;

    /* 后台按键可触发平台设置请求。 */
    BackendKey_Task();

    /* 出珠过程帧只描述当前订单本次已经真实吐出的数量。 */
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

    /* 出珠终态必须获得 Android 业务确认，成功入队后记录终态帧 ID。 */
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

    /*
     * 现金已经被物理接收后立即关闭两种现金入口，再可靠上报队首现金事实。
     * Android 在对应交易安全结束后，才会显式重新应用现金配置。
     */
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

    /* 上报现金设备实际使能掩码及已经成功提交的配置版本。 */
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CashAcceptanceStatus))
    {
        if (SendMesgWithResend(CashAcceptanceStatus,
                               MakeCashAcceptanceData(),
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_CashAcceptanceStatus);
        }
    }

    /* 上报纸钞机状态、硬币器电源状态和待确认现金队列数量。 */
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_CashDeviceStatus))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               CashDeviceStatus,
                               CashDevice_GetStatusData(),
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_CashDeviceStatus);
    }

    /* 上报实时库存；ExpandCode=1 表示存珠电机正在工作。 */
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BeadStockStatus))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               BeadStockStatus,
                               Hardware_GetBeadStock(),
                               Hardware_IsCollectActive() ? 0x01U : 0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_BeadStockStatus);
    }

    /* 低库存、无珠和补珠属于需要可靠送达的库存事件。 */
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

    /* 请求 Android 从后台重新同步设备设置。 */
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_BackendSettingsRequest))
    {
        if (SendMesgWithResend(BackendSettingsRequest,
                               1U,
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_BackendSettingsRequest);
        }
    }

    /* 电子锁动作完成提示不需要可靠重发。 */
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_Unlock))
    {
        Comm_SendMesg_FillData(&Tx1,
                               Board_to_Android,
                               AlreadyUnlock,
                               0U,
                               0x00U);
        EventGroupClearBits(&Mesg_event, MesgEvent_Unlock);
    }

    /* 固件版本使用可靠消息上报，便于 Android 判断控制板协议能力。 */
    if (EventGroupCheckBits(&Mesg_event, MesgEvent_VersionRequest))
    {
        if (SendMesgWithResend(VersionReport,
                               VERSION,
                               0x00U))
        {
            EventGroupClearBits(&Mesg_event, MesgEvent_VersionRequest);
        }
    }

    /* 维护待确认发送队列和 Android 命令去重窗口。 */
    Resend_Task();
    MesgDeal_Task();
}
