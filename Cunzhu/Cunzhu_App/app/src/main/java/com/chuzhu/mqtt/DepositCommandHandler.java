package com.chuzhu.mqtt;

import android.content.Context;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.activation.BootstrapRepository;
import com.chuzhu.activation.SdkCredentialStore;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.data.CommandStore;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.data.PendingOutboxStore;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.device.DeviceUtil;
import com.chuzhu.hardware.SerialMarbleCollectHardwareAdapter;
import com.chuzhu.serial.BoardSerialPort;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.CollectMarblesCommandData;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResult;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandTypes;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 纯存珠机 MQTT 指令处理器。
 */
public final class DepositCommandHandler {

    private static final String TAG = "CunzhuDeposit";
    private static final String CMD_MEMBER_DEPOSIT_SESSION_BOUND = "member_deposit_session_bound";
    private static final String CMD_COMMAND_RESULT_ACK = "command_result_ack";
    private static volatile DepositCommandHandler instance;

    private final Context context;
    private final DepositCommandCodec codec = new DepositCommandCodec();
    private final CommandStore commandStore;
    private final HardwareSessionStore sessionStore;
    private final CommandResultReporter resultReporter;
    private final SerialMarbleCollectHardwareAdapter hardware;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private DepositCommandHandler(Context context) {
        this.context = context.getApplicationContext();
        commandStore = new CommandStore(this.context);
        sessionStore = new HardwareSessionStore(this.context);
        resultReporter = new CommandResultReporter(this.context);
        hardware = SerialMarbleCollectHardwareAdapter.get(this.context);
    }

    public static DepositCommandHandler get(Context context) {
        if (instance == null) {
            synchronized (DepositCommandHandler.class) {
                if (instance == null) {
                    instance = new DepositCommandHandler(context);
                }
            }
        }
        return instance;
    }

    public void handle(String topic, byte[] payload) {
        executor.execute(() -> handleInternal(topic, payload));
    }

    public void recoverUnfinishedSession() {
        DeviceStateRepository.get(context).reconcileFromStoredSession();
    }

    private void handleInternal(String topic, byte[] payload) {
        String deviceNo = DeviceUtil.requireDeviceNo(context);
        DepositCommandCodec.Decoded decoded = codec.decode(
                topic,
                payload,
                deviceNo,
                System.currentTimeMillis()
        );
        String messageId = decoded.messageId();
        String commandType = decoded.commandType();

        if (CMD_MEMBER_DEPOSIT_SESSION_BOUND.equals(commandType)) {
            handleMemberSessionBound(decoded);
            return;
        }
        if (CMD_COMMAND_RESULT_ACK.equals(commandType)) {
            handleCommandResultAck(decoded);
            return;
        }

        if (commandStore.hasCommand(messageId)) {
            resultReporter.replay(messageId);
            return;
        }
        if (decoded.command == null) {
            reject(decoded, "COMMAND_INVALID", messageOf(decoded.error));
            return;
        }
        if (!DeviceMqttCommandTypes.COLLECT_MARBLES.equals(commandType)) {
            rejectUnsupported(decoded);
            return;
        }
        processCollect(decoded.command, decoded.envelope);
    }

    private void handleMemberSessionBound(DepositCommandCodec.Decoded decoded) {
        if (decoded.command == null) {
            Log.w(TAG, "忽略未通过 SDK Codec 校验的会员绑定事件：" + messageOf(decoded.error));
            return;
        }
        String targetDeviceNo = decoded.deviceNo();
        if (!targetDeviceNo.isEmpty() && !targetDeviceNo.equals(DeviceUtil.requireDeviceNo(context))) {
            Log.w(TAG, "忽略非本机会员绑定事件 deviceNo=" + targetDeviceNo);
            return;
        }
        new MemberDepositStore(context).applyBoundCommand(decoded.envelope);
        Log.i(TAG, "会员存珠扫码绑定事件已更新到设备屏UI");
    }

    private void handleCommandResultAck(DepositCommandCodec.Decoded decoded) {
        if (decoded.command == null) {
            Log.w(TAG, "忽略未通过 SDK Codec 校验的 command_result_ack：" + messageOf(decoded.error));
            return;
        }
        JSONObject data = dataOf(decoded.envelope);
        if (data == null) {
            Log.w(TAG, "command_result_ack 缺少 data，保留 outbox");
            return;
        }

        String sourceMessageId = firstString(data, "sourceMessageId", "messageId");
        String eventNo = firstString(data, "eventNo");
        String resultStatus = firstString(data, "resultStatus", "status");
        boolean recorded = data.optBoolean("recorded", false);
        boolean retryable = data.optBoolean("retryable", true);

        if (!recorded || retryable) {
            new MemberDepositStore(context).setMessage(
                    retryable ? "平台要求重试业务回执" : "平台尚未确认业务回执"
            );
            Log.w(TAG, "command_result_ack 未确认清理：recorded=" + recorded
                    + "，retryable=" + retryable
                    + "，sourceMessageId=" + sourceMessageId
                    + "，eventNo=" + eventNo);
            return;
        }
        if (sourceMessageId.isEmpty() || eventNo.isEmpty() || resultStatus.isEmpty()) {
            Log.w(TAG, "command_result_ack 缺少 receiptKey 字段，保留 outbox");
            return;
        }

        int removed = new PendingOutboxStore(context).removeConfirmed(
                sourceMessageId,
                eventNo,
                resultStatus
        );
        new MemberDepositStore(context).setMessage(
                removed > 0 ? "平台已确认业务回执" : "平台已确认回执，本地未找到对应待发送记录"
        );
        Log.i(TAG, "command_result_ack 已处理：sourceMessageId=" + sourceMessageId
                + "，eventNo=" + eventNo
                + "，resultStatus=" + resultStatus
                + "，removed=" + removed);
    }

    private void processCollect(DeviceMqttCommand<?> command, JSONObject envelope) {
        String messageId = command.getMessageId();
        CollectMarblesCommandData data;
        try {
            data = command.requireData(CollectMarblesCommandData.class);
        } catch (Throwable error) {
            reject(command, "DATA_INVALID", messageOf(error));
            return;
        }
        if (!new ActivationStore(context).isActivated()
                || SdkCredentialStore.get(context).load() == null) {
            reject(command, "DEVICE_NOT_ACTIVATED", "设备尚未激活");
            return;
        }
        if (!MqttManager.get(context).isConnected() || !MqttManager.get(context).isSubscribed()) {
            reject(command, "MQTT_NOT_CONNECTED", "MQTT 未完成连接和命令订阅");
            return;
        }

        /*
         * 物理执行前再次读取 bootstrap 校验 deviceType=3，避免 UI 门禁被绕过后仍能驱动收珠机构。
         * 这是存珠业务白名单之外的第二道设备类型防线。
         */
        try {
            new BootstrapRepository(context).requireMarbleDepositMachine();
        } catch (Throwable error) {
            reject(command, "DEVICE_TYPE_NOT_ALLOWED", "bootstrap 存珠机校验失败：" + messageOf(error));
            return;
        }

        if (!BoardSerialPort.get(context).isOpen()) {
            reject(command, "SERIAL_NOT_OPEN", "控制板串口未打开");
            return;
        }
        DepositSession active = sessionStore.load();
        if (hardware.isCollecting()
                || (active != null && DepositSession.STATE_COLLECTING.equals(active.state))) {
            reject(command, "DEVICE_BUSY", "已有收珠任务正在执行");
            return;
        }
        int maximumQuantity = data.getMaximumQuantity() == null
                ? 0
                : data.getMaximumQuantity();
        int timeoutSeconds = data.getSessionTimeoutSeconds() == null
                ? 0
                : data.getSessionTimeoutSeconds();
        if (messageId == null || messageId.trim().isEmpty()
                || command.getDeviceNo() == null
                || !command.getDeviceNo().equals(DeviceUtil.requireDeviceNo(context))
                || maximumQuantity <= 0
                || timeoutSeconds <= 0
                || data.getOperationNo() == null
                || data.getOperationNo().trim().isEmpty()
                || data.getOperationToken() == null
                || data.getOperationToken().trim().isEmpty()) {
            reject(command, "COMMAND_INVALID", "collect_marbles 字段不完整");
            return;
        }

        long now = System.currentTimeMillis();
        DepositSession session = new DepositSession();
        session.messageId = messageId;
        session.operationNo = data.getOperationNo();
        session.operationToken = data.getOperationToken();
        session.maximumQuantity = maximumQuantity;
        session.actualQuantity = 0;
        session.state = DepositSession.STATE_ACCEPTED;
        session.startedAt = now;
        session.updatedAt = now;
        sessionStore.save(session);
        commandStore.saveCommand(messageId, envelope);
        resultReporter.reportAck(command);

        boolean started = hardware.startCollect(
                maximumQuantity,
                timeoutSeconds,
                new HardwareListener(command, session)
        );
        if (!started) {
            finishFailed(command, session, "COLLECT_START_FAILED", "启动收珠硬件失败", 0);
            return;
        }
        session.state = DepositSession.STATE_COLLECTING;
        session.updatedAt = System.currentTimeMillis();
        sessionStore.save(session);
        DeviceStateRepository.get(context).markCollecting();
        new MemberDepositStore(context).setMessage("收珠机构已启动，请投入弹珠");
        new DeviceStatusReporter(context).report();
        executor.schedule(
                () -> onCollectTimeout(command.getMessageId(), timeoutSeconds),
                timeoutSeconds,
                TimeUnit.SECONDS
        );
    }

    private void onCollectTimeout(String messageId, int timeoutSeconds) {
        DepositSession session = sessionStore.load();
        if (session == null
                || !messageId.equals(session.messageId)
                || !DepositSession.STATE_COLLECTING.equals(session.state)) {
            return;
        }
        hardware.onBoardFault(
                "COLLECT_TIMEOUT",
                "控制板 " + timeoutSeconds + " 秒内未返回收珠终态"
        );
    }

    private void finishSuccess(DeviceMqttCommand<?> command, DepositSession session, int actual) {
        if (actual < 0 || actual > session.maximumQuantity) {
            finishFailed(
                    command,
                    session,
                    "ACTUAL_QUANTITY_INVALID",
                    "控制板实际收珠数量超出授权范围",
                    Math.max(0, actual)
            );
            return;
        }
        session.actualQuantity = actual;
        session.state = DepositSession.STATE_FINISHED;
        session.updatedAt = System.currentTimeMillis();
        session.finishedAt = session.updatedAt;
        sessionStore.save(session);
        HardwareExecutionResult hardwareResult = HardwareExecutionResult.success(actual);
        DeviceCommandResult terminal = DeviceCommandResult.physicalTerminal(
                command,
                command.getMessageId() + "-terminal",
                true,
                hardwareResult.getActualQuantity(),
                hardwareResult.getResultCode(),
                hardwareResult.getResultMessage(),
                System.currentTimeMillis()
        );
        resultReporter.reportTerminal(terminal, command.getMessageId());
        new MemberDepositStore(context).setMessage("收珠完成，已上报平台：" + actual + " 颗");
        /* MCU 的 0x21 终态在电机停止后才产生，因此此处具备真实机构停止事实。 */
        DeviceStateRepository.get(context).markIdle();
        new DeviceStatusReporter(context).report();
    }

    private void finishFailed(
            DeviceMqttCommand<?> command,
            DepositSession session,
            String errorCode,
            String errorMessage,
            int actual
    ) {
        int safeActual = Math.max(0, Math.min(actual, session.maximumQuantity));
        session.actualQuantity = safeActual;
        session.state = "COLLECT_TIMEOUT".equals(errorCode)
                || "SERIAL_ERROR".equals(errorCode)
                ? DepositSession.STATE_FAULT
                : DepositSession.STATE_FAILED;
        session.errorCode = errorCode;
        session.errorMessage = errorMessage;
        session.updatedAt = System.currentTimeMillis();
        session.finishedAt = session.updatedAt;
        sessionStore.save(session);
        DeviceCommandResult terminal = DeviceCommandResult.physicalTerminal(
                command,
                command.getMessageId() + "-terminal",
                false,
                safeActual,
                errorCode,
                errorMessage,
                System.currentTimeMillis()
        );
        resultReporter.reportTerminal(terminal, command.getMessageId());
        new MemberDepositStore(context).setMessage("收珠失败：" + errorMessage);
        DeviceStateRepository.get(context).markFault(errorMessage);
        new DeviceStatusReporter(context).report();
    }

    private void rejectUnsupported(DepositCommandCodec.Decoded decoded) {
        String type = decoded.commandType();
        String reason;
        if (DeviceMqttCommandTypes.DISPENSE_MARBLES.equals(type)
                || DeviceMqttCommandTypes.CONTINUE_MARBLE_DISPENSE.equals(type)) {
            reason = "存珠机拒绝售珠机出珠命令";
        } else if (DeviceMqttCommandTypes.SYNC_CASH_CONFIGURATION.equals(type)
                || DeviceMqttCommandTypes.CASH_EVENT_RESPONSE.equals(type)) {
            reason = "存珠机拒绝现金相关命令";
        } else if (DeviceMqttCommandTypes.REDEMPTION_RESPONSE.equals(type)) {
            reason = "存珠机拒绝取珠/核销相关命令";
        } else {
            reason = "存珠机只处理会员存珠白名单命令";
        }
        if (decoded.command != null) {
            reject(decoded.command, "COMMAND_NOT_SUPPORTED", reason);
        } else {
            reject(decoded, "COMMAND_NOT_SUPPORTED", reason);
        }
    }

    private void reject(DeviceMqttCommand<?> command, String resultCode, String reason) {
        DeviceCommandResult terminal = DeviceCommandResult.physicalTerminal(
                command,
                command.getMessageId() + "-reject",
                false,
                0,
                resultCode,
                reason,
                System.currentTimeMillis()
        );
        resultReporter.reportTerminal(terminal, command.getMessageId());
        try {
            JSONObject json = new JSONObject();
            json.put("messageId", command.getMessageId());
            json.put("deviceNo", command.getDeviceNo());
            json.put("commandType", command.getCommandType());
            commandStore.saveCommand(command.getMessageId(), json);
        } catch (Throwable ignored) {
        }
    }

    private void reject(DepositCommandCodec.Decoded decoded, String resultCode, String reason) {
        resultReporter.reportFailureJson(
                decoded.messageId(),
                decoded.deviceNo(),
                decoded.commandType(),
                resultCode,
                reason
        );
        if (!decoded.messageId().isEmpty() && decoded.envelope != null) {
            commandStore.saveCommand(decoded.messageId(), decoded.envelope);
        }
    }

    private static JSONObject dataOf(JSONObject envelope) {
        if (envelope == null) {
            return null;
        }
        JSONObject data = envelope.optJSONObject("data");
        return data == null ? envelope : data;
    }

    private static String firstString(JSONObject json, String... keys) {
        if (json == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key != null && json.has(key) && !json.isNull(key)) {
                String value = String.valueOf(json.opt(key)).trim();
                if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable cursor = error;
        String message = "";
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().trim().isEmpty()) {
                message = cursor.getMessage().trim();
            }
            cursor = cursor.getCause();
        }
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private final class HardwareListener implements SerialMarbleCollectHardwareAdapter.Listener {

        private final DeviceMqttCommand<?> command;
        private final DepositSession session;

        HardwareListener(DeviceMqttCommand<?> command, DepositSession session) {
            this.command = command;
            this.session = session;
        }

        @Override
        public void onCountChanged(int actualQuantity) {
            if (actualQuantity < 0 || actualQuantity > session.maximumQuantity) {
                hardware.onBoardFault(
                        "ACTUAL_QUANTITY_INVALID",
                        "控制板计数超出本次最大允许数量"
                );
                return;
            }
            session.actualQuantity = actualQuantity;
            session.updatedAt = System.currentTimeMillis();
            sessionStore.save(session);
            Log.i(TAG, "收珠计数更新 actualQuantity=" + actualQuantity);
        }

        @Override
        public void onFinished(int actualQuantity) {
            finishSuccess(command, session, actualQuantity);
        }

        @Override
        public void onFault(String errorCode, String errorMessage, int actualQuantity) {
            finishFailed(command, session, errorCode, errorMessage, actualQuantity);
        }
    }
}
