package com.gouzhu.mqtt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.gouzhu.hardware.SerialMarbleHardwareAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 平台统一出珠业务数据库。
 *
 * <p>命令、现金事实、完整现金配置和等待平台业务确认的 outbox 均落 SQLite。
 * MQTT PUBACK 不删除任何业务记录；只有 command_result_ack 或明确现金业务响应
 * 才能删除对应 outbox。旧 SharedPreferences 状态不读取、不迁移。</p>
 */
public final class DeviceCommandStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "gouzhu_platform_control_v2.db";
    private static final int DB_VERSION = 3;

    private static final String META_BOARD_VERSION = "board_version";
    private static final String META_NEXT_ORDER_SEQUENCE = "next_order_sequence";
    private static final String META_PHYSICAL_BLOCKED = "physical_blocked";
    private static final String META_PENDING_CONFIG = "pending_cash_config_message";
    private static final String META_LATEST_CONFIG_VERSION = "latest_cash_config_version";
    private static final String META_PENDING_CONFIG_VERSION = "pending_cash_config_version";
    private static final String META_PENDING_CONFIG_ENABLED = "pending_cash_config_enabled";
    private static final String META_PENDING_CONFIG_CHANGE = "pending_cash_config_change";
    private static final String META_PENDING_CONFIG_SNAPSHOT = "pending_cash_config_snapshot";
    private static final String META_PENDING_CONFIG_FAILURE_EVENT_NO =
            "pending_cash_config_failure_event_no";
    private static final String META_PENDING_CONFIG_FAILURE_STATUS =
            "pending_cash_config_failure_status";
    private static final String META_PENDING_CONFIG_FAILURE_PAYLOAD =
            "pending_cash_config_failure_payload";
    private static final String META_CASH_BLOCKED = "cash_blocked";

    public DeviceCommandStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE commands ("
                + "message_id TEXT PRIMARY KEY,"
                + "envelope TEXT NOT NULL,"
                + "state TEXT NOT NULL DEFAULT 'received',"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE outbox ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "receipt_key TEXT NOT NULL UNIQUE,"
                + "kind TEXT NOT NULL,"
                + "source_message_id TEXT NOT NULL,"
                + "event_no TEXT NOT NULL,"
                + "result_status TEXT NOT NULL,"
                + "payload TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE cash_events ("
                + "event_no TEXT PRIMARY KEY,"
                + "board_sequence INTEGER NOT NULL UNIQUE,"
                + "status TEXT NOT NULL,"
                + "payload TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE cash_configuration ("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "config_version INTEGER NOT NULL,"
                + "enabled INTEGER NOT NULL,"
                + "change_enabled INTEGER NOT NULL,"
                + "snapshot_json TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_outbox_kind_id ON outbox(kind,id)");
        createActivePhysicalOrderTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createActivePhysicalOrderTable(db);
        }
        if (oldVersion < 3) {
            addColumnIfMissing(
                    db,
                    "active_physical_order",
                    "source_topic",
                    "TEXT NOT NULL DEFAULT ''"
            );
        }
    }

    public synchronized boolean saveCommand(JSONObject envelope) {
        if (envelope == null) {
            return false;
        }
        String messageId = envelope.optString("messageId", "").trim();
        if (messageId.isEmpty()) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("message_id", messageId);
        values.put("envelope", envelope.toString());
        values.put("state", commandState(envelope));
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "commands", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L;
    }

    public synchronized JSONObject loadCommand(String messageId) {
        if (blank(messageId)) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query(
                "commands",
                new String[]{"envelope"},
                "message_id=?",
                new String[]{messageId},
                null,
                null,
                null)) {
            return cursor.moveToFirst() ? parseObject(cursor.getString(0)) : null;
        }
    }

    public synchronized boolean hasCommand(String messageId) {
        return loadCommand(messageId) != null;
    }

    public synchronized CreatePhysicalOrderResult createActivePhysicalOrder(
            JSONObject envelope,
            String sourceTopic,
            int requestedQuantity
    ) {
        CreatePhysicalOrderResult result = new CreatePhysicalOrderResult();
        if (envelope == null || blank(sourceTopic)
                || requestedQuantity <= 0 || requestedQuantity > 0xFFFF) {
            result.resultCode = "PARAM_INVALID";
            return result;
        }
        String messageId = envelope.optString("messageId", "").trim();
        JSONObject data = envelope.optJSONObject("data");
        if (messageId.isEmpty() || data == null) {
            result.resultCode = "COMMAND_INVALID";
            return result;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (hasActivePhysicalOrder(db)) {
                result.resultCode = "PREVIOUS_PHYSICAL_ORDER_ACTIVE";
                return result;
            }

            int sequence = parsePositiveInt(getMeta(db, META_NEXT_ORDER_SEQUENCE));
            if (sequence <= 0 || sequence > 0xFFFF) {
                sequence = 1;
            }
            int nextSequence = sequence >= 0xFFFF ? 1 : sequence + 1;
            long now = System.currentTimeMillis();

            data.put("orderSequence", sequence);
            data.put("deviceStartRequested", true);
            data.put("deviceActualQuantity", 0);
            data.put("deviceTerminal", false);
            data.put("deviceStartRequestedAt", now);
            if (!saveCommandEnvelope(db, envelope, "dispensing")) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }

            ContentValues values = new ContentValues();
            values.put("id", 1);
            values.put("message_id", messageId);
            values.put("source_topic", sourceTopic);
            values.put("order_sequence", sequence);
            values.put("requested_quantity", requestedQuantity);
            values.put("state", "DISPENSING");
            values.put("last_progress_actual", 0);
            values.put("terminal_ack_sent", 0);
            values.put("terminal_ack_echoed", 0);
            values.put("created_at", now);
            values.put("updated_at", now);
            if (db.insert("active_physical_order", null, values) == -1L) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }

            putMeta(db, META_NEXT_ORDER_SEQUENCE, String.valueOf(nextSequence));
            db.setTransactionSuccessful();
            result.success = true;
            result.messageId = messageId;
            result.orderSequence = sequence;
            result.requestedQuantity = requestedQuantity;
            result.resultCode = "OK";
            return result;
        } catch (Throwable error) {
            result.resultCode = "LOCAL_STORAGE_ERROR";
            result.resultMessage = messageOf(error);
            return result;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized ActivePhysicalOrder loadActivePhysicalOrder() {
        try (Cursor cursor = getReadableDatabase().query(
                "active_physical_order",
                new String[]{
                        "message_id",
                        "source_topic",
                        "order_sequence",
                        "requested_quantity",
                        "state",
                        "last_progress_actual",
                        "terminal_frame_id",
                        "terminal_actual",
                        "controller_terminal_actual",
                        "terminal_result_code",
                        "terminal_received_at",
                        "terminal_event_no",
                        "terminal_payload",
                        "terminal_result_status",
                        "terminal_ack_sent",
                        "terminal_ack_echoed",
                        "blocked_reason"
                },
                "id=1",
                null,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ActivePhysicalOrder result = new ActivePhysicalOrder();
            result.messageId = cursor.getString(0);
            result.sourceTopic = cursor.getString(1);
            result.orderSequence = cursor.getInt(2);
            result.requestedQuantity = cursor.getInt(3);
            result.state = cursor.getString(4);
            result.lastProgressActual = cursor.getInt(5);
            result.terminalFrameId = cursor.isNull(6) ? -1 : cursor.getInt(6);
            result.terminalActual = cursor.isNull(7) ? -1 : cursor.getInt(7);
            result.controllerTerminalActual = cursor.isNull(8) ? -1 : cursor.getInt(8);
            result.terminalResultCode = cursor.isNull(9) ? -1 : cursor.getInt(9);
            result.terminalReceivedAt = cursor.isNull(10) ? 0L : cursor.getLong(10);
            result.terminalEventNo = cursor.getString(11);
            result.terminalPayload = cursor.getString(12);
            result.terminalResultStatus = cursor.getString(13);
            result.terminalAckSent = cursor.getInt(14) != 0;
            result.terminalAckEchoed = cursor.getInt(15) != 0;
            result.blockedReason = cursor.getString(16);
            return result;
        }
    }

    public synchronized boolean hasActivePhysicalOrder() {
        return hasActivePhysicalOrder(getReadableDatabase());
    }

    public synchronized boolean updatePhysicalProgress(
            int orderSequence,
            int actual
    ) {
        if (orderSequence <= 0 || actual < 0 || actual > 0xFFFF) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ActivePhysicalOrder active = loadActivePhysicalOrder(db);
            if (active == null || active.orderSequence != orderSequence) {
                return false;
            }
            int safeActual = Math.max(active.lastProgressActual, actual);
            ContentValues values = new ContentValues();
            values.put("last_progress_actual", safeActual);
            values.put("updated_at", System.currentTimeMillis());
            boolean updated = db.update(
                    "active_physical_order",
                    values,
                    "id=1 AND order_sequence=?",
                    new String[]{String.valueOf(orderSequence)}
            ) == 1;
            if (updated) {
                db.setTransactionSuccessful();
            }
            return updated;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized TerminalStoreResult savePhysicalTerminalAndOutbox(
            JSONObject envelope,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
            boolean success,
            int finalActual,
            String resultCode,
            String resultMessage,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        TerminalStoreResult result = new TerminalStoreResult();
        if (envelope == null || evidence == null || blank(eventNo)
                || blank(resultStatus) || blank(payload)) {
            result.resultCode = "PARAM_INVALID";
            return result;
        }
        String messageId = envelope.optString("messageId", "").trim();
        JSONObject data = envelope.optJSONObject("data");
        if (messageId.isEmpty() || data == null) {
            result.resultCode = "COMMAND_INVALID";
            return result;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ActivePhysicalOrder active = loadActivePhysicalOrder(db);
            if (active == null
                    || !messageId.equals(active.messageId)
                    || active.orderSequence != evidence.orderSequence) {
                result.resultCode = "PHYSICAL_ORDER_NOT_ACTIVE";
                return result;
            }

            if (active.terminalFrameId >= 0) {
                boolean sameTerminal = active.terminalFrameId == evidence.frameId
                        && active.controllerTerminalActual == evidence.terminalActual
                        && active.terminalResultCode == evidence.controllerResultCode;
                if (sameTerminal) {
                    String storedPayload = blank(active.terminalPayload)
                            ? payload
                            : active.terminalPayload;
                    String storedEventNo = blank(active.terminalEventNo)
                            ? eventNo
                            : active.terminalEventNo;
                    String storedStatus = blank(active.terminalResultStatus)
                            ? resultStatus
                            : active.terminalResultStatus;
                    if (!saveOutbox(
                            db,
                            messageId + "|" + storedEventNo + "|" + storedStatus,
                            "command_result",
                            messageId,
                            storedEventNo,
                            storedStatus,
                            storedPayload
                    )) {
                        result.resultCode = "LOCAL_STORAGE_ERROR";
                        return result;
                    }
                    db.setTransactionSuccessful();
                    result.success = true;
                    result.duplicate = true;
                    result.eventNo = storedEventNo;
                    result.resultStatus = storedStatus;
                    result.payload = storedPayload;
                    return result;
                }

                data.put("terminalConflictFrameId", evidence.frameId);
                data.put("terminalConflictActual", evidence.terminalActual);
                data.put("terminalConflictResultCode", evidence.controllerResultCode);
                data.put("terminalConflictReceivedAt", evidence.receivedAt);
                data.put("blockedReason", "PHYSICAL_TERMINAL_CONFLICT");
                if (!saveCommandEnvelope(db, envelope, "blocked")) {
                    result.resultCode = "LOCAL_STORAGE_ERROR";
                    return result;
                }
                ContentValues values = new ContentValues();
                values.put("state", "BLOCKED");
                values.put("blocked_reason", "PHYSICAL_TERMINAL_CONFLICT");
                values.put("updated_at", System.currentTimeMillis());
                db.update("active_physical_order", values, "id=1", null);
                putMeta(db, META_PHYSICAL_BLOCKED, "1");
                db.setTransactionSuccessful();
                result.success = true;
                result.conflict = true;
                result.resultCode = "PHYSICAL_TERMINAL_CONFLICT";
                return result;
            }

            if (!blank(active.terminalEventNo)) {
                data.put("lateControllerTerminalFrameId", evidence.frameId);
                data.put("lateControllerTerminalActual", evidence.terminalActual);
                data.put("lateControllerTerminalResultCode", evidence.controllerResultCode);
                data.put("lateControllerTerminalReceivedAt", evidence.receivedAt);
                if (!saveCommandEnvelope(db, envelope, "blocked")) {
                    result.resultCode = "LOCAL_STORAGE_ERROR";
                    return result;
                }
                ContentValues lateValues = new ContentValues();
                lateValues.put("state", "BLOCKED");
                lateValues.put("terminal_frame_id", evidence.frameId);
                lateValues.put("controller_terminal_actual", evidence.terminalActual);
                lateValues.put("terminal_result_code", evidence.controllerResultCode);
                lateValues.put("terminal_received_at", evidence.receivedAt);
                lateValues.put("terminal_ack_sent", 0);
                lateValues.put("terminal_ack_echoed", 0);
                lateValues.put("blocked_reason", blank(active.blockedReason)
                        ? "CONTROLLER_TERMINAL_MISSING"
                        : active.blockedReason);
                lateValues.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        "active_physical_order",
                        lateValues,
                        "id=1 AND order_sequence=?",
                        new String[]{String.valueOf(evidence.orderSequence)}
                ) != 1) {
                    result.resultCode = "LOCAL_STORAGE_ERROR";
                    return result;
                }
                putMeta(db, META_PHYSICAL_BLOCKED, "1");
                db.setTransactionSuccessful();
                result.success = true;
                result.lateAfterUnknown = true;
                result.eventNo = active.terminalEventNo;
                result.resultStatus = active.terminalResultStatus;
                result.payload = active.terminalPayload;
                result.resultCode = "LATE_TERMINAL_AFTER_UNKNOWN";
                return result;
            }

            long now = System.currentTimeMillis();
            data.put("deviceActualQuantity", finalActual);
            data.put("deviceTerminal", true);
            data.put("deviceTerminalAt", now);
            data.put("deviceResultCode", safe(resultCode));
            data.put("deviceResultMessage", safe(resultMessage));
            data.put("controllerTerminalFrameId", evidence.frameId);
            data.put("controllerTerminalActual", evidence.terminalActual);
            data.put("controllerTerminalResultCode", evidence.controllerResultCode);
            data.put("controllerTerminalReceivedAt", evidence.receivedAt);
            data.put("lastProgressActual", evidence.lastProgressActual);
            data.put("terminalEventNo", eventNo);

            if (!saveCommandEnvelope(db, envelope, success ? "terminal" : "blocked")) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }
            ContentValues values = new ContentValues();
            values.put("state", success ? "FINISHING" : "BLOCKED");
            values.put("last_progress_actual", Math.max(
                    evidence.lastProgressActual,
                    Math.max(0, active.lastProgressActual)
            ));
            values.put("terminal_frame_id", evidence.frameId);
            values.put("terminal_actual", finalActual);
            values.put("controller_terminal_actual", evidence.terminalActual);
            values.put("terminal_result_code", evidence.controllerResultCode);
            values.put("terminal_received_at", evidence.receivedAt);
            values.put("terminal_event_no", eventNo);
            values.put("terminal_payload", payload);
            values.put("terminal_result_status", resultStatus);
            values.put("terminal_ack_sent", 0);
            values.put("terminal_ack_echoed", 0);
            values.put("blocked_reason", success ? "" : safe(resultCode));
            values.put("updated_at", now);
            if (db.update(
                    "active_physical_order",
                    values,
                    "id=1 AND order_sequence=?",
                    new String[]{String.valueOf(evidence.orderSequence)}
            ) != 1) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }
            if (!saveOutbox(
                    db,
                    messageId + "|" + eventNo + "|" + resultStatus,
                    "command_result",
                    messageId,
                    eventNo,
                    resultStatus,
                    payload
            )) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }
            putMeta(db, META_PHYSICAL_BLOCKED, success ? "0" : "1");
            db.setTransactionSuccessful();
            result.success = true;
            result.eventNo = eventNo;
            result.resultStatus = resultStatus;
            result.payload = payload;
            result.resultCode = "OK";
            return result;
        } catch (Throwable error) {
            result.resultCode = "LOCAL_STORAGE_ERROR";
            result.resultMessage = messageOf(error);
            return result;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized TerminalStoreResult savePhysicalUnknownResult(
            JSONObject envelope,
            int orderSequence,
            int observedActual,
            String resultCode,
            String resultMessage,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        TerminalStoreResult result = new TerminalStoreResult();
        if (envelope == null || orderSequence <= 0 || blank(eventNo)
                || blank(resultStatus) || blank(payload)) {
            result.resultCode = "PARAM_INVALID";
            return result;
        }
        String messageId = envelope.optString("messageId", "").trim();
        JSONObject data = envelope.optJSONObject("data");
        if (messageId.isEmpty() || data == null) {
            result.resultCode = "COMMAND_INVALID";
            return result;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ActivePhysicalOrder active = loadActivePhysicalOrder(db);
            if (active == null
                    || !messageId.equals(active.messageId)
                    || active.orderSequence != orderSequence) {
                result.resultCode = "PHYSICAL_ORDER_NOT_ACTIVE";
                return result;
            }
            if (!blank(active.terminalEventNo)) {
                db.setTransactionSuccessful();
                result.success = true;
                result.duplicate = true;
                result.eventNo = active.terminalEventNo;
                result.resultStatus = active.terminalResultStatus;
                result.payload = active.terminalPayload;
                result.resultCode = "OK";
                return result;
            }

            long now = System.currentTimeMillis();
            int safeActual = Math.max(0, Math.min(0xFFFF, observedActual));
            data.put("deviceActualQuantity", safeActual);
            data.put("deviceTerminal", true);
            data.put("deviceTerminalAt", now);
            data.put("deviceResultCode", safe(resultCode));
            data.put("deviceResultMessage", safe(resultMessage));
            data.put("deviceResultUnknown", true);
            data.put("lastProgressActual", Math.max(active.lastProgressActual, safeActual));
            data.put("terminalEventNo", eventNo);

            if (!saveCommandEnvelope(db, envelope, "blocked")) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }
            ContentValues values = new ContentValues();
            values.put("state", "BLOCKED");
            values.put("last_progress_actual", Math.max(active.lastProgressActual, safeActual));
            values.put("terminal_actual", safeActual);
            values.put("terminal_event_no", eventNo);
            values.put("terminal_payload", payload);
            values.put("terminal_result_status", resultStatus);
            values.put("terminal_ack_sent", 0);
            values.put("terminal_ack_echoed", 0);
            values.put("blocked_reason", "CONTROLLER_TERMINAL_MISSING");
            values.put("updated_at", now);
            if (db.update(
                    "active_physical_order",
                    values,
                    "id=1 AND order_sequence=?",
                    new String[]{String.valueOf(orderSequence)}
            ) != 1) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }
            if (!saveOutbox(
                    db,
                    messageId + "|" + eventNo + "|" + resultStatus,
                    "command_result",
                    messageId,
                    eventNo,
                    resultStatus,
                    payload
            )) {
                result.resultCode = "LOCAL_STORAGE_ERROR";
                return result;
            }
            putMeta(db, META_PHYSICAL_BLOCKED, "1");
            db.setTransactionSuccessful();
            result.success = true;
            result.eventNo = eventNo;
            result.resultStatus = resultStatus;
            result.payload = payload;
            result.resultCode = "OK";
            return result;
        } catch (Throwable error) {
            result.resultCode = "LOCAL_STORAGE_ERROR";
            result.resultMessage = messageOf(error);
            return result;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean markTerminalAckEchoed(
            int orderSequence,
            int terminalFrameId,
            boolean terminalSuccess
    ) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ActivePhysicalOrder active = loadActivePhysicalOrder(db);
            if (active == null
                    || active.orderSequence != orderSequence
                    || active.terminalFrameId != terminalFrameId) {
                return false;
            }
            if (terminalSuccess) {
                db.delete("active_physical_order", "id=1", null);
                putMeta(db, META_PHYSICAL_BLOCKED, "0");
            } else {
                ContentValues values = new ContentValues();
                values.put("state", "BLOCKED");
                values.put("terminal_ack_sent", 1);
                values.put("terminal_ack_echoed", 1);
                values.put("blocked_reason", blank(active.blockedReason)
                        ? "PHYSICAL_TERMINAL_FAILED"
                        : active.blockedReason);
                values.put("updated_at", System.currentTimeMillis());
                db.update("active_physical_order", values, "id=1", null);
                putMeta(db, META_PHYSICAL_BLOCKED, "1");
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean markTerminalAckSentWithoutEcho(
            int orderSequence,
            int terminalFrameId
    ) {
        ContentValues values = new ContentValues();
        values.put("terminal_ack_sent", 1);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(
                "active_physical_order",
                values,
                "id=1 AND order_sequence=? AND terminal_frame_id=?",
                new String[]{
                        String.valueOf(orderSequence),
                        String.valueOf(terminalFrameId)
                }
        ) == 1;
    }

    public synchronized void markActivePhysicalBlocked(String reason) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("state", "BLOCKED");
            values.put("blocked_reason", safe(reason));
            values.put("updated_at", System.currentTimeMillis());
            db.update("active_physical_order", values, "id=1", null);
            putMeta(db, META_PHYSICAL_BLOCKED, "1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean saveCommandResult(
            String sourceMessageId,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        if (blank(sourceMessageId) || blank(eventNo)
                || blank(resultStatus) || blank(payload)) {
            return false;
        }
        String receiptKey = sourceMessageId + "|" + eventNo + "|" + resultStatus;
        return saveOutbox(
                receiptKey,
                "command_result",
                sourceMessageId,
                eventNo,
                resultStatus,
                payload
        );
    }

    public synchronized List<OutboxItem> listCommandResults() {
        return listOutbox("command_result");
    }

    public synchronized void removeCommandResult(
            String sourceMessageId,
            String eventNo,
            String resultStatus
    ) {
        String receiptKey = safe(sourceMessageId) + "|"
                + safe(eventNo) + "|" + safe(resultStatus);
        getWritableDatabase().delete(
                "outbox", "receipt_key=?", new String[]{receiptKey});
    }

    public synchronized boolean saveCashEvent(
            String eventNo,
            int boardSequence,
            String payload
    ) {
        if (blank(eventNo) || boardSequence <= 0 || blank(payload)) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues event = new ContentValues();
            event.put("event_no", eventNo);
            event.put("board_sequence", boardSequence);
            event.put("status", "pending");
            event.put("payload", payload);
            event.put("created_at", System.currentTimeMillis());
            long eventResult = db.insertWithOnConflict(
                    "cash_events", null, event, SQLiteDatabase.CONFLICT_IGNORE);

            ContentValues outbox = new ContentValues();
            outbox.put("receipt_key", "cash|" + eventNo);
            outbox.put("kind", "cash_event");
            outbox.put("source_message_id", eventNo);
            outbox.put("event_no", eventNo);
            outbox.put("result_status", "pending");
            outbox.put("payload", payload);
            outbox.put("created_at", System.currentTimeMillis());
            long outboxResult = db.insertWithOnConflict(
                    "outbox", null, outbox, SQLiteDatabase.CONFLICT_IGNORE);

            boolean exists = eventResult != -1L || findCashEventBySequence(boardSequence) != null;
            boolean queued = outboxResult != -1L || hasOutbox("cash|" + eventNo);
            if (exists && queued) {
                db.setTransactionSuccessful();
                return true;
            }
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized CashEventRecord findCashEventBySequence(int boardSequence) {
        try (Cursor cursor = getReadableDatabase().query(
                "cash_events",
                new String[]{"event_no", "board_sequence", "status", "payload"},
                "board_sequence=?",
                new String[]{String.valueOf(boardSequence)},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            CashEventRecord result = new CashEventRecord();
            result.eventNo = cursor.getString(0);
            result.boardSequence = cursor.getInt(1);
            result.status = cursor.getString(2);
            result.payload = cursor.getString(3);
            return result;
        }
    }

    public synchronized CashEventRecord findCashEvent(String eventNo) {
        if (blank(eventNo)) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query(
                "cash_events",
                new String[]{"event_no", "board_sequence", "status", "payload"},
                "event_no=?",
                new String[]{eventNo},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            CashEventRecord result = new CashEventRecord();
            result.eventNo = cursor.getString(0);
            result.boardSequence = cursor.getInt(1);
            result.status = cursor.getString(2);
            result.payload = cursor.getString(3);
            return result;
        }
    }

    public synchronized List<OutboxItem> listCashEvents() {
        return listOutbox("cash_event");
    }

    public synchronized void updateCashEventStatus(String eventNo, String status) {
        ContentValues values = new ContentValues();
        values.put("status", safe(status));
        getWritableDatabase().update(
                "cash_events", values, "event_no=?", new String[]{safe(eventNo)});
    }

    public synchronized void removeCashOutbox(String eventNo) {
        getWritableDatabase().delete(
                "outbox", "receipt_key=?", new String[]{"cash|" + safe(eventNo)});
    }

    public synchronized boolean saveCashConfiguration(
            int configVersion,
            boolean enabled,
            boolean changeEnabled,
            String snapshotJson
    ) {
        if (configVersion <= 0 || blank(snapshotJson)) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("config_version", configVersion);
        values.put("enabled", enabled ? 1 : 0);
        values.put("change_enabled", changeEnabled ? 1 : 0);
        values.put("snapshot_json", snapshotJson);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "cash_configuration", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L;
    }

    public synchronized int getLatestCashConfigVersion() {
        return Math.max(
                getCashConfigVersion(),
                Math.max(
                        parsePositiveInt(getMeta(META_LATEST_CONFIG_VERSION)),
                        parsePositiveInt(getMeta(META_PENDING_CONFIG_VERSION))
                )
        );
    }

    public synchronized boolean savePendingCashConfiguration(
            JSONObject envelope,
            int configVersion,
            boolean enabled,
            boolean changeEnabled,
            String snapshotJson,
            String ackEventNo,
            String ackResultStatus,
            String ackPayload,
            String interruptedEventNo,
            String interruptedResultStatus,
            String interruptedPayload
    ) {
        if (envelope == null || configVersion <= 0 || blank(snapshotJson)
                || blank(ackEventNo) || blank(ackResultStatus) || blank(ackPayload)
                || blank(interruptedEventNo)
                || blank(interruptedResultStatus)
                || blank(interruptedPayload)) {
            return false;
        }
        String messageId = envelope.optString("messageId", "").trim();
        if (messageId.isEmpty()) {
            return false;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int latestVersion = Math.max(
                    getCashConfigVersion(db),
                    Math.max(
                            parsePositiveInt(
                                    getMeta(db, META_LATEST_CONFIG_VERSION)
                            ),
                            parsePositiveInt(
                                    getMeta(db, META_PENDING_CONFIG_VERSION)
                            )
                    )
            );
            if (configVersion <= latestVersion) {
                return false;
            }
            if (!saveCommandEnvelope(db, envelope, "pending")) {
                return false;
            }

            putMeta(db, META_PENDING_CONFIG, messageId);
            putMeta(db, META_LATEST_CONFIG_VERSION, String.valueOf(configVersion));
            putMeta(db, META_PENDING_CONFIG_VERSION, String.valueOf(configVersion));
            putMeta(db, META_PENDING_CONFIG_ENABLED, enabled ? "1" : "0");
            putMeta(db, META_PENDING_CONFIG_CHANGE, changeEnabled ? "1" : "0");
            putMeta(db, META_PENDING_CONFIG_SNAPSHOT, snapshotJson);
            putMeta(db, META_PENDING_CONFIG_FAILURE_EVENT_NO, interruptedEventNo);
            putMeta(
                    db,
                    META_PENDING_CONFIG_FAILURE_STATUS,
                    interruptedResultStatus
            );
            putMeta(
                    db,
                    META_PENDING_CONFIG_FAILURE_PAYLOAD,
                    interruptedPayload
            );

            String receiptKey = messageId + "|" + ackEventNo + "|"
                    + ackResultStatus;
            if (!saveOutbox(
                    db,
                    receiptKey,
                    "command_result",
                    messageId,
                    ackEventNo,
                    ackResultStatus,
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

    public synchronized boolean commitPendingCashConfigurationAndResult(
            String messageId,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        if (blank(messageId) || blank(eventNo) || blank(payload)
                || !"success".equals(resultStatus)) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!messageId.equals(getMeta(db, META_PENDING_CONFIG))) {
                return false;
            }
            int version = parsePositiveInt(
                    getMeta(db, META_PENDING_CONFIG_VERSION));
            String snapshot = getMeta(db, META_PENDING_CONFIG_SNAPSHOT);
            if (version <= 0 || blank(snapshot)) {
                return false;
            }

            ContentValues values = new ContentValues();
            values.put("id", 1);
            values.put("config_version", version);
            values.put(
                    "enabled",
                    "1".equals(getMeta(db, META_PENDING_CONFIG_ENABLED)) ? 1 : 0
            );
            values.put(
                    "change_enabled",
                    "1".equals(getMeta(db, META_PENDING_CONFIG_CHANGE)) ? 1 : 0
            );
            values.put("snapshot_json", snapshot);
            values.put("updated_at", System.currentTimeMillis());
            if (db.insertWithOnConflict(
                    "cash_configuration",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            ) == -1L) {
                return false;
            }
            if (!updateCommandState(db, messageId, "applied")) {
                return false;
            }
            String receiptKey = messageId + "|" + eventNo + "|"
                    + resultStatus;
            if (!saveOutbox(
                    db,
                    receiptKey,
                    "command_result",
                    messageId,
                    eventNo,
                    resultStatus,
                    payload
            )) {
                return false;
            }

            putMeta(db, META_CASH_BLOCKED, "0");
            clearPendingCashConfiguration(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean failCashConfigurationAndResult(
            JSONObject envelope,
            String sourceMessageId,
            String eventNo,
            String resultStatus,
            String payload,
            boolean clearPending
    ) {
        if (envelope == null || blank(sourceMessageId) || blank(eventNo)
                || blank(payload) || !"failed".equals(resultStatus)
                || !sourceMessageId.equals(
                        envelope.optString("messageId", "").trim())) {
            return false;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!saveCommandEnvelope(db, envelope, "failed")) {
                return false;
            }
            putMeta(db, META_CASH_BLOCKED, "1");
            String receiptKey = sourceMessageId + "|" + eventNo + "|"
                    + resultStatus;
            if (!saveOutbox(
                    db,
                    receiptKey,
                    "command_result",
                    sourceMessageId,
                    eventNo,
                    resultStatus,
                    payload
            )) {
                return false;
            }
            if (clearPending
                    && sourceMessageId.equals(
                            getMeta(db, META_PENDING_CONFIG))) {
                clearPendingCashConfiguration(db);
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized OutboxItem failInterruptedCashConfiguration() {
        SQLiteDatabase db = getWritableDatabase();
        OutboxItem result = null;
        db.beginTransaction();
        try {
            String messageId = getMeta(db, META_PENDING_CONFIG);
            String eventNo = getMeta(
                    db,
                    META_PENDING_CONFIG_FAILURE_EVENT_NO
            );
            String resultStatus = getMeta(
                    db,
                    META_PENDING_CONFIG_FAILURE_STATUS
            );
            String payload = getMeta(
                    db,
                    META_PENDING_CONFIG_FAILURE_PAYLOAD
            );
            if (blank(messageId) || blank(eventNo) || blank(payload)
                    || !"failed".equals(resultStatus)) {
                return null;
            }
            if (!updateCommandState(db, messageId, "failed")) {
                return null;
            }
            putMeta(db, META_CASH_BLOCKED, "1");
            String receiptKey = messageId + "|" + eventNo + "|"
                    + resultStatus;
            if (!saveOutbox(
                    db,
                    receiptKey,
                    "command_result",
                    messageId,
                    eventNo,
                    resultStatus,
                    payload
            )) {
                return null;
            }

            clearPendingCashConfiguration(db);
            result = new OutboxItem();
            result.receiptKey = receiptKey;
            result.kind = "command_result";
            result.sourceMessageId = messageId;
            result.eventNo = eventNo;
            result.resultStatus = resultStatus;
            result.payload = payload;
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    public synchronized void clearPendingCashConfiguration(String messageId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String pendingMessageId = getMeta(db, META_PENDING_CONFIG);
            if (blank(messageId) || messageId.equals(pendingMessageId)) {
                clearPendingCashConfiguration(db);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized CashConfigurationRecord loadCashConfiguration() {
        try (Cursor cursor = getReadableDatabase().query(
                "cash_configuration",
                new String[]{"config_version", "enabled", "change_enabled", "snapshot_json"},
                "id=1",
                null,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            CashConfigurationRecord result = new CashConfigurationRecord();
            result.configVersion = cursor.getInt(0);
            result.enabled = cursor.getInt(1) != 0;
            result.changeEnabled = cursor.getInt(2) != 0;
            result.snapshotJson = cursor.getString(3);
            return result;
        }
    }

    public synchronized CashTier findCashTier(String mediumType, int amountFen) {
        CashConfigurationRecord config = loadCashConfiguration();
        if (config == null || !config.enabled || config.changeEnabled) {
            return null;
        }
        JSONObject snapshot = parseObject(config.snapshotJson);
        JSONObject data = snapshot == null ? null : snapshot.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("cashSaleItems");
        if (items == null) {
            return null;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            if (safe(mediumType).equals(item.optString("cashMediumType", ""))
                    && amountFen == item.optInt("denominationAmount", -1)) {
                CashTier tier = new CashTier();
                tier.cashMediumType = mediumType;
                tier.denominationAmount = amountFen;
                tier.marbleQuantity = item.optInt("marbleQuantity", 0);
                tier.cashSaleTierNo = item.optString("cashSaleTierNo", "");
                tier.configVersion = config.configVersion;
                return tier;
            }
        }
        return null;
    }

    public synchronized int getCashConfigVersion() {
        CashConfigurationRecord record = loadCashConfiguration();
        return record == null ? 0 : record.configVersion;
    }

    public synchronized boolean isCashEnabled() {
        CashConfigurationRecord record = loadCashConfiguration();
        return record != null
                && record.enabled
                && !record.changeEnabled
                && !isCashBlocked()
                && !isPhysicalBlocked()
                && !hasActivePhysicalOrder();
    }

    public synchronized void setCashBlocked(boolean blocked) {
        putMeta(META_CASH_BLOCKED, blocked ? "1" : "0");
    }

    public synchronized boolean isCashBlocked() {
        return "1".equals(getMeta(META_CASH_BLOCKED));
    }

    public synchronized boolean isPhysicalBlocked() {
        ActivePhysicalOrder active = loadActivePhysicalOrder();
        return "1".equals(getMeta(META_PHYSICAL_BLOCKED))
                || (active != null && "BLOCKED".equals(active.state));
    }

    public synchronized void setPendingConfigMessageId(String messageId) {
        putMeta(META_PENDING_CONFIG, safe(messageId));
    }

    public synchronized String getPendingConfigMessageId() {
        return getMeta(META_PENDING_CONFIG);
    }

    public synchronized void clearPendingConfigMessageId() {
        clearPendingCashConfiguration((String) null);
    }

    public synchronized void saveBoardVersion(long value) {
        putMeta(META_BOARD_VERSION, Long.toUnsignedString(value));
    }

    public synchronized long getBoardVersion() {
        try {
            return Long.parseUnsignedLong(getMeta(META_BOARD_VERSION));
        } catch (Throwable error) {
            return 0L;
        }
    }

    private static void createActivePhysicalOrderTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS active_physical_order ("
                + "id INTEGER PRIMARY KEY CHECK(id = 1),"
                + "message_id TEXT NOT NULL UNIQUE,"
                + "source_topic TEXT NOT NULL DEFAULT '',"
                + "order_sequence INTEGER NOT NULL,"
                + "requested_quantity INTEGER NOT NULL,"
                + "state TEXT NOT NULL,"
                + "last_progress_actual INTEGER NOT NULL DEFAULT 0,"
                + "terminal_frame_id INTEGER,"
                + "terminal_actual INTEGER,"
                + "controller_terminal_actual INTEGER,"
                + "terminal_result_code INTEGER,"
                + "terminal_received_at INTEGER,"
                + "terminal_event_no TEXT,"
                + "terminal_payload TEXT,"
                + "terminal_result_status TEXT,"
                + "terminal_ack_sent INTEGER NOT NULL DEFAULT 0,"
                + "terminal_ack_echoed INTEGER NOT NULL DEFAULT 0,"
                + "blocked_reason TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
    }

    private static void addColumnIfMissing(
            SQLiteDatabase db,
            String table,
            String column,
            String definition
    ) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(1))) {
                    return;
                }
            }
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static boolean hasActivePhysicalOrder(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "active_physical_order",
                new String[]{"id"},
                "id=1",
                null,
                null,
                null,
                null)) {
            return cursor.moveToFirst();
        }
    }

    private static ActivePhysicalOrder loadActivePhysicalOrder(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "active_physical_order",
                new String[]{
                        "message_id",
                        "source_topic",
                        "order_sequence",
                        "requested_quantity",
                        "state",
                        "last_progress_actual",
                        "terminal_frame_id",
                        "terminal_actual",
                        "controller_terminal_actual",
                        "terminal_result_code",
                        "terminal_received_at",
                        "terminal_event_no",
                        "terminal_payload",
                        "terminal_result_status",
                        "terminal_ack_sent",
                        "terminal_ack_echoed",
                        "blocked_reason"
                },
                "id=1",
                null,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ActivePhysicalOrder result = new ActivePhysicalOrder();
            result.messageId = cursor.getString(0);
            result.sourceTopic = cursor.getString(1);
            result.orderSequence = cursor.getInt(2);
            result.requestedQuantity = cursor.getInt(3);
            result.state = cursor.getString(4);
            result.lastProgressActual = cursor.getInt(5);
            result.terminalFrameId = cursor.isNull(6) ? -1 : cursor.getInt(6);
            result.terminalActual = cursor.isNull(7) ? -1 : cursor.getInt(7);
            result.controllerTerminalActual = cursor.isNull(8) ? -1 : cursor.getInt(8);
            result.terminalResultCode = cursor.isNull(9) ? -1 : cursor.getInt(9);
            result.terminalReceivedAt = cursor.isNull(10) ? 0L : cursor.getLong(10);
            result.terminalEventNo = cursor.getString(11);
            result.terminalPayload = cursor.getString(12);
            result.terminalResultStatus = cursor.getString(13);
            result.terminalAckSent = cursor.getInt(14) != 0;
            result.terminalAckEchoed = cursor.getInt(15) != 0;
            result.blockedReason = cursor.getString(16);
            return result;
        }
    }

    private boolean saveOutbox(
            String receiptKey,
            String kind,
            String sourceMessageId,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        return saveOutbox(
                getWritableDatabase(),
                receiptKey,
                kind,
                sourceMessageId,
                eventNo,
                resultStatus,
                payload
        );
    }

    private static boolean saveOutbox(
            SQLiteDatabase db,
            String receiptKey,
            String kind,
            String sourceMessageId,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        ContentValues values = new ContentValues();
        values.put("receipt_key", receiptKey);
        values.put("kind", kind);
        values.put("source_message_id", sourceMessageId);
        values.put("event_no", eventNo);
        values.put("result_status", resultStatus);
        values.put("payload", payload);
        values.put("created_at", System.currentTimeMillis());
        return db.insertWithOnConflict(
                "outbox",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L || hasOutbox(db, receiptKey, payload);
    }

    private boolean hasOutbox(String receiptKey) {
        return hasOutbox(getReadableDatabase(), receiptKey, null);
    }

    private static boolean hasOutbox(
            SQLiteDatabase db,
            String receiptKey,
            String expectedPayload
    ) {
        try (Cursor cursor = db.query(
                "outbox",
                new String[]{"payload"},
                "receipt_key=?",
                new String[]{receiptKey},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            return expectedPayload == null
                    || expectedPayload.equals(cursor.getString(0));
        }
    }

    private List<OutboxItem> listOutbox(String kind) {
        List<OutboxItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "outbox",
                new String[]{"id", "receipt_key", "kind", "source_message_id",
                        "event_no", "result_status", "payload"},
                "kind=?",
                new String[]{kind},
                null,
                null,
                "id ASC")) {
            while (cursor.moveToNext()) {
                OutboxItem item = new OutboxItem();
                item.id = cursor.getLong(0);
                item.receiptKey = cursor.getString(1);
                item.kind = cursor.getString(2);
                item.sourceMessageId = cursor.getString(3);
                item.eventNo = cursor.getString(4);
                item.resultStatus = cursor.getString(5);
                item.payload = cursor.getString(6);
                result.add(item);
            }
        }
        return result;
    }

    private void putMeta(String key, String value) {
        putMeta(getWritableDatabase(), key, value);
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", safe(value));
        db.insertWithOnConflict(
                "meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String getMeta(String key) {
        return getMeta(getReadableDatabase(), key);
    }

    private static String getMeta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                "meta", new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? safe(cursor.getString(0)) : "";
        }
    }

    private void deleteMeta(String key) {
        deleteMeta(getWritableDatabase(), key);
    }

    private static void deleteMeta(SQLiteDatabase db, String key) {
        db.delete("meta", "key=?", new String[]{key});
    }

    private static boolean saveCommandEnvelope(
            SQLiteDatabase db,
            JSONObject envelope,
            String state
    ) {
        if (envelope == null || blank(state)) {
            return false;
        }
        String messageId = envelope.optString("messageId", "").trim();
        if (messageId.isEmpty()) {
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

    private static boolean updateCommandState(
            SQLiteDatabase db,
            String messageId,
            String state
    ) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return db.update(
                "commands",
                values,
                "message_id=?",
                new String[]{messageId}
        ) == 1;
    }

    private static int getCashConfigVersion(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "cash_configuration",
                new String[]{"config_version"},
                "id=1",
                null,
                null,
                null,
                null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static int parsePositiveInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(safe(value)));
        } catch (Throwable error) {
            return 0;
        }
    }

    private static void clearPendingCashConfiguration(SQLiteDatabase db) {
        deleteMeta(db, META_PENDING_CONFIG);
        deleteMeta(db, META_PENDING_CONFIG_VERSION);
        deleteMeta(db, META_PENDING_CONFIG_ENABLED);
        deleteMeta(db, META_PENDING_CONFIG_CHANGE);
        deleteMeta(db, META_PENDING_CONFIG_SNAPSHOT);
        deleteMeta(db, META_PENDING_CONFIG_FAILURE_EVENT_NO);
        deleteMeta(db, META_PENDING_CONFIG_FAILURE_STATUS);
        deleteMeta(db, META_PENDING_CONFIG_FAILURE_PAYLOAD);
    }

    private static String commandState(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        if (data == null) {
            return "received";
        }
        if (data.optBoolean("deviceTerminal", false)) {
            return "terminal";
        }
        if (data.optBoolean("deviceStartRequested", false)) {
            return "dispensing";
        }
        return "received";
    }

    private static JSONObject parseObject(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return new JSONObject(value);
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

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    public static final class CreatePhysicalOrderResult {
        public boolean success;
        public String messageId;
        public int orderSequence;
        public int requestedQuantity;
        public String resultCode;
        public String resultMessage;
    }

    public static final class ActivePhysicalOrder {
        public String messageId;
        public String sourceTopic;
        public int orderSequence;
        public int requestedQuantity;
        public String state;
        public int lastProgressActual;
        public int terminalFrameId = -1;
        public int terminalActual = -1;
        public int controllerTerminalActual = -1;
        public int terminalResultCode = -1;
        public long terminalReceivedAt;
        public String terminalEventNo;
        public String terminalPayload;
        public String terminalResultStatus;
        public boolean terminalAckSent;
        public boolean terminalAckEchoed;
        public String blockedReason;
    }

    public static final class TerminalStoreResult {
        public boolean success;
        public boolean duplicate;
        public boolean conflict;
        public boolean lateAfterUnknown;
        public String eventNo;
        public String resultStatus;
        public String payload;
        public String resultCode;
        public String resultMessage;
    }

    public static final class OutboxItem {
        public long id;
        public String receiptKey;
        public String kind;
        public String sourceMessageId;
        public String eventNo;
        public String resultStatus;
        public String payload;
    }

    public static final class CashEventRecord {
        public String eventNo;
        public int boardSequence;
        public String status;
        public String payload;
    }

    public static final class CashConfigurationRecord {
        public int configVersion;
        public boolean enabled;
        public boolean changeEnabled;
        public String snapshotJson;
    }

    public static final class CashTier {
        public String cashMediumType;
        public int denominationAmount;
        public int marbleQuantity;
        public String cashSaleTierNo;
        public int configVersion;
    }
}
