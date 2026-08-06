package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.gouzhu.AppConfig;

/**
 * 统一处理控制板 K1 补珠确认。
 *
 * <p>收到 BeadRefilled 后，在同一个 SQLite 事务中完成三件事：</p>
 * <ol>
 *     <li>将旧版 APP 保存的“部分出珠 SENSOR_TIMEOUT”归一化为本地无珠结果码；</li>
 *     <li>清除库存阻塞门禁；</li>
 *     <li>清除人工补珠/复位门禁。</li>
 * </ol>
 *
 * <p>这样继续出珠校验不会再读到两个接收器分别更新造成的中间状态。补珠事件只
 * 解除本地门禁，不会自行启动继续出珠；仍必须等待商家下发 continue_marble_dispense。</p>
 */
public final class RefillStateReceiver extends BroadcastReceiver {

    private static final String TAG = "GouzhuRefillState";

    private static final int EVT_BEAD_REFILLED = 0x23;
    private static final int CONTROLLER_NO_MARBLES = 2;
    private static final int CONTROLLER_SENSOR_TIMEOUT = 4;

    private static final String META_CASH_BLOCKED = "cash_blocked";
    private static final String META_LOCAL_RESET_REQUIRED =
            "manual_operation_local_reset_required";

    @Override
    public void onReceive(Context receiverContext, Intent intent) {
        if (intent == null
                || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())
                || intent.getIntExtra("code2", -1) != EVT_BEAD_REFILLED) {
            return;
        }

        long reportedStock = intent.getLongExtra("data", 0L);
        if (reportedStock <= 0L) {
            Log.e(TAG, "拒绝清除补珠门禁：BeadRefilled库存无效，stock=" + reportedStock);
            return;
        }

        Context context = receiverContext.getApplicationContext();
        DeviceCommandStore store = new DeviceCommandStore(context);
        boolean normalized = false;
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                try (Cursor cursor = db.query(
                        "active_physical_order",
                        new String[]{
                                "requested_quantity",
                                "last_progress_actual",
                                "terminal_actual",
                                "terminal_result_code"
                        },
                        "id=1 AND state='BLOCKED'",
                        null,
                        null,
                        null,
                        null
                )) {
                    if (cursor.moveToFirst()) {
                        int requested = cursor.getInt(0);
                        int progressActual = cursor.isNull(1) ? 0 : cursor.getInt(1);
                        int terminalActual = cursor.isNull(2) ? 0 : cursor.getInt(2);
                        int resultCode = cursor.isNull(3) ? -1 : cursor.getInt(3);
                        int firstActual = Math.max(
                                Math.max(0, progressActual),
                                Math.max(0, terminalActual)
                        );
                        if (shouldNormalizeSensorTimeout(
                                resultCode,
                                firstActual,
                                requested
                        )) {
                            ContentValues values = new ContentValues();
                            values.put("terminal_result_code", CONTROLLER_NO_MARBLES);
                            values.put("updated_at", System.currentTimeMillis());
                            normalized = db.update(
                                    "active_physical_order",
                                    values,
                                    "id=1 AND state='BLOCKED' AND terminal_result_code=?",
                                    new String[]{String.valueOf(CONTROLLER_SENSOR_TIMEOUT)}
                            ) == 1;
                        }
                    }
                }

                putMeta(db, META_CASH_BLOCKED, "0");
                putMeta(db, META_LOCAL_RESET_REQUIRED, "0");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            Log.i(
                    TAG,
                    "K1补珠状态已原子提交：stock=" + reportedStock
                            + "，sensorTimeoutNormalized=" + normalized
                            + "，cashBlocked=0，localResetRequired=0"
            );
            MqttManager.get(context).reportStatus();
        } catch (Throwable error) {
            Log.e(TAG, "K1补珠状态提交失败，继续出珠门禁保持关闭", error);
        }
    }

    /** 仅将真实部分出珠后的控制板超时归一化为本地库存不足。 */
    static boolean shouldNormalizeSensorTimeout(
            int resultCode,
            int actualQuantity,
            int requestedQuantity
    ) {
        return resultCode == CONTROLLER_SENSOR_TIMEOUT
                && actualQuantity > 0
                && requestedQuantity > actualQuantity;
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        db.insertWithOnConflict(
                "meta",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }
}
