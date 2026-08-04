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
import com.gouzhu.util.DeviceUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 平台人工结案命令的本地会话收尾器。
 *
 * <p>resolve_marble_operation只关闭精确匹配的BLOCKED业务会话，不会继续出珠，
 * 也不会根据settledQuantity修改设备实际出珠数量。控制板故障复位和补充库存必须
 * 由工作人员按下K1补珠键完成，控制板上报BEAD_REFILLED后才允许恢复现金入口。</p>
 */
final class OperationResolutionManager {

    private static final String TAG = "GouzhuOperationResolve";
    private static final String COMMAND_TYPE = "resolve_marble_operation";
    private static final String DISPENSE_COMMAND_TYPE = "dispense_marbles";
    private static final String TABLE = "operation_resolutions";
    private static final String META_PHYSICAL_BLOCKED = "physical_blocked";
    private static final String META_LOCAL_RESET_REQUIRED =
            "manual_operation_local_reset_required";

    private static final int CMD_HARDWARE_STATUS = 0x20;
    private static final int CMD_CASH_ACCEPTANCE_APPLY_V22 = 0x33;

    private static final int EVT_BEAD_STOCK = 0x20;
    private static final int EVT_BEAD_EMPTY = 0x22;
    private static final int EVT_BEAD_REFILLED = 0x23;
    private static final int EVT_DISPENSE_TERMINAL = 0x41;

    private static final long MIN_CONTROLLER_PROTOCOL_VERSION = 0x02020000L;
    private static final long HARDWARE_STATUS_TIMEOUT_MS = 1500L;

    private static final Set<String> RESOLUTION_TYPES = new HashSet<>();

    static {
        RESOLUTION_TYPES.add("manual_settlement");
        RESOLUTION_TYPES.add("offline_cash_refund");
        RESOLUTION_TYPES.add("offline_marble_delivery");
        RESOLUTION_TYPES.add("device_cash_return");
        RESOLUTION_TYPES.add("accept_actual_delivery");
    }

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder decoder = new SdkCommandDecoder();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-operation-resolve");
                thread.setDaemon(true);
                return thread;
            });

    private volatile CountDownLatch hardwareStatusLatch;
    private boolean receiverRegistered;

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                return;
            }
            int code2 = intent.getIntExtra("code2", -1);
            long packed = intent.getLongExtra("data", 0L);
            int expandCode = intent.getIntExtra("expandCode", 0) & 0xFF;

            if (code2 == EVT_DISPENSE_TERMINAL) {
                DeviceCommandStore.ActivePhysicalOrder active =
                        store.loadActivePhysicalOrder();
                int terminalActual = (int) (packed & 0xFFFFL);
                if (active != null
                        && (expandCode != 0
                        || terminalActual != active.requestedQuantity)) {
                    setLocalResetRequired(true);
                }
            } else if (code2 == EVT_BEAD_EMPTY) {
                setLocalResetRequired(true);
            } else if (code2 == EVT_BEAD_REFILLED) {
                // K1补珠键是唯一允许解除本地硬件复位门禁的入口。
                setLocalResetRequired(false);
                executor.execute(() -> {
                    sleepQuietly(100L);
                    // 主运行时会在BEAD_REFILLED后恢复现金配置；这里只刷新平台状态，
                    // 避免同一时刻重复发送两次现金配置命令。
                    MqttManager.get(context).reportStatus();
                });
            }

            if (code2 == EVT_BEAD_STOCK
                    || code2 == EVT_BEAD_EMPTY
                    || code2 == EVT_BEAD_REFILLED) {
                CountDownLatch latch = hardwareStatusLatch;
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    };

    OperationResolutionManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
    }

    synchronized void start() {
        ensureSchema();
        initializeResetGate();
        if (receiverRegistered) {
            return;
        }
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

    synchronized void stop() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(boardReceiver);
        } catch (Throwable ignored) {
        }
        receiverRegistered = false;
    }

    boolean handles(byte[] payload) {
        try {
            JSONObject envelope = new JSONObject(new String(
                    payload == null ? new byte[0] : payload,
                    StandardCharsets.UTF_8
            ));
            return COMMAND_TYPE.equals(envelope.optString("commandType", ""));
        } catch (Throwable ignored) {
            return false;
        }
    }

    void handleCommand(String topic, byte[] payload) {
        byte[] copy = payload == null ? new byte[0] : payload.clone();
        executor.execute(() -> acceptCommand(topic, copy));
    }

    int getRunningStatus() {
        DeviceCommandStore.ActivePhysicalOrder active =
                store.loadActivePhysicalOrder();
        if (active != null) {
            if ("DISPENSING".equals(active.state)
                    || "FINISHING".equals(active.state)) {
                return 1;
            }
            return 2;
        }
        if (isLocalResetRequired()
                || store.isPhysicalBlocked()
                || store.isCashBlocked()) {
            return 2;
        }
        return 0;
    }

    private void acceptCommand(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportProtocolFault("人工结案指令未通过SDK校验：" + messageOf(error));
            return;
        }

        if (!COMMAND_TYPE.equals(decoded.sdkCommand.getCommandType())) {
            return;
        }

        JSONObject data = decoded.envelope.optJSONObject("data");
        String messageId = decoded.envelope.optString("messageId", "").trim();
        String operationNo = data == null
                ? ""
                : data.optString("operationNo", "").trim();
        String resolutionNo = data == null
                ? ""
                : data.optString("resolutionNo", "").trim();
        String resolutionType = data == null
                ? ""
                : data.optString("resolutionType", "").trim();
        int settledQuantity = data == null
                ? -1
                : data.optInt("settledQuantity", -1);
        String resolvedAt = data == null
                ? ""
                : data.optString("resolvedAt", "").trim();

        if (blank(messageId)
                || blank(operationNo)
                || blank(resolutionNo)
                || !RESOLUTION_TYPES.contains(resolutionType)
                || settledQuantity < 0
                || blank(resolvedAt)) {
            reportProtocolFault("人工结案指令字段不完整或取值无效：messageId="
                    + messageId + ", operationNo=" + operationNo);
            return;
        }

        ensureSchema();
        ResolutionRecord existing = loadByMessageId(messageId);
        if (existing != null) {
            replayOrResume(decoded, existing);
            return;
        }

        try {
            SdkCommandDecoder.EncodedResult ack = decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            String ackPayload = addOperationNo(ack.payload, operationNo);
            ResolutionRecord previousBusiness =
                    loadCompletedBusinessResolution(operationNo, resolutionNo);

            SeedOutcome seed = seedFromBusinessDuplicate(previousBusiness);
            if (!persistReceipt(
                    decoded.envelope,
                    operationNo,
                    resolutionNo,
                    resolutionType,
                    settledQuantity,
                    resolvedAt,
                    ack.eventNo,
                    ack.resultStatus,
                    ackPayload,
                    seed
            )) {
                reportStorageFault("人工结案指令和ACK未能可靠保存：" + messageId);
                return;
            }

            MqttManager.get(context).reportCommandResult(ackPayload);
            ResolutionRecord saved = loadByMessageId(messageId);
            if (saved == null) {
                reportStorageFault("人工结案指令保存后无法读取：" + messageId);
                return;
            }
            finishResolution(decoded, saved);
        } catch (Throwable error) {
            reportStorageFault("人工结案ACK编码或保存失败：" + messageOf(error));
        }
    }

    private void replayOrResume(
            SdkCommandDecoder.DecodedCommand decoded,
            ResolutionRecord record
    ) {
        if (!requeueStoredResults(record)) {
            reportStorageFault("人工结案重复回执未能重新进入outbox：" + record.messageId);
            return;
        }
        if (!blank(record.ackPayload)) {
            MqttManager.get(context).reportCommandResult(record.ackPayload);
        }
        if (!blank(record.terminalPayload)) {
            MqttManager.get(context).reportStatus();
            MqttManager.get(context).reportCommandResult(record.terminalPayload);
            return;
        }
        finishResolution(decoded, record);
    }

    private void finishResolution(
            SdkCommandDecoder.DecodedCommand decoded,
            ResolutionRecord record
    ) {
        ResolutionRecord current = record;
        if (blank(current.resultCode)) {
            if (!evaluateAndPersistOutcome(current)) {
                reportStorageFault("人工结案本地会话判定未能保存：" + current.messageId);
                return;
            }
            current = loadByMessageId(current.messageId);
        }
        if (current == null || blank(current.resultCode)) {
            reportStorageFault("人工结案结果状态缺失：" + record.messageId);
            return;
        }

        awaitFreshHardwareStatus();
        int runningStatus = getRunningStatus();
        if (runningStatus == 0) {
            restoreCashAcceptanceIfSafe();
        }
        MqttManager.get(context).reportStatus();
        if (current.outcomeSuccess && !store.hasActivePhysicalOrder()) {
            broadcastResolvedSession(runningStatus);
        }

        String resultMessage = terminalResultMessage(current, runningStatus);
        try {
            SdkCommandDecoder.EncodedResult terminal = decoded.genericTerminal(
                    current.messageId + "-result",
                    current.outcomeSuccess,
                    current.resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            );
            String terminalPayload = addOperationNo(
                    terminal.payload,
                    current.operationNo
            );
            if (!persistTerminal(
                    current.messageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminalPayload,
                    resultMessage
            )) {
                reportStorageFault("人工结案终态未能可靠保存：" + current.messageId);
                return;
            }
            MqttManager.get(context).reportCommandResult(terminalPayload);
            Log.i(
                    TAG,
                    "人工结案处理完成：operationNo=" + current.operationNo
                            + ", resolutionNo=" + current.resolutionNo
                            + ", resultCode=" + current.resultCode
                            + ", runningStatus=" + runningStatus
            );
        } catch (Throwable error) {
            reportStorageFault("人工结案终态编码失败：" + messageOf(error));
        }
    }

    private boolean evaluateAndPersistOutcome(ResolutionRecord record) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ResolutionRecord latest = loadByMessageId(db, record.messageId);
                if (latest == null) {
                    return false;
                }
                if (!blank(latest.resultCode)) {
                    db.setTransactionSuccessful();
                    return true;
                }

                ActiveSession active = loadActiveSession(db);
                boolean outcomeSuccess;
                boolean sideEffectApplied = false;
                String resultCode;
                String resultMessage;

                if (active != null) {
                    String activeOperationNo = loadDispenseOperationNo(
                            db,
                            active.messageId
                    );
                    if (blank(activeOperationNo)) {
                        outcomeSuccess = false;
                        resultCode = "LOCAL_SESSION_STATE_INVALID";
                        resultMessage = "active operation has no persisted operationNo";
                    } else if (!record.operationNo.equals(activeOperationNo)) {
                        outcomeSuccess = false;
                        resultCode = "OPERATION_MISMATCH";
                        resultMessage = "active operation does not match";
                    } else if (!"BLOCKED".equals(active.state)) {
                        outcomeSuccess = false;
                        resultCode = "LOCAL_SESSION_STATE_INVALID";
                        resultMessage = "matched operation is still physically active";
                    } else {
                        if (db.delete(
                                "active_physical_order",
                                "id=1 AND message_id=?",
                                new String[]{active.messageId}
                        ) != 1) {
                            return false;
                        }
                        putMeta(db, META_PHYSICAL_BLOCKED, "0");
                        outcomeSuccess = true;
                        sideEffectApplied = true;
                        resultCode = "OPERATION_RESOLVED";
                        resultMessage = "local operation resolved";
                    }
                } else if (hasHistoricalDispenseOperation(db, record.operationNo)) {
                    outcomeSuccess = true;
                    sideEffectApplied = true;
                    resultCode = "OPERATION_ALREADY_RESOLVED";
                    resultMessage = "local operation already resolved";
                } else {
                    outcomeSuccess = false;
                    resultCode = "LOCAL_SESSION_STATE_INVALID";
                    resultMessage = "local operation cannot be identified safely";
                }

                ContentValues values = new ContentValues();
                values.put("outcome_success", outcomeSuccess ? 1 : 0);
                values.put("side_effect_applied", sideEffectApplied ? 1 : 0);
                values.put("result_code", resultCode);
                values.put("result_message", resultMessage);
                values.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        TABLE,
                        values,
                        "message_id=?",
                        new String[]{record.messageId}
                ) != 1) {
                    return false;
                }

                ContentValues commandValues = new ContentValues();
                commandValues.put("state", outcomeSuccess ? "resolved" : "failed");
                commandValues.put("updated_at", System.currentTimeMillis());
                db.update(
                        "commands",
                        commandValues,
                        "message_id=?",
                        new String[]{record.messageId}
                );

                db.setTransactionSuccessful();
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "人工结案本地状态事务失败", error);
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    private boolean persistReceipt(
            JSONObject envelope,
            String operationNo,
            String resolutionNo,
            String resolutionType,
            int settledQuantity,
            String resolvedAt,
            String ackEventNo,
            String ackStatus,
            String ackPayload,
            SeedOutcome seed
    ) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                String messageId = envelope.optString("messageId", "").trim();
                if (!saveCommandEnvelope(db, envelope, "received")) {
                    return false;
                }

                long now = System.currentTimeMillis();
                ContentValues values = new ContentValues();
                values.put("message_id", messageId);
                values.put("operation_no", operationNo);
                values.put("resolution_no", resolutionNo);
                values.put("resolution_type", resolutionType);
                values.put("settled_quantity", settledQuantity);
                values.put("resolved_at", resolvedAt);
                values.put("command_envelope", envelope.toString());
                values.put("ack_event_no", ackEventNo);
                values.put("ack_payload", ackPayload);
                values.put("side_effect_applied", seed.sideEffectApplied ? 1 : 0);
                if (!blank(seed.resultCode)) {
                    values.put("outcome_success", seed.outcomeSuccess ? 1 : 0);
                    values.put("result_code", seed.resultCode);
                    values.put("result_message", seed.resultMessage);
                }
                values.put("created_at", now);
                values.put("updated_at", now);
                if (db.insert(TABLE, null, values) == -1L) {
                    return false;
                }
                if (!saveOutbox(
                        db,
                        messageId + "|" + ackEventNo + "|" + ackStatus,
                        messageId,
                        ackEventNo,
                        ackStatus,
                        ackPayload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "保存人工结案接收记录失败", error);
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    private boolean persistTerminal(
            String messageId,
            String eventNo,
            String status,
            String payload,
            String resultMessage
    ) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ResolutionRecord record = loadByMessageId(db, messageId);
                if (record == null) {
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
                values.put("result_message", resultMessage);
                values.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        TABLE,
                        values,
                        "message_id=?",
                        new String[]{messageId}
                ) != 1) {
                    return false;
                }
                if (!saveOutbox(
                        db,
                        messageId + "|" + eventNo + "|" + status,
                        messageId,
                        eventNo,
                        status,
                        payload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "保存人工结案终态失败", error);
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    private boolean requeueStoredResults(ResolutionRecord record) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (!blank(record.ackPayload) && !saveOutbox(
                        db,
                        record.messageId + "|" + record.ackEventNo + "|ack",
                        record.messageId,
                        record.ackEventNo,
                        "ack",
                        record.ackPayload
                )) {
                    return false;
                }
                if (!blank(record.terminalPayload) && !saveOutbox(
                        db,
                        record.messageId + "|" + record.terminalEventNo
                                + "|" + record.terminalStatus,
                        record.messageId,
                        record.terminalEventNo,
                        record.terminalStatus,
                        record.terminalPayload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "人工结案重复回执重新入队失败", error);
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    private void awaitFreshHardwareStatus() {
        CountDownLatch latch = new CountDownLatch(1);
        hardwareStatusLatch = latch;
        try {
            if (!SerialManager.get(context).sendCommand(
                    CMD_HARDWARE_STATUS,
                    0L,
                    false
            )) {
                return;
            }
            latch.await(HARDWARE_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (hardwareStatusLatch == latch) {
                hardwareStatusLatch = null;
            }
        }
    }

    /**
     * 只在业务会话已结束、K1本地复位已完成且库存状态正常时恢复现金入口。
     * 本方法不会发送补珠命令，也不会触发任何出珠动作。
     */
    private void restoreCashAcceptanceIfSafe() {
        if (getRunningStatus() != 0
                || store.getBoardVersion() < MIN_CONTROLLER_PROTOCOL_VERSION) {
            return;
        }
        DeviceCommandStore.CashConfigurationRecord record =
                store.loadCashConfiguration();
        if (record == null || !record.enabled || record.changeEnabled
                || record.configVersion <= 0
                || record.configVersion > 0x00FFFFFF) {
            return;
        }

        JSONObject snapshot = parseObject(record.snapshotJson);
        JSONObject data = snapshot == null ? null : snapshot.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("cashSaleItems");
        if (items == null || items.length() == 0) {
            return;
        }
        int mask = 0;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                return;
            }
            String medium = item.optString("cashMediumType", "");
            if ("banknote".equals(medium)) {
                mask |= 0x01;
            } else if ("coin".equals(medium)) {
                mask |= 0x02;
            } else {
                return;
            }
        }
        if (mask == 0) {
            return;
        }

        long packed = ((long) mask << 24)
                | (record.configVersion & 0x00FFFFFFL);
        boolean sent = SerialManager.get(context).sendCommand(
                CMD_CASH_ACCEPTANCE_APPLY_V22,
                packed,
                true
        );
        Log.i(
                TAG,
                "人工结案后恢复现金配置：sent=" + sent
                        + ", mask=0x" + Integer.toHexString(mask)
                        + ", version=" + record.configVersion
        );
    }

    private void broadcastResolvedSession(int runningStatus) {
        Intent intent = new Intent(AppConfig.ACTION_DISPENSE_ORDER_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra("eventType", "idle");
        intent.putExtra("orderSequence", 0);
        intent.putExtra("requestedQuantity", 0);
        intent.putExtra("actualQuantity", 0);
        intent.putExtra("resultCode", 0);
        intent.putExtra(
                "message",
                runningStatus == 0
                        ? "manual operation resolved"
                        : "manual operation resolved; hardware fault remains"
        );
        context.sendBroadcast(intent);
    }

    private void ensureSchema() {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "message_id TEXT PRIMARY KEY,"
                    + "operation_no TEXT NOT NULL,"
                    + "resolution_no TEXT NOT NULL,"
                    + "resolution_type TEXT NOT NULL,"
                    + "settled_quantity INTEGER NOT NULL,"
                    + "resolved_at TEXT NOT NULL,"
                    + "command_envelope TEXT NOT NULL,"
                    + "ack_event_no TEXT NOT NULL,"
                    + "ack_payload TEXT NOT NULL,"
                    + "outcome_success INTEGER,"
                    + "side_effect_applied INTEGER NOT NULL DEFAULT 0,"
                    + "result_code TEXT,"
                    + "result_message TEXT,"
                    + "terminal_event_no TEXT,"
                    + "terminal_status TEXT,"
                    + "terminal_payload TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_operation_resolution_business "
                    + "ON " + TABLE + "(operation_no,resolution_no,updated_at)");
        }
    }

    private void initializeResetGate() {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            if (hasMeta(db, META_LOCAL_RESET_REQUIRED)) {
                return;
            }
            DeviceCommandStore.ActivePhysicalOrder active =
                    store.loadActivePhysicalOrder();
            if (active != null && "BLOCKED".equals(active.state)) {
                putMeta(db, META_LOCAL_RESET_REQUIRED, "1");
            }
        }
    }

    private ResolutionRecord loadByMessageId(String messageId) {
        synchronized (store) {
            return loadByMessageId(store.getReadableDatabase(), messageId);
        }
    }

    private static ResolutionRecord loadByMessageId(
            SQLiteDatabase db,
            String messageId
    ) {
        try (Cursor cursor = db.query(
                TABLE,
                null,
                "message_id=?",
                new String[]{messageId},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? readRecord(cursor) : null;
        }
    }

    private ResolutionRecord loadCompletedBusinessResolution(
            String operationNo,
            String resolutionNo
    ) {
        synchronized (store) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    TABLE,
                    null,
                    "operation_no=? AND resolution_no=? "
                            + "AND terminal_payload IS NOT NULL",
                    new String[]{operationNo, resolutionNo},
                    null,
                    null,
                    "updated_at DESC",
                    "1"
            )) {
                return cursor.moveToFirst() ? readRecord(cursor) : null;
            }
        }
    }

    private static ResolutionRecord readRecord(Cursor cursor) {
        ResolutionRecord record = new ResolutionRecord();
        record.messageId = cursor.getString(cursor.getColumnIndexOrThrow("message_id"));
        record.operationNo = cursor.getString(cursor.getColumnIndexOrThrow("operation_no"));
        record.resolutionNo = cursor.getString(cursor.getColumnIndexOrThrow("resolution_no"));
        record.resolutionType = cursor.getString(cursor.getColumnIndexOrThrow("resolution_type"));
        record.settledQuantity = cursor.getInt(cursor.getColumnIndexOrThrow("settled_quantity"));
        record.resolvedAt = cursor.getString(cursor.getColumnIndexOrThrow("resolved_at"));
        record.ackEventNo = cursor.getString(cursor.getColumnIndexOrThrow("ack_event_no"));
        record.ackPayload = cursor.getString(cursor.getColumnIndexOrThrow("ack_payload"));
        int outcomeIndex = cursor.getColumnIndexOrThrow("outcome_success");
        record.outcomeSuccess = !cursor.isNull(outcomeIndex)
                && cursor.getInt(outcomeIndex) != 0;
        record.sideEffectApplied = cursor.getInt(
                cursor.getColumnIndexOrThrow("side_effect_applied")) != 0;
        record.resultCode = cursor.getString(cursor.getColumnIndexOrThrow("result_code"));
        record.resultMessage = cursor.getString(cursor.getColumnIndexOrThrow("result_message"));
        record.terminalEventNo = cursor.getString(
                cursor.getColumnIndexOrThrow("terminal_event_no"));
        record.terminalStatus = cursor.getString(
                cursor.getColumnIndexOrThrow("terminal_status"));
        record.terminalPayload = cursor.getString(
                cursor.getColumnIndexOrThrow("terminal_payload"));
        return record;
    }

    private static ActiveSession loadActiveSession(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "active_physical_order",
                new String[]{"message_id", "state"},
                "id=1",
                null,
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ActiveSession result = new ActiveSession();
            result.messageId = cursor.getString(0);
            result.state = cursor.getString(1);
            return result;
        }
    }

    private static String loadDispenseOperationNo(
            SQLiteDatabase db,
            String messageId
    ) {
        try (Cursor cursor = db.query(
                "commands",
                new String[]{"envelope"},
                "message_id=?",
                new String[]{messageId},
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return "";
            }
            JSONObject envelope = parseObject(cursor.getString(0));
            if (envelope == null
                    || !DISPENSE_COMMAND_TYPE.equals(
                    envelope.optString("commandType", ""))) {
                return "";
            }
            JSONObject data = envelope.optJSONObject("data");
            return data == null ? "" : data.optString("operationNo", "").trim();
        }
    }

    private static boolean hasHistoricalDispenseOperation(
            SQLiteDatabase db,
            String operationNo
    ) {
        try (Cursor cursor = db.query(
                "commands",
                new String[]{"envelope", "state"},
                null,
                null,
                null,
                null,
                "updated_at DESC"
        )) {
            while (cursor.moveToNext()) {
                JSONObject envelope = parseObject(cursor.getString(0));
                String state = cursor.getString(1);
                if (envelope == null
                        || !DISPENSE_COMMAND_TYPE.equals(
                        envelope.optString("commandType", ""))) {
                    continue;
                }
                JSONObject data = envelope.optJSONObject("data");
                if (data == null || !operationNo.equals(
                        data.optString("operationNo", "").trim())) {
                    continue;
                }
                boolean terminalEvidence = data.optBoolean("deviceTerminal", false)
                        || "terminal".equals(state)
                        || "finishing".equals(state)
                        || "blocked".equals(state)
                        || "resolved".equals(state);
                if (terminalEvidence) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean saveCommandEnvelope(
            SQLiteDatabase db,
            JSONObject envelope,
            String state
    ) {
        String messageId = envelope.optString("messageId", "").trim();
        try (Cursor cursor = db.query(
                "commands",
                new String[]{"envelope"},
                "message_id=?",
                new String[]{messageId},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return envelope.toString().equals(cursor.getString(0));
            }
        }
        ContentValues values = new ContentValues();
        values.put("message_id", messageId);
        values.put("envelope", envelope.toString());
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return db.insert("commands", null, values) != -1L;
    }

    private static boolean saveOutbox(
            SQLiteDatabase db,
            String receiptKey,
            String sourceMessageId,
            String eventNo,
            String status,
            String payload
    ) {
        ContentValues values = new ContentValues();
        values.put("receipt_key", receiptKey);
        values.put("kind", "command_result");
        values.put("source_message_id", sourceMessageId);
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

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value == null ? "" : value);
        db.insertWithOnConflict(
                "meta",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    private static boolean hasMeta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                "meta",
                new String[]{"key"},
                "key=?",
                new String[]{key},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst();
        }
    }

    private void setLocalResetRequired(boolean required) {
        synchronized (store) {
            putMeta(
                    store.getWritableDatabase(),
                    META_LOCAL_RESET_REQUIRED,
                    required ? "1" : "0"
            );
        }
    }

    private boolean isLocalResetRequired() {
        synchronized (store) {
            SQLiteDatabase db = store.getReadableDatabase();
            try (Cursor cursor = db.query(
                    "meta",
                    new String[]{"value"},
                    "key=?",
                    new String[]{META_LOCAL_RESET_REQUIRED},
                    null,
                    null,
                    null
            )) {
                return cursor.moveToFirst() && "1".equals(cursor.getString(0));
            }
        }
    }

    private static SeedOutcome seedFromBusinessDuplicate(
            ResolutionRecord previous
    ) {
        SeedOutcome seed = new SeedOutcome();
        if (previous == null) {
            return seed;
        }
        if (previous.outcomeSuccess) {
            seed.outcomeSuccess = true;
            seed.sideEffectApplied = true;
            seed.resultCode = "OPERATION_ALREADY_RESOLVED";
            seed.resultMessage = "local operation already resolved";
        } else {
            seed.outcomeSuccess = false;
            seed.sideEffectApplied = previous.sideEffectApplied;
            seed.resultCode = previous.resultCode;
            seed.resultMessage = previous.resultMessage;
        }
        return seed;
    }

    private static String terminalResultMessage(
            ResolutionRecord record,
            int runningStatus
    ) {
        if (record.outcomeSuccess) {
            String prefix = "OPERATION_ALREADY_RESOLVED".equals(record.resultCode)
                    ? "local operation already resolved"
                    : "local operation resolved";
            return prefix + "; runningStatus=" + runningStatus;
        }
        return blank(record.resultMessage)
                ? "local operation resolution failed"
                : record.resultMessage;
    }

    private static String addOperationNo(String payload, String operationNo) {
        try {
            JSONObject json = new JSONObject(payload);
            json.put("operationNo", operationNo);
            return json.toString();
        } catch (Throwable error) {
            throw new IllegalArgumentException(
                    "command-result JSON augmentation failed: " + messageOf(error),
                    error
            );
        }
    }

    private static JSONObject parseObject(String value) {
        try {
            return blank(value) ? null : new JSONObject(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private void reportProtocolFault(String description) {
        MqttManager.get(context).reportFault(
                "RESOLVE_MARBLE_OPERATION_INVALID",
                "resolve marble operation command invalid",
                3,
                description
        );
    }

    private void reportStorageFault(String description) {
        MqttManager.get(context).reportFault(
                "LOCAL_STORAGE_ERROR",
                "local business database error",
                3,
                description
        );
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
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

    private static final class ActiveSession {
        String messageId;
        String state;
    }

    private static final class SeedOutcome {
        boolean outcomeSuccess;
        boolean sideEffectApplied;
        String resultCode;
        String resultMessage;
    }

    private static final class ResolutionRecord {
        String messageId;
        String operationNo;
        String resolutionNo;
        String resolutionType;
        int settledQuantity;
        String resolvedAt;
        String ackEventNo;
        String ackPayload;
        boolean outcomeSuccess;
        boolean sideEffectApplied;
        String resultCode;
        String resultMessage;
        String terminalEventNo;
        String terminalStatus;
        String terminalPayload;
    }
}
