package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.hardware.SerialCashConfigurationAdapter;
import com.gouzhu.hardware.SerialMarbleHardwareAdapter;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;
import com.pinball.xiaoda.device.sdk.hardware.CashTier;
import com.pinball.xiaoda.device.sdk.hardware.DispenseRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.CashConfigurationCommandData;
import com.pinball.xiaoda.device.sdk.protocol.CashEventResponseCommandData;
import com.pinball.xiaoda.device.sdk.protocol.CommandResultAcknowledgement;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Platform command runtime backed by one active physical dispense order.
 */
final class PlatformCommandRuntime {

    private static final String TAG = "GouzhuPlatformV22";

    private static final int CMD_VERSION = 0x00;
    private static final int CMD_CASH_EVENT_STORED = 0x1A;
    private static final int CMD_HARDWARE_STATUS = 0x20;
    private static final int CMD_DISPENSE_TERMINAL_ACK = 0x31;

    private static final int EVT_VERSION = 0x00;
    private static final int EVT_CASH_ACCEPTED = 0x10;
    private static final int EVT_CASH_DEVICE_STATUS = 0x12;
    private static final int EVT_BEAD_STOCK = 0x20;
    private static final int EVT_BEAD_LOW = 0x21;
    private static final int EVT_BEAD_EMPTY = 0x22;
    private static final int EVT_BEAD_REFILLED = 0x23;
    private static final int EVT_DISPENSE_TERMINAL = 0x41;

    private static final long TERMINAL_ACK_ECHO_TIMEOUT_MS = 2500L;
    private static final long CONTROLLER_VERSION_TIMEOUT_MS = 5000L;
    private static final long MIN_CONTROLLER_PROTOCOL_VERSION = 0x02020000L;
    private static final int OUTBOX_FLUSH_BATCH_SIZE = 4;

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder decoder;
    private final SerialMarbleHardwareAdapter marbleAdapter;
    private final SerialCashConfigurationAdapter cashAdapter;
    private final ExecutorService hardwareExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-platform-hardware-v22");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentHashMap<String, SdkCommandDecoder.DecodedCommand>
            liveCommands = new ConcurrentHashMap<>();

    private boolean receiverRegistered;
    private long lastCashDeviceStatus = Long.MIN_VALUE;
    private volatile boolean controllerVersionKnown;
    private volatile boolean controllerProtocolReady;
    private volatile boolean controllerRecoveryCompleted;
    private volatile CountDownLatch controllerVersionLatch;

    private final SerialMarbleHardwareAdapter.Observer hardwareObserver =
            new SerialMarbleHardwareAdapter.Observer() {
                @Override
                public void onProgress(String messageId, int orderSequence, int actual) {
                    store.updatePhysicalProgress(orderSequence, actual);
                    DeviceCommandStore.ActivePhysicalOrder active =
                            store.loadActivePhysicalOrder();
                    int requested = active == null ? 0 : active.requestedQuantity;
                    broadcastDispenseOrder(
                            "progress",
                            orderSequence,
                            requested,
                            actual,
                            0,
                            "dispense progress"
                    );
                }

                @Override
                public boolean onTerminalEvidence(
                        String messageId,
                        SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence
                ) {
                    return persistTerminalEvidence(messageId, evidence);
                }

                @Override
                public void onTerminalAckEcho(
                        String messageId,
                        SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
                        boolean echoed
                ) {
                    handleTerminalAckEcho(messageId, evidence, echoed);
                }
            };

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                return;
            }
            handleBoardEvent(
                    intent.getIntExtra("frameId", -1),
                    intent.getIntExtra("code2", -1),
                    intent.getLongExtra("data", 0L),
                    intent.getIntExtra("expandCode", 0)
            );
        }
    };

    PlatformCommandRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.decoder = new SdkCommandDecoder();
        this.marbleAdapter = new SerialMarbleHardwareAdapter(this.context);
        this.cashAdapter = new SerialCashConfigurationAdapter(this.context);
    }

    synchronized void start() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_EVENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        boardReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                context.registerReceiver(boardReceiver, filter);
            }
            receiverRegistered = true;
        }

        marbleAdapter.start(hardwareObserver);
        cashAdapter.start();
        cashAdapter.markApplied(store.getCashConfigVersion());
        controllerVersionKnown = false;
        controllerProtocolReady = false;
        controllerRecoveryCompleted = false;
        cashAdapter.setProtocolV22Ready(false);
        cashAdapter.disableCashAcceptance();
        CountDownLatch versionLatch = new CountDownLatch(1);
        controllerVersionLatch = versionLatch;
        SerialManager.get(context).sendCommand(CMD_VERSION, 0L, false);
        try {
            versionLatch.await(CONTROLLER_VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            controllerVersionLatch = null;
        }
        if (!controllerProtocolReady) {
            cashAdapter.disableCashAcceptance();
            long lastPersistedBoardVersion = store.getBoardVersion();
            String versionDetail = controllerVersionKnown
                    ? "boardVersion=" + Long.toUnsignedString(lastPersistedBoardVersion)
                    : "boardVersion=unavailable, lastPersistedBoardVersion="
                            + Long.toUnsignedString(lastPersistedBoardVersion);
            MqttManager.get(context).reportFault(
                    "CONTROLLER_PROTOCOL_UNSUPPORTED",
                    "controller protocol version is below 2.2.0.0 or unavailable",
                    3,
                    versionDetail
            );
            return;
        }

        completeControllerRecoveryOnce();
    }

    synchronized void stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(boardReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        controllerRecoveryCompleted = false;
        cashAdapter.stop();
        marbleAdapter.stop();
    }

    /**
     * 控制板协议确认后只执行一次启动恢复。
     * 启动阶段版本查询若超时，后续重连收到有效 VersionReport 时仍会从这里自动补恢复。
     */
    private synchronized void completeControllerRecoveryOnce() {
        if (!controllerProtocolReady || controllerRecoveryCompleted) {
            return;
        }
        controllerRecoveryCompleted = true;
        try {
            int cleanedCashEvents = cleanupCompletedCashEvents();
            if (cleanedCashEvents > 0) {
                Log.i(TAG, "已清理完成的历史现金事件：" + cleanedCashEvents);
            }

            SerialManager.get(context).sendCommand(CMD_HARDWARE_STATUS, 0L, false);
            recoverActivePhysicalOrder();
            discardInterruptedCashConfiguration();
            if (canEnableCash()) {
                reapplyCashConfiguration();
            } else {
                cashAdapter.disableCashAcceptance();
            }
            Log.i(TAG, "控制板协议已确认，启动恢复流程完成：boardVersion=0x"
                    + Long.toHexString(store.getBoardVersion()));
        } catch (Throwable error) {
            controllerRecoveryCompleted = false;
            cashAdapter.disableCashAcceptance();
            Log.e(TAG, "控制板启动恢复流程失败，等待后续版本帧重试", error);
        }
    }

    void handleCommand(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.e(TAG, "reject invalid sdk command", error);
            MqttManager.get(context).reportFault(
                    "COMMAND_PROTOCOL_INVALID",
                    "platform command validation failed",
                    3,
                    messageOf(error)
            );
            return;
        }

        String messageId = decoded.sdkCommand.getMessageId();
        String commandType = decoded.sdkCommand.getCommandType();
        if (blank(messageId) || blank(commandType)) {
            return;
        }

        if ("command_result_ack".equals(commandType)) {
            handleCommandResultAcknowledgement(decoded);
            return;
        }
        if ("cash_event_response".equals(commandType)) {
            handleCashEventResponse(decoded);
            return;
        }
        if ("redemption_response".equals(commandType)) {
            handleRedemptionResponse(decoded.envelope.optJSONObject("data"));
            return;
        }

        if (store.hasCommand(messageId)) {
            resendCommandResults(messageId);
            return;
        }

        switch (commandType) {
            case "dispense_marbles":
                acceptDispense(decoded, topic);
                break;
            case "collect_marbles":
                acceptCollect(decoded);
                break;
            case "sync_cash_configuration":
                acceptCashConfiguration(decoded);
                break;
            default:
                if (store.saveCommand(decoded.envelope)) {
                    publishSdkGenericTerminal(
                            decoded,
                            false,
                            "UNSUPPORTED_COMMAND",
                            "unsupported command type: " + commandType
                    );
                }
                break;
        }
    }

    private void acceptDispense(SdkCommandDecoder.DecodedCommand decoded, String sourceTopic) {
        if (!controllerProtocolReady) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "CONTROLLER_PROTOCOL_UNSUPPORTED",
                    "controller protocol 2.2.0.0 is required"
            );
            cashAdapter.disableCashAcceptance();
            return;
        }

        final DispenseRequest request;
        try {
            request = decoded.toDispenseRequest(System.currentTimeMillis());
        } catch (Throwable error) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "SDK_HARDWARE_MAPPING_FAILED",
                    messageOf(error)
            );
            return;
        }

        int quantity = request.getQuantity();
        if (quantity <= 0 || quantity > 0xFFFF) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "PARAM_INVALID",
                    "quantity must be 1..65535"
            );
            return;
        }

        DeviceCommandStore.CreatePhysicalOrderResult createResult =
                store.createActivePhysicalOrder(decoded.envelope, sourceTopic, quantity);
        if (!createResult.success) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    safe(createResult.resultCode, "PREVIOUS_PHYSICAL_ORDER_ACTIVE"),
                    blank(createResult.resultMessage)
                            ? "previous physical order is still active"
                            : createResult.resultMessage
            );
            return;
        }

        liveCommands.put(createResult.messageId, decoded);
        if (!publishSdkAck(decoded)) {
            store.markActivePhysicalBlocked("LOCAL_ACK_OUTBOX_FAILED");
            liveCommands.remove(createResult.messageId);
            reportStorageFault("dispense acknowledgement could not be saved");
            return;
        }

        cashAdapter.disableCashAcceptance();
        broadcastDispenseOrder(
                "started",
                createResult.orderSequence,
                createResult.requestedQuantity,
                0,
                0,
                "dispense started"
        );

        hardwareExecutor.execute(() -> {
            HardwareExecutionResult result = marbleAdapter.dispenseOrder(
                    request,
                    createResult.orderSequence
            );
            if (result == null) {
                persistUnknownPhysicalResult(
                        createResult.messageId,
                        0,
                        "hardware result missing"
                );
                broadcastDispenseOrder(
                        "blocked",
                        createResult.orderSequence,
                        createResult.requestedQuantity,
                        0,
                        -1,
                        "hardware result missing"
                );
                return;
            }
            String resultCode = safe(result.getResultCode());
            if ("CONTROLLER_RESULT_TIMEOUT".equals(resultCode)
                    || "CONTROLLER_TERMINAL_MISSING".equals(resultCode)) {
                persistUnknownPhysicalResult(
                        createResult.messageId,
                        Math.max(0, result.getActualQuantity()),
                        safe(result.getResultMessage())
                );
                broadcastDispenseOrder(
                        "blocked",
                        createResult.orderSequence,
                        createResult.requestedQuantity,
                        Math.max(0, result.getActualQuantity()),
                        -1,
                        safe(result.getResultMessage())
                );
                reportPhysicalUnknown(
                        createResult.messageId,
                        Math.max(0, result.getActualQuantity()),
                        safe(result.getResultMessage())
                );
            }
        });
    }

    private void acceptCollect(SdkCommandDecoder.DecodedCommand decoded) {
        if (store.saveCommand(decoded.envelope)) {
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "COLLECT_MAINTENANCE_ONLY",
                    "collect is a local maintenance function"
            );
        }
    }

    synchronized boolean startPendingCollection() {
        broadcastCollection(
                DeviceCommandManager.COLLECTION_FAILED,
                "collect is a local maintenance function"
        );
        return false;
    }

    synchronized boolean finishPendingCollection() {
        return false;
    }

    boolean hasPendingCollection() {
        return false;
    }

    int getRunningStatus() {
        return store.hasActivePhysicalOrder() ? 1 : 0;
    }

    void broadcastActivePhysicalOrderState() {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null) {
            broadcastDispenseOrder("idle", 0, 0, 0, 0, "");
            return;
        }
        if ("FINISHING".equals(active.state)) {
            broadcastDispenseOrder(
                    "finishing",
                    active.orderSequence,
                    active.requestedQuantity,
                    Math.max(active.lastProgressActual, active.terminalActual),
                    active.terminalResultCode,
                    "finishing dispense order"
            );
            return;
        }
        if ("BLOCKED".equals(active.state)) {
            broadcastDispenseOrder(
                    "blocked",
                    active.orderSequence,
                    active.requestedQuantity,
                    Math.max(active.lastProgressActual, active.terminalActual),
                    active.terminalResultCode,
                    safe(active.blockedReason, "physical order blocked")
            );
            return;
        }
        broadcastDispenseOrder(
                "started",
                active.orderSequence,
                active.requestedQuantity,
                active.lastProgressActual,
                0,
                "dispense order active"
        );
    }

    void flushPending() {
        int remaining = OUTBOX_FLUSH_BATCH_SIZE;
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (remaining-- <= 0
                    || !MqttManager.get(context).reportCommandResult(item.payload)) {
                return;
            }
        }
        for (DeviceCommandStore.OutboxItem item : store.listCashEvents()) {
            if (remaining-- <= 0
                    || !MqttManager.get(context).reportCashEvent(item.payload)) {
                return;
            }
        }
    }

    private void acceptCashConfiguration(SdkCommandDecoder.DecodedCommand decoded) {
        String messageId = decoded.sdkCommand.getMessageId();
        final CashConfigurationCommandData config;
        try {
            config = decoded.sdkCommand.requireData(CashConfigurationCommandData.class);
        } catch (Throwable error) {
            cashAdapter.disableCashAcceptance();
            persistAndPublishConfigurationFailure(
                    decoded,
                    "CASH_CONFIGURATION_INVALID",
                    messageOf(error),
                    false
            );
            return;
        }

        String validationError = validateCashConfiguration(config);
        if (validationError != null) {
            cashAdapter.disableCashAcceptance();
            persistAndPublishConfigurationFailure(
                    decoded,
                    "CASH_CONFIGURATION_INVALID",
                    validationError,
                    false
            );
            return;
        }

        long configVersion = config.getConfigVersion();
        if (configVersion <= store.getLatestCashConfigVersion()) {
            cashAdapter.disableCashAcceptance();
            persistAndPublishConfigurationFailure(
                    decoded,
                    "CASH_CONFIGURATION_STALE",
                    "configVersion must be greater than local latest version",
                    false
            );
            return;
        }

        boolean enabled = config.isCashAcceptanceEnabled();
        if (enabled && !canEnableCash()) {
            Log.w(
                    TAG,
                    "现金启用被门禁拒绝："
                            + "protocolReady=" + controllerProtocolReady
                            + ", activeOrder=" + store.hasActivePhysicalOrder()
                            + ", physicalBlocked=" + store.isPhysicalBlocked()
                            + ", cashBlocked=" + store.isCashBlocked()
                            + ", boardVersion=0x"
                            + Long.toHexString(store.getBoardVersion())
            );

            cashAdapter.disableCashAcceptance();
            persistAndPublishConfigurationFailure(
                    decoded,
                    controllerProtocolReady
                            ? "PHYSICAL_ORDER_ACTIVE"
                            : "CONTROLLER_PROTOCOL_UNSUPPORTED",
                    controllerProtocolReady
                            ? "cash cannot be enabled while a physical order is active or blocked"
                            : "controller protocol 2.2.0.0 is required before enabling cash",
                    false
            );
            return;
        }

        List<CashTier> tiers = toCashTiers(config);
        final SdkCommandDecoder.EncodedResult acknowledgement;
        final SdkCommandDecoder.EncodedResult interruptedTerminal;
        try {
            long nowMillis = System.currentTimeMillis();
            acknowledgement = decoded.configurationAcknowledgement(
                    messageId + "-ack",
                    nowMillis
            );
            interruptedTerminal = decoded.configurationTerminal(
                    messageId + "-result",
                    false,
                    "CASH_CONFIGURATION_INTERRUPTED",
                    "cash configuration was interrupted by app restart",
                    nowMillis
            );
        } catch (Throwable error) {
            cashAdapter.disableCashAcceptance();
            reportStorageFault("cash configuration receipt encode failed: " + messageOf(error));
            return;
        }

        if (!store.savePendingCashConfiguration(
                decoded.envelope,
                (int) configVersion,
                enabled,
                config.isChangeEnabled(),
                decoded.envelope.toString(),
                acknowledgement.eventNo,
                acknowledgement.resultStatus,
                acknowledgement.payload,
                interruptedTerminal.eventNo,
                interruptedTerminal.resultStatus,
                interruptedTerminal.payload
        )) {
            cashAdapter.disableCashAcceptance();
            persistAndPublishConfigurationFailure(
                    decoded,
                    "LOCAL_STORAGE_ERROR",
                    "cash configuration, ack, and interrupted terminal could not be saved",
                    false
            );
            return;
        }

        liveCommands.put(messageId, decoded);
        MqttManager.get(context).reportCommandResult(acknowledgement.payload);
        hardwareExecutor.execute(() -> {
            try {
                CashConfigurationResult result = enabled
                        ? cashAdapter.apply(configVersion, tiers)
                        : cashAdapter.applyDisabled(configVersion);
                if (result != null && result.isApplied()) {
                    final SdkCommandDecoder.EncodedResult successTerminal;
                    try {
                        successTerminal = decoded.configurationTerminal(
                                messageId + "-result",
                                true,
                                "CASH_CONFIGURATION_APPLIED",
                                "cash configuration applied",
                                System.currentTimeMillis()
                        );
                    } catch (Throwable error) {
                        cashAdapter.disableCashAcceptance();
                        persistAndPublishConfigurationFailure(
                                decoded,
                                "LOCAL_STORAGE_ERROR",
                                "cash configuration success terminal encode failed: "
                                        + messageOf(error),
                                true
                        );
                        return;
                    }

                    if (store.commitPendingCashConfigurationAndResult(
                            messageId,
                            successTerminal.eventNo,
                            successTerminal.resultStatus,
                            successTerminal.payload
                    )) {
                        cashAdapter.markApplied(configVersion);
                        MqttManager.get(context).reportCommandResult(
                                successTerminal.payload
                        );
                    } else {
                        cashAdapter.disableCashAcceptance();
                        persistAndPublishConfigurationFailure(
                                decoded,
                                "LOCAL_STORAGE_ERROR",
                                "controller applied cash config but local transaction failed",
                                true
                        );
                    }
                } else {
                    cashAdapter.disableCashAcceptance();
                    persistAndPublishConfigurationFailure(
                            decoded,
                            "CASH_CONFIGURATION_APPLY_FAILED",
                            result == null
                                    ? "cash adapter did not return a result"
                                    : safe(result.getMessage()),
                            true
                    );
                }
            } finally {
                liveCommands.remove(messageId);
            }
        });
    }

    private boolean persistTerminalEvidence(
            String messageId,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence
    ) {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null || active.orderSequence != evidence.orderSequence) {
            return false;
        }
        String sourceMessageId = blank(messageId) ? active.messageId : messageId;
        SdkCommandDecoder.DecodedCommand decoded = liveCommands.get(sourceMessageId);
        JSONObject envelope = store.loadCommand(sourceMessageId);
        if (decoded == null && envelope != null) {
            decoded = decodeStoredCommand(envelope, active.sourceTopic);
        }
        if (decoded == null || envelope == null) {
            if (blank(active.sourceTopic)) {
                store.markActivePhysicalBlocked("STORED_COMMAND_TOPIC_MISSING");
                cashAdapter.disableCashAcceptance();
                reportStorageFault("stored physical command source topic is missing: "
                        + sourceMessageId);
            }
            reportStorageFault("active physical command context is missing: " + sourceMessageId);
            return false;
        }

        Log.i(
                TAG,
                "收到出珠终态："
                        + "state=" + active.state
                        + ", sequence=" + evidence.orderSequence
                        + ", requested=" + active.requestedQuantity
                        + ", terminalActual=" + evidence.terminalActual
                        + ", progressActual=" + evidence.lastProgressActual
                        + ", controllerResult=" + evidence.controllerResultCode
        );

        int finalActual = Math.max(
                Math.max(active.lastProgressActual, evidence.lastProgressActual),
                evidence.terminalActual
        );
        boolean success = isSuccessfulTerminal(active, evidence);
        String resultCode = terminalResultCode(active, evidence, success);
        String resultMessage = terminalResultMessage(active, evidence, success);
        String eventNo = blank(active.terminalEventNo)
                ? sourceMessageId + "-result"
                : active.terminalEventNo;

        try {
            SdkCommandDecoder.EncodedResult terminal = decoded.physicalTerminal(
                    eventNo,
                    success,
                    finalActual,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            );
            DeviceCommandStore.TerminalStoreResult storeResult =
                    store.savePhysicalTerminalAndOutbox(
                            envelope,
                            evidence,
                            success,
                            finalActual,
                            resultCode,
                            resultMessage,
                            terminal.eventNo,
                            terminal.resultStatus,
                            terminal.payload
                    );
            if (!storeResult.success) {
                reportStorageFault("physical terminal could not be saved: "
                        + safe(storeResult.resultCode));
                return false;
            }
            if (storeResult.conflict) {
                MqttManager.get(context).reportFault(
                        "PHYSICAL_TERMINAL_CONFLICT",
                        "controller returned conflicting physical terminal evidence",
                        3,
                        "messageId=" + sourceMessageId
                                + ", seq=" + evidence.orderSequence
                                + ", frameId=" + evidence.frameId
                );
                broadcastDispenseOrder(
                        "blocked",
                        evidence.orderSequence,
                        active.requestedQuantity,
                        finalActual,
                        evidence.controllerResultCode,
                        "terminal conflict"
                );
                return true;
            }
            if (storeResult.lateAfterUnknown) {
                MqttManager.get(context).reportFault(
                        "LATE_CONTROLLER_TERMINAL_AFTER_UNKNOWN",
                        "controller terminal arrived after unknown result was persisted",
                        2,
                        "messageId=" + sourceMessageId
                                + ", seq=" + evidence.orderSequence
                                + ", frameId=" + evidence.frameId
                );
                broadcastDispenseOrder(
                        "blocked",
                        evidence.orderSequence,
                        active.requestedQuantity,
                        finalActual,
                        evidence.controllerResultCode,
                        "late terminal recorded after unknown result"
                );
                return true;
            }
            MqttManager.get(context).reportCommandResult(terminal.payload);
            broadcastDispenseOrder(
                    success ? "finishing" : "blocked",
                    evidence.orderSequence,
                    active.requestedQuantity,
                    finalActual,
                    evidence.controllerResultCode,
                    success ? "finishing dispense order" : resultMessage
            );
            return true;
        } catch (Throwable error) {
            reportStorageFault("physical terminal encode failed: " + messageOf(error));
            return false;
        }
    }

    private void handleTerminalAckEcho(
            String messageId,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
            boolean echoed
    ) {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null || active.orderSequence != evidence.orderSequence) {
            return;
        }
        boolean success = isSuccessfulTerminal(active, evidence);
        if (echoed) {
            boolean updated = store.markTerminalAckEchoed(
                    evidence.orderSequence,
                    evidence.frameId,
                    success
            );
            if (!updated) {
                reportStorageFault(
                        "terminal ack echo state could not be saved: seq="
                                + evidence.orderSequence
                                + ", frameId=" + evidence.frameId
                );
                return;
            }

            liveCommands.remove(active.messageId);
            if (success) {
                broadcastDispenseOrder(
                        "finished",
                        evidence.orderSequence,
                        active.requestedQuantity,
                        evidence.terminalActual,
                        evidence.controllerResultCode,
                        "dispense completed"
                );
                if (canEnableCash()) {
                    reapplyCashConfiguration();
                }
            } else {
                broadcastDispenseOrder(
                        "blocked",
                        evidence.orderSequence,
                        active.requestedQuantity,
                        Math.max(active.lastProgressActual, evidence.terminalActual),
                        evidence.controllerResultCode,
                        terminalResultMessage(active, evidence, false)
                );
            }
            return;
        }

        store.markTerminalAckSentWithoutEcho(evidence.orderSequence, evidence.frameId);
        cashAdapter.disableCashAcceptance();
    }

    private void handleBoardEvent(
            int frameId,
            int code2,
            long packed,
            int expandCode
    ) {
        switch (code2) {
            case EVT_VERSION:
                store.saveBoardVersion(packed);
                controllerVersionKnown = true;
                controllerProtocolReady = isSupportedControllerVersion(packed);
                cashAdapter.setProtocolV22Ready(controllerProtocolReady);
                CountDownLatch versionLatch = controllerVersionLatch;
                if (versionLatch != null) {
                    versionLatch.countDown();
                }
                if (!controllerProtocolReady) {
                    cashAdapter.disableCashAcceptance();
                    MqttManager.get(context).reportFault(
                            "CONTROLLER_PROTOCOL_UNSUPPORTED",
                            "controller protocol version is below 2.2.0.0",
                            3,
                            "boardVersion=" + Long.toUnsignedString(packed)
                    );
                } else {
                    // 启动时版本查询即使已经超时，后续重连收到有效版本也必须补执行恢复。
                    completeControllerRecoveryOnce();
                }
                MqttManager.get(context).reportStatus();
                break;
            case EVT_CASH_ACCEPTED:
                persistCashFact(packed, expandCode);
                break;
            case EVT_CASH_DEVICE_STATUS:
                if (packed != lastCashDeviceStatus) {
                    lastCashDeviceStatus = packed;
                    Log.i(TAG, "cash device status=0x" + Long.toHexString(packed));
                }
                break;
            case EVT_BEAD_STOCK: {
                boolean hasStock = packed > 0L;
                boolean wasCashBlocked = store.isCashBlocked();

                // cash_blocked只表示真实库存/硬件阻塞，
                // 不再表示历史现金配置失败。
                store.setCashBlocked(!hasStock);

                broadcastHardwareStatus("stock: " + packed);

                if (!hasStock) {
                    cashAdapter.disableCashAcceptance();
                } else if (wasCashBlocked && canEnableCash()) {
                    // 自动修复旧版本遗留的cash_blocked=1。
                    reapplyCashConfiguration();
                }
                break;
            }
            case EVT_BEAD_LOW:
                broadcastHardwareStatus("stock low: " + packed);
                break;
            case EVT_BEAD_EMPTY:
                cashAdapter.disableCashAcceptance();
                store.setCashBlocked(true);
                broadcastHardwareStatus("empty; cash disabled");
                break;
            case EVT_BEAD_REFILLED:
                store.setCashBlocked(false);
                broadcastHardwareStatus("refilled: " + packed);
                if (canEnableCash()) {
                    reapplyCashConfiguration();
                }
                break;
            case EVT_DISPENSE_TERMINAL:
                persistRecoveringTerminal(frameId, packed, expandCode);
                break;
            default:
                break;
        }
    }

    private void persistRecoveringTerminal(int frameId, long packed, int expandCode) {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null || liveCommands.containsKey(active.messageId)) {
            return;
        }
        int sequence = (int) ((packed >>> 16) & 0xFFFF);
        int actual = (int) (packed & 0xFFFF);
        if (sequence != active.orderSequence || frameId < 0 || frameId > 0xFF) {
            return;
        }
        SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence =
                new SerialMarbleHardwareAdapter.ControllerTerminalEvidence(
                        frameId,
                        sequence,
                        actual,
                        expandCode & 0xFF,
                        active.lastProgressActual,
                        System.currentTimeMillis()
                );
        if (!persistTerminalEvidence(active.messageId, evidence)) {
            return;
        }
        hardwareExecutor.execute(() -> {
            boolean echoed = sendTerminalAck(evidence.orderSequence, evidence.frameId);
            handleTerminalAckEcho(active.messageId, evidence, echoed);
        });
    }

    private boolean sendTerminalAck(int orderSequence, int frameId) {
        try {
            long data = ((long) (orderSequence & 0xFFFF) << 16)
                    | ((long) (frameId & 0xFF) << 8);
            return SerialManager.get(context).sendCommandAndWaitEcho(
                    CMD_DISPENSE_TERMINAL_ACK,
                    data,
                    TERMINAL_ACK_ECHO_TIMEOUT_MS
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void persistUnknownPhysicalResult(
            String messageId,
            int observedActual,
            String description
    ) {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null || !safe(messageId).equals(active.messageId)) {
            return;
        }
        SdkCommandDecoder.DecodedCommand decoded = liveCommands.get(active.messageId);
        JSONObject envelope = store.loadCommand(active.messageId);
        if (decoded == null && envelope != null) {
            decoded = decodeStoredCommand(envelope, active.sourceTopic);
        }
        if (decoded == null || envelope == null) {
            store.markActivePhysicalBlocked("STORED_COMMAND_TOPIC_MISSING");
            cashAdapter.disableCashAcceptance();
            reportStorageFault("unknown physical result context is missing: "
                    + active.messageId);
            return;
        }

        int safeActual = Math.max(0, Math.min(0xFFFF, observedActual));
        String eventNo = blank(active.terminalEventNo)
                ? active.messageId + "-result"
                : active.terminalEventNo;
        String resultMessage = blank(description)
                ? "controller did not return final dispense result; manual review required"
                : description;
        try {
            SdkCommandDecoder.EncodedResult terminal = decoded.physicalTerminal(
                    eventNo,
                    false,
                    safeActual,
                    "CONTROLLER_TERMINAL_MISSING",
                    resultMessage,
                    System.currentTimeMillis()
            );
            DeviceCommandStore.TerminalStoreResult storeResult =
                    store.savePhysicalUnknownResult(
                            envelope,
                            active.orderSequence,
                            safeActual,
                            "CONTROLLER_TERMINAL_MISSING",
                            resultMessage,
                            terminal.eventNo,
                            terminal.resultStatus,
                            terminal.payload
                    );
            if (!storeResult.success) {
                reportStorageFault("unknown physical result could not be saved: "
                        + safe(storeResult.resultCode));
                return;
            }
            MqttManager.get(context).reportCommandResult(
                    blank(storeResult.payload) ? terminal.payload : storeResult.payload
            );
            cashAdapter.disableCashAcceptance();
        } catch (Throwable error) {
            reportStorageFault("unknown physical result encode failed: "
                    + messageOf(error));
        }
    }

    private void recoverActivePhysicalOrder() {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null) {
            return;
        }
        cashAdapter.disableCashAcceptance();
        broadcastDispenseOrder(
                "recovering",
                active.orderSequence,
                active.requestedQuantity,
                Math.max(0, active.lastProgressActual),
                Math.max(0, active.terminalResultCode),
                "recovering previous physical order"
        );
        MqttManager.get(context).reportFault(
                "PHYSICAL_ORDER_RECOVERING",
                "app restarted with an active physical order",
                3,
                "messageId=" + active.messageId
                        + ", seq=" + active.orderSequence
                        + ", state=" + safe(active.state)
        );
        if ("FINISHING".equals(active.state)
                && active.terminalFrameId >= 0
                && !active.terminalAckEchoed) {
            hardwareExecutor.execute(() -> {
                boolean echoed = sendTerminalAck(
                        active.orderSequence,
                        active.terminalFrameId
                );
                SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence =
                        new SerialMarbleHardwareAdapter.ControllerTerminalEvidence(
                                active.terminalFrameId,
                                active.orderSequence,
                                Math.max(0, active.controllerTerminalActual),
                                Math.max(0, active.terminalResultCode),
                                Math.max(0, active.lastProgressActual),
                                System.currentTimeMillis()
                        );
                handleTerminalAckEcho(active.messageId, evidence, echoed);
            });
        }
    }

    private static boolean isSuccessfulTerminal(
            DeviceCommandStore.ActivePhysicalOrder active,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence
    ) {
        if (active == null || evidence == null) {
            return false;
        }
        boolean validOrderState = "DISPENSING".equals(active.state)
                || "FINISHING".equals(active.state);
        return validOrderState
                && evidence.controllerResultCode == 0
                && evidence.terminalActual > 0
                && evidence.terminalActual == active.requestedQuantity;
    }

    private String terminalResultCode(
            DeviceCommandStore.ActivePhysicalOrder active,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
            boolean success
    ) {
        if (success) {
            return "OK";
        }
        if (evidence.terminalActual < active.lastProgressActual
                || evidence.terminalActual < evidence.lastProgressActual) {
            return "CONTROLLER_ACTUAL_REGRESSION";
        }
        if (evidence.controllerResultCode == 0) {
            return "ACTUAL_QUANTITY_MISMATCH";
        }
        return SerialMarbleHardwareAdapter.boardResultName(evidence.controllerResultCode);
    }

    private String terminalResultMessage(
            DeviceCommandStore.ActivePhysicalOrder active,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
            boolean success
    ) {
        if (success) {
            return "dispense completed";
        }
        if (evidence.terminalActual < active.lastProgressActual
                || evidence.terminalActual < evidence.lastProgressActual) {
            return "terminal actual regressed from progress actual";
        }
        if (evidence.controllerResultCode == 0) {
            return "terminal actual does not equal requested quantity";
        }
        return "controller result: "
                + SerialMarbleHardwareAdapter.boardResultName(evidence.controllerResultCode);
    }

    private void persistCashFact(long packed, int sequenceLow) {
        int mediumCode = (int) ((packed >>> 24) & 0xFF);
        int amountFen = (int) ((packed >>> 8) & 0xFFFF);
        int sequence = (((int) packed & 0xFF) << 8) | (sequenceLow & 0xFF);
        if (sequence <= 0 || amountFen <= 0
                || (mediumCode != 0 && mediumCode != 1)) {
            return;
        }

        String medium = mediumCode == 0 ? "coin" : "banknote";
        int currentConfigVersion = store.getCashConfigVersion();
        DeviceCommandStore.CashEventRecord existing =
                store.findCashEventBySequence(sequence);
        if (existing != null) {
            JSONObject existingPayload = parseObject(existing.payload);
            boolean sameCashFact = existingPayload != null
                    && medium.equals(existingPayload.optString("cashMediumType", ""))
                    && amountFen == existingPayload.optInt("denominationAmount", -1)
                    && currentConfigVersion == existingPayload.optInt("configVersion", -1);
            boolean stillPending = hasPendingCashOutbox(existing.eventNo);

            if (stillPending && sameCashFact) {
                confirmCashStored(sequence);
                MqttManager.get(context).reportCashEvent(existing.payload);
                return;
            }

            Log.w(
                    TAG,
                    "现金序号复用，删除旧现金记录：sequence="
                            + sequence
                            + ", oldEventNo=" + existing.eventNo
                            + ", oldStatus=" + safe(existing.status)
                            + ", sameCashFact=" + sameCashFact
                            + ", hasOutbox=" + stillPending
            );
            if (!removeCashEventRecord(existing.eventNo)) {
                reportStorageFault("old cash event could not be removed: "
                        + existing.eventNo);
                return;
            }
        }

        DeviceCommandStore.CashTier tier = store.findCashTier(medium, amountFen);
        String eventNo = newCashEventNo(sequence);
        try {
            JSONObject payload = new JSONObject();
            payload.put("eventNo", eventNo);
            payload.put("eventType", "accepted");
            payload.put("cashMediumType", medium);
            payload.put("denominationAmount", amountFen);
            payload.put("cashCount", 1);
            payload.put("boardSequence", sequence);
            payload.put("cashSaleTierNo", tier == null ? "" : tier.cashSaleTierNo);
            payload.put(
                    "configVersion",
                    tier == null ? currentConfigVersion : tier.configVersion
            );
            payload.put("timestamp", System.currentTimeMillis());

            if (!store.saveCashEvent(eventNo, sequence, payload.toString())) {
                reportStorageFault("cash fact could not be saved");
                return;
            }

            confirmCashStored(sequence);
            MqttManager.get(context).reportCashEvent(payload.toString());

            if (tier == null) {
                MqttManager.get(context).reportFault(
                        "CASH_TIER_NOT_FOUND",
                        "cash tier not found",
                        3,
                        medium + ", amountFen=" + amountFen
                );
            }
        } catch (Throwable error) {
            reportStorageFault("cash fact encode failed: " + messageOf(error));
        }
    }

    private boolean hasPendingCashOutbox(String eventNo) {
        if (blank(eventNo)) {
            return false;
        }
        synchronized (store) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    "outbox",
                    new String[]{"id"},
                    "kind=? AND event_no=?",
                    new String[]{"cash_event", eventNo},
                    null,
                    null,
                    null)) {
                return cursor.moveToFirst();
            }
        }
    }

    private boolean removeCashEventRecord(String eventNo) {
        if (blank(eventNo)) {
            return false;
        }
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(
                        "outbox",
                        "kind=? AND event_no=?",
                        new String[]{"cash_event", eventNo}
                );
                int deleted = db.delete(
                        "cash_events",
                        "event_no=?",
                        new String[]{eventNo}
                );
                if (deleted != 1) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "remove cash event failed: " + eventNo, error);
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    private int cleanupCompletedCashEvents() {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                int deleted = db.delete(
                        "cash_events",
                        "status NOT IN (?,?) AND NOT EXISTS ("
                                + "SELECT 1 FROM outbox "
                                + "WHERE outbox.kind=? "
                                + "AND outbox.event_no=cash_events.event_no)",
                        new String[]{"pending", "unknown", "cash_event"}
                );
                db.setTransactionSuccessful();
                return deleted;
            } catch (Throwable error) {
                Log.e(TAG, "cleanup completed cash events failed", error);
                return 0;
            } finally {
                db.endTransaction();
            }
        }
    }

    private void confirmCashStored(int sequence) {
        SerialManager.get(context).sendCommand(
                CMD_CASH_EVENT_STORED,
                sequence & 0xFFFFL,
                true
        );
    }

    private void handleCashEventResponse(SdkCommandDecoder.DecodedCommand decoded) {
        CashEventResponseCommandData response = decoded.requireCashEventResponse();
        String eventNo = response.getEventNo();
        DeviceCommandStore.CashEventRecord record = store.findCashEvent(eventNo);
        if (record == null) {
            return;
        }

        if (response.isUnknown()) {
            store.updateCashEventStatus(eventNo, "unknown");
            MqttManager.get(context).reportCashEvent(record.payload);
            return;
        }

        if (!removeCashEventRecord(eventNo)) {
            reportStorageFault("final cash event could not be removed: " + eventNo);
            return;
        }
        Log.i(
                TAG,
                "现金事实已获平台终态并删除本地记录：eventNo="
                        + eventNo
                        + ", status=" + safe(response.getStatus())
        );
        if (response.isManualReview() || response.isRejected()) {
            MqttManager.get(context).reportFault(
                    "CASH_EVENT_" + safe(response.getStatus()).toUpperCase(Locale.ROOT),
                    "cash event needs handling",
                    2,
                    safe(response.getMessage())
            );
        }
    }

    private void handleCommandResultAcknowledgement(
            SdkCommandDecoder.DecodedCommand decoded
    ) {
        CommandResultAcknowledgement acknowledgement =
                decoded.sdkCommand.requireData(CommandResultAcknowledgement.class);
        if (!acknowledgement.isRecorded()) {
            for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
                if (safe(acknowledgement.getSourceMessageId()).equals(item.sourceMessageId)
                        && safe(acknowledgement.getEventNo()).equals(item.eventNo)
                        && safe(acknowledgement.getResultStatus()).equals(
                                item.resultStatus)) {
                    MqttManager.get(context).reportCommandResult(item.payload);
                }
            }
            return;
        }
        store.removeCommandResult(
                acknowledgement.getSourceMessageId(),
                acknowledgement.getEventNo(),
                acknowledgement.getResultStatus()
        );
    }

    private void handleRedemptionResponse(JSONObject data) {
        String status = data == null
                ? "unknown"
                : data.optString("status", "unknown");
        String message;
        if ("accepted".equals(status)) {
            message = "redemption accepted; waiting for platform dispense command";
        } else if ("rejected".equals(status)) {
            message = data.optString("message", "redemption failed");
        } else {
            message = "redemption result unknown; please contact staff";
        }
        Intent intent = new Intent(PaymentManager.ACTION_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(PaymentManager.EXTRA_EVENT, PaymentManager.EVENT_WAITING);
        intent.putExtra(PaymentManager.EXTRA_MESSAGE, message);
        context.sendBroadcast(intent);
    }

    private boolean publishSdkAck(SdkCommandDecoder.DecodedCommand decoded) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            return saveAndPublish(decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            ));
        } catch (Throwable error) {
            Log.e(TAG, "SDK ACK encode failed", error);
            return false;
        }
    }

    private boolean persistAndPublishConfigurationFailure(
            SdkCommandDecoder.DecodedCommand decoded,
            String resultCode,
            String resultMessage,
            boolean clearPending
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            SdkCommandDecoder.EncodedResult terminal =
                    decoded.configurationTerminal(
                            messageId + "-result",
                            false,
                            resultCode,
                            safe(resultMessage),
                            System.currentTimeMillis()
                    );
            if (!store.failCashConfigurationAndResult(
                    decoded.envelope,
                    terminal.sourceMessageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminal.payload,
                    clearPending
            )) {
                reportStorageFault("cash configuration failed terminal could not be saved");
                return false;
            }
            MqttManager.get(context).reportCommandResult(terminal.payload);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "cash configuration failed terminal encode failed", error);
            reportStorageFault(
                    "cash configuration failed terminal encode failed: " + messageOf(error));
            return false;
        }
    }

    private boolean publishSdkGenericTerminal(
            SdkCommandDecoder.DecodedCommand decoded,
            boolean success,
            String resultCode,
            String resultMessage
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            return saveAndPublish(decoded.genericTerminal(
                    messageId + "-result",
                    success,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            ));
        } catch (Throwable error) {
            Log.e(TAG, "SDK generic terminal encode failed", error);
            return false;
        }
    }

    private boolean saveAndPublish(SdkCommandDecoder.EncodedResult result) {
        if (result == null || !store.saveCommandResult(
                result.sourceMessageId,
                result.eventNo,
                result.resultStatus,
                result.payload
        )) {
            return false;
        }
        MqttManager.get(context).reportCommandResult(result.payload);
        return true;
    }

    private void resendCommandResults(String messageId) {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (messageId.equals(item.sourceMessageId)) {
                MqttManager.get(context).reportCommandResult(item.payload);
            }
        }
    }

    private void discardInterruptedCashConfiguration() {
        String messageId = store.getPendingConfigMessageId();
        if (blank(messageId)) {
            return;
        }
        cashAdapter.disableCashAcceptance();
        DeviceCommandStore.OutboxItem terminal =
                store.failInterruptedCashConfiguration();
        if (terminal == null) {
            reportStorageFault("interrupted cash configuration could not be failed: "
                    + messageId);
            return;
        }
        MqttManager.get(context).reportCommandResult(terminal.payload);
        MqttManager.get(context).reportFault(
                "CASH_CONFIGURATION_INTERRUPTED",
                "cash configuration interrupted by app restart",
                3,
                "messageId=" + messageId
        );
    }

    private void reapplyCashConfiguration() {
        DeviceCommandStore.CashConfigurationRecord record =
                store.loadCashConfiguration();
        if (record == null || record.changeEnabled) {
            return;
        }
        if (record.enabled && !canEnableCash()) {
            cashAdapter.disableCashAcceptance();
            return;
        }

        JSONObject envelope = parseObject(record.snapshotJson);
        JSONObject data = envelope == null
                ? null
                : envelope.optJSONObject("data");
        JSONArray items = data == null
                ? null
                : data.optJSONArray("cashSaleItems");
        if (data == null || (record.enabled && items == null)) {
            return;
        }

        List<CashTier> tiers = new ArrayList<>();
        int itemCount = items == null ? 0 : items.length();
        for (int index = 0; index < itemCount; index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                return;
            }
            tiers.add(new CashTier(
                    item.optString("cashMediumType", ""),
                    item.optInt("denominationAmount", 0),
                    item.optInt("marbleQuantity", 0),
                    item.optString("cashSaleTierNo", "")
            ));
        }

        hardwareExecutor.execute(() -> {
            CashConfigurationResult result = record.enabled
                    ? cashAdapter.apply(record.configVersion, tiers)
                    : cashAdapter.applyDisabled(record.configVersion);
            if (!result.isApplied()) {
                MqttManager.get(context).reportFault(
                        "CASH_CONFIGURATION_REAPPLY_FAILED",
                        "cash configuration reapply failed",
                        2,
                        safe(result.getMessage())
                );
            } else {
                cashAdapter.markApplied(record.configVersion);
            }
        });
    }

    private boolean canEnableCash() {
        return controllerProtocolReady
                && !store.hasActivePhysicalOrder()
                && !store.isPhysicalBlocked()
                && !store.isCashBlocked();
    }

    private static boolean isSupportedControllerVersion(long value) {
        return (value & 0xFFFFFFFFL) >= MIN_CONTROLLER_PROTOCOL_VERSION;
    }

    private SdkCommandDecoder.DecodedCommand decodeStoredCommand(
            JSONObject envelope,
            String sourceTopic
    ) {
        if (blank(sourceTopic)) {
            MqttManager.get(context).reportFault(
                    "STORED_COMMAND_TOPIC_MISSING",
                    "stored physical command source topic is missing",
                    3,
                    "messageId=" + (envelope == null ? "" : envelope.optString("messageId", ""))
            );
            return null;
        }
        try {
            return decoder.decode(
                    sourceTopic,
                    envelope.toString().getBytes(StandardCharsets.UTF_8),
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            MqttManager.get(context).reportFault(
                    "PHYSICAL_ORDER_RECOVERY_CONTEXT_MISSING",
                    "stored physical command could not be decoded",
                    3,
                    messageOf(error)
            );
            return null;
        }
    }

    private String validateCashConfiguration(CashConfigurationCommandData config) {
        if (config == null || config.getConfigVersion() == null
                || config.getConfigVersion() <= 0L
                || config.getConfigVersion() > 0x00FFFFFFL) {
            return "configVersion must be 1..16777215";
        }
        if (config.isChangeEnabled()) {
            return "changeEnabled must be false";
        }
        if (!config.isCashAcceptanceEnabled()) {
            return null;
        }

        List<CashConfigurationCommandData.CashSaleItem> items =
                config.getCashSaleItems();
        if (items == null || items.isEmpty()) {
            return "cashSaleItems cannot be empty when cash is enabled";
        }

        Set<String> unique = new HashSet<>();
        for (CashConfigurationCommandData.CashSaleItem item : items) {
            if (item == null
                    || item.getDenominationAmount() == null
                    || item.getMarbleQuantity() == null) {
                return "cash sale item fields are incomplete";
            }
            String medium = safe(item.getCashMediumType());
            int amount = item.getDenominationAmount();
            int quantity = item.getMarbleQuantity();
            String tierNo = safe(item.getCashSaleTierNo());
            if (!"banknote".equals(medium) && !"coin".equals(medium)) {
                return "cashMediumType must be banknote or coin";
            }
            if (amount <= 0 || amount > 0xFFFF) {
                return "denominationAmount must be 1..65535";
            }
            if (quantity <= 0 || quantity > 0xFFFF) {
                return "marbleQuantity must be 1..65535";
            }
            if (tierNo.trim().isEmpty()) {
                return "cashSaleTierNo cannot be empty";
            }
            if (!unique.add(medium + "|" + amount)) {
                return "duplicate cash tier medium and amount";
            }
        }
        return null;
    }

    private List<CashTier> toCashTiers(CashConfigurationCommandData config) {
        List<CashTier> result = new ArrayList<>();
        List<CashConfigurationCommandData.CashSaleItem> items =
                config.getCashSaleItems();
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

    private void broadcastDispenseOrder(
            String eventType,
            int orderSequence,
            int requestedQuantity,
            int actualQuantity,
            int resultCode,
            String message
    ) {
        Intent intent = new Intent(AppConfig.ACTION_DISPENSE_ORDER_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra("eventType", safe(eventType));
        intent.putExtra("orderSequence", orderSequence);
        intent.putExtra("requestedQuantity", requestedQuantity);
        intent.putExtra("actualQuantity", actualQuantity);
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("message", safe(message));
        context.sendBroadcast(intent);
    }

    private void broadcastCollection(String event, String message) {
        Intent intent = new Intent(AppConfig.ACTION_COLLECTION_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(DeviceCommandManager.EXTRA_COLLECTION_EVENT, event);
        intent.putExtra(DeviceCommandManager.EXTRA_COLLECTION_MESSAGE, message);
        context.sendBroadcast(intent);
    }

    private void broadcastHardwareStatus(String value) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(context.getPackageName());
        intent.putExtra("key", "hardware");
        intent.putExtra("value", value);
        context.sendBroadcast(intent);
    }

    private void reportStorageFault(String message) {
        MqttManager.get(context).reportFault(
                "LOCAL_STORAGE_ERROR",
                "local business database error",
                3,
                message
        );
    }

    private void reportPhysicalUnknown(
            String messageId,
            int actual,
            String description
    ) {
        MqttManager.get(context).reportFault(
                "PHYSICAL_RESULT_REQUIRES_MANUAL_REVIEW",
                "physical result requires manual review",
                3,
                description + ", messageId=" + messageId + ", actual=" + actual
        );
    }

    private String newCashEventNo(int sequence) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyyMMddHHmmssSSS",
                Locale.ROOT
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return "CE-A-" + format.format(new Date()) + "-"
                + String.format(Locale.ROOT, "%04X", sequence) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static JSONObject parseObject(String value) {
        try {
            return blank(value) ? null : new JSONObject(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value, String fallback) {
        return blank(value) ? fallback : value;
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
}
