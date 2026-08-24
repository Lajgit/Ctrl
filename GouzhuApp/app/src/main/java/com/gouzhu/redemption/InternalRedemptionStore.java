package com.gouzhu.redemption;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 官方小程序套餐券核销会话。
 *
 * <p>扫码原文属于一次性业务凭据，绝不写入数据库；这里只保存 requestNo、服务端状态和
 * 出珠数量，确保进程重建后能够查询同一个请求，而不是再次消费同一张券。</p>
 */
public final class InternalRedemptionStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "gouzhu_internal_redemption_v1.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "internal_redemption_session";

    public InternalRedemptionStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "client_request_no TEXT NOT NULL UNIQUE,"
                + "operation_id INTEGER NOT NULL DEFAULT -1,"
                + "operation_no TEXT NOT NULL DEFAULT '',"
                + "requested_quantity INTEGER NOT NULL DEFAULT 0,"
                + "dispensed_quantity INTEGER NOT NULL DEFAULT 0,"
                + "operation_status INTEGER NOT NULL DEFAULT -1,"
                + "redemption_status TEXT NOT NULL DEFAULT '',"
                + "expire_time TEXT NOT NULL DEFAULT '',"
                + "ui_state TEXT NOT NULL,"
                + "message TEXT NOT NULL DEFAULT '',"
                + "terminal INTEGER NOT NULL DEFAULT 0,"
                + "submitted_at INTEGER NOT NULL DEFAULT 0,"
                + "last_status_checked_at INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 独立首版数据库，无破坏性升级逻辑。
    }

    public synchronized Session load() {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, null, "id=1", null, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            Session value = new Session();
            value.clientRequestNo = text(cursor, "client_request_no");
            value.operationId = longValue(cursor, "operation_id", -1L);
            value.operationNo = text(cursor, "operation_no");
            value.requestedQuantity = intValue(cursor, "requested_quantity", 0);
            value.dispensedQuantity = intValue(cursor, "dispensed_quantity", 0);
            value.operationStatus = intValue(cursor, "operation_status", -1);
            value.redemptionStatus = text(cursor, "redemption_status");
            value.expireTime = text(cursor, "expire_time");
            value.uiState = text(cursor, "ui_state");
            value.message = text(cursor, "message");
            value.terminal = intValue(cursor, "terminal", 0) != 0;
            value.submittedAt = longValue(cursor, "submitted_at", 0L);
            value.lastStatusCheckedAt = longValue(cursor, "last_status_checked_at", 0L);
            value.createdAt = longValue(cursor, "created_at", 0L);
            value.updatedAt = longValue(cursor, "updated_at", 0L);
            return value;
        }
    }

    public synchronized boolean save(Session value) {
        if (value == null || blank(value.clientRequestNo) || blank(value.uiState)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (value.createdAt <= 0L) {
            value.createdAt = now;
        }
        value.updatedAt = now;

        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("client_request_no", safe(value.clientRequestNo));
        values.put("operation_id", value.operationId);
        values.put("operation_no", safe(value.operationNo));
        values.put("requested_quantity", Math.max(0, value.requestedQuantity));
        values.put("dispensed_quantity", Math.max(0, value.dispensedQuantity));
        values.put("operation_status", value.operationStatus);
        values.put("redemption_status", safe(value.redemptionStatus));
        values.put("expire_time", safe(value.expireTime));
        values.put("ui_state", safe(value.uiState));
        values.put("message", safe(value.message));
        values.put("terminal", value.terminal ? 1 : 0);
        values.put("submitted_at", value.submittedAt);
        values.put("last_status_checked_at", value.lastStatusCheckedAt);
        values.put("created_at", value.createdAt);
        values.put("updated_at", value.updatedAt);
        return getWritableDatabase().insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L;
    }

    public synchronized void clear() {
        getWritableDatabase().delete(TABLE, "id=1", null);
    }

    private static String text(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static int intValue(Cursor cursor, String name, int fallback) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static long longValue(Cursor cursor, String name, long fallback) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Session {
        public String clientRequestNo = "";
        public long operationId = -1L;
        public String operationNo = "";
        public int requestedQuantity;
        public int dispensedQuantity;
        public int operationStatus = -1;
        public String redemptionStatus = "";
        public String expireTime = "";
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long submittedAt;
        public long lastStatusCheckedAt;
        public long createdAt;
        public long updatedAt;
    }
}
