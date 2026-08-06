package com.gouzhu.mqtt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * 修复现金配置首次硬件应用失败后遗留的本地版本占位。
 *
 * <p>现金配置版本表示配置内容版本，不表示单次下发尝试。只有配置真正应用成功，
 * 才应永久阻止同版本再次尝试。当前端没有待处理配置、已应用版本低于本次版本，
 * 且本次版本恰好等于失败遗留的 latest 版本时，允许释放该占位。</p>
 */
final class CashConfigurationRetryState {

    private static final String TAG = "GouzhuCashRetry";

    private static final String META_LATEST_CONFIG_VERSION =
            "latest_cash_config_version";
    private static final String META_PENDING_CONFIG =
            "pending_cash_config_message";
    private static final String META_PENDING_CONFIG_VERSION =
            "pending_cash_config_version";

    private CashConfigurationRetryState() {
    }

    static void repairForIncomingVersion(Context context, int incomingVersion) {
        if (context == null || incomingVersion <= 0) {
            return;
        }

        DeviceCommandStore store = new DeviceCommandStore(
                context.getApplicationContext()
        );
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            int appliedVersion = readAppliedVersion(db);
            int latestVersion = readMetaInt(db, META_LATEST_CONFIG_VERSION);
            int pendingVersion = readMetaInt(db, META_PENDING_CONFIG_VERSION);
            String pendingMessageId = readMeta(db, META_PENDING_CONFIG);
            boolean hasPending = !pendingMessageId.trim().isEmpty()
                    && pendingVersion > 0;

            if (!shouldReleaseFailedReservation(
                    incomingVersion,
                    appliedVersion,
                    latestVersion,
                    pendingVersion,
                    hasPending
            )) {
                db.setTransactionSuccessful();
                return;
            }

            if (appliedVersion > 0) {
                ContentValues values = new ContentValues();
                values.put("key", META_LATEST_CONFIG_VERSION);
                values.put("value", String.valueOf(appliedVersion));
                db.insertWithOnConflict(
                        "meta",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                );
            } else {
                db.delete(
                        "meta",
                        "key=?",
                        new String[]{META_LATEST_CONFIG_VERSION}
                );
            }
            db.setTransactionSuccessful();

            Log.w(
                    TAG,
                    "已释放失败现金配置版本占位：incomingVersion="
                            + incomingVersion
                            + "，appliedVersion=" + appliedVersion
                            + "，staleLatestVersion=" + latestVersion
            );
        } catch (Throwable error) {
            Log.e(
                    TAG,
                    "检查现金配置失败版本占位时发生异常：incomingVersion="
                            + incomingVersion,
                    error
            );
        } finally {
            db.endTransaction();
        }
    }

    static boolean shouldReleaseFailedReservation(
            int incomingVersion,
            int appliedVersion,
            int latestVersion,
            int pendingVersion,
            boolean hasPending
    ) {
        return incomingVersion > 0
                && !hasPending
                && pendingVersion <= 0
                && latestVersion == incomingVersion
                && appliedVersion < incomingVersion;
    }

    private static int readAppliedVersion(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "cash_configuration",
                new String[]{"config_version"},
                "id=1",
                null,
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? Math.max(0, cursor.getInt(0)) : 0;
        }
    }

    private static int readMetaInt(SQLiteDatabase db, String key) {
        try {
            return Math.max(0, Integer.parseInt(readMeta(db, key)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String readMeta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                "meta",
                new String[]{"value"},
                "key=?",
                new String[]{key},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() && cursor.getString(0) != null
                    ? cursor.getString(0)
                    : "";
        }
    }
}
