package com.gouzhu.transaction;

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
import com.gouzhu.mqtt.CashRuntimeCoordinator;
import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.redemption.MemberWithdrawalManager;
import com.gouzhu.redemption.ThirdPartyRedemptionManager;
import com.gouzhu.serial.SerialManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One persisted device-wide transaction occupancy lock.
 *
 * <p>QR purchase, cash purchase and member deposit keep independent business state,
 * but only one owner can reserve the device at a time. Every release and transition is
 * guarded by sessionId so a late callback cannot release a newer transaction.</p>
 */
public final class TransactionOccupancyManager {

    public static final String ACTION_CHANGED =
            AppConfig.ACTION_TRANSACTION_OCCUPANCY_CHANGED;
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_OWNER_TYPE = "ownerType";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_BLOCKED_REASON = "blockedReason";

    public static final String OWNER_QR_PURCHASE = "QR_PURCHASE";
    public static final String OWNER_CASH_PURCHASE = "CASH_PURCHASE";
    public static final String OWNER_MEMBER_DEPOSIT = "MEMBER_DEPOSIT";
    public static final String OWNER_MEMBER_WITHDRAWAL = "MEMBER_WITHDRAWAL";
    public static final String OWNER_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";
    public static final String OWNER_GENERIC_DISPENSE = "GENERIC_DISPENSE";

    public static final String PHASE_PREPARING = "PREPARING";
    public static final String PHASE_ACCEPTED = "ACCEPTED";
    public static final String PHASE_REPORTING = "REPORTING";
    public static final String PHASE_WAITING_PAYMENT = "WAITING_PAYMENT";
    public static final String PHASE_CANCELLING = "CANCELLING";
    public static final String PHASE_CONFIRMING_CLOSE = "CONFIRMING_CLOSE";
    public static final String PHASE_WAITING_DISPENSE = "WAITING_DISPENSE";
    public static final String PHASE_DISPENSE_RESERVED = "DISPENSE_RESERVED";
    public static final String PHASE_DISPENSING = "DISPENSING";
    public static final String PHASE_FINISHING = "FINISHING";
    public static final String PHASE_READY = "READY";
    public static final String PHASE_COLLECTING = "COLLECTING";
    public static final String PHASE_REFUNDING = "REFUNDING";
    public static final String PHASE_BLOCKED = "BLOCKED";

    private static final String TAG = "GouzhuTransaction";
    private static final String TABLE = "transaction_occupancy";
    private static final Object DB_LOCK = new Object();

    private static final int CMD_CASH_APPLY_V22 = 0x33;
    private static final int EVT_CASH_ACCEPTED = 0x10;
    private static final int EVT_CASH_ACCEPTANCE_STATUS = 0x11;
    private static final int EVT_BEAD_EMPTY = 0x22;
    private static final int EVT_BEAD_REFILLED = 0x23;
    private static final long CASH_DISABLE_TIMEOUT_MS = 3500L;
    private static final long MIN_CONTROLLER_PROTOCOL_VERSION = 0x02020000L;
    private static final String META_LOCAL_RESET_REQUIRED =
            "manual_operation_local_reset_required";

    private static volatile TransactionOccupancyManager instance;

    private final Context context;
    private final DeviceCommandStore store;
    private volatile CashDisableWaiter cashDisableWaiter;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (AppConfig.ACTION_BOARD_EVENT.equals(action)) {
                onBoardEvent(intent);
            } else if (AppConfig.ACTION_DISPENSE_ORDER_EVENT.equals(action)) {
                onDispenseEvent(intent);
            }
        }
    };

    private TransactionOccupancyManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
    }

    public static TransactionOccupancyManager get(Context context) {
        if (instance == null) {
            synchronized (TransactionOccupancyManager.class) {
                if (instance == null) {
                    instance = new TransactionOccupancyManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized void start() {
        ensureSchema();
        recoverPhysicalSession();
        if (receiverRegistered) {
            broadcastCurrent();
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_BOARD_EVENT);
        filter.addAction(AppConfig.ACTION_DISPENSE_ORDER_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
        broadcastCurrent();
    }

    public synchronized void stop() {
        CashDisableWaiter waiter = cashDisableWaiter;
        if (waiter != null) {
            waiter.latch.countDown();
        }
        cashDisableWaiter = null;
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiverRegistered = false;
    }

    public Snapshot current() {
        ensureSchema();
        synchronized (DB_LOCK) {
            return load(store.getReadableDatabase());
        }
    }

    public boolean isIdle() {
        return current() == null;
    }

    public AcquireResult tryAcquireQr(String clientRequestNo) {
        if (blank(clientRequestNo)) {
            return AcquireResult.denied("clientRequestNo is empty", current());
        }
        if (!canStartNewTransaction()) {
            return AcquireResult.denied(
                    "device hardware is not ready for a new transaction",
                    current()
            );
        }
        return tryAcquire(
                OWNER_QR_PURCHASE,
                clientRequestNo,
                PHASE_PREPARING,
                clientRequestNo,
                "",
                "",
                ""
        );
    }

    /** Rebuilds a persisted QR session after process restart without replacing another owner. */
    public AcquireResult recoverQr(String clientRequestNo) {
        Snapshot snapshot = current();
        if (snapshot != null) {
            if (OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                    && clientRequestNo.equals(snapshot.clientRequestNo)) {
                return AcquireResult.acquired(snapshot, false);
            }
            return AcquireResult.denied("device is occupied", snapshot);
        }
        if (blank(clientRequestNo)) {
            return AcquireResult.denied("clientRequestNo is empty", null);
        }
        return tryAcquire(
                OWNER_QR_PURCHASE,
                clientRequestNo,
                PHASE_PREPARING,
                clientRequestNo,
                "",
                "",
                ""
        );
    }

    /** 会员取珠和团购核销使用独立 owner，避免与购珠、现金和存珠会话混用。 */
    public AcquireResult tryAcquireRedemption(String ownerType, String clientRequestNo) {
        if (!isRedemptionOwner(ownerType) || blank(clientRequestNo)) {
            return AcquireResult.denied("redemption identity is invalid", current());
        }
        if (!canStartNewTransaction()) {
            return AcquireResult.denied(
                    "device hardware is not ready for a new transaction",
                    current()
            );
        }
        return tryAcquire(
                ownerType,
                clientRequestNo,
                PHASE_PREPARING,
                clientRequestNo,
                "",
                "",
                ""
        );
    }

    /** APP 重启只恢复原 requestNo，不替换其他业务 owner。 */
    public AcquireResult recoverRedemption(String ownerType, String clientRequestNo) {
        if (!isRedemptionOwner(ownerType) || blank(clientRequestNo)) {
            return AcquireResult.denied("redemption identity is invalid", current());
        }
        Snapshot snapshot = current();
        if (snapshot != null) {
            if (ownerType.equals(snapshot.ownerType)
                    && clientRequestNo.equals(snapshot.clientRequestNo)) {
                return AcquireResult.acquired(snapshot, false);
            }
            return AcquireResult.denied("device is occupied", snapshot);
        }
        return tryAcquire(
                ownerType,
                clientRequestNo,
                PHASE_PREPARING,
                clientRequestNo,
                "",
                "",
                ""
        );
    }

    /** 核销扫码前先关闭纸钞机/硬币器并等待控制板确认 mask=0。 */
    public boolean prepareRedemptionCashIsolation(String sessionId, String ownerType) {
        Snapshot snapshot = current();
        if (snapshot == null
                || !sessionId.equals(snapshot.sessionId)
                || !isRedemptionOwner(ownerType)
                || !ownerType.equals(snapshot.ownerType)
                || !(PHASE_PREPARING.equals(snapshot.phase)
                || PHASE_READY.equals(snapshot.phase))) {
            return false;
        }

        int configVersion = Math.max(1, store.getCashConfigVersion());
        CashDisableWaiter waiter = new CashDisableWaiter(configVersion);
        cashDisableWaiter = waiter;
        try {
            long packed = configVersion & 0x00FFFFFFL;
            boolean sent = SerialManager.get(context).sendCommand(
                    CMD_CASH_APPLY_V22,
                    packed,
                    true
            );
            if (!sent) {
                return false;
            }
            if (!waiter.latch.await(CASH_DISABLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    || !waiter.matched) {
                return false;
            }
            Snapshot after = current();
            if (after == null
                    || !sessionId.equals(after.sessionId)
                    || !ownerType.equals(after.ownerType)) {
                return false;
            }
            if (PHASE_READY.equals(after.phase)) {
                return true;
            }
            return transition(
                    sessionId,
                    PHASE_PREPARING,
                    PHASE_READY,
                    null,
                    null,
                    null,
                    null,
                    ""
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (cashDisableWaiter == waiter) {
                cashDisableWaiter = null;
            }
        }
    }

    public boolean transitionRedemption(String clientRequestNo, String phase) {
        Snapshot snapshot = current();
        if (snapshot == null
                || !isRedemptionOwner(snapshot.ownerType)
                || !safe(clientRequestNo).equals(snapshot.clientRequestNo)) {
            return false;
        }
        return transitionAnyPhase(snapshot.sessionId, phase, "");
    }

    public boolean isRedemptionOwned(String ownerType, String clientRequestNo) {
        Snapshot snapshot = current();
        return snapshot != null
                && ownerType.equals(snapshot.ownerType)
                && safe(clientRequestNo).equals(snapshot.clientRequestNo);
    }

    /**
     * Disables both cash devices and waits for the controller's applied mask=0 report.
     * The QR order must not be created unless this method succeeds and the session still owns the lock.
     */
    public boolean prepareQrCashIsolation(String sessionId) {
        Snapshot snapshot = current();
        if (snapshot == null
                || !sessionId.equals(snapshot.sessionId)
                || !OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                || !(PHASE_PREPARING.equals(snapshot.phase)
                || PHASE_WAITING_PAYMENT.equals(snapshot.phase))) {
            return false;
        }

        int configVersion = Math.max(1, store.getCashConfigVersion());
        CashDisableWaiter waiter = new CashDisableWaiter(configVersion);
        cashDisableWaiter = waiter;
        try {
            long packed = configVersion & 0x00FFFFFFL;
            boolean sent = SerialManager.get(context).sendCommand(
                    CMD_CASH_APPLY_V22,
                    packed,
                    true
            );
            if (!sent) {
                return false;
            }
            if (!waiter.latch.await(CASH_DISABLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    || !waiter.matched) {
                return false;
            }
            Snapshot after = current();
            if (after == null
                    || !sessionId.equals(after.sessionId)
                    || !OWNER_QR_PURCHASE.equals(after.ownerType)) {
                return false;
            }
            if (PHASE_WAITING_PAYMENT.equals(after.phase)) {
                return true;
            }
            return transition(
                    sessionId,
                    PHASE_PREPARING,
                    PHASE_WAITING_PAYMENT,
                    null,
                    null,
                    null,
                    null,
                    ""
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (cashDisableWaiter == waiter) {
                cashDisableWaiter = null;
            }
        }
    }

    public boolean markQrCancelling(String clientRequestNo) {
        if (blank(clientRequestNo)) {
            return false;
        }
        ensureSchema();
        Snapshot changed;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                Snapshot current = load(db);
                if (current == null
                        || !OWNER_QR_PURCHASE.equals(current.ownerType)
                        || !clientRequestNo.equals(current.clientRequestNo)
                        || !(PHASE_PREPARING.equals(current.phase)
                        || PHASE_WAITING_PAYMENT.equals(current.phase)
                        || PHASE_CONFIRMING_CLOSE.equals(current.phase))) {
                    return false;
                }
                changed = current.copy();
                changed.phase = PHASE_CANCELLING;
                changed.blockedReason = "";
                changed.updatedAt = System.currentTimeMillis();
                if (!update(db, changed)) {
                    return false;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        broadcast(changed);
        return true;
    }

    public void onQrPurchaseStatus(String clientRequestNo, String purchaseStatus) {
        Snapshot snapshot = current();
        if (snapshot == null
                || !OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                || !clientRequestNo.equals(snapshot.clientRequestNo)) {
            return;
        }
        String normalized = TransactionOccupancyPolicy.normalize(purchaseStatus);
        if ("CANCELED".equals(normalized) || "CLOSED".equals(normalized)) {
            if (store.hasActivePhysicalOrder()) {
                transitionAnyPhase(
                        snapshot.sessionId,
                        PHASE_BLOCKED,
                        "PAYMENT_CLOSED_WITH_ACTIVE_DISPENSE"
                );
            } else {
                release(snapshot.sessionId, "qr terminal: " + normalized, true);
            }
            return;
        }
        if ("COMPLETED".equals(normalized)) {
            if (store.hasActivePhysicalOrder()) {
                transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
            } else {
                release(snapshot.sessionId, "qr completed", true);
            }
            return;
        }
        if ("REFUNDED".equals(normalized)) {
            if (store.hasActivePhysicalOrder()) {
                transitionAnyPhase(
                        snapshot.sessionId,
                        PHASE_BLOCKED,
                        "PAYMENT_REFUNDED_WITH_ACTIVE_DISPENSE"
                );
            } else {
                release(snapshot.sessionId, "qr refunded", true);
            }
            return;
        }
        if ("REFUNDING".equals(normalized)
                && TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)) {
            // 物理出珠已经开始后再进入退款属于高风险冲突，保持占用并转人工处理。
            transitionAnyPhase(
                    snapshot.sessionId,
                    PHASE_BLOCKED,
                    "PAYMENT_REFUNDING_WITH_ACTIVE_DISPENSE"
            );
            return;
        }
        if (TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                snapshot.phase,
                normalized
        )) {
            // 忽略迟到的 HTTP 非终态，绝不把 DISPENSING/FINISHING 等物理阶段向后回退。
            return;
        }
        String next = TransactionOccupancyPolicy.paymentPhase(normalized);
        if (!"TERMINAL".equals(next)) {
            transitionAnyPhase(snapshot.sessionId, next, "");
        }
    }

    public boolean isQrOwned(String clientRequestNo) {
        Snapshot snapshot = current();
        return snapshot != null
                && OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && safe(clientRequestNo).equals(snapshot.clientRequestNo);
    }

    public void onCashEventResponse(String status) {
        Snapshot snapshot = current();
        if (snapshot == null || !OWNER_CASH_PURCHASE.equals(snapshot.ownerType)) {
            return;
        }
        String normalized = TransactionOccupancyPolicy.normalize(status);
        if ("ACCEPTED".equals(normalized) || "SUCCESS".equals(normalized)) {
            transitionAnyPhase(snapshot.sessionId, PHASE_WAITING_DISPENSE, "");
        } else if ("UNKNOWN".equals(normalized) || normalized.isEmpty()) {
            transitionAnyPhase(snapshot.sessionId, PHASE_REPORTING, "");
        } else {
            transitionAnyPhase(
                    snapshot.sessionId,
                    PHASE_BLOCKED,
                    "CASH_EVENT_" + normalized
            );
        }
    }

    public AcquireResult tryAcquireCollection(
            String sourceMessageId,
            String operationNo
    ) {
        if (blank(sourceMessageId) || blank(operationNo)) {
            return AcquireResult.denied("collection identity is invalid", current());
        }
        if (!canStartNewTransaction()) {
            return AcquireResult.denied(
                    "device hardware is not ready for a new transaction",
                    current()
            );
        }
        AcquireResult result = tryAcquire(
                OWNER_MEMBER_DEPOSIT,
                sourceMessageId,
                PHASE_READY,
                "",
                "",
                operationNo,
                sourceMessageId
        );
        if (result.success) {
            disableCashNow();
        }
        return result;
    }

    /** Restores a previously persisted collection session even while it is fault-blocked. */
    public AcquireResult recoverCollection(
            String sourceMessageId,
            String operationNo
    ) {
        if (blank(sourceMessageId) || blank(operationNo)) {
            return AcquireResult.denied("collection identity is invalid", current());
        }
        AcquireResult result = tryAcquire(
                OWNER_MEMBER_DEPOSIT,
                sourceMessageId,
                PHASE_READY,
                "",
                "",
                operationNo,
                sourceMessageId
        );
        if (result.success) {
            disableCashNow();
        }
        return result;
    }

    public boolean transitionCollection(String sessionId, String phase) {
        return transitionAnyPhase(sessionId, phase, "");
    }

    public boolean markBlocked(String reason) {
        Snapshot snapshot = current();
        return snapshot != null
                && transitionAnyPhase(snapshot.sessionId, PHASE_BLOCKED, safe(reason));
    }

    public DispenseReservation reserveDispense(
            String sourceMessageId,
            String operationNo
    ) {
        if (blank(sourceMessageId)) {
            return DispenseReservation.denied("PARAM_INVALID", "messageId is empty");
        }
        ensureSchema();
        Snapshot changed;
        Snapshot previous;
        boolean createdNew = false;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                previous = load(db);
                if (previous == null && !canStartNewTransactionLocked()) {
                    return DispenseReservation.denied(
                            "DEVICE_NOT_READY",
                            "device hardware is not ready for a new transaction"
                    );
                }
                if (previous != null
                        && !TransactionOccupancyPolicy.canReserveDispense(
                        previous.ownerType,
                        previous.phase)) {
                    return DispenseReservation.denied(
                            "DEVICE_TRANSACTION_OCCUPIED",
                            "device transaction is occupied by " + previous.ownerType
                    );
                }

                long now = System.currentTimeMillis();
                if (previous == null) {
                    changed = new Snapshot();
                    changed.sessionId = newSessionId();
                    changed.ownerType = OWNER_GENERIC_DISPENSE;
                    changed.phase = PHASE_DISPENSE_RESERVED;
                    changed.ownerReference = sourceMessageId;
                    changed.sourceMessageId = sourceMessageId;
                    changed.operationNo = safe(operationNo);
                    changed.acquiredAt = now;
                    changed.updatedAt = now;
                    if (!insert(db, changed)) {
                        return DispenseReservation.denied(
                                "LOCAL_STORAGE_ERROR",
                                "occupancy insert failed"
                        );
                    }
                    createdNew = true;
                } else {
                    changed = previous.copy();
                    changed.phase = PHASE_DISPENSE_RESERVED;
                    changed.sourceMessageId = sourceMessageId;
                    if (!blank(operationNo)) {
                        changed.operationNo = operationNo;
                    }
                    changed.updatedAt = now;
                    changed.blockedReason = "";
                    if (!update(db, changed)) {
                        return DispenseReservation.denied(
                                "LOCAL_STORAGE_ERROR",
                                "occupancy update failed"
                        );
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        broadcast(changed);
        return DispenseReservation.allowed(changed, previous, createdNew);
    }

    public boolean commitDispense(DispenseReservation reservation) {
        if (reservation == null || !reservation.allowed || reservation.current == null) {
            return false;
        }
        return transitionAnyPhase(
                reservation.current.sessionId,
                PHASE_DISPENSING,
                ""
        );
    }

    public void rollbackDispense(DispenseReservation reservation) {
        if (reservation == null || !reservation.allowed || reservation.current == null) {
            return;
        }
        if (reservation.createdNew) {
            release(reservation.current.sessionId, "dispense command rejected", false);
            return;
        }
        Snapshot previous = reservation.previous;
        if (previous == null) {
            release(reservation.current.sessionId, "dispense command rejected", false);
            return;
        }
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                Snapshot latest = load(db);
                if (latest == null
                        || !reservation.current.sessionId.equals(latest.sessionId)) {
                    return;
                }
                previous.updatedAt = System.currentTimeMillis();
                if (!update(db, previous)) {
                    return;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        broadcast(previous);
    }

    public boolean release(String sessionId, String reason, boolean restoreCash) {
        if (blank(sessionId)) {
            return false;
        }
        Snapshot released;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                released = load(db);
                if (released == null || !sessionId.equals(released.sessionId)) {
                    return false;
                }
                if (db.delete(TABLE, "id=1 AND session_id=?",
                        new String[]{sessionId}) != 1) {
                    return false;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        Log.i(TAG, "交易占用已释放：owner=" + released.ownerType
                + "，sessionId=" + sessionId + "，reason=" + safe(reason));
        broadcast(null);
        if (OWNER_QR_PURCHASE.equals(released.ownerType)) {
            PaymentManager.get(context).onOccupancyReleased(released.clientRequestNo);
        } else if (OWNER_MEMBER_WITHDRAWAL.equals(released.ownerType)) {
            MemberWithdrawalManager.get(context).onOccupancyReleased(released.clientRequestNo);
        } else if (OWNER_THIRD_PARTY_REDEMPTION.equals(released.ownerType)) {
            ThirdPartyRedemptionManager.get(context).onOccupancyReleased(released.clientRequestNo);
        }
        if (restoreCash) {
            // 现金恢复必须先刷新bootstrap并统一协调，禁止直接按本地旧配置重新开硬件。
            CashRuntimeCoordinator.get(context).onTransactionIdle();
        }
        MqttManager.get(context).reportStatus();
        return true;
    }

    /**
     * 兼容旧调用点。恢复现金不再直接发送非零掩码，而是统一交给运行协调器。
     */
    public void restoreCashAcceptanceIfSafe() {
        CashRuntimeCoordinator.get(context).onTransactionIdle();
    }

    public boolean canStartNewTransaction() {
        if (!isIdle()) {
            return false;
        }
        return canStartNewTransactionLocked();
    }

    private boolean canStartNewTransactionLocked() {
        return !store.hasActivePhysicalOrder()
                && !store.isPhysicalBlocked()
                && !store.isCashBlocked()
                && !isLocalResetRequired()
                && store.getBoardVersion() >= MIN_CONTROLLER_PROTOCOL_VERSION;
    }

    public String displayMessage(Snapshot snapshot) {
        if (snapshot == null) {
            return "设备空闲";
        }
        if (PHASE_BLOCKED.equals(snapshot.phase)) {
            return "交易异常，等待人工处理";
        }
        if (OWNER_QR_PURCHASE.equals(snapshot.ownerType)) {
            if (PHASE_CANCELLING.equals(snapshot.phase)
                    || PHASE_CONFIRMING_CLOSE.equals(snapshot.phase)) {
                return "正在确认并关闭当前付款二维码";
            }
            if (PHASE_WAITING_DISPENSE.equals(snapshot.phase)
                    || PHASE_DISPENSING.equals(snapshot.phase)
                    || PHASE_FINISHING.equals(snapshot.phase)) {
                return "扫码支付已确认，正在处理出珠";
            }
            return "扫码购珠交易进行中";
        }
        if (OWNER_CASH_PURCHASE.equals(snapshot.ownerType)) {
            return "现金已投入，正在确认并处理出珠";
        }
        if (OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
            return PHASE_COLLECTING.equals(snapshot.phase)
                    ? "正在存珠"
                    : "存珠会话已占用设备";
        }
        if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {
            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)
                    ? "会员取珠正在出珠"
                    : "会员取珠处理中";
        }
        if (OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)) {
            return TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)
                    ? "团购核销正在出珠"
                    : "团购核销处理中";
        }
        return "设备正在执行出珠任务";
    }

    private AcquireResult tryAcquire(
            String ownerType,
            String ownerReference,
            String phase,
            String clientRequestNo,
            String cashEventNo,
            String operationNo,
            String sourceMessageId
    ) {
        ensureSchema();
        Snapshot changed;
        boolean created = false;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                Snapshot current = load(db);
                if (current != null) {
                    boolean same = ownerType.equals(current.ownerType)
                            && ownerReference.equals(current.ownerReference);
                    if (same) {
                        db.setTransactionSuccessful();
                        return AcquireResult.acquired(current, false);
                    }
                    return AcquireResult.denied("device is occupied", current);
                }

                long now = System.currentTimeMillis();
                changed = new Snapshot();
                changed.sessionId = newSessionId();
                changed.ownerType = ownerType;
                changed.phase = phase;
                changed.ownerReference = ownerReference;
                changed.clientRequestNo = safe(clientRequestNo);
                changed.cashEventNo = safe(cashEventNo);
                changed.operationNo = safe(operationNo);
                changed.sourceMessageId = safe(sourceMessageId);
                changed.acquiredAt = now;
                changed.updatedAt = now;
                if (!insert(db, changed)) {
                    return AcquireResult.denied("occupancy insert failed", null);
                }
                db.setTransactionSuccessful();
                created = true;
            } finally {
                db.endTransaction();
            }
        }
        if (created) {
            Log.i(TAG, "交易占用已获得：owner=" + ownerType
                    + "，sessionId=" + changed.sessionId);
            broadcast(changed);
        }
        return AcquireResult.acquired(changed, true);
    }

    private boolean transitionAnyPhase(String sessionId, String nextPhase, String reason) {
        Snapshot snapshot = current();
        if (snapshot == null || !sessionId.equals(snapshot.sessionId)) {
            return false;
        }
        return transition(
                sessionId,
                snapshot.phase,
                nextPhase,
                null,
                null,
                null,
                null,
                reason
        );
    }

    private boolean transition(
            String sessionId,
            String expectedPhase,
            String nextPhase,
            String sourceMessageId,
            String operationNo,
            String clientRequestNo,
            String cashEventNo,
            String blockedReason
    ) {
        Snapshot changed;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                changed = load(db);
                if (changed == null
                        || !sessionId.equals(changed.sessionId)
                        || !expectedPhase.equals(changed.phase)) {
                    return false;
                }
                changed.phase = nextPhase;
                if (sourceMessageId != null) {
                    changed.sourceMessageId = sourceMessageId;
                }
                if (operationNo != null) {
                    changed.operationNo = operationNo;
                }
                if (clientRequestNo != null) {
                    changed.clientRequestNo = clientRequestNo;
                }
                if (cashEventNo != null) {
                    changed.cashEventNo = cashEventNo;
                }
                changed.blockedReason = safe(blockedReason);
                changed.updatedAt = System.currentTimeMillis();
                if (!update(db, changed)) {
                    return false;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        broadcast(changed);
        return true;
    }

    private void onBoardEvent(Intent intent) {
        int code2 = intent.getIntExtra("code2", -1);
        long packed = intent.getLongExtra("data", 0L);
        int expandCode = intent.getIntExtra("expandCode", 0) & 0xFF;
        if (code2 == EVT_CASH_ACCEPTANCE_STATUS) {
            int mask = (int) ((packed >>> 24) & 0xFF);
            int version = (int) (packed & 0x00FFFFFFL);
            CashDisableWaiter waiter = cashDisableWaiter;
            if (waiter != null
                    && mask == 0
                    && version == waiter.expectedVersion) {
                waiter.matched = true;
                waiter.latch.countDown();
            }

            // A late configuration callback or stock/refill handler must never reopen the
            // bill/coin inputs while any QR, cash, collection or physical transaction owns
            // the machine. Immediately drive the mask back to zero. This also closes the
            // narrow race with legacy code paths that do not yet consult the occupancy table.
            Snapshot occupied = current();
            if (occupied != null && mask != 0) {
                Log.e(
                        TAG,
                        "检测到交易占用期间现金入口被开启，立即关闭：owner="
                                + occupied.ownerType
                                + "，phase=" + occupied.phase
                                + "，mask=0x" + Integer.toHexString(mask)
                                + "，version=" + version
                );
                disableCashNow();
            }
            return;
        }
        if (code2 == EVT_CASH_ACCEPTED) {
            onCashAccepted(packed, expandCode);
            return;
        }
        if (code2 == EVT_BEAD_EMPTY) {
            Snapshot snapshot = current();
            if (snapshot != null) {
                transitionAnyPhase(snapshot.sessionId, PHASE_BLOCKED, "NO_MARBLES");
            }
            return;
        }
        if (code2 == EVT_BEAD_REFILLED) {
            broadcastCurrent();
        }
    }

    private void onCashAccepted(long packed, int sequenceLow) {
        int sequence = (((int) packed & 0xFF) << 8) | (sequenceLow & 0xFF);
        String reference = "cash-seq-" + Math.max(0, sequence);
        Snapshot changed;
        String displacedQr = "";
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                Snapshot current = load(db);
                long now = System.currentTimeMillis();
                if (current == null) {
                    changed = new Snapshot();
                    changed.sessionId = newSessionId();
                    changed.ownerType = OWNER_CASH_PURCHASE;
                    changed.phase = PHASE_ACCEPTED;
                    changed.ownerReference = reference;
                    changed.cashEventNo = reference;
                    changed.acquiredAt = now;
                    changed.updatedAt = now;
                    if (!insert(db, changed)) {
                        return;
                    }
                } else if (OWNER_CASH_PURCHASE.equals(current.ownerType)) {
                    if (reference.equals(current.ownerReference)) {
                        changed = current;
                    } else {
                        current.phase = PHASE_BLOCKED;
                        current.blockedReason = "MULTIPLE_CASH_ACCEPTED";
                        current.updatedAt = now;
                        if (!update(db, current)) {
                            return;
                        }
                        changed = current;
                    }
                } else if (OWNER_QR_PURCHASE.equals(current.ownerType)
                        && !TransactionOccupancyPolicy.isPhysicalPhase(current.phase)
                        && !PHASE_BLOCKED.equals(current.phase)) {
                    displacedQr = current.clientRequestNo;
                    changed = new Snapshot();
                    changed.sessionId = newSessionId();
                    changed.ownerType = OWNER_CASH_PURCHASE;
                    changed.phase = PHASE_ACCEPTED;
                    changed.ownerReference = reference;
                    changed.cashEventNo = reference;
                    changed.blockedReason = "QR_PREEMPTED_BY_ACCEPTED_CASH";
                    changed.acquiredAt = now;
                    changed.updatedAt = now;
                    if (!replace(db, changed)) {
                        return;
                    }
                } else {
                    current.phase = PHASE_BLOCKED;
                    current.blockedReason = "CASH_ACCEPTED_WHILE_"
                            + safe(current.ownerType) + "_ACTIVE";
                    current.updatedAt = now;
                    if (!update(db, current)) {
                        return;
                    }
                    changed = current;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        disableCashNow();
        broadcast(changed);
        if (!blank(displacedQr)) {
            PaymentManager.get(context).cancelDisplacedPayment(displacedQr);
        }
    }

    private void onDispenseEvent(Intent intent) {
        String eventType = intent.getStringExtra("eventType");
        if (blank(eventType)) {
            return;
        }
        Snapshot snapshot = current();
        if (snapshot == null) {
            return;
        }
        switch (eventType) {
            case "started":
            case "progress":
            case "recovering":
                transitionAnyPhase(snapshot.sessionId, PHASE_DISPENSING, "");
                break;
            case "finishing":
                transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                break;
            case "blocked":
                transitionAnyPhase(
                        snapshot.sessionId,
                        PHASE_BLOCKED,
                        safe(intent.getStringExtra("message"))
                );
                break;
            case "finished":
                if (OWNER_QR_PURCHASE.equals(snapshot.ownerType)) {
                    String purchaseStatus = PaymentManager.get(context).getCurrentPurchaseStatus();
                    if ("COMPLETED".equals(purchaseStatus)) {
                        // 统一购珠只有服务端 COMPLETED 后才允许释放并生成下一笔 clientRequestNo。
                        release(snapshot.sessionId, "qr dispense completed and server terminal", false);
                    } else if (!PHASE_BLOCKED.equals(snapshot.phase)) {
                        // 控制板完成只代表物理动作结束，继续保持订单占用等待服务端终态。
                        transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    }
                } else if (OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)) {
                    // 物理完成不等于第三方核销业务终态，继续等待 status 收敛。
                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    ThirdPartyRedemptionManager.get(context).onPhysicalDispenseFinished();
                } else if (OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)) {
                    transitionAnyPhase(snapshot.sessionId, PHASE_FINISHING, "");
                    MemberWithdrawalManager.get(context).onPhysicalDispenseFinished();
                } else if (!OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
                    release(snapshot.sessionId, "dispense completed", false);
                }
                break;
            case "idle":
                String message = safe(intent.getStringExtra("message"));
                if (PHASE_BLOCKED.equals(snapshot.phase)
                        && message.startsWith("manual operation resolved")) {
                    release(snapshot.sessionId, message, true);
                }
                break;
            default:
                break;
        }
    }

    private void recoverPhysicalSession() {
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active == null) {
            return;
        }
        Snapshot current = current();
        String operationNo = loadOperationNo(active.messageId);
        if (current == null) {
            AcquireResult result = tryAcquire(
                    OWNER_GENERIC_DISPENSE,
                    active.messageId,
                    "BLOCKED".equals(active.state) ? PHASE_BLOCKED : PHASE_DISPENSING,
                    "",
                    "",
                    operationNo,
                    active.messageId
            );
            if (!result.success) {
                Log.e(TAG, "恢复物理订单占用失败：messageId=" + active.messageId);
            }
            return;
        }
        if (!OWNER_MEMBER_DEPOSIT.equals(current.ownerType)) {
            transitionAnyPhase(
                    current.sessionId,
                    "BLOCKED".equals(active.state) ? PHASE_BLOCKED : PHASE_DISPENSING,
                    safe(active.blockedReason)
            );
        }
    }

    private String loadOperationNo(String messageId) {
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        return data == null ? "" : data.optString("operationNo", "").trim();
    }

    private void disableCashNow() {
        int version = Math.max(1, store.getCashConfigVersion());
        SerialManager.get(context).sendCommand(
                CMD_CASH_APPLY_V22,
                version & 0x00FFFFFFL,
                true
        );
    }

    private boolean isLocalResetRequired() {
        synchronized (DB_LOCK) {
            try (Cursor cursor = store.getReadableDatabase().query(
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

    private void ensureSchema() {
        synchronized (DB_LOCK) {
            store.getWritableDatabase().execSQL(
                    "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "id INTEGER PRIMARY KEY CHECK(id=1),"
                            + "session_id TEXT NOT NULL UNIQUE,"
                            + "owner_type TEXT NOT NULL,"
                            + "phase TEXT NOT NULL,"
                            + "owner_reference TEXT NOT NULL,"
                            + "client_request_no TEXT NOT NULL DEFAULT '',"
                            + "cash_event_no TEXT NOT NULL DEFAULT '',"
                            + "operation_no TEXT NOT NULL DEFAULT '',"
                            + "source_message_id TEXT NOT NULL DEFAULT '',"
                            + "blocked_reason TEXT NOT NULL DEFAULT '',"
                            + "acquired_at INTEGER NOT NULL,"
                            + "updated_at INTEGER NOT NULL)"
            );
        }
    }

    private static boolean insert(SQLiteDatabase db, Snapshot snapshot) {
        ContentValues values = values(snapshot);
        values.put("id", 1);
        return db.insert(TABLE, null, values) != -1L;
    }

    private static boolean replace(SQLiteDatabase db, Snapshot snapshot) {
        ContentValues values = values(snapshot);
        values.put("id", 1);
        return db.insertWithOnConflict(
                TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L;
    }

    private static boolean update(SQLiteDatabase db, Snapshot snapshot) {
        return db.update(
                TABLE,
                values(snapshot),
                "id=1 AND session_id=?",
                new String[]{snapshot.sessionId}
        ) == 1;
    }

    private static ContentValues values(Snapshot snapshot) {
        ContentValues values = new ContentValues();
        values.put("session_id", safe(snapshot.sessionId));
        values.put("owner_type", safe(snapshot.ownerType));
        values.put("phase", safe(snapshot.phase));
        values.put("owner_reference", safe(snapshot.ownerReference));
        values.put("client_request_no", safe(snapshot.clientRequestNo));
        values.put("cash_event_no", safe(snapshot.cashEventNo));
        values.put("operation_no", safe(snapshot.operationNo));
        values.put("source_message_id", safe(snapshot.sourceMessageId));
        values.put("blocked_reason", safe(snapshot.blockedReason));
        values.put("acquired_at", snapshot.acquiredAt);
        values.put("updated_at", snapshot.updatedAt);
        return values;
    }

    private static Snapshot load(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                TABLE,
                new String[]{
                        "session_id",
                        "owner_type",
                        "phase",
                        "owner_reference",
                        "client_request_no",
                        "cash_event_no",
                        "operation_no",
                        "source_message_id",
                        "blocked_reason",
                        "acquired_at",
                        "updated_at"
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
            Snapshot snapshot = new Snapshot();
            snapshot.sessionId = cursor.getString(0);
            snapshot.ownerType = cursor.getString(1);
            snapshot.phase = cursor.getString(2);
            snapshot.ownerReference = cursor.getString(3);
            snapshot.clientRequestNo = cursor.getString(4);
            snapshot.cashEventNo = cursor.getString(5);
            snapshot.operationNo = cursor.getString(6);
            snapshot.sourceMessageId = cursor.getString(7);
            snapshot.blockedReason = cursor.getString(8);
            snapshot.acquiredAt = cursor.getLong(9);
            snapshot.updatedAt = cursor.getLong(10);
            return snapshot;
        }
    }

    private void broadcastCurrent() {
        Snapshot snapshot = current();
        broadcast(snapshot);
    }

    private void broadcast(Snapshot snapshot) {
        Intent intent = new Intent(ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        if (snapshot == null) {
            intent.putExtra(EXTRA_SESSION_ID, "");
            intent.putExtra(EXTRA_OWNER_TYPE, "NONE");
            intent.putExtra(EXTRA_PHASE, "IDLE");
            intent.putExtra(EXTRA_BLOCKED_REASON, "");
            intent.putExtra(EXTRA_MESSAGE, "设备空闲");
        } else {
            intent.putExtra(EXTRA_SESSION_ID, snapshot.sessionId);
            intent.putExtra(EXTRA_OWNER_TYPE, snapshot.ownerType);
            intent.putExtra(EXTRA_PHASE, snapshot.phase);
            intent.putExtra(EXTRA_BLOCKED_REASON, safe(snapshot.blockedReason));
            intent.putExtra(EXTRA_MESSAGE, displayMessage(snapshot));
        }
        context.sendBroadcast(intent);
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

    private static boolean isRedemptionOwner(String ownerType) {
        return OWNER_MEMBER_WITHDRAWAL.equals(ownerType)
                || OWNER_THIRD_PARTY_REDEMPTION.equals(ownerType);
    }

    private static String newSessionId() {
        return "txn-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class CashDisableWaiter {
        final CountDownLatch latch = new CountDownLatch(1);
        final int expectedVersion;
        volatile boolean matched;

        CashDisableWaiter(int expectedVersion) {
            this.expectedVersion = expectedVersion;
        }
    }

    public static final class Snapshot {
        public String sessionId;
        public String ownerType;
        public String phase;
        public String ownerReference;
        public String clientRequestNo;
        public String cashEventNo;
        public String operationNo;
        public String sourceMessageId;
        public String blockedReason;
        public long acquiredAt;
        public long updatedAt;

        Snapshot copy() {
            Snapshot copy = new Snapshot();
            copy.sessionId = sessionId;
            copy.ownerType = ownerType;
            copy.phase = phase;
            copy.ownerReference = ownerReference;
            copy.clientRequestNo = clientRequestNo;
            copy.cashEventNo = cashEventNo;
            copy.operationNo = operationNo;
            copy.sourceMessageId = sourceMessageId;
            copy.blockedReason = blockedReason;
            copy.acquiredAt = acquiredAt;
            copy.updatedAt = updatedAt;
            return copy;
        }
    }

    public static final class AcquireResult {
        public final boolean success;
        public final boolean created;
        public final String reason;
        public final Snapshot snapshot;

        private AcquireResult(
                boolean success,
                boolean created,
                String reason,
                Snapshot snapshot
        ) {
            this.success = success;
            this.created = created;
            this.reason = reason;
            this.snapshot = snapshot;
        }

        static AcquireResult acquired(Snapshot snapshot, boolean created) {
            return new AcquireResult(true, created, "", snapshot);
        }

        static AcquireResult denied(String reason, Snapshot snapshot) {
            return new AcquireResult(false, false, safe(reason), snapshot);
        }
    }

    public static final class DispenseReservation {
        public final boolean allowed;
        public final String resultCode;
        public final String reason;
        public final Snapshot current;
        public final Snapshot previous;
        public final boolean createdNew;

        private DispenseReservation(
                boolean allowed,
                String resultCode,
                String reason,
                Snapshot current,
                Snapshot previous,
                boolean createdNew
        ) {
            this.allowed = allowed;
            this.resultCode = safe(resultCode);
            this.reason = reason;
            this.current = current;
            this.previous = previous;
            this.createdNew = createdNew;
        }

        static DispenseReservation allowed(
                Snapshot current,
                Snapshot previous,
                boolean createdNew
        ) {
            return new DispenseReservation(
                    true,
                    "",
                    "",
                    current,
                    previous,
                    createdNew
            );
        }

        static DispenseReservation denied(String resultCode, String reason) {
            return new DispenseReservation(
                    false,
                    resultCode,
                    safe(reason),
                    null,
                    null,
                    false
            );
        }
    }
}
