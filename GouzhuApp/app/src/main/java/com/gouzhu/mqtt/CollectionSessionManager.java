package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.hardware.CollectRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Durable member-deposit session, independent from QR and cash purchase state.
 *
 * <p>The controller exposes CollectStart/CollectStop but no dedicated collect progress frame;
 * therefore the application derives the accepted quantity from fresh bead-stock reports while
 * the persisted collection session is COLLECTING. A process restart never restarts the motor.</p>
 */
final class CollectionSessionManager {

    private static final String TAG = "GouzhuCollection";
    private static final String COMMAND_COLLECT = "collect_marbles";
    private static final String COMMAND_RESOLVE = "resolve_marble_operation";
    private static final String SESSION_TABLE = "collection_sessions";
    private static final String RESOLUTION_TABLE = "collection_resolutions";
    private static final Object DB_LOCK = new Object();

    private static final int CMD_COLLECT_START = 0x02;
    private static final int CMD_COLLECT_STOP = 0x03;
    private static final int CMD_HARDWARE_STATUS = 0x20;
    private static final int EVT_BEAD_STOCK = 0x20;
    private static final long SERIAL_ECHO_TIMEOUT_MS = 2500L;
    private static final long STOCK_WAIT_TIMEOUT_MS = 1500L;
    private static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 300;

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder decoder = new SdkCommandDecoder();
    private final TransactionOccupancyManager occupancy;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gouzhu-collection-session");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-collection-timeout");
                thread.setDaemon(true);
                return thread;
            });

    private volatile int lastObservedStock = -1;
    private volatile CountDownLatch stockWaiter;
    private volatile ScheduledFuture<?> timeoutTask;
    private boolean receiverRegistered;

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null
                    || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())
                    || intent.getIntExtra("code2", -1) != EVT_BEAD_STOCK) {
                return;
            }
            int stock = (int) Math.max(0L, Math.min(0xFFFFL,
                    intent.getLongExtra("data", 0L)));
            lastObservedStock = stock;
            CountDownLatch waiter = stockWaiter;
            if (waiter != null) {
                waiter.countDown();
            }
            executor.execute(() -> updateProgressFromStock(stock));
        }
    };

    CollectionSessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.occupancy = TransactionOccupancyManager.get(this.context);
    }

    synchronized void start() {
        ensureSchema();
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
        executor.execute(this::recoverSession);
    }

    synchronized void stop() {
        cancelTimeout();
        CountDownLatch waiter = stockWaiter;
        if (waiter != null) {
            waiter.countDown();
        }
        stockWaiter = null;
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(boardReceiver);
        } catch (Throwable ignored) {
        }
        receiverRegistered = false;
    }

    boolean handlesCollect(byte[] payload) {
        return hasCommandType(payload, COMMAND_COLLECT);
    }

    boolean handlesResolution(byte[] payload) {
        if (!hasCommandType(payload, COMMAND_RESOLVE)) {
            return false;
        }
        try {
            JSONObject envelope = parseEnvelope(payload);
            JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
            String messageId = envelope == null
                    ? ""
                    : envelope.optString("messageId", "").trim();
            String operationNo = data == null
                    ? ""
                    : data.optString("operationNo", "").trim();
            String resolutionNo = data == null
                    ? ""
                    : data.optString("resolutionNo", "").trim();
            if (blank(messageId) || blank(operationNo) || blank(resolutionNo)) {
                return false;
            }
            Session session = loadSession();
            if (session != null) {
                // Route every resolution to the active collection session so a stale or
                // mismatched operation gets an explicit OPERATION_MISMATCH result instead
                // of accidentally reaching the physical-dispense resolver.
                return true;
            }
            return loadResolution(messageId) != null
                    || hasCompletedCollectionResolution(operationNo, resolutionNo)
                    || hasTerminalCollectionOperation(operationNo);
        } catch (Throwable ignored) {
            return false;
        }
    }

    void handleCollect(String topic, byte[] payload) {
        byte[] copy = payload == null ? new byte[0] : payload.clone();
        executor.execute(() -> acceptCollect(topic, copy));
    }

    void handleResolution(String topic, byte[] payload) {
        byte[] copy = payload == null ? new byte[0] : payload.clone();
        executor.execute(() -> resolveCollection(topic, copy));
    }

    boolean hasPendingCollection() {
        return loadSession() != null;
    }

    boolean startPendingCollection() {
        Session session = loadSession();
        if (session == null || !"READY".equals(session.state)) {
            return false;
        }
        if (!compareAndSetState(session.messageId, "READY", "STARTING")) {
            return false;
        }
        executor.execute(() -> startCollection(session.messageId));
        return true;
    }

    boolean finishPendingCollection() {
        Session session = loadSession();
        if (session == null
                || !("COLLECTING".equals(session.state)
                || "STARTING".equals(session.state))) {
            return false;
        }
        executor.execute(() -> finishCollection(session.messageId, false));
        return true;
    }

    void broadcastCurrentState() {
        Session session = loadSession();
        if (session == null) {
            return;
        }
        if ("READY".equals(session.state)) {
            broadcast(
                    DeviceCommandManager.COLLECTION_READY,
                    "请倒入需要存入的珠子，再点击开始存珠"
            );
        } else if ("COLLECTING".equals(session.state)
                || "STARTING".equals(session.state)) {
            broadcast(
                    DeviceCommandManager.COLLECTION_PROGRESS,
                    "已识别 " + Math.max(0, session.actualQuantity) + " 颗"
            );
        } else {
            broadcast(
                    DeviceCommandManager.COLLECTION_FAILED,
                    blank(session.blockedReason)
                            ? "存珠会话异常，等待人工处理"
                            : session.blockedReason
            );
        }
    }

    private void acceptCollect(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportFault("COLLECTION_COMMAND_INVALID", messageOf(error));
            return;
        }
        if (!COMMAND_COLLECT.equals(decoded.sdkCommand.getCommandType())) {
            return;
        }

        String messageId = decoded.sdkCommand.getMessageId();
        if (store.hasCommand(messageId)) {
            resendResults(messageId);
            broadcastCurrentState();
            return;
        }

        final CollectRequest request;
        try {
            request = decoded.toCollectRequest(System.currentTimeMillis());
        } catch (Throwable error) {
            persistGenericFailure(
                    decoded,
                    "SDK_HARDWARE_MAPPING_FAILED",
                    messageOf(error)
            );
            return;
        }
        if (request == null) {
            persistGenericFailure(decoded, "PARAM_INVALID", "collect request is null");
            return;
        }

        JSONObject data = decoded.envelope.optJSONObject("data");
        String operationNo = data == null
                ? ""
                : data.optString("operationNo", "").trim();
        int maximumQuantity = readPositiveInt(
                data,
                "maximumQuantity",
                "quantity",
                "maxQuantity"
        );
        int timeoutSeconds = readPositiveInt(
                data,
                "sessionTimeoutSeconds",
                "timeoutSeconds"
        );
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_SESSION_TIMEOUT_SECONDS;
        }
        if (blank(operationNo)
                || maximumQuantity <= 0
                || maximumQuantity > 0xFFFF
                || timeoutSeconds > 24 * 60 * 60) {
            persistGenericFailure(
                    decoded,
                    "PARAM_INVALID",
                    "operationNo, maximumQuantity or sessionTimeoutSeconds is invalid"
            );
            return;
        }

        TransactionOccupancyManager.AcquireResult acquired =
                occupancy.tryAcquireCollection(messageId, operationNo);
        if (!acquired.success || acquired.snapshot == null) {
            persistGenericFailure(
                    decoded,
                    "DEVICE_TRANSACTION_OCCUPIED",
                    acquired.snapshot == null
                            ? acquired.reason
                            : occupancy.displayMessage(acquired.snapshot)
            );
            return;
        }

        try {
            SdkCommandDecoder.EncodedResult ack = decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            String ackPayload = addOperationNo(ack.payload, operationNo);
            if (!persistCollectionReceipt(
                    decoded.envelope,
                    topic,
                    operationNo,
                    maximumQuantity,
                    timeoutSeconds,
                    acquired.snapshot.sessionId,
                    ack.eventNo,
                    ack.resultStatus,
                    ackPayload
            )) {
                occupancy.release(
                        acquired.snapshot.sessionId,
                        "collection receipt persistence failed",
                        true
                );
                reportFault(
                        "LOCAL_STORAGE_ERROR",
                        "collection command and acknowledgement could not be saved"
                );
                return;
            }
            MqttManager.get(context).reportCommandResult(ackPayload);
            broadcast(
                    DeviceCommandManager.COLLECTION_READY,
                    "存珠会话已建立，请倒入珠子后点击开始"
            );
        } catch (Throwable error) {
            occupancy.release(
                    acquired.snapshot.sessionId,
                    "collection acknowledgement failed",
                    true
            );
            reportFault("LOCAL_STORAGE_ERROR", messageOf(error));
        }
    }

    private void startCollection(String messageId) {
        Session session = loadSession();
        if (session == null || !messageId.equals(session.messageId)) {
            return;
        }
        int baseline = requestFreshStock();
        if (baseline < 0) {
            failCollectionStart(session, "COLLECT_STOCK_STATUS_UNKNOWN");
            return;
        }
        try {
            boolean echoed = SerialManager.get(context).sendCommandAndWaitEcho(
                    CMD_COLLECT_START,
                    session.maximumQuantity,
                    SERIAL_ECHO_TIMEOUT_MS
            );
            if (!echoed) {
                failCollectionStart(session, "COLLECT_START_ECHO_TIMEOUT");
                return;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failCollectionStart(session, "COLLECT_START_INTERRUPTED");
            return;
        }

        if (!markCollecting(messageId, baseline)) {
            SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, true);
            failCollectionStart(session, "COLLECT_STATE_PERSISTENCE_FAILED");
            return;
        }
        occupancy.transitionCollection(session.occupancySessionId,
                TransactionOccupancyManager.PHASE_COLLECTING);
        broadcast(DeviceCommandManager.COLLECTION_STARTED, "存珠电机已启动");
        scheduleTimeout(messageId, session.timeoutSeconds);
    }

    private void failCollectionStart(Session session, String code) {
        markSessionBlocked(session.messageId, code);
        occupancy.markBlocked(code);
        persistCollectionTerminal(
                session,
                false,
                Math.max(0, session.actualQuantity),
                code,
                "collection could not start safely",
                false
        );
        broadcast(DeviceCommandManager.COLLECTION_FAILED, code);
    }

    private int requestFreshStock() {
        CountDownLatch waiter = new CountDownLatch(1);
        stockWaiter = waiter;
        lastObservedStock = -1;
        try {
            if (!SerialManager.get(context).sendCommand(
                    CMD_HARDWARE_STATUS,
                    0L,
                    false
            )) {
                return -1;
            }
            if (!waiter.await(STOCK_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return -1;
            }
            return lastObservedStock;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            if (stockWaiter == waiter) {
                stockWaiter = null;
            }
        }
    }

    private void updateProgressFromStock(int stock) {
        Session session = loadSession();
        if (session == null || !"COLLECTING".equals(session.state)
                || session.baselineStock < 0) {
            return;
        }
        int actual = Math.max(0, stock - session.baselineStock);
        actual = Math.min(session.maximumQuantity, actual);
        if (actual <= session.actualQuantity) {
            return;
        }
        updateActual(session.messageId, stock, actual);
        broadcast(
                DeviceCommandManager.COLLECTION_PROGRESS,
                "已识别 " + actual + " / " + session.maximumQuantity + " 颗"
        );
        if (actual >= session.maximumQuantity) {
            finishCollection(session.messageId, false);
        }
    }

    private void finishCollection(String messageId, boolean timedOut) {
        cancelTimeout();
        Session session = loadSession();
        if (session == null || !messageId.equals(session.messageId)) {
            return;
        }
        if (!"FINISHING".equals(session.state)) {
            if (!("COLLECTING".equals(session.state)
                    || "STARTING".equals(session.state))
                    || !compareAndSetState(messageId, session.state, "FINISHING")) {
                return;
            }
            session = loadSession();
            if (session == null) {
                return;
            }
        }
        boolean echoed = false;
        try {
            echoed = SerialManager.get(context).sendCommandAndWaitEcho(
                    CMD_COLLECT_STOP,
                    0L,
                    SERIAL_ECHO_TIMEOUT_MS
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        Session latest = loadSession();
        if (latest != null && messageId.equals(latest.messageId)) {
            session = latest;
        }
        int actual = Math.max(0, session.actualQuantity);
        if (!echoed) {
            markSessionBlocked(messageId, "COLLECT_STOP_ECHO_TIMEOUT");
            occupancy.markBlocked("COLLECT_STOP_ECHO_TIMEOUT");
            persistCollectionTerminal(
                    session,
                    false,
                    actual,
                    "COLLECT_STOP_ECHO_TIMEOUT",
                    "controller did not confirm CollectStop",
                    false
            );
            broadcast(
                    DeviceCommandManager.COLLECTION_FAILED,
                    "控制板未确认结束存珠，等待人工处理"
            );
            return;
        }

        String code = timedOut ? "COLLECT_SESSION_TIMEOUT" : "COLLECT_COMPLETED";
        String message = timedOut
                ? "collection session reached its configured timeout"
                : "collection completed";
        if (!persistCollectionTerminal(
                session,
                true,
                actual,
                code,
                message,
                true
        )) {
            markSessionBlocked(messageId, "COLLECT_TERMINAL_PERSISTENCE_FAILED");
            occupancy.markBlocked("COLLECT_TERMINAL_PERSISTENCE_FAILED");
            broadcast(
                    DeviceCommandManager.COLLECTION_FAILED,
                    "存珠结果无法可靠保存，等待人工处理"
            );
            return;
        }

        occupancy.release(session.occupancySessionId, code, true);
        broadcast(
                DeviceCommandManager.COLLECTION_FINISHED,
                "存珠完成，共识别 " + actual + " 颗"
        );
    }

    private void scheduleTimeout(String messageId, int timeoutSeconds) {
        cancelTimeout();
        timeoutTask = scheduler.schedule(
                () -> executor.execute(() -> finishCollection(messageId, true)),
                Math.max(1, timeoutSeconds),
                TimeUnit.SECONDS
        );
    }

    private void cancelTimeout() {
        ScheduledFuture<?> active = timeoutTask;
        if (active != null) {
            active.cancel(false);
            timeoutTask = null;
        }
    }

    private void recoverSession() {
        Session session = loadSession();
        if (session == null) {
            TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
            if (snapshot != null
                    && TransactionOccupancyManager.OWNER_MEMBER_DEPOSIT.equals(
                    snapshot.ownerType)) {
                if (hasTerminalCollectionCommand(snapshot.sourceMessageId)
                        || hasCompletedCollectionResolution(snapshot.operationNo)) {
                    occupancy.release(
                            snapshot.sessionId,
                            "collection session already terminal",
                            true
                    );
                } else {
                    occupancy.markBlocked("COLLECTION_SESSION_STATE_MISSING");
                }
            }
            return;
        }
        TransactionOccupancyManager.AcquireResult acquired =
                occupancy.recoverCollection(session.messageId, session.operationNo);
        if (!acquired.success || acquired.snapshot == null) {
            markSessionBlocked(session.messageId, "COLLECTION_OCCUPANCY_RECOVERY_FAILED");
            broadcast(
                    DeviceCommandManager.COLLECTION_FAILED,
                    "存珠会话无法恢复设备占用"
            );
            return;
        }
        if (!acquired.snapshot.sessionId.equals(session.occupancySessionId)) {
            updateOccupancySessionId(session.messageId, acquired.snapshot.sessionId);
            session.occupancySessionId = acquired.snapshot.sessionId;
        }
        if ("READY".equals(session.state)) {
            broadcastCurrentState();
            return;
        }
        if ("COLLECTING".equals(session.state) || "STARTING".equals(session.state)) {
            SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, true);
            markSessionBlocked(session.messageId, "COLLECT_INTERRUPTED_BY_RESTART");
            occupancy.markBlocked("COLLECT_INTERRUPTED_BY_RESTART");
            persistCollectionTerminal(
                    session,
                    false,
                    Math.max(0, session.actualQuantity),
                    "COLLECT_INTERRUPTED_BY_RESTART",
                    "application restarted during a physical collection",
                    false
            );
        }
        broadcastCurrentState();
    }

    private void resolveCollection(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportFault("COLLECTION_RESOLUTION_INVALID", messageOf(error));
            return;
        }
        JSONObject data = decoded.envelope.optJSONObject("data");
        String messageId = decoded.sdkCommand.getMessageId();
        String operationNo = data == null ? "" : data.optString("operationNo", "").trim();
        String resolutionNo = data == null ? "" : data.optString("resolutionNo", "").trim();
        if (blank(messageId) || blank(operationNo) || blank(resolutionNo)) {
            reportFault("COLLECTION_RESOLUTION_INVALID", "resolution identity is missing");
            return;
        }

        ResolutionRecord existing = loadResolution(messageId);
        if (existing != null) {
            replayOrResumeResolution(decoded, existing);
            return;
        }

        final SdkCommandDecoder.EncodedResult ack;
        final String ackPayload;
        try {
            ack = decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            ackPayload = addOperationNo(ack.payload, operationNo);
        } catch (Throwable error) {
            reportFault("LOCAL_STORAGE_ERROR", "resolution acknowledgement encode failed");
            return;
        }

        if (!persistResolutionReceipt(
                decoded.envelope,
                operationNo,
                resolutionNo,
                ack.eventNo,
                ack.resultStatus,
                ackPayload
        )) {
            reportFault("LOCAL_STORAGE_ERROR", "resolution receipt could not be saved");
            return;
        }
        MqttManager.get(context).reportCommandResult(ackPayload);

        ResolutionOutcome outcome = evaluateResolutionOutcome(messageId);
        if (outcome == null) {
            reportFault("LOCAL_STORAGE_ERROR", "resolution outcome could not be saved");
            return;
        }
        if (!blank(outcome.releasedOccupancySessionId)) {
            occupancy.release(
                    outcome.releasedOccupancySessionId,
                    outcome.resultCode,
                    true
            );
            broadcast(
                    DeviceCommandManager.COLLECTION_FINISHED,
                    "存珠异常已由平台人工结案"
            );
        }

        try {
            SdkCommandDecoder.EncodedResult terminal = decoded.genericTerminal(
                    messageId + "-result",
                    outcome.success,
                    outcome.resultCode,
                    outcome.resultMessage + "; runningStatus="
                            + DeviceCommandManager.get(context).getRunningStatus(),
                    System.currentTimeMillis()
            );
            String terminalPayload = addOperationNo(terminal.payload, operationNo);
            if (!persistResolutionTerminal(
                    messageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminalPayload
            )) {
                reportFault("LOCAL_STORAGE_ERROR", "resolution terminal could not be saved");
                return;
            }
            MqttManager.get(context).reportStatus();
            MqttManager.get(context).reportCommandResult(terminalPayload);
        } catch (Throwable error) {
            reportFault("LOCAL_STORAGE_ERROR", messageOf(error));
        }
    }

    private void replayOrResumeResolution(
            SdkCommandDecoder.DecodedCommand decoded,
            ResolutionRecord record
    ) {
        requeueResolution(record);
        if (!blank(record.ackPayload)) {
            MqttManager.get(context).reportCommandResult(record.ackPayload);
        }
        if (!blank(record.terminalPayload)) {
            MqttManager.get(context).reportCommandResult(record.terminalPayload);
            return;
        }
        if (!record.hasOutcome) {
            ResolutionOutcome outcome = evaluateResolutionOutcome(record.messageId);
            if (outcome == null) {
                reportFault("LOCAL_STORAGE_ERROR", "resolution resume failed");
                return;
            }
            record = loadResolution(record.messageId);
        }
        if (record == null || !record.hasOutcome) {
            reportFault("LOCAL_STORAGE_ERROR", "resolution outcome is missing");
            return;
        }
        if (!blank(record.releasedOccupancySessionId)) {
            occupancy.release(
                    record.releasedOccupancySessionId,
                    record.resultCode,
                    true
            );
        }
        try {
            SdkCommandDecoder.EncodedResult terminal = decoded.genericTerminal(
                    record.messageId + "-result",
                    record.outcomeSuccess,
                    record.resultCode,
                    record.resultMessage + "; runningStatus="
                            + DeviceCommandManager.get(context).getRunningStatus(),
                    System.currentTimeMillis()
            );
            String terminalPayload = addOperationNo(terminal.payload, record.operationNo);
            if (persistResolutionTerminal(
                    record.messageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminalPayload
            )) {
                MqttManager.get(context).reportCommandResult(terminalPayload);
            }
        } catch (Throwable error) {
            reportFault("LOCAL_STORAGE_ERROR", messageOf(error));
        }
    }

    private boolean persistCollectionReceipt(
            JSONObject envelope,
            String sourceTopic,
            String operationNo,
            int maximumQuantity,
            int timeoutSeconds,
            String occupancySessionId,
            String ackEventNo,
            String ackStatus,
            String ackPayload
    ) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                String messageId = envelope.optString("messageId", "").trim();
                if (!saveCommand(db, envelope, "ready")) {
                    return false;
                }
                ContentValues values = new ContentValues();
                values.put("id", 1);
                values.put("message_id", messageId);
                values.put("operation_no", operationNo);
                values.put("source_topic", sourceTopic);
                values.put("command_envelope", envelope.toString());
                values.put("maximum_quantity", maximumQuantity);
                values.put("timeout_seconds", timeoutSeconds);
                values.put("state", "READY");
                values.put("baseline_stock", -1);
                values.put("last_stock", -1);
                values.put("actual_quantity", 0);
                values.put("occupancy_session_id", occupancySessionId);
                values.put("blocked_reason", "");
                values.put("created_at", System.currentTimeMillis());
                values.put("updated_at", System.currentTimeMillis());
                if (db.insert(SESSION_TABLE, null, values) == -1L) {
                    return false;
                }
                if (!saveOutbox(
                        db,
                        messageId,
                        ackEventNo,
                        ackStatus,
                        ackPayload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    private boolean persistCollectionTerminal(
            Session session,
            boolean success,
            int actual,
            String resultCode,
            String resultMessage,
            boolean deleteOnSuccess
    ) {
        JSONObject envelope = parseObject(session.commandEnvelope);
        if (envelope == null) {
            return false;
        }
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    session.sourceTopic,
                    envelope.toString().getBytes(StandardCharsets.UTF_8),
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.e(TAG, "恢复存珠命令失败", error);
            return false;
        }

        try {
            SdkCommandDecoder.EncodedResult terminal;
            try {
                terminal = decoded.physicalTerminal(
                        session.messageId + "-result",
                        success,
                        actual,
                        resultCode,
                        resultMessage,
                        System.currentTimeMillis()
                );
            } catch (Throwable mappingError) {
                terminal = decoded.genericTerminal(
                        session.messageId + "-result",
                        success,
                        resultCode,
                        resultMessage,
                        System.currentTimeMillis()
                );
            }
            String payload = addCollectionActual(
                    addOperationNo(terminal.payload, session.operationNo),
                    actual
            );
            synchronized (DB_LOCK) {
                SQLiteDatabase db = store.getWritableDatabase();
                db.beginTransaction();
                try {
                    if (!saveOutbox(
                            db,
                            session.messageId,
                            terminal.eventNo,
                            terminal.resultStatus,
                            payload
                    )) {
                        return false;
                    }
                    ContentValues command = new ContentValues();
                    command.put("state", success ? "terminal" : "blocked");
                    command.put("updated_at", System.currentTimeMillis());
                    if (db.update(
                            "commands",
                            command,
                            "message_id=?",
                            new String[]{session.messageId}
                    ) != 1) {
                        return false;
                    }
                    if (success && deleteOnSuccess) {
                        if (db.delete(
                                SESSION_TABLE,
                                "id=1 AND message_id=?",
                                new String[]{session.messageId}
                        ) != 1) {
                            return false;
                        }
                    } else {
                        ContentValues blocked = new ContentValues();
                        blocked.put("state", "BLOCKED");
                        blocked.put("blocked_reason", resultCode);
                        blocked.put("actual_quantity", actual);
                        blocked.put("updated_at", System.currentTimeMillis());
                        db.update(
                                SESSION_TABLE,
                                blocked,
                                "id=1 AND message_id=?",
                                new String[]{session.messageId}
                        );
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            MqttManager.get(context).reportCommandResult(payload);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "存珠终态编码失败", error);
            return false;
        }
    }

    private boolean persistResolutionReceipt(
            JSONObject envelope,
            String operationNo,
            String resolutionNo,
            String ackEventNo,
            String ackStatus,
            String ackPayload
    ) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                String messageId = envelope.optString("messageId", "").trim();
                if (!saveCommand(db, envelope, "received")) {
                    return false;
                }
                ContentValues values = new ContentValues();
                values.put("message_id", messageId);
                values.put("operation_no", operationNo);
                values.put("resolution_no", resolutionNo);
                values.put("ack_event_no", ackEventNo);
                values.put("ack_status", ackStatus);
                values.put("ack_payload", ackPayload);
                values.put("created_at", System.currentTimeMillis());
                values.put("updated_at", System.currentTimeMillis());
                if (db.insert(RESOLUTION_TABLE, null, values) == -1L) {
                    return false;
                }
                if (!saveOutbox(
                        db,
                        messageId,
                        ackEventNo,
                        ackStatus,
                        ackPayload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    private ResolutionOutcome evaluateResolutionOutcome(String messageId) {
        ResolutionRecord before = loadResolution(messageId);
        if (before == null) {
            return null;
        }
        if (before.hasOutcome) {
            return ResolutionOutcome.from(before, "");
        }

        Session observedSession = loadSession();
        if (observedSession != null
                && before.operationNo.equals(observedSession.operationNo)) {
            SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, true);
            cancelTimeout();
        }

        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ResolutionRecord latest = loadResolution(db, messageId);
                if (latest == null) {
                    return null;
                }
                if (latest.hasOutcome) {
                    db.setTransactionSuccessful();
                    return ResolutionOutcome.from(latest, "");
                }

                Session session = loadSession(db);
                boolean success;
                String resultCode;
                String resultMessage;
                String releasedSessionId = "";
                if (session == null) {
                    boolean alreadyResolved = hasCompletedResolution(
                            db,
                            latest.operationNo,
                            latest.resolutionNo,
                            latest.messageId
                    ) || hasTerminalCollectionOperation(db, latest.operationNo);
                    success = alreadyResolved;
                    resultCode = alreadyResolved
                            ? "OPERATION_ALREADY_RESOLVED"
                            : "LOCAL_SESSION_STATE_INVALID";
                    resultMessage = alreadyResolved
                            ? "collection operation already resolved"
                            : "collection operation cannot be identified safely";
                } else if (!latest.operationNo.equals(session.operationNo)) {
                    success = false;
                    resultCode = "OPERATION_MISMATCH";
                    resultMessage = "active collection operation does not match";
                } else {
                    if (db.delete(
                            SESSION_TABLE,
                            "id=1 AND message_id=?",
                            new String[]{session.messageId}
                    ) != 1) {
                        return null;
                    }
                    success = true;
                    resultCode = "OPERATION_RESOLVED";
                    resultMessage = "local collection operation resolved";
                    releasedSessionId = session.occupancySessionId;
                }

                ContentValues result = new ContentValues();
                result.put("outcome_success", success ? 1 : 0);
                result.put("result_code", resultCode);
                result.put("result_message", resultMessage);
                result.put("released_occupancy_session_id", releasedSessionId);
                result.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        RESOLUTION_TABLE,
                        result,
                        "message_id=?",
                        new String[]{messageId}
                ) != 1) {
                    return null;
                }

                ContentValues command = new ContentValues();
                command.put("state", success ? "resolved" : "failed");
                command.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        "commands",
                        command,
                        "message_id=?",
                        new String[]{messageId}
                ) != 1) {
                    return null;
                }
                db.setTransactionSuccessful();
                ResolutionOutcome outcome = new ResolutionOutcome();
                outcome.success = success;
                outcome.resultCode = resultCode;
                outcome.resultMessage = resultMessage;
                outcome.releasedOccupancySessionId = releasedSessionId;
                return outcome;
            } finally {
                db.endTransaction();
            }
        }
    }

    private boolean persistResolutionTerminal(
            String messageId,
            String eventNo,
            String status,
            String payload
    ) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ResolutionRecord record = loadResolution(db, messageId);
                if (record == null || !record.hasOutcome) {
                    return false;
                }
                if (!blank(record.terminalPayload)) {
                    boolean same = eventNo.equals(record.terminalEventNo)
                            && status.equals(record.terminalStatus)
                            && payload.equals(record.terminalPayload);
                    if (same) {
                        db.setTransactionSuccessful();
                    }
                    return same;
                }
                ContentValues values = new ContentValues();
                values.put("terminal_event_no", eventNo);
                values.put("terminal_status", status);
                values.put("terminal_payload", payload);
                values.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        RESOLUTION_TABLE,
                        values,
                        "message_id=?",
                        new String[]{messageId}
                ) != 1) {
                    return false;
                }
                if (!saveOutbox(db, messageId, eventNo, status, payload)) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    private void requeueResolution(ResolutionRecord record) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (!blank(record.ackPayload)) {
                    saveOutbox(
                            db,
                            record.messageId,
                            record.ackEventNo,
                            record.ackStatus,
                            record.ackPayload
                    );
                }
                if (!blank(record.terminalPayload)) {
                    saveOutbox(
                            db,
                            record.messageId,
                            record.terminalEventNo,
                            record.terminalStatus,
                            record.terminalPayload
                    );
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    private ResolutionRecord loadResolution(String messageId) {
        ensureSchema();
        synchronized (DB_LOCK) {
            return loadResolution(store.getReadableDatabase(), messageId);
        }
    }

    private static ResolutionRecord loadResolution(
            SQLiteDatabase db,
            String messageId
    ) {
        try (Cursor cursor = db.query(
                RESOLUTION_TABLE,
                null,
                "message_id=?",
                new String[]{messageId},
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ResolutionRecord record = new ResolutionRecord();
            record.messageId = value(cursor, "message_id");
            record.operationNo = value(cursor, "operation_no");
            record.resolutionNo = value(cursor, "resolution_no");
            record.ackEventNo = value(cursor, "ack_event_no");
            record.ackStatus = value(cursor, "ack_status");
            record.ackPayload = value(cursor, "ack_payload");
            int outcomeIndex = cursor.getColumnIndexOrThrow("outcome_success");
            record.hasOutcome = !cursor.isNull(outcomeIndex);
            record.outcomeSuccess = record.hasOutcome && cursor.getInt(outcomeIndex) != 0;
            record.resultCode = value(cursor, "result_code");
            record.resultMessage = value(cursor, "result_message");
            record.releasedOccupancySessionId = value(
                    cursor,
                    "released_occupancy_session_id"
            );
            record.terminalEventNo = value(cursor, "terminal_event_no");
            record.terminalStatus = value(cursor, "terminal_status");
            record.terminalPayload = value(cursor, "terminal_payload");
            return record;
        }
    }

    private static boolean hasCompletedResolution(
            SQLiteDatabase db,
            String operationNo,
            String resolutionNo,
            String excludingMessageId
    ) {
        try (Cursor cursor = db.query(
                RESOLUTION_TABLE,
                new String[]{"message_id"},
                "operation_no=? AND resolution_no=? "
                        + "AND message_id<>? AND outcome_success=1",
                new String[]{operationNo, resolutionNo, excludingMessageId},
                null,
                null,
                "updated_at DESC",
                "1"
        )) {
            return cursor.moveToFirst();
        }
    }

    private void persistGenericFailure(
            SdkCommandDecoder.DecodedCommand decoded,
            String resultCode,
            String resultMessage
    ) {
        try {
            SdkCommandDecoder.EncodedResult terminal = decoded.genericTerminal(
                    decoded.sdkCommand.getMessageId() + "-result",
                    false,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            );
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
            reportFault("LOCAL_STORAGE_ERROR", messageOf(error));
        }
    }

    private void resendResults(String messageId) {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (messageId.equals(item.sourceMessageId)) {
                MqttManager.get(context).reportCommandResult(item.payload);
            }
        }
    }

    private boolean compareAndSetState(String messageId, String expected, String next) {
        ContentValues values = new ContentValues();
        values.put("state", next);
        values.put("updated_at", System.currentTimeMillis());
        synchronized (DB_LOCK) {
            return store.getWritableDatabase().update(
                    SESSION_TABLE,
                    values,
                    "id=1 AND message_id=? AND state=?",
                    new String[]{messageId, expected}
            ) == 1;
        }
    }

    private boolean markCollecting(String messageId, int baselineStock) {
        ContentValues values = new ContentValues();
        values.put("state", "COLLECTING");
        values.put("baseline_stock", baselineStock);
        values.put("last_stock", baselineStock);
        values.put("actual_quantity", 0);
        values.put("updated_at", System.currentTimeMillis());
        synchronized (DB_LOCK) {
            return store.getWritableDatabase().update(
                    SESSION_TABLE,
                    values,
                    "id=1 AND message_id=? AND state='STARTING'",
                    new String[]{messageId}
            ) == 1;
        }
    }

    private void updateActual(String messageId, int stock, int actual) {
        ContentValues values = new ContentValues();
        values.put("last_stock", stock);
        values.put("actual_quantity", actual);
        values.put("updated_at", System.currentTimeMillis());
        synchronized (DB_LOCK) {
            store.getWritableDatabase().update(
                    SESSION_TABLE,
                    values,
                    "id=1 AND message_id=? AND actual_quantity<=?",
                    new String[]{messageId, String.valueOf(actual)}
            );
        }
    }

    private void markSessionBlocked(String messageId, String reason) {
        ContentValues values = new ContentValues();
        values.put("state", "BLOCKED");
        values.put("blocked_reason", safe(reason));
        values.put("updated_at", System.currentTimeMillis());
        synchronized (DB_LOCK) {
            store.getWritableDatabase().update(
                    SESSION_TABLE,
                    values,
                    "id=1 AND message_id=?",
                    new String[]{messageId}
            );
        }
    }

    private boolean deleteSession(String messageId) {
        synchronized (DB_LOCK) {
            return store.getWritableDatabase().delete(
                    SESSION_TABLE,
                    "id=1 AND message_id=?",
                    new String[]{messageId}
            ) == 1;
        }
    }

    private void updateOccupancySessionId(String messageId, String sessionId) {
        ContentValues values = new ContentValues();
        values.put("occupancy_session_id", sessionId);
        values.put("updated_at", System.currentTimeMillis());
        synchronized (DB_LOCK) {
            store.getWritableDatabase().update(
                    SESSION_TABLE,
                    values,
                    "id=1 AND message_id=?",
                    new String[]{messageId}
            );
        }
    }

    private Session loadSession() {
        ensureSchema();
        synchronized (DB_LOCK) {
            return loadSession(store.getReadableDatabase());
        }
    }

    private static Session loadSession(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                SESSION_TABLE,
                null,
                "id=1",
                null,
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            Session session = new Session();
            session.messageId = value(cursor, "message_id");
            session.operationNo = value(cursor, "operation_no");
            session.sourceTopic = value(cursor, "source_topic");
            session.commandEnvelope = value(cursor, "command_envelope");
            session.maximumQuantity = integer(cursor, "maximum_quantity");
            session.timeoutSeconds = integer(cursor, "timeout_seconds");
            session.state = value(cursor, "state");
            session.baselineStock = integer(cursor, "baseline_stock");
            session.lastStock = integer(cursor, "last_stock");
            session.actualQuantity = integer(cursor, "actual_quantity");
            session.occupancySessionId = value(cursor, "occupancy_session_id");
            session.blockedReason = value(cursor, "blocked_reason");
            return session;
        }
    }

    private boolean hasTerminalCollectionCommand(String messageId) {
        if (blank(messageId)) {
            return false;
        }
        synchronized (DB_LOCK) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    "commands",
                    new String[]{"state", "envelope"},
                    "message_id=?",
                    new String[]{messageId},
                    null,
                    null,
                    null
            )) {
                if (!cursor.moveToFirst()) {
                    return false;
                }
                String state = cursor.getString(0);
                JSONObject envelope = parseObject(cursor.getString(1));
                return envelope != null
                        && COMMAND_COLLECT.equals(
                        envelope.optString("commandType", ""))
                        && ("terminal".equals(state)
                        || "resolved".equals(state));
            }
        }
    }

    private boolean hasCompletedCollectionResolution(String operationNo) {
        if (blank(operationNo)) {
            return false;
        }
        synchronized (DB_LOCK) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    RESOLUTION_TABLE,
                    new String[]{"message_id"},
                    "operation_no=? AND outcome_success=1",
                    new String[]{operationNo},
                    null,
                    null,
                    "updated_at DESC",
                    "1"
            )) {
                return cursor.moveToFirst();
            }
        }
    }

    private boolean hasCompletedCollectionResolution(
            String operationNo,
            String resolutionNo
    ) {
        if (blank(operationNo) || blank(resolutionNo)) {
            return false;
        }
        synchronized (DB_LOCK) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    RESOLUTION_TABLE,
                    new String[]{"message_id"},
                    "operation_no=? AND resolution_no=? AND outcome_success=1",
                    new String[]{operationNo, resolutionNo},
                    null,
                    null,
                    "updated_at DESC",
                    "1"
            )) {
                return cursor.moveToFirst();
            }
        }
    }

    private boolean hasTerminalCollectionOperation(String operationNo) {
        if (blank(operationNo)) {
            return false;
        }
        synchronized (DB_LOCK) {
            return hasTerminalCollectionOperation(
                    store.getReadableDatabase(),
                    operationNo
            );
        }
    }

    private static boolean hasTerminalCollectionOperation(
            SQLiteDatabase db,
            String operationNo
    ) {
        if (blank(operationNo)) {
            return false;
        }
        try (Cursor cursor = db.query(
                "commands",
                new String[]{"state", "envelope"},
                "state IN ('terminal','resolved')",
                null,
                null,
                null,
                "updated_at DESC"
        )) {
            while (cursor.moveToNext()) {
                JSONObject envelope = parseObject(cursor.getString(1));
                if (envelope == null
                        || !COMMAND_COLLECT.equals(
                        envelope.optString("commandType", ""))) {
                    continue;
                }
                JSONObject data = envelope.optJSONObject("data");
                if (data != null && operationNo.equals(
                        data.optString("operationNo", "").trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureSchema() {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS " + SESSION_TABLE + " ("
                    + "id INTEGER PRIMARY KEY CHECK(id=1),"
                    + "message_id TEXT NOT NULL UNIQUE,"
                    + "operation_no TEXT NOT NULL,"
                    + "source_topic TEXT NOT NULL,"
                    + "command_envelope TEXT NOT NULL,"
                    + "maximum_quantity INTEGER NOT NULL,"
                    + "timeout_seconds INTEGER NOT NULL,"
                    + "state TEXT NOT NULL,"
                    + "baseline_stock INTEGER NOT NULL DEFAULT -1,"
                    + "last_stock INTEGER NOT NULL DEFAULT -1,"
                    + "actual_quantity INTEGER NOT NULL DEFAULT 0,"
                    + "occupancy_session_id TEXT NOT NULL,"
                    + "blocked_reason TEXT NOT NULL DEFAULT '',"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + RESOLUTION_TABLE + " ("
                    + "message_id TEXT PRIMARY KEY,"
                    + "operation_no TEXT NOT NULL,"
                    + "resolution_no TEXT NOT NULL,"
                    + "ack_event_no TEXT NOT NULL,"
                    + "ack_status TEXT NOT NULL,"
                    + "ack_payload TEXT NOT NULL,"
                    + "outcome_success INTEGER,"
                    + "result_code TEXT,"
                    + "result_message TEXT,"
                    + "released_occupancy_session_id TEXT NOT NULL DEFAULT '',"
                    + "terminal_event_no TEXT,"
                    + "terminal_status TEXT,"
                    + "terminal_payload TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_collection_resolution_business "
                    + "ON " + RESOLUTION_TABLE
                    + "(operation_no,resolution_no,updated_at)");
        }
    }

    private static boolean saveCommand(
            SQLiteDatabase db,
            JSONObject envelope,
            String state
    ) {
        String messageId = envelope == null
                ? ""
                : envelope.optString("messageId", "").trim();
        if (blank(messageId)) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("message_id", messageId);
        values.put("envelope", envelope.toString());
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return db.insertWithOnConflict(
                "commands",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L;
    }

    private static boolean saveOutbox(
            SQLiteDatabase db,
            String messageId,
            String eventNo,
            String status,
            String payload
    ) {
        String receiptKey = messageId + "|" + eventNo + "|" + status;
        ContentValues values = new ContentValues();
        values.put("receipt_key", receiptKey);
        values.put("kind", "command_result");
        values.put("source_message_id", messageId);
        values.put("event_no", eventNo);
        values.put("result_status", status);
        values.put("payload", payload);
        values.put("created_at", System.currentTimeMillis());
        long inserted = db.insertWithOnConflict(
                "outbox",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
        if (inserted != -1L) {
            return true;
        }
        try (Cursor cursor = db.query(
                "outbox",
                new String[]{"payload"},
                "receipt_key=?",
                new String[]{receiptKey},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() && payload.equals(cursor.getString(0));
        }
    }

    private static String addOperationNo(String payload, String operationNo) {
        try {
            JSONObject json = new JSONObject(payload);
            json.put("operationNo", operationNo);
            return json.toString();
        } catch (Throwable error) {
            return payload;
        }
    }

    private static String addCollectionActual(String payload, int actual) {
        try {
            JSONObject json = new JSONObject(payload);
            json.put("actualQuantity", Math.max(0, actual));
            return json.toString();
        } catch (Throwable error) {
            return payload;
        }
    }

    private static int readPositiveInt(JSONObject data, String... keys) {
        if (data == null || keys == null) {
            return 0;
        }
        for (String key : keys) {
            int value = data.optInt(key, 0);
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static boolean hasCommandType(byte[] payload, String expected) {
        JSONObject envelope = parseEnvelope(payload);
        return envelope != null
                && expected.equals(envelope.optString("commandType", ""));
    }

    private static JSONObject parseEnvelope(byte[] payload) {
        try {
            return new JSONObject(new String(
                    payload == null ? new byte[0] : payload,
                    StandardCharsets.UTF_8
            ));
        } catch (Throwable error) {
            return null;
        }
    }

    private static JSONObject parseObject(String value) {
        try {
            return blank(value) ? null : new JSONObject(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private void broadcast(String event, String message) {
        Intent intent = new Intent(AppConfig.ACTION_COLLECTION_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(DeviceCommandManager.EXTRA_COLLECTION_EVENT, event);
        intent.putExtra(DeviceCommandManager.EXTRA_COLLECTION_MESSAGE, safe(message));
        context.sendBroadcast(intent);
    }

    private void reportFault(String code, String description) {
        Log.e(TAG, code + ": " + safe(description));
        MqttManager.get(context).reportFault(
                code,
                "member deposit session error",
                3,
                safe(description)
        );
    }

    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static int integer(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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

    private static final class ResolutionRecord {
        String messageId;
        String operationNo;
        String resolutionNo;
        String ackEventNo;
        String ackStatus;
        String ackPayload;
        boolean hasOutcome;
        boolean outcomeSuccess;
        String resultCode;
        String resultMessage;
        String releasedOccupancySessionId;
        String terminalEventNo;
        String terminalStatus;
        String terminalPayload;
    }

    private static final class ResolutionOutcome {
        boolean success;
        String resultCode;
        String resultMessage;
        String releasedOccupancySessionId;

        static ResolutionOutcome from(ResolutionRecord record, String releasedSessionId) {
            ResolutionOutcome outcome = new ResolutionOutcome();
            outcome.success = record.outcomeSuccess;
            outcome.resultCode = record.resultCode;
            outcome.resultMessage = record.resultMessage;
            outcome.releasedOccupancySessionId = blank(releasedSessionId)
                    ? safe(record.releasedOccupancySessionId)
                    : safe(releasedSessionId);
            return outcome;
        }
    }

    private static final class Session {
        String messageId;
        String operationNo;
        String sourceTopic;
        String commandEnvelope;
        int maximumQuantity;
        int timeoutSeconds;
        String state;
        int baselineStock;
        int lastStock;
        int actualQuantity;
        String occupancySessionId;
        String blockedReason;
    }
}
