package com.gouzhu.mqtt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.gouzhu.hardware.SerialCashConfigurationAdapter;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;
import com.pinball.xiaoda.device.sdk.hardware.CashTier;
import com.pinball.xiaoda.device.sdk.protocol.CashConfigurationCommandData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * sync_cash_configuration 的唯一 APP 侧处理器。
 *
 * <p>处理顺序固定为 messageId 幂等 -> highestKnownVersion -> 同版本内容一致性 ->
 * durable ACK/终态 -> 串行硬件应用。交易占用期间允许可靠接收并 ACK 新配置，但不会
 * 因正常占用回 failed；待占用释放后再执行真实应用。</p>
 */
final class CashConfigurationCommandCoordinator {

    private static final String TAG = "GouzhuCashCommand";
    private static final long MIN_CONTROLLER_PROTOCOL_VERSION = 0x02020000L;

    private static final String STATE_QUEUED = "QUEUED";
    private static final String STATE_DEFERRED = "DEFERRED";
    private static final String STATE_APPLYING = "APPLYING";
    private static final String STATE_APPLIED = "APPLIED";
    private static final String STATE_FAILED = "FAILED";

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder decoder = new SdkCommandDecoder();
    private final SerialCashConfigurationAdapter cashAdapter;
    private final Ledger ledger;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-cash-config-command");
                thread.setDaemon(true);
                return thread;
            });

    private boolean started;
    private boolean recoveryDone;

    CashConfigurationCommandCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.cashAdapter = new SerialCashConfigurationAdapter(this.context);
        this.ledger = new Ledger(this.context);
    }

    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        cashAdapter.start();
        cashAdapter.markApplied(store.getCashConfigVersion());
        cashAdapter.setProtocolV22Ready(
                store.getBoardVersion() >= MIN_CONTROLLER_PROTOCOL_VERSION
        );
        recoverInterruptedIfNeeded();
        resumeDeferredIfPossible();
    }

    synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        cashAdapter.stop();
    }

    synchronized void handleCommand(String topic, byte[] payload) {
        if (!started) {
            start();
        }
        recoverInterruptedIfNeeded();

        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.e(TAG, "现金配置SDK解码失败", error);
            MqttManager.get(context).reportFault(
                    "COMMAND_PROTOCOL_INVALID",
                    "cash configuration validation failed",
                    3,
                    messageOf(error)
            );
            return;
        }

        if (!"sync_cash_configuration".equals(decoded.sdkCommand.getCommandType())) {
            return;
        }

        String messageId = safe(decoded.sdkCommand.getMessageId()).trim();
        if (messageId.isEmpty()) {
            return;
        }

        MessageRecord duplicateMessage = ledger.findMessage(messageId);
        if (duplicateMessage != null) {
            replaySavedMessage(duplicateMessage);
            return;
        }

        // 兼容升级前已经由旧运行时处理过的 messageId；仍只重放 durable outbox。
        if (store.hasCommand(messageId)) {
            resendStoredResults(messageId);
            return;
        }

        final CashConfigurationCommandData config;
        try {
            config = decoded.sdkCommand.requireData(CashConfigurationCommandData.class);
        } catch (Throwable error) {
            int rawVersion = rawConfigVersion(decoded.envelope);
            rejectCurrent(
                    decoded,
                    rawVersion,
                    "CASH_CONFIGURATION_INVALID",
                    messageOf(error),
                    true
            );
            return;
        }

        String validationError = validate(config);
        if (validationError != null) {
            rejectCurrent(
                    decoded,
                    safeVersion(config),
                    "CASH_CONFIGURATION_INVALID",
                    validationError,
                    true
            );
            return;
        }

        int incomingVersion = safeVersion(config);
        String canonical = canonical(config);
        int highestKnownVersion = Math.max(
                ledger.getHighestKnownVersion(),
                store.getLatestCashConfigVersion()
        );
        VersionRecord existingVersion = ledger.findVersion(incomingVersion);

        if (incomingVersion < highestKnownVersion) {
            rejectCurrent(
                    decoded,
                    incomingVersion,
                    "CASH_CONFIGURATION_STALE",
                    "现金配置版本已过期，highestKnownVersion=" + highestKnownVersion,
                    false
            );
            return;
        }

        if (incomingVersion == highestKnownVersion) {
            if (existingVersion != null) {
                if (!canonical.equals(existingVersion.canonical)) {
                    CashRuntimeCoordinator.get(context).onConfigurationPending(incomingVersion);
                    rejectCurrent(
                            decoded,
                            incomingVersion,
                            "CASH_CONFIGURATION_VERSION_CONFLICT",
                            "现金配置同版本内容冲突",
                            false
                    );
                    return;
                }
                if (existingVersion.isTerminal()) {
                    replyFromVersion(decoded, existingVersion);
                    return;
                }
                attachAliasAndAck(decoded, existingVersion);
                resumeDeferredIfPossible();
                return;
            }

            // 兼容升级前已经成功应用、但尚未写入新版本账本的配置。
            if (store.getCashConfigVersion() == incomingVersion) {
                if (canonical.equals(canonicalAppliedSnapshot())) {
                    replyTerminal(
                            decoded,
                            incomingVersion,
                            true,
                            "CASH_CONFIGURATION_APPLIED",
                            "cash configuration already applied"
                    );
                } else {
                    CashRuntimeCoordinator.get(context).onConfigurationPending(incomingVersion);
                    rejectCurrent(
                            decoded,
                            incomingVersion,
                            "CASH_CONFIGURATION_VERSION_CONFLICT",
                            "现金配置同版本内容冲突",
                            false
                    );
                }
                return;
            }

            rejectCurrent(
                    decoded,
                    incomingVersion,
                    "CASH_CONFIGURATION_STALE",
                    "同版本没有可复用的设备处理结果，请等待更高configVersion",
                    false
            );
            return;
        }

        final SdkCommandDecoder.EncodedResult ack;
        final SdkCommandDecoder.EncodedResult interrupted;
        try {
            long now = System.currentTimeMillis();
            ack = decoded.configurationAcknowledgement(messageId + "-ack", now);
            interrupted = decoded.configurationTerminal(
                    messageId + "-result",
                    false,
                    "CASH_CONFIGURATION_INTERRUPTED",
                    "cash configuration was interrupted by app restart",
                    now
            );
        } catch (Throwable error) {
            rejectCurrent(
                    decoded,
                    incomingVersion,
                    "LOCAL_STORAGE_ERROR",
                    "现金配置回执编码失败：" + messageOf(error),
                    true
            );
            return;
        }

        VersionRecord record = new VersionRecord();
        record.configVersion = incomingVersion;
        record.canonical = canonical;
        record.topic = safe(topic);
        record.payload = new String(
                payload == null ? new byte[0] : payload,
                StandardCharsets.UTF_8
        );
        record.primaryMessageId = messageId;
        record.state = STATE_QUEUED;
        record.success = false;
        record.resultCode = "";
        record.resultMessage = "";
        record.pendingStored = false;
        record.interruptedEventNo = interrupted.eventNo;
        record.interruptedStatus = interrupted.resultStatus;
        record.interruptedPayload = interrupted.payload;

        MessageRecord message = new MessageRecord();
        message.messageId = messageId;
        message.configVersion = incomingVersion;
        message.topic = safe(topic);
        message.payload = record.payload;
        message.ackEventNo = ack.eventNo;
        message.ackStatus = ack.resultStatus;
        message.ackPayload = ack.payload;

        if (!ledger.insertVersionAndMessage(record, message)
                || !store.saveCommand(decoded.envelope)
                || !store.saveCommandResult(
                ack.sourceMessageId,
                ack.eventNo,
                ack.resultStatus,
                ack.payload
        )) {
            ledger.markVersionTerminal(
                    incomingVersion,
                    false,
                    "LOCAL_STORAGE_ERROR",
                    "现金配置pending/ACK无法可靠保存"
            );
            CashRuntimeCoordinator.get(context).onConfigurationTerminal(
                    incomingVersion,
                    false
            );
            MqttManager.get(context).reportFault(
                    "LOCAL_STORAGE_ERROR",
                    "cash configuration durable ack failed",
                    3,
                    "messageId=" + messageId
            );
            return;
        }

        MqttManager.get(context).reportCommandResult(ack.payload);
        CashRuntimeCoordinator.get(context).onConfigurationPending(incomingVersion);
        drainQueueLocked();
    }

    synchronized void resumeDeferredIfPossible() {
        if (!started) {
            return;
        }
        recoverInterruptedIfNeeded();
        if (TransactionOccupancyManager.get(context).isIdle()) {
            ledger.moveDeferredToQueued();
        }
        drainQueueLocked();
    }

    private void drainQueueLocked() {
        if (ledger.hasApplyingVersion()) {
            return;
        }
        VersionRecord next = ledger.findNextNonTerminal();
        if (next == null) {
            return;
        }
        if (STATE_DEFERRED.equals(next.state)
                && !TransactionOccupancyManager.get(context).isIdle()) {
            return;
        }
        if (STATE_DEFERRED.equals(next.state)) {
            ledger.updateState(next.configVersion, STATE_QUEUED);
            next.state = STATE_QUEUED;
        }
        if (!STATE_QUEUED.equals(next.state)) {
            return;
        }
        prepareAndApply(next);
    }

    private void prepareAndApply(VersionRecord record) {
        final SdkCommandDecoder.DecodedCommand decoded;
        final CashConfigurationCommandData config;
        try {
            decoded = decoder.decode(
                    record.topic,
                    record.payload.getBytes(StandardCharsets.UTF_8),
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
            config = decoded.sdkCommand.requireData(CashConfigurationCommandData.class);
        } catch (Throwable error) {
            finishVersionFailure(
                    record,
                    "CASH_CONFIGURATION_INVALID",
                    "已持久化现金配置无法重新解码：" + messageOf(error)
            );
            return;
        }

        if (!record.pendingStored) {
            MessageRecord primary = ledger.findMessage(record.primaryMessageId);
            if (primary == null
                    || !store.savePendingCashConfiguration(
                    decoded.envelope,
                    record.configVersion,
                    config.isCashAcceptanceEnabled(),
                    config.isChangeEnabled(),
                    decoded.envelope.toString(),
                    primary.ackEventNo,
                    primary.ackStatus,
                    primary.ackPayload,
                    record.interruptedEventNo,
                    record.interruptedStatus,
                    record.interruptedPayload
            )) {
                finishVersionFailure(
                        record,
                        "LOCAL_STORAGE_ERROR",
                        "现金配置pending状态无法可靠保存"
                );
                return;
            }
            ledger.markPendingStored(record.configVersion);
            record.pendingStored = true;
        }

        if (config.isCashAcceptanceEnabled()
                && !TransactionOccupancyManager.get(context).isIdle()) {
            ledger.updateState(record.configVersion, STATE_DEFERRED);
            Log.i(
                    TAG,
                    "现金配置已ACK并延迟硬件应用：configVersion="
                            + record.configVersion
                            + "，原因=正常交易占用"
            );
            return;
        }

        ledger.updateState(record.configVersion, STATE_APPLYING);
        executor.execute(() -> applyVersion(record.configVersion));
    }

    private void applyVersion(int configVersion) {
        VersionRecord record = ledger.findVersion(configVersion);
        if (record == null || !STATE_APPLYING.equals(record.state)) {
            return;
        }

        final SdkCommandDecoder.DecodedCommand decoded;
        final CashConfigurationCommandData config;
        try {
            decoded = decoder.decode(
                    record.topic,
                    record.payload.getBytes(StandardCharsets.UTF_8),
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
            config = decoded.sdkCommand.requireData(CashConfigurationCommandData.class);
        } catch (Throwable error) {
            finishVersionFailure(
                    record,
                    "CASH_CONFIGURATION_INVALID",
                    "现金配置执行前重新解码失败：" + messageOf(error)
            );
            return;
        }

        cashAdapter.setProtocolV22Ready(
                store.getBoardVersion() >= MIN_CONTROLLER_PROTOCOL_VERSION
        );
        final CashConfigurationResult result;
        try {
            result = config.isCashAcceptanceEnabled()
                    ? cashAdapter.applyConfiguration(
                    configVersion,
                    toCashTiers(config)
            )
                    : cashAdapter.applyConfigurationDisabled(configVersion);
        } catch (Throwable error) {
            finishVersionFailure(
                    record,
                    "CASH_CONFIGURATION_APPLY_FAILED",
                    messageOf(error)
            );
            return;
        }

        if (result == null || !result.isApplied()) {
            finishVersionFailure(
                    record,
                    "CASH_CONFIGURATION_APPLY_FAILED",
                    result == null
                            ? "cash adapter did not return a result"
                            : safe(result.getMessage())
            );
            return;
        }

        final SdkCommandDecoder.EncodedResult terminal;
        try {
            terminal = decoded.configurationTerminal(
                    record.primaryMessageId + "-result",
                    true,
                    "CASH_CONFIGURATION_APPLIED",
                    "cash configuration applied",
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            finishVersionFailure(
                    record,
                    "LOCAL_STORAGE_ERROR",
                    "现金配置成功终态编码失败：" + messageOf(error)
            );
            return;
        }

        if (!store.commitPendingCashConfigurationAndResult(
                record.primaryMessageId,
                terminal.eventNo,
                terminal.resultStatus,
                terminal.payload
        )) {
            finishVersionFailure(
                    record,
                    "LOCAL_STORAGE_ERROR",
                    "控制板已应用现金配置，但本地成功事务提交失败"
            );
            return;
        }

        cashAdapter.markApplied(configVersion);
        ledger.markVersionTerminal(
                configVersion,
                true,
                "CASH_CONFIGURATION_APPLIED",
                "cash configuration applied"
        );
        publishVersionTerminals(
                configVersion,
                true,
                "CASH_CONFIGURATION_APPLIED",
                "cash configuration applied"
        );
        CashRuntimeCoordinator.get(context).onConfigurationTerminal(configVersion, true);

        synchronized (this) {
            drainQueueLocked();
        }
    }

    private void finishVersionFailure(
            VersionRecord record,
            String resultCode,
            String resultMessage
    ) {
        if (record == null) {
            return;
        }
        cashAdapter.disableCashAcceptance();

        boolean durablePrimaryTerminal = false;
        MessageRecord primaryMessage = ledger.findMessage(record.primaryMessageId);
        if (primaryMessage != null) {
            try {
                SdkCommandDecoder.DecodedCommand decoded = decoder.decode(
                        primaryMessage.topic,
                        primaryMessage.payload.getBytes(StandardCharsets.UTF_8),
                        DeviceUtil.requireDeviceNo(context),
                        System.currentTimeMillis()
                );
                SdkCommandDecoder.EncodedResult terminal = decoded.configurationTerminal(
                        record.primaryMessageId + "-result",
                        false,
                        resultCode,
                        safe(resultMessage),
                        System.currentTimeMillis()
                );
                durablePrimaryTerminal = store.failCashConfigurationAndResult(
                        decoded.envelope,
                        terminal.sourceMessageId,
                        terminal.eventNo,
                        terminal.resultStatus,
                        terminal.payload,
                        true
                );
                if (!durablePrimaryTerminal) {
                    // 即使旧 pending 事务损坏，也至少把终态放入 durable outbox。
                    if (!store.hasCommand(record.primaryMessageId)) {
                        store.saveCommand(decoded.envelope);
                    }
                    durablePrimaryTerminal = store.saveCommandResult(
                            terminal.sourceMessageId,
                            terminal.eventNo,
                            terminal.resultStatus,
                            terminal.payload
                    );
                    store.clearPendingCashConfiguration(record.primaryMessageId);
                }
            } catch (Throwable error) {
                Log.e(TAG, "保存现金配置失败终态异常", error);
            }
        } else {
            store.clearPendingCashConfiguration(record.primaryMessageId);
        }

        if (!durablePrimaryTerminal) {
            MqttManager.get(context).reportFault(
                    "LOCAL_STORAGE_ERROR",
                    "cash configuration failed terminal could not be saved",
                    3,
                    "messageId=" + record.primaryMessageId
            );
        }

        ledger.markVersionTerminal(
                record.configVersion,
                false,
                resultCode,
                safe(resultMessage)
        );
        publishVersionTerminals(
                record.configVersion,
                false,
                resultCode,
                safe(resultMessage)
        );
        CashRuntimeCoordinator.get(context).onConfigurationTerminal(
                record.configVersion,
                false
        );

        synchronized (this) {
            drainQueueLocked();
        }
    }

    private void publishVersionTerminals(
            int configVersion,
            boolean success,
            String resultCode,
            String resultMessage
    ) {
        VersionRecord version = ledger.findVersion(configVersion);
        if (version == null) {
            return;
        }

        for (MessageRecord message : ledger.listMessages(configVersion)) {
            if (!blank(message.terminalPayload)) {
                MqttManager.get(context).reportCommandResult(message.terminalPayload);
                continue;
            }

            DeviceCommandStore.OutboxItem storedTerminal = findStoredTerminal(message.messageId);
            if (storedTerminal != null) {
                ledger.saveTerminal(
                        message.messageId,
                        storedTerminal.resultStatus,
                        storedTerminal.payload
                );
                MqttManager.get(context).reportCommandResult(storedTerminal.payload);
                continue;
            }

            try {
                SdkCommandDecoder.DecodedCommand decoded = decoder.decode(
                        message.topic,
                        message.payload.getBytes(StandardCharsets.UTF_8),
                        DeviceUtil.requireDeviceNo(context),
                        System.currentTimeMillis()
                );
                SdkCommandDecoder.EncodedResult terminal = decoded.configurationTerminal(
                        message.messageId + "-result",
                        success,
                        resultCode,
                        resultMessage,
                        System.currentTimeMillis()
                );
                if (!store.hasCommand(message.messageId)) {
                    store.saveCommand(decoded.envelope);
                }
                if (!store.saveCommandResult(
                        terminal.sourceMessageId,
                        terminal.eventNo,
                        terminal.resultStatus,
                        terminal.payload
                )) {
                    Log.e(TAG, "现金配置终态outbox保存失败：messageId=" + message.messageId);
                    continue;
                }
                ledger.saveTerminal(
                        message.messageId,
                        terminal.resultStatus,
                        terminal.payload
                );
                MqttManager.get(context).reportCommandResult(terminal.payload);
            } catch (Throwable error) {
                Log.e(TAG, "生成现金配置终态失败：messageId=" + message.messageId, error);
            }
        }
    }

    private DeviceCommandStore.OutboxItem findStoredTerminal(String messageId) {
        String expectedEventNo = safe(messageId) + "-result";
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (safe(messageId).equals(item.sourceMessageId)
                    && expectedEventNo.equals(item.eventNo)) {
                return item;
            }
        }
        return null;
    }

    private void attachAliasAndAck(
            SdkCommandDecoder.DecodedCommand decoded,
            VersionRecord version
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            SdkCommandDecoder.EncodedResult ack = decoded.configurationAcknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            MessageRecord message = new MessageRecord();
            message.messageId = messageId;
            message.configVersion = version.configVersion;
            message.topic = version.topic;
            message.payload = decoded.envelope.toString();
            message.ackEventNo = ack.eventNo;
            message.ackStatus = ack.resultStatus;
            message.ackPayload = ack.payload;
            if (ledger.insertMessage(message)
                    && store.saveCommand(decoded.envelope)
                    && store.saveCommandResult(
                    ack.sourceMessageId,
                    ack.eventNo,
                    ack.resultStatus,
                    ack.payload
            )) {
                MqttManager.get(context).reportCommandResult(ack.payload);
            }
        } catch (Throwable error) {
            Log.e(TAG, "保存同版本现金配置别名ACK失败", error);
        }
    }

    private void replyFromVersion(
            SdkCommandDecoder.DecodedCommand decoded,
            VersionRecord version
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            SdkCommandDecoder.EncodedResult ack = decoded.configurationAcknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            SdkCommandDecoder.EncodedResult terminal = decoded.configurationTerminal(
                    messageId + "-result",
                    version.success,
                    version.resultCode,
                    version.resultMessage,
                    System.currentTimeMillis()
            );
            MessageRecord message = new MessageRecord();
            message.messageId = messageId;
            message.configVersion = version.configVersion;
            message.topic = version.topic;
            message.payload = decoded.envelope.toString();
            message.ackEventNo = ack.eventNo;
            message.ackStatus = ack.resultStatus;
            message.ackPayload = ack.payload;
            message.terminalStatus = terminal.resultStatus;
            message.terminalPayload = terminal.payload;
            if (ledger.insertMessage(message)
                    && store.saveCommand(decoded.envelope)
                    && store.saveCommandResult(
                    ack.sourceMessageId,
                    ack.eventNo,
                    ack.resultStatus,
                    ack.payload
            )
                    && store.saveCommandResult(
                    terminal.sourceMessageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminal.payload
            )) {
                MqttManager.get(context).reportCommandResult(ack.payload);
                MqttManager.get(context).reportCommandResult(terminal.payload);
            }
        } catch (Throwable error) {
            Log.e(TAG, "复用现金配置同版本终态失败", error);
        }
    }

    private void replyTerminal(
            SdkCommandDecoder.DecodedCommand decoded,
            int configVersion,
            boolean success,
            String resultCode,
            String resultMessage
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            SdkCommandDecoder.EncodedResult terminal = decoded.configurationTerminal(
                    messageId + "-result",
                    success,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            );
            MessageRecord message = new MessageRecord();
            message.messageId = messageId;
            message.configVersion = configVersion;
            message.topic = "";
            message.payload = decoded.envelope.toString();
            message.terminalStatus = terminal.resultStatus;
            message.terminalPayload = terminal.payload;
            ledger.insertMessage(message);
            if (store.saveCommand(decoded.envelope)
                    && store.saveCommandResult(
                    terminal.sourceMessageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminal.payload
            )) {
                MqttManager.get(context).reportCommandResult(terminal.payload);
            }
        } catch (Throwable error) {
            Log.e(TAG, "现金配置终态编码失败", error);
        }
    }

    private void rejectCurrent(
            SdkCommandDecoder.DecodedCommand decoded,
            int configVersion,
            String resultCode,
            String resultMessage,
            boolean reserveVersion
    ) {
        if (reserveVersion && configVersion > 0) {
            int highest = Math.max(
                    ledger.getHighestKnownVersion(),
                    store.getLatestCashConfigVersion()
            );
            if (configVersion > highest) {
                ledger.insertRejectedVersion(
                        configVersion,
                        canonicalRaw(decoded.envelope),
                        decoded.envelope.toString(),
                        decoded.sdkCommand.getMessageId(),
                        resultCode,
                        resultMessage
                );
            }
        }
        replyTerminal(
                decoded,
                Math.max(0, configVersion),
                false,
                resultCode,
                resultMessage
        );
        if (reserveVersion) {
            CashRuntimeCoordinator.get(context).onConfigurationTerminal(
                    Math.max(0, configVersion),
                    false
            );
        }
    }

    private void replaySavedMessage(MessageRecord message) {
        if (!blank(message.ackPayload)) {
            MqttManager.get(context).reportCommandResult(message.ackPayload);
        }
        if (!blank(message.terminalPayload)) {
            MqttManager.get(context).reportCommandResult(message.terminalPayload);
        }
    }

    private void resendStoredResults(String messageId) {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (messageId.equals(item.sourceMessageId)) {
                MqttManager.get(context).reportCommandResult(item.payload);
            }
        }
    }

    private void recoverInterruptedIfNeeded() {
        if (recoveryDone) {
            return;
        }
        recoveryDone = true;
        List<VersionRecord> interrupted = ledger.listNonTerminalVersions();
        if (interrupted.isEmpty()) {
            return;
        }

        // APP 重启后不能猜测旧 pending 的硬件应用结果，全部收敛为失败并保持现金关闭。
        store.clearPendingCashConfiguration((String) null);
        for (VersionRecord version : interrupted) {
            ledger.markVersionTerminal(
                    version.configVersion,
                    false,
                    "CASH_CONFIGURATION_INTERRUPTED",
                    "cash configuration was interrupted by app restart"
            );
            publishVersionTerminals(
                    version.configVersion,
                    false,
                    "CASH_CONFIGURATION_INTERRUPTED",
                    "cash configuration was interrupted by app restart"
            );
            CashRuntimeCoordinator.get(context).onConfigurationTerminal(
                    version.configVersion,
                    false
            );
        }
    }

    private String validate(CashConfigurationCommandData config) {
        if (config == null || config.getConfigVersion() == null
                || config.getConfigVersion() <= 0L
                || config.getConfigVersion() > 0x00FFFFFFL) {
            return "configVersion must be 1..16777215";
        }
        if (config.isChangeEnabled()) {
            return "changeEnabled must be false";
        }

        List<CashConfigurationCommandData.CashSaleItem> items = config.getCashSaleItems();
        if (!config.isCashAcceptanceEnabled()) {
            return items != null && !items.isEmpty()
                    ? "cashSaleItems must be empty when cashAcceptanceEnabled=false"
                    : null;
        }
        if (items == null || items.isEmpty()) {
            return "cashSaleItems cannot be empty when cash is enabled";
        }

        Set<String> mediumAmounts = new HashSet<>();
        Map<String, String> tierSignatures = new HashMap<>();
        for (CashConfigurationCommandData.CashSaleItem item : items) {
            if (item == null
                    || item.getDenominationAmount() == null
                    || item.getMarbleQuantity() == null) {
                return "cash sale item fields are incomplete";
            }
            String medium = safe(item.getCashMediumType());
            int amount = item.getDenominationAmount();
            int quantity = item.getMarbleQuantity();
            String tierNo = safe(item.getCashSaleTierNo()).trim();
            if (!"banknote".equals(medium) && !"coin".equals(medium)) {
                return "cashMediumType must be banknote or coin";
            }
            if (amount <= 0 || amount > 0xFFFF) {
                return "denominationAmount must be 1..65535";
            }
            if (quantity <= 0 || quantity > 0xFFFF) {
                return "marbleQuantity must be 1..65535";
            }
            if (tierNo.isEmpty()) {
                return "cashSaleTierNo cannot be empty";
            }
            if (!mediumAmounts.add(medium + "|" + amount)) {
                return "duplicate cash tier medium and amount";
            }

            String signature = amount + "|" + quantity;
            String previous = tierSignatures.put(tierNo, signature);
            if (previous != null && !previous.equals(signature)) {
                return "same cashSaleTierNo must use the same amount and marble quantity";
            }
        }
        return null;
    }

    private List<CashTier> toCashTiers(CashConfigurationCommandData config) {
        List<CashTier> result = new ArrayList<>();
        List<CashConfigurationCommandData.CashSaleItem> items = config.getCashSaleItems();
        if (items == null) {
            return result;
        }
        for (CashConfigurationCommandData.CashSaleItem item : items) {
            result.add(new CashTier(
                    item.getCashMediumType(),
                    item.getDenominationAmount(),
                    item.getMarbleQuantity(),
                    item.getCashSaleTierNo()
            ));
        }
        return result;
    }

    private String canonical(CashConfigurationCommandData config) {
        List<String> items = new ArrayList<>();
        if (config.getCashSaleItems() != null) {
            for (CashConfigurationCommandData.CashSaleItem item : config.getCashSaleItems()) {
                items.add(
                        safe(item.getCashMediumType()) + "|"
                                + item.getDenominationAmount() + "|"
                                + item.getMarbleQuantity() + "|"
                                + safe(item.getCashSaleTierNo())
                );
            }
        }
        Collections.sort(items);
        return "enabled=" + config.isCashAcceptanceEnabled()
                + ";change=" + config.isChangeEnabled()
                + ";items=" + String.join(",", items);
    }

    private String canonicalAppliedSnapshot() {
        DeviceCommandStore.CashConfigurationRecord record = store.loadCashConfiguration();
        if (record == null || blank(record.snapshotJson)) {
            return "";
        }
        try {
            JSONObject envelope = new JSONObject(record.snapshotJson);
            JSONObject data = envelope.optJSONObject("data");
            if (data == null) {
                return "";
            }
            List<String> items = new ArrayList<>();
            JSONArray array = data.optJSONArray("cashSaleItems");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    items.add(
                            item.optString("cashMediumType", "") + "|"
                                    + item.optInt("denominationAmount", 0) + "|"
                                    + item.optInt("marbleQuantity", 0) + "|"
                                    + item.optString("cashSaleTierNo", "")
                    );
                }
            }
            Collections.sort(items);
            return "enabled=" + data.optBoolean("cashAcceptanceEnabled", false)
                    + ";change=" + data.optBoolean("changeEnabled", false)
                    + ";items=" + String.join(",", items);
        } catch (Throwable error) {
            return "";
        }
    }

    private static String canonicalRaw(JSONObject envelope) {
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        return "raw=" + (data == null ? "" : data.toString());
    }

    private static int rawConfigVersion(JSONObject envelope) {
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        return data == null ? -1 : data.optInt("configVersion", -1);
    }

    private static int safeVersion(CashConfigurationCommandData config) {
        if (config == null || config.getConfigVersion() == null) {
            return -1;
        }
        long value = config.getConfigVersion();
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class VersionRecord {
        int configVersion;
        String canonical;
        String topic;
        String payload;
        String primaryMessageId;
        String state;
        boolean success;
        String resultCode;
        String resultMessage;
        boolean pendingStored;
        String interruptedEventNo;
        String interruptedStatus;
        String interruptedPayload;

        boolean isTerminal() {
            return STATE_APPLIED.equals(state) || STATE_FAILED.equals(state);
        }
    }

    private static final class MessageRecord {
        String messageId;
        int configVersion;
        String topic;
        String payload;
        String ackEventNo;
        String ackStatus;
        String ackPayload;
        String terminalStatus;
        String terminalPayload;
    }

    /** 独立版本账本，保留失败版本，不能因应用失败把 highestKnownVersion 回退。 */
    private static final class Ledger extends SQLiteOpenHelper {

        private static final String DB_NAME = "gouzhu_cash_configuration_ledger.db";
        private static final int DB_VERSION = 1;

        Ledger(Context context) {
            super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE versions ("
                    + "config_version INTEGER PRIMARY KEY,"
                    + "canonical TEXT NOT NULL,"
                    + "topic TEXT NOT NULL,"
                    + "payload TEXT NOT NULL,"
                    + "primary_message_id TEXT NOT NULL,"
                    + "state TEXT NOT NULL,"
                    + "success INTEGER NOT NULL DEFAULT 0,"
                    + "result_code TEXT NOT NULL DEFAULT '',"
                    + "result_message TEXT NOT NULL DEFAULT '',"
                    + "pending_stored INTEGER NOT NULL DEFAULT 0,"
                    + "interrupted_event_no TEXT NOT NULL DEFAULT '',"
                    + "interrupted_status TEXT NOT NULL DEFAULT '',"
                    + "interrupted_payload TEXT NOT NULL DEFAULT '',"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE messages ("
                    + "message_id TEXT PRIMARY KEY,"
                    + "config_version INTEGER NOT NULL,"
                    + "topic TEXT NOT NULL,"
                    + "payload TEXT NOT NULL,"
                    + "ack_event_no TEXT NOT NULL DEFAULT '',"
                    + "ack_status TEXT NOT NULL DEFAULT '',"
                    + "ack_payload TEXT NOT NULL DEFAULT '',"
                    + "terminal_status TEXT NOT NULL DEFAULT '',"
                    + "terminal_payload TEXT NOT NULL DEFAULT '',"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX idx_cash_ledger_state_version "
                    + "ON versions(state, config_version)");
            db.execSQL("CREATE INDEX idx_cash_ledger_messages_version "
                    + "ON messages(config_version, message_id)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }

        synchronized int getHighestKnownVersion() {
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT MAX(config_version) FROM versions",
                    null
            )) {
                return cursor.moveToFirst() && !cursor.isNull(0)
                        ? Math.max(0, cursor.getInt(0))
                        : 0;
            }
        }

        synchronized boolean insertVersionAndMessage(
                VersionRecord version,
                MessageRecord message
        ) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                if (insertVersion(db, version) == -1L
                        || insertMessage(db, message) == -1L) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }

        synchronized boolean insertMessage(MessageRecord message) {
            return insertMessage(getWritableDatabase(), message) != -1L;
        }

        synchronized void insertRejectedVersion(
                int configVersion,
                String canonical,
                String payload,
                String primaryMessageId,
                String resultCode,
                String resultMessage
        ) {
            VersionRecord record = new VersionRecord();
            record.configVersion = configVersion;
            record.canonical = safe(canonical);
            record.topic = "";
            record.payload = safe(payload);
            record.primaryMessageId = safe(primaryMessageId);
            record.state = STATE_FAILED;
            record.success = false;
            record.resultCode = safe(resultCode);
            record.resultMessage = safe(resultMessage);
            record.pendingStored = false;
            record.interruptedEventNo = "";
            record.interruptedStatus = "";
            record.interruptedPayload = "";
            insertVersion(getWritableDatabase(), record);
        }

        synchronized VersionRecord findVersion(int configVersion) {
            try (Cursor cursor = getReadableDatabase().query(
                    "versions",
                    versionColumns(),
                    "config_version=?",
                    new String[]{String.valueOf(configVersion)},
                    null,
                    null,
                    null
            )) {
                return cursor.moveToFirst() ? readVersion(cursor) : null;
            }
        }

        synchronized MessageRecord findMessage(String messageId) {
            try (Cursor cursor = getReadableDatabase().query(
                    "messages",
                    messageColumns(),
                    "message_id=?",
                    new String[]{safe(messageId)},
                    null,
                    null,
                    null
            )) {
                return cursor.moveToFirst() ? readMessage(cursor) : null;
            }
        }

        synchronized List<MessageRecord> listMessages(int configVersion) {
            List<MessageRecord> result = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(
                    "messages",
                    messageColumns(),
                    "config_version=?",
                    new String[]{String.valueOf(configVersion)},
                    null,
                    null,
                    "message_id ASC"
            )) {
                while (cursor.moveToNext()) {
                    result.add(readMessage(cursor));
                }
            }
            return result;
        }

        synchronized List<VersionRecord> listNonTerminalVersions() {
            List<VersionRecord> result = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(
                    "versions",
                    versionColumns(),
                    "state NOT IN (?,?)",
                    new String[]{STATE_APPLIED, STATE_FAILED},
                    null,
                    null,
                    "config_version ASC"
            )) {
                while (cursor.moveToNext()) {
                    result.add(readVersion(cursor));
                }
            }
            return result;
        }

        synchronized boolean hasApplyingVersion() {
            try (Cursor cursor = getReadableDatabase().query(
                    "versions",
                    new String[]{"config_version"},
                    "state=?",
                    new String[]{STATE_APPLYING},
                    null,
                    null,
                    null,
                    "1"
            )) {
                return cursor.moveToFirst();
            }
        }

        synchronized VersionRecord findNextNonTerminal() {
            try (Cursor cursor = getReadableDatabase().query(
                    "versions",
                    versionColumns(),
                    "state IN (?,?)",
                    new String[]{STATE_QUEUED, STATE_DEFERRED},
                    null,
                    null,
                    "config_version ASC",
                    "1"
            )) {
                return cursor.moveToFirst() ? readVersion(cursor) : null;
            }
        }

        synchronized void moveDeferredToQueued() {
            ContentValues values = new ContentValues();
            values.put("state", STATE_QUEUED);
            values.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().update(
                    "versions",
                    values,
                    "state=?",
                    new String[]{STATE_DEFERRED}
            );
        }

        synchronized void updateState(int configVersion, String state) {
            ContentValues values = new ContentValues();
            values.put("state", safe(state));
            values.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().update(
                    "versions",
                    values,
                    "config_version=?",
                    new String[]{String.valueOf(configVersion)}
            );
        }

        synchronized void markPendingStored(int configVersion) {
            ContentValues values = new ContentValues();
            values.put("pending_stored", 1);
            values.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().update(
                    "versions",
                    values,
                    "config_version=?",
                    new String[]{String.valueOf(configVersion)}
            );
        }

        synchronized void markVersionTerminal(
                int configVersion,
                boolean success,
                String resultCode,
                String resultMessage
        ) {
            ContentValues values = new ContentValues();
            values.put("state", success ? STATE_APPLIED : STATE_FAILED);
            values.put("success", success ? 1 : 0);
            values.put("result_code", safe(resultCode));
            values.put("result_message", safe(resultMessage));
            values.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().update(
                    "versions",
                    values,
                    "config_version=?",
                    new String[]{String.valueOf(configVersion)}
            );
        }

        synchronized void saveTerminal(
                String messageId,
                String terminalStatus,
                String terminalPayload
        ) {
            ContentValues values = new ContentValues();
            values.put("terminal_status", safe(terminalStatus));
            values.put("terminal_payload", safe(terminalPayload));
            values.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().update(
                    "messages",
                    values,
                    "message_id=?",
                    new String[]{safe(messageId)}
            );
        }

        private static long insertVersion(SQLiteDatabase db, VersionRecord record) {
            ContentValues values = new ContentValues();
            values.put("config_version", record.configVersion);
            values.put("canonical", safe(record.canonical));
            values.put("topic", safe(record.topic));
            values.put("payload", safe(record.payload));
            values.put("primary_message_id", safe(record.primaryMessageId));
            values.put("state", safe(record.state));
            values.put("success", record.success ? 1 : 0);
            values.put("result_code", safe(record.resultCode));
            values.put("result_message", safe(record.resultMessage));
            values.put("pending_stored", record.pendingStored ? 1 : 0);
            values.put("interrupted_event_no", safe(record.interruptedEventNo));
            values.put("interrupted_status", safe(record.interruptedStatus));
            values.put("interrupted_payload", safe(record.interruptedPayload));
            values.put("updated_at", System.currentTimeMillis());
            return db.insertWithOnConflict(
                    "versions",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );
        }

        private static long insertMessage(SQLiteDatabase db, MessageRecord message) {
            ContentValues values = new ContentValues();
            values.put("message_id", safe(message.messageId));
            values.put("config_version", message.configVersion);
            values.put("topic", safe(message.topic));
            values.put("payload", safe(message.payload));
            values.put("ack_event_no", safe(message.ackEventNo));
            values.put("ack_status", safe(message.ackStatus));
            values.put("ack_payload", safe(message.ackPayload));
            values.put("terminal_status", safe(message.terminalStatus));
            values.put("terminal_payload", safe(message.terminalPayload));
            values.put("updated_at", System.currentTimeMillis());
            return db.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );
        }

        private static String[] versionColumns() {
            return new String[]{
                    "config_version",
                    "canonical",
                    "topic",
                    "payload",
                    "primary_message_id",
                    "state",
                    "success",
                    "result_code",
                    "result_message",
                    "pending_stored",
                    "interrupted_event_no",
                    "interrupted_status",
                    "interrupted_payload"
            };
        }

        private static String[] messageColumns() {
            return new String[]{
                    "message_id",
                    "config_version",
                    "topic",
                    "payload",
                    "ack_event_no",
                    "ack_status",
                    "ack_payload",
                    "terminal_status",
                    "terminal_payload"
            };
        }

        private static VersionRecord readVersion(Cursor cursor) {
            VersionRecord result = new VersionRecord();
            result.configVersion = cursor.getInt(0);
            result.canonical = cursor.getString(1);
            result.topic = cursor.getString(2);
            result.payload = cursor.getString(3);
            result.primaryMessageId = cursor.getString(4);
            result.state = cursor.getString(5);
            result.success = cursor.getInt(6) != 0;
            result.resultCode = cursor.getString(7);
            result.resultMessage = cursor.getString(8);
            result.pendingStored = cursor.getInt(9) != 0;
            result.interruptedEventNo = cursor.getString(10);
            result.interruptedStatus = cursor.getString(11);
            result.interruptedPayload = cursor.getString(12);
            return result;
        }

        private static MessageRecord readMessage(Cursor cursor) {
            MessageRecord result = new MessageRecord();
            result.messageId = cursor.getString(0);
            result.configVersion = cursor.getInt(1);
            result.topic = cursor.getString(2);
            result.payload = cursor.getString(3);
            result.ackEventNo = cursor.getString(4);
            result.ackStatus = cursor.getString(5);
            result.ackPayload = cursor.getString(6);
            result.terminalStatus = cursor.getString(7);
            result.terminalPayload = cursor.getString(8);
            return result;
        }
    }
}
