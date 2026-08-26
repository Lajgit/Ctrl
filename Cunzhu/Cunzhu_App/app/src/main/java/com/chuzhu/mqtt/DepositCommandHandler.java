package com.chuzhu.mqtt;

import android.content.Context;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.activation.SdkCredentialStore;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.data.CommandStore;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;
import com.chuzhu.data.MemberDepositStore;
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
        String targetDeviceNo = decoded.deviceNo();
        if (!targetDeviceNo.isEmpty() && !targetDeviceNo.equals(DeviceUtil.requireDeviceNo(context))) {
            Log.w(TAG, "忽略非本机会员绑定事件 deviceNo=" + targetDeviceNo);
            return;
        }
        new MemberDepositStore(context).applyBoundCommand(decoded.envelope);
        Log.i(TAG, "会员存珠扫码绑定事件已更新到设备屏UI");
    }

    private void handleCommandResultAck(DepositCommandCodec.Decoded decoded) {
        /*
         * 第一阶段 outbox 仍为 SharedPreferences 简化实现，暂不删除业务记录。
         * 这里先保证 command_result_ack 不会误判为不支持命令并回 failed。
         */
        new MemberDepositStore(context).setMessage("平台已返回 command_result_ack");
        Log.i(TAG, "收到 command_result_ack：messageId=" + decoded.messageId());
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
        if (!MqttManager.get(context).isConnected()) {
            reject(command, "MQTT_NOT_CONNECTED", "MQTT 未连接");
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
        if (messageId == null || messageId.trim().isEmpty()
                || command.getDeviceNo() == null
                || !command.getDeviceNo().equals(DeviceUtil.requireDeviceNo(context))
                || maximumQuantity <= 0
                || data.getOperationNo() == null
                || data.getOperationNo().trim().isEmpty()) {
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

        int timeoutSeconds = data.getSessionTimeoutSeconds() == null
                ? AppConfig.DEFAULT_COLLECT_TIMEOUT_SECONDS
                : Math.max(1, data.getSessionTimeoutSeconds());
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
        session.actualQuantity = actual;
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
                actual,
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
            reason = "存珠机第一阶段拒绝现金相关命令";
        } else if (DeviceMqttCommandTypes.REDEMPTION_RESPONSE.equals(type)) {
            reason = "存珠机第一阶段拒绝取珠/核销相关命令";
        } else {
            reason = "存珠机第一阶段只处理会员存珠白名单命令";
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

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
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
