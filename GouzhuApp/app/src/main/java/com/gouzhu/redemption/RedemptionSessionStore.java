package com.gouzhu.redemption;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 会员取珠和第三方团购核销的业务会话数据库。
 *
 * <p>第三方券原文绝不入库；只保存不可逆摘要快照、服务端候选券和恢复状态。
 * MQTT messageId、ACK、物理事实和 outbox 继续由 DeviceCommandStore 负责。</p>
 */
public final class RedemptionSessionStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "gouzhu_redemption_v1.db";
    private static final int DB_VERSION = 1;

    public RedemptionSessionStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE third_party_session ("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "client_request_no TEXT NOT NULL UNIQUE,"
                + "channel_code TEXT NOT NULL,"
                + "channel_name TEXT NOT NULL DEFAULT '',"
                + "voucher_snapshot TEXT NOT NULL DEFAULT '',"
                + "session_expire_time INTEGER NOT NULL DEFAULT 0,"
                + "candidates_json TEXT NOT NULL DEFAULT '[]',"
                + "selected_certificate_id TEXT NOT NULL DEFAULT '',"
                + "redemption_no TEXT NOT NULL DEFAULT '',"
                + "channel_status TEXT NOT NULL DEFAULT '',"
                + "fulfillment_status TEXT NOT NULL DEFAULT '',"
                + "resolution_status TEXT NOT NULL DEFAULT '',"
                + "requested_quantity INTEGER NOT NULL DEFAULT 0,"
                + "actual_quantity INTEGER NOT NULL DEFAULT -1,"
                + "ui_state TEXT NOT NULL,"
                + "confirm_requested_at INTEGER NOT NULL DEFAULT 0,"
                + "last_status_checked_at INTEGER NOT NULL DEFAULT 0,"
                + "message TEXT NOT NULL DEFAULT '',"
                + "terminal INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)"
        );
        db.execSQL("CREATE TABLE member_withdrawal_session ("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "client_request_no TEXT NOT NULL UNIQUE,"
                + "operation_no TEXT NOT NULL DEFAULT '',"
                + "withdrawal_status TEXT NOT NULL DEFAULT '',"
                + "requested_quantity INTEGER NOT NULL DEFAULT 0,"
                + "dispensed_quantity INTEGER NOT NULL DEFAULT 0,"
                + "operation_status INTEGER NOT NULL DEFAULT -1,"
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
        // 当前为首版业务会话表；后续升级必须保持会话可恢复，禁止直接 DROP。
    }

    public synchronized ThirdPartySession loadThirdParty() {
        try (Cursor cursor = getReadableDatabase().query(
                "third_party_session", null, "id=1", null, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ThirdPartySession value = new ThirdPartySession();
            value.clientRequestNo = text(cursor, "client_request_no");
            value.channelCode = text(cursor, "channel_code");
            value.channelName = text(cursor, "channel_name");
            value.voucherSnapshot = text(cursor, "voucher_snapshot");
            value.sessionExpireTime = longValue(cursor, "session_expire_time");
            value.candidatesJson = text(cursor, "candidates_json");
            value.selectedCertificateId = text(cursor, "selected_certificate_id");
            value.redemptionNo = text(cursor, "redemption_no");
            value.channelStatus = text(cursor, "channel_status");
            value.fulfillmentStatus = text(cursor, "fulfillment_status");
            value.resolutionStatus = text(cursor, "resolution_status");
            value.requestedQuantity = intValue(cursor, "requested_quantity");
            value.actualQuantity = intValue(cursor, "actual_quantity");
            value.uiState = text(cursor, "ui_state");
            value.confirmRequestedAt = longValue(cursor, "confirm_requested_at");
            value.lastStatusCheckedAt = longValue(cursor, "last_status_checked_at");
            value.message = text(cursor, "message");
            value.terminal = intValue(cursor, "terminal") != 0;
            value.createdAt = longValue(cursor, "created_at");
            value.updatedAt = longValue(cursor, "updated_at");
            return value;
        }
    }

    public synchronized boolean saveThirdParty(ThirdPartySession value) {
        if (value == null || blank(value.clientRequestNo) || blank(value.channelCode)) {
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
        values.put("channel_code", safe(value.channelCode));
        values.put("channel_name", safe(value.channelName));
        values.put("voucher_snapshot", safe(value.voucherSnapshot));
        values.put("session_expire_time", value.sessionExpireTime);
        values.put("candidates_json", blank(value.candidatesJson) ? "[]" : value.candidatesJson);
        values.put("selected_certificate_id", safe(value.selectedCertificateId));
        values.put("redemption_no", safe(value.redemptionNo));
        values.put("channel_status", safe(value.channelStatus));
        values.put("fulfillment_status", safe(value.fulfillmentStatus));
        values.put("resolution_status", safe(value.resolutionStatus));
        values.put("requested_quantity", Math.max(0, value.requestedQuantity));
        values.put("actual_quantity", value.actualQuantity);
        values.put("ui_state", safe(value.uiState));
        values.put("confirm_requested_at", value.confirmRequestedAt);
        values.put("last_status_checked_at", value.lastStatusCheckedAt);
        values.put("message", safe(value.message));
        values.put("terminal", value.terminal ? 1 : 0);
        values.put("created_at", value.createdAt);
        values.put("updated_at", value.updatedAt);
        return getWritableDatabase().insertWithOnConflict(
                "third_party_session", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L;
    }

    public synchronized void clearThirdParty() {
        getWritableDatabase().delete("third_party_session", "id=1", null);
    }

    public synchronized MemberSession loadMember() {
        try (Cursor cursor = getReadableDatabase().query(
                "member_withdrawal_session", null, "id=1", null, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            MemberSession value = new MemberSession();
            value.clientRequestNo = text(cursor, "client_request_no");
            value.operationNo = text(cursor, "operation_no");
            value.withdrawalStatus = text(cursor, "withdrawal_status");
            value.requestedQuantity = intValue(cursor, "requested_quantity");
            value.dispensedQuantity = intValue(cursor, "dispensed_quantity");
            value.operationStatus = intValue(cursor, "operation_status");
            value.uiState = text(cursor, "ui_state");
            value.message = text(cursor, "message");
            value.terminal = intValue(cursor, "terminal") != 0;
            value.submittedAt = longValue(cursor, "submitted_at");
            value.lastStatusCheckedAt = longValue(cursor, "last_status_checked_at");
            value.createdAt = longValue(cursor, "created_at");
            value.updatedAt = longValue(cursor, "updated_at");
            return value;
        }
    }

    public synchronized boolean saveMember(MemberSession value) {
        if (value == null || blank(value.clientRequestNo)) {
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
        values.put("operation_no", safe(value.operationNo));
        values.put("withdrawal_status", safe(value.withdrawalStatus));
        values.put("requested_quantity", Math.max(0, value.requestedQuantity));
        values.put("dispensed_quantity", Math.max(0, value.dispensedQuantity));
        values.put("operation_status", value.operationStatus);
        values.put("ui_state", safe(value.uiState));
        values.put("message", safe(value.message));
        values.put("terminal", value.terminal ? 1 : 0);
        values.put("submitted_at", value.submittedAt);
        values.put("last_status_checked_at", value.lastStatusCheckedAt);
        values.put("created_at", value.createdAt);
        values.put("updated_at", value.updatedAt);
        return getWritableDatabase().insertWithOnConflict(
                "member_withdrawal_session", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L;
    }

    public synchronized void clearMember() {
        getWritableDatabase().delete("member_withdrawal_session", "id=1", null);
    }

    private static String text(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static int intValue(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private static long longValue(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? 0L : cursor.getLong(index);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ThirdPartySession {
        public String clientRequestNo = "";
        public String channelCode = "";
        public String channelName = "";
        public String voucherSnapshot = "";
        public long sessionExpireTime;
        public String candidatesJson = "[]";
        public String selectedCertificateId = "";
        public String redemptionNo = "";
        public String channelStatus = "";
        public String fulfillmentStatus = "";
        public String resolutionStatus = "";
        public int requestedQuantity;
        public int actualQuantity = -1;
        public String uiState = "";
        public long confirmRequestedAt;
        public long lastStatusCheckedAt;
        public String message = "";
        public boolean terminal;
        public long createdAt;
        public long updatedAt;
    }

    public static final class MemberSession {
        public String clientRequestNo = "";
        public String operationNo = "";
        public String withdrawalStatus = "";
        public int requestedQuantity;
        public int dispensedQuantity;
        public int operationStatus = -1;
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long submittedAt;
        public long lastStatusCheckedAt;
        public long createdAt;
        public long updatedAt;
    }
}
