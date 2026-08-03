package com.gouzhu.mqtt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
    private static final int DB_VERSION = 1;

    private static final String META_ACTIVE_DISPENSE = "active_dispense";
    private static final String META_ACTIVE_COLLECT = "active_collect";
    private static final String META_BOARD_VERSION = "board_version";
    private static final String META_PENDING_CONFIG = "pending_cash_config_message";
    private static final String META_LATEST_CONFIG_VERSION = "latest_cash_config_version";
    private static final String META_PENDING_CONFIG_VERSION = "pending_cash_config_version";
    private static final String META_PENDING_CONFIG_ENABLED = "pending_cash_config_enabled";
    private static final String META_PENDING_CONFIG_CHANGE = "pending_cash_config_change";
    private static final String META_PENDING_CONFIG_SNAPSHOT = "pending_cash_config_snapshot";
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("V2数据库不支持旧结构兼容升级");
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
            String snapshotJson
    ) {
        if (envelope == null || configVersion <= 0 || blank(snapshotJson)) {
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

            ContentValues command = new ContentValues();
            command.put("message_id", messageId);
            command.put("envelope", envelope.toString());
            command.put("state", commandState(envelope));
            command.put("updated_at", System.currentTimeMillis());
            if (db.insertWithOnConflict(
                    "commands",
                    null,
                    command,
                    SQLiteDatabase.CONFLICT_REPLACE
            ) == -1L) {
                return false;
            }

            putMeta(db, META_PENDING_CONFIG, messageId);
            putMeta(db, META_LATEST_CONFIG_VERSION, String.valueOf(configVersion));
            putMeta(db, META_PENDING_CONFIG_VERSION, String.valueOf(configVersion));
            putMeta(db, META_PENDING_CONFIG_ENABLED, enabled ? "1" : "0");
            putMeta(db, META_PENDING_CONFIG_CHANGE, changeEnabled ? "1" : "0");
            putMeta(db, META_PENDING_CONFIG_SNAPSHOT, snapshotJson);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean commitPendingCashConfiguration(String messageId) {
        if (blank(messageId)) {
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

            clearPendingCashConfiguration(db);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
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
        return record != null && record.enabled && !record.changeEnabled && !isCashBlocked();
    }

    public synchronized void setCashBlocked(boolean blocked) {
        putMeta(META_CASH_BLOCKED, blocked ? "1" : "0");
    }

    public synchronized boolean isCashBlocked() {
        return "1".equals(getMeta(META_CASH_BLOCKED));
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

    public synchronized void setActiveDispense(String messageId) {
        putMeta(META_ACTIVE_DISPENSE, safe(messageId));
    }

    public synchronized String getActiveDispense() {
        return getMeta(META_ACTIVE_DISPENSE);
    }

    public synchronized void clearActiveDispense() {
        deleteMeta(META_ACTIVE_DISPENSE);
    }

    public synchronized void setActiveCollect(String messageId) {
        putMeta(META_ACTIVE_COLLECT, safe(messageId));
    }

    public synchronized String getActiveCollect() {
        return getMeta(META_ACTIVE_COLLECT);
    }

    public synchronized void clearActiveCollect() {
        deleteMeta(META_ACTIVE_COLLECT);
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

    private boolean saveOutbox(
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
        return getWritableDatabase().insertWithOnConflict(
                "outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                || hasOutbox(receiptKey);
    }

    private boolean hasOutbox(String receiptKey) {
        try (Cursor cursor = getReadableDatabase().query(
                "outbox", new String[]{"id"}, "receipt_key=?",
                new String[]{receiptKey}, null, null, null)) {
            return cursor.moveToFirst();
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
    }

    private static String commandState(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        if (data == null) {
            return "received";
        }
        if (data.optBoolean("deviceTerminal", false)) {
            return "terminal";
        }
        if (data.optBoolean("deviceStarted", false)) {
            return "started";
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
