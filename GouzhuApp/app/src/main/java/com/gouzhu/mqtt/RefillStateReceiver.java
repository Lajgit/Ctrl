package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.transaction.TransactionOccupancyManager;

/**
 * 统一处理控制板 K1 补珠确认以及“已补珠、等待继续出珠”状态恢复。
 *
 * <p>收到 BeadRefilled 后，在同一个 SQLite 事务中完成：</p>
 * <ol>
 *     <li>将真实部分出珠后的 SENSOR_TIMEOUT 归一化为本地无珠结果码；</li>
 *     <li>清除现金阻塞、本地复位门禁；</li>
 *     <li>仅对可继续且尚未进入继续/人工流程的库存不足会话清除 physical_blocked；</li>
 *     <li>保留 active_physical_order，同时把交易占用从 BLOCKED 切到
 *     WAITING_CONTINUATION，等待商家明确下发 continue_marble_dispense。</li>
 * </ol>
 *
 * <p>本接收器还会修复进程重建或支付状态轮询把等待继续会话改回其他阶段的情况；
 * 人工结案完成时则释放本接收器维护的等待继续占用。任何补珠事件都不会自行驱动出珠。</p>
 */
public final class RefillStateReceiver extends BroadcastReceiver {

    private static final String TAG = "GouzhuRefillState";

    private static final int EVT_BEAD_REFILLED = 0x23;
    private static final int CONTROLLER_NO_MARBLES = 2;
    private static final int CONTROLLER_SENSOR_TIMEOUT = 4;

    private static final String TABLE_OCCUPANCY = "transaction_occupancy";
    private static final String TABLE_FLOW_CLAIMS = "operation_flow_claims";
    private static final String TABLE_RESOLUTIONS = "operation_resolutions";

    private static final String PHASE_BLOCKED = "BLOCKED";
    private static final String PHASE_WAITING_DISPENSE = "WAITING_DISPENSE";
    private static final String PHASE_FINISHING = "FINISHING";
    private static final String PHASE_WAITING_CONTINUATION = "WAITING_CONTINUATION";

    private static final String META_PHYSICAL_BLOCKED = "physical_blocked";
    private static final String META_CASH_BLOCKED = "cash_blocked";
    private static final String META_LOCAL_RESET_REQUIRED =
            "manual_operation_local_reset_required";

    @Override
    public void onReceive(Context receiverContext, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Context context = receiverContext.getApplicationContext();

        if (AppConfig.ACTION_BOARD_EVENT.equals(action)) {
            if (intent.getIntExtra("code2", -1) == EVT_BEAD_REFILLED) {
                handleRefill(context, intent.getLongExtra("data", 0L));
            }
            return;
        }

        if (AppConfig.ACTION_TRANSACTION_OCCUPANCY_CHANGED.equals(action)) {
            String phase = safe(intent.getStringExtra(
                    TransactionOccupancyManager.EXTRA_PHASE));
            if (isRecoverableOccupancyPhase(phase)) {
                recoverWaitingContinuationIfReady(
                        context,
                        "occupancy-" + phase.toLowerCase()
                );
            }
            return;
        }

        if (!AppConfig.ACTION_DISPENSE_ORDER_EVENT.equals(action)) {
            return;
        }
        String eventType = safe(intent.getStringExtra("eventType"));
        if ("blocked".equals(eventType)) {
            recoverWaitingContinuationIfReady(context, "dispense-blocked");
            return;
        }
        if ("idle".equals(eventType)
                && safe(intent.getStringExtra("message")).startsWith(
                "manual operation resolved")) {
            releaseWaitingContinuationAfterResolution(context);
        }
    }

    private void handleRefill(Context context, long reportedStock) {
        if (reportedStock <= 0L) {
            Log.e(TAG, "拒绝清除补珠门禁：BeadRefilled库存无效，stock=" + reportedStock);
            return;
        }

        DeviceCommandStore store = new DeviceCommandStore(context);
        boolean normalized = false;
        boolean physicalCleared = false;
        boolean waitingContinuation = false;
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ActiveSnapshot active = loadBlockedActive(db);
                if (active != null) {
                    boolean continuable = isContinuableStockInsufficiency(
                            active.resultCode,
                            active.firstActual,
                            active.requestedQuantity
                    );
                    if (shouldNormalizeSensorTimeout(
                            active.resultCode,
                            active.firstActual,
                            active.requestedQuantity
                    )) {
                        ContentValues values = new ContentValues();
                        values.put("terminal_result_code", CONTROLLER_NO_MARBLES);
                        values.put("blocked_reason", "MARBLE_STOCK_INSUFFICIENT");
                        values.put("updated_at", System.currentTimeMillis());
                        normalized = db.update(
                                "active_physical_order",
                                values,
                                "id=1 AND state='BLOCKED' AND terminal_result_code=?",
                                new String[]{String.valueOf(CONTROLLER_SENSOR_TIMEOUT)}
                        ) == 1;
                    } else if (continuable) {
                        ContentValues values = new ContentValues();
                        values.put("blocked_reason", "MARBLE_STOCK_INSUFFICIENT");
                        values.put("updated_at", System.currentTimeMillis());
                        db.update(
                                "active_physical_order",
                                values,
                                "id=1 AND state='BLOCKED'",
                                null
                        );
                    }

                    if (continuable) {
                        OccupancySnapshot occupancy = loadOccupancy(db);
                        if (occupancy != null
                                && active.messageId.equals(occupancy.sourceMessageId)
                                && !hasOperationFlowStarted(db, occupancy.operationNo)) {
                            ContentValues values = new ContentValues();
                            values.put("phase", PHASE_WAITING_CONTINUATION);
                            values.put("blocked_reason", "");
                            values.put("updated_at", System.currentTimeMillis());
                            int updated = db.update(
                                    TABLE_OCCUPANCY,
                                    values,
                                    "id=1 AND source_message_id=? "
                                            + "AND phase IN (?,?,?,?)",
                                    new String[]{
                                            active.messageId,
                                            PHASE_BLOCKED,
                                            PHASE_WAITING_DISPENSE,
                                            PHASE_FINISHING,
                                            PHASE_WAITING_CONTINUATION
                                    }
                            );
                            waitingContinuation = updated == 1;
                        }
                        if (waitingContinuation) {
                            putMeta(db, META_PHYSICAL_BLOCKED, "0");
                            physicalCleared = true;
                        }
                    }
                } else {
                    // 人工结案已删除物理会话后，K1仍是解除残留物理门禁的安全入口。
                    putMeta(db, META_PHYSICAL_BLOCKED, "0");
                    physicalCleared = true;
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
                            + "，physicalBlockedCleared=" + physicalCleared
                            + "，waitingContinuation=" + waitingContinuation
                            + "，cashBlocked=0，localResetRequired=0"
            );
            if (waitingContinuation) {
                broadcastCurrentOccupancy(context, store);
            }
            MqttManager.get(context).reportStatus();
        } catch (Throwable error) {
            Log.e(TAG, "K1补珠状态提交失败，继续出珠门禁保持关闭", error);
        }
    }

    /**
     * 进程重建时会根据保留的 BLOCKED 物理会话重建占用；扫码订单轮询也可能把状态
     * 改为 WAITING_DISPENSE/FINISHING。如果 K1 门禁已经可靠清除，且该 operationNo
     * 尚未进入继续或人工结案流程，则统一恢复成 WAITING_CONTINUATION。
     */
    private void recoverWaitingContinuationIfReady(Context context, String source) {
        DeviceCommandStore store = new DeviceCommandStore(context);
        boolean changed = false;
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (!"0".equals(getMeta(db, META_PHYSICAL_BLOCKED))
                        || !"0".equals(getMeta(db, META_CASH_BLOCKED))
                        || !"0".equals(getMeta(db, META_LOCAL_RESET_REQUIRED))) {
                    return;
                }
                ActiveSnapshot active = loadBlockedActive(db);
                if (active == null
                        || !isContinuableStockInsufficiency(
                        active.resultCode,
                        active.firstActual,
                        active.requestedQuantity)) {
                    return;
                }
                OccupancySnapshot occupancy = loadOccupancy(db);
                if (occupancy == null
                        || !isRecoverableOccupancyPhase(occupancy.phase)
                        || !active.messageId.equals(occupancy.sourceMessageId)
                        || hasOperationFlowStarted(db, occupancy.operationNo)) {
                    return;
                }

                ContentValues activeValues = new ContentValues();
                activeValues.put("blocked_reason", "MARBLE_STOCK_INSUFFICIENT");
                activeValues.put("updated_at", System.currentTimeMillis());
                db.update(
                        "active_physical_order",
                        activeValues,
                        "id=1 AND state='BLOCKED'",
                        null
                );

                ContentValues values = new ContentValues();
                values.put("phase", PHASE_WAITING_CONTINUATION);
                values.put("blocked_reason", "");
                values.put("updated_at", System.currentTimeMillis());
                changed = db.update(
                        TABLE_OCCUPANCY,
                        values,
                        "id=1 AND source_message_id=? AND phase IN (?,?,?)",
                        new String[]{
                                active.messageId,
                                PHASE_BLOCKED,
                                PHASE_WAITING_DISPENSE,
                                PHASE_FINISHING
                        }
                ) == 1;
                if (changed) {
                    db.setTransactionSuccessful();
                }
            } finally {
                db.endTransaction();
            }

            if (!changed) {
                return;
            }
            Log.i(TAG, "已恢复补珠后的等待继续状态：source=" + source);
            broadcastCurrentOccupancy(context, store);
            MqttManager.get(context).reportStatus();
        } catch (Throwable error) {
            Log.e(TAG, "恢复等待继续状态失败：source=" + source, error);
        }
    }

    /**
     * 人工结案成功会先删除 active_physical_order，再广播 idle。等待继续状态可能被扫码
     * 订单轮询短暂改成 WAITING_DISPENSE/FINISHING，因此仅在同一 operationNo 已存在
     * 成功人工结案记录时释放这些可识别阶段，避免迟到广播误删后续新交易。
     */
    private void releaseWaitingContinuationAfterResolution(Context context) {
        DeviceCommandStore store = new DeviceCommandStore(context);
        OccupancySnapshot released = null;
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (hasActivePhysicalOrder(db)) {
                    return;
                }
                OccupancySnapshot occupancy = loadOccupancy(db);
                if (occupancy == null
                        || !isResolutionReleasePhase(occupancy.phase)
                        || !hasSuccessfulResolution(db, occupancy.operationNo)) {
                    return;
                }
                if (db.delete(
                        TABLE_OCCUPANCY,
                        "id=1 AND session_id=?",
                        new String[]{occupancy.sessionId}
                ) != 1) {
                    return;
                }
                released = occupancy;
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            if (released == null) {
                return;
            }
            Log.i(
                    TAG,
                    "人工结案后已释放等待继续占用：owner=" + released.ownerType
                            + "，operationNo=" + released.operationNo
            );
            if (TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(released.ownerType)) {
                PaymentManager.get(context).onOccupancyReleased(released.clientRequestNo);
            }
            broadcastIdleOccupancy(context);
            MqttManager.get(context).reportStatus();
        } catch (Throwable error) {
            Log.e(TAG, "人工结案后释放等待继续占用失败", error);
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

    /** NO_MARBLES 或已确认无珠的 SENSOR_TIMEOUT 均可进入补珠后等待继续状态。 */
    static boolean isContinuableStockInsufficiency(
            int resultCode,
            int actualQuantity,
            int requestedQuantity
    ) {
        return (resultCode == CONTROLLER_NO_MARBLES
                || resultCode == CONTROLLER_SENSOR_TIMEOUT)
                && actualQuantity > 0
                && requestedQuantity > actualQuantity;
    }

    static boolean isRecoverableOccupancyPhase(String phase) {
        return PHASE_BLOCKED.equals(phase)
                || PHASE_WAITING_DISPENSE.equals(phase)
                || PHASE_FINISHING.equals(phase);
    }

    private static boolean isResolutionReleasePhase(String phase) {
        return PHASE_WAITING_CONTINUATION.equals(phase)
                || PHASE_WAITING_DISPENSE.equals(phase)
                || PHASE_FINISHING.equals(phase);
    }

    private static ActiveSnapshot loadBlockedActive(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "active_physical_order",
                new String[]{
                        "message_id",
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
            if (!cursor.moveToFirst()) {
                return null;
            }
            ActiveSnapshot result = new ActiveSnapshot();
            result.messageId = cursor.getString(0);
            result.requestedQuantity = cursor.getInt(1);
            int progressActual = cursor.isNull(2) ? 0 : cursor.getInt(2);
            int terminalActual = cursor.isNull(3) ? 0 : cursor.getInt(3);
            result.firstActual = Math.max(
                    Math.max(0, progressActual),
                    Math.max(0, terminalActual)
            );
            result.resultCode = cursor.isNull(4) ? -1 : cursor.getInt(4);
            return result;
        }
    }

    private static OccupancySnapshot loadOccupancy(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                TABLE_OCCUPANCY,
                new String[]{
                        "session_id",
                        "owner_type",
                        "phase",
                        "client_request_no",
                        "operation_no",
                        "source_message_id",
                        "blocked_reason"
                },
                "id=1",
                null,
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            OccupancySnapshot result = new OccupancySnapshot();
            result.sessionId = cursor.getString(0);
            result.ownerType = cursor.getString(1);
            result.phase = cursor.getString(2);
            result.clientRequestNo = cursor.getString(3);
            result.operationNo = cursor.getString(4);
            result.sourceMessageId = cursor.getString(5);
            result.blockedReason = cursor.getString(6);
            return result;
        }
    }

    /**
     * operation_flow_claims 对继续出珠和人工结案共用同一 operationNo 互斥记录。
     * 只要任一流程已经取得过该 operationNo，就不能因再次按 K1 重新开放继续按钮。
     */
    private static boolean hasOperationFlowStarted(SQLiteDatabase db, String operationNo) {
        if (blank(operationNo)) {
            return false;
        }
        try (Cursor cursor = db.query(
                TABLE_FLOW_CLAIMS,
                new String[]{"operation_no"},
                "operation_no=?",
                new String[]{operationNo},
                null,
                null,
                null,
                "1"
        )) {
            if (cursor.moveToFirst()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 兼容继续出珠模块尚未完成建表的启动窗口，继续检查人工结案表。
        }
        try (Cursor cursor = db.query(
                TABLE_RESOLUTIONS,
                new String[]{"operation_no"},
                "operation_no=?",
                new String[]{operationNo},
                null,
                null,
                null,
                "1"
        )) {
            return cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasSuccessfulResolution(SQLiteDatabase db, String operationNo) {
        if (blank(operationNo)) {
            return false;
        }
        try (Cursor cursor = db.query(
                TABLE_RESOLUTIONS,
                new String[]{"operation_no"},
                "operation_no=? AND outcome_success=1",
                new String[]{operationNo},
                null,
                null,
                "updated_at DESC",
                "1"
        )) {
            return cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasActivePhysicalOrder(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "active_physical_order",
                new String[]{"id"},
                "id=1",
                null,
                null,
                null,
                null
        )) {
            return cursor.moveToFirst();
        }
    }

    private static String getMeta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                "meta",
                new String[]{"value"},
                "key=?",
                new String[]{key},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? safe(cursor.getString(0)) : "";
        }
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

    private static void broadcastCurrentOccupancy(
            Context context,
            DeviceCommandStore store
    ) {
        OccupancySnapshot snapshot;
        try {
            snapshot = loadOccupancy(store.getReadableDatabase());
        } catch (Throwable error) {
            Log.e(TAG, "读取等待继续占用失败", error);
            return;
        }
        if (snapshot == null) {
            broadcastIdleOccupancy(context);
            return;
        }
        Intent intent = new Intent(TransactionOccupancyManager.ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(TransactionOccupancyManager.EXTRA_SESSION_ID, snapshot.sessionId);
        intent.putExtra(TransactionOccupancyManager.EXTRA_OWNER_TYPE, snapshot.ownerType);
        intent.putExtra(TransactionOccupancyManager.EXTRA_PHASE, snapshot.phase);
        intent.putExtra(TransactionOccupancyManager.EXTRA_BLOCKED_REASON,
                safe(snapshot.blockedReason));
        intent.putExtra(
                TransactionOccupancyManager.EXTRA_MESSAGE,
                PHASE_WAITING_CONTINUATION.equals(snapshot.phase)
                        ? "已补珠，等待商家继续出珠"
                        : "设备交易状态已更新"
        );
        context.sendBroadcast(intent);
    }

    private static void broadcastIdleOccupancy(Context context) {
        Intent intent = new Intent(TransactionOccupancyManager.ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(TransactionOccupancyManager.EXTRA_SESSION_ID, "");
        intent.putExtra(TransactionOccupancyManager.EXTRA_OWNER_TYPE, "NONE");
        intent.putExtra(TransactionOccupancyManager.EXTRA_PHASE, "IDLE");
        intent.putExtra(TransactionOccupancyManager.EXTRA_BLOCKED_REASON, "");
        intent.putExtra(TransactionOccupancyManager.EXTRA_MESSAGE, "设备空闲");
        context.sendBroadcast(intent);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ActiveSnapshot {
        String messageId;
        int requestedQuantity;
        int firstActual;
        int resultCode;
    }

    private static final class OccupancySnapshot {
        String sessionId;
        String ownerType;
        String phase;
        String clientRequestNo;
        String operationNo;
        String sourceMessageId;
        String blockedReason;
    }
}
