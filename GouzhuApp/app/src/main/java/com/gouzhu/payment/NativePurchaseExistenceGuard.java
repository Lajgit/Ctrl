package com.gouzhu.payment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Releases a phantom QR transaction after the platform repeatedly confirms that the order
 * does not exist.
 *
 * <p>The platform query result is authoritative for order existence. A stale local QR image or
 * WAITING_PAYMENT status must not keep the device occupied forever when the platform repeatedly
 * returns the exact "order does not exist" result. Destructive release is still limited to the
 * same QR owner and to pre-physical phases; dispensing, finishing, refunding and blocked sessions
 * are never released here.</p>
 */
public final class NativePurchaseExistenceGuard extends BroadcastReceiver {

    private static final String TAG = "GouzhuPayGuard";

    private static final String PAYMENT_PREF = "payment_state_sdk_v1";
    private static final String KEY_CURRENT_REQUEST_NO = "currentRequestNo";
    private static final String KEY_CURRENT_STATUS = "currentPurchaseStatus";
    private static final String KEY_CURRENT_SCAN_URL = "currentScanUrl";

    private static final String GUARD_PREF = "payment_order_existence_guard_v1";
    private static final String KEY_GUARD_REQUEST_NO = "requestNo";
    private static final String KEY_NOT_FOUND_COUNT = "notFoundCount";

    private static final int REQUIRED_NOT_FOUND_CONFIRMATIONS = 3;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-native-order-guard");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public void onReceive(Context receiverContext, Intent intent) {
        if (intent == null
                || !PaymentManager.ACTION_PAYMENT_EVENT.equals(intent.getAction())) {
            return;
        }
        String status = safe(intent.getStringExtra(PaymentManager.EXTRA_PURCHASE_STATUS));
        if (!("QUERY_RETRY".equals(status) || "CANCEL_UNKNOWN".equals(status))) {
            return;
        }

        Context context = receiverContext.getApplicationContext();
        PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            try {
                verifyOrderExistence(context);
            } finally {
                pendingResult.finish();
            }
        });
    }

    private static void verifyOrderExistence(Context context) {
        SharedPreferences payment = context.getSharedPreferences(
                PAYMENT_PREF,
                Context.MODE_PRIVATE
        );
        String requestNo = safe(payment.getString(KEY_CURRENT_REQUEST_NO, ""));
        String localStatus = safe(payment.getString(KEY_CURRENT_STATUS, ""));
        String scanUrl = safe(payment.getString(KEY_CURRENT_SCAN_URL, ""));

        if (requestNo.isEmpty()) {
            resetCounter(context);
            return;
        }

        TransactionOccupancyManager occupancy = TransactionOccupancyManager.get(context);
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (!isSameSafeQrSession(snapshot, requestNo)) {
            Log.d(
                    TAG,
                    "忽略空订单检查：requestNo=" + requestNo
                            + "，localStatus=" + localStatus
                            + "，hasQr=" + !scanUrl.isEmpty()
                            + "，owner=" + (snapshot == null ? "NONE" : snapshot.ownerType)
                            + "，phase=" + (snapshot == null ? "NONE" : snapshot.phase)
            );
            resetCounter(context);
            return;
        }

        try {
            DeviceSdkManager.get(context).queryNativePurchase(requestNo);
            Log.i(TAG, "扫码订单重新查询成功，清除空订单计数：requestNo=" + requestNo);
            resetCounter(context);
        } catch (Throwable error) {
            if (!isDefinitiveOrderNotFound(error)) {
                Log.d(TAG, "扫码订单查询不是明确不存在，不解除占用：requestNo=" + requestNo);
                resetCounter(context);
                return;
            }

            int count = incrementNotFoundCount(context, requestNo);
            Log.w(
                    TAG,
                    "平台确认扫码订单不存在：requestNo=" + requestNo
                            + "，confirmation=" + count
                            + "/" + REQUIRED_NOT_FOUND_CONFIRMATIONS
                            + "，localStatus=" + localStatus
                            + "，hasQr=" + !scanUrl.isEmpty()
                            + "，phase=" + snapshot.phase,
                    error
            );
            if (count < REQUIRED_NOT_FOUND_CONFIRMATIONS) {
                return;
            }

            // Re-read both local and occupancy state immediately before the destructive action.
            String latestRequestNo = safe(payment.getString(KEY_CURRENT_REQUEST_NO, ""));
            String latestStatus = safe(payment.getString(KEY_CURRENT_STATUS, ""));
            String latestScanUrl = safe(payment.getString(KEY_CURRENT_SCAN_URL, ""));
            TransactionOccupancyManager.Snapshot latest = occupancy.current();
            if (!requestNo.equals(latestRequestNo)
                    || !isSameSafeQrSession(latest, requestNo)) {
                Log.w(
                        TAG,
                        "空订单释放前状态已变化，放弃释放：requestNo=" + requestNo
                                + "，latestRequestNo=" + latestRequestNo
                                + "，latestStatus=" + latestStatus
                                + "，hasQr=" + !latestScanUrl.isEmpty()
                                + "，owner=" + (latest == null ? "NONE" : latest.ownerType)
                                + "，phase=" + (latest == null ? "NONE" : latest.phase)
                );
                resetCounter(context);
                return;
            }

            boolean released = occupancy.release(
                    latest.sessionId,
                    "platform confirmed native purchase does not exist",
                    true
            );
            if (!released) {
                Log.e(TAG, "扫码空订单占用释放失败：requestNo=" + requestNo);
                return;
            }

            Log.w(
                    TAG,
                    "扫码空订单已解除占用：requestNo=" + requestNo
                            + "，localStatus=" + latestStatus
                            + "，hadQr=" + !latestScanUrl.isEmpty()
                            + "，phase=" + latest.phase
            );
            resetCounter(context);
            Intent event = new Intent(PaymentManager.ACTION_PAYMENT_EVENT);
            event.setPackage(context.getPackageName());
            event.putExtra(PaymentManager.EXTRA_EVENT, PaymentManager.EVENT_FAILED);
            event.putExtra(
                    PaymentManager.EXTRA_MESSAGE,
                    "平台确认本次扫码订单不存在，设备已解除占用，请重新选择套餐"
            );
            event.putExtra(PaymentManager.EXTRA_ORDER_ID, requestNo);
            event.putExtra(PaymentManager.EXTRA_PURCHASE_STATUS, "ORDER_NOT_CREATED");
            context.sendBroadcast(event);
        }
    }

    private static boolean isSameSafeQrSession(
            TransactionOccupancyManager.Snapshot snapshot,
            String requestNo
    ) {
        return snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && requestNo.equals(snapshot.clientRequestNo)
                && isSafePrePhysicalPhase(snapshot.phase);
    }

    private static boolean isSafePrePhysicalPhase(String phase) {
        return TransactionOccupancyManager.PHASE_PREPARING.equals(phase)
                || TransactionOccupancyManager.PHASE_WAITING_PAYMENT.equals(phase)
                || TransactionOccupancyManager.PHASE_CANCELLING.equals(phase)
                || TransactionOccupancyManager.PHASE_CONFIRMING_CLOSE.equals(phase);
    }

    static boolean isDefinitiveOrderNotFound(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            String message = safe(current.getMessage());
            if (message.contains("设备扫码购珠订单不存在")
                    && !message.contains("归属不一致")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static int incrementNotFoundCount(Context context, String requestNo) {
        SharedPreferences guard = context.getSharedPreferences(GUARD_PREF, Context.MODE_PRIVATE);
        String storedRequestNo = safe(guard.getString(KEY_GUARD_REQUEST_NO, ""));
        int count = requestNo.equals(storedRequestNo)
                ? guard.getInt(KEY_NOT_FOUND_COUNT, 0) + 1
                : 1;
        guard.edit()
                .putString(KEY_GUARD_REQUEST_NO, requestNo)
                .putInt(KEY_NOT_FOUND_COUNT, count)
                .commit();
        return count;
    }

    private static void resetCounter(Context context) {
        context.getSharedPreferences(GUARD_PREF, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
