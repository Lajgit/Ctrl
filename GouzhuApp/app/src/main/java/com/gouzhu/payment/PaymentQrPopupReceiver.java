package com.gouzhu.payment;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.MainActivity;
import com.gouzhu.transaction.TransactionOccupancyManager;

/**
 * 根据统一购珠状态打开/关闭支付窗口。
 * 60 秒只表示“尚未选定任何支付方式”的顾客操作窗口，截止时间按 requestNo 持久化。
 */
public final class PaymentQrPopupReceiver extends BroadcastReceiver {

    static final String POPUP_PREF = "payment_qr_popup_v2";
    static final String KEY_REQUEST_NO = "requestNo";
    static final String KEY_DEADLINE = "deadline";
    static final long PAYMENT_SELECTION_TIMEOUT_MS = 60_000L;

    private static final String TAG = "GouzhuPayPopup";

    @Override
    public void onReceive(Context receiverContext, Intent intent) {
        if (intent == null
                || !PaymentManager.ACTION_PAYMENT_EVENT.equals(intent.getAction())) {
            return;
        }

        Context context = receiverContext.getApplicationContext();
        String event = safe(intent.getStringExtra(PaymentManager.EXTRA_EVENT));
        String requestNo = safe(intent.getStringExtra(PaymentManager.EXTRA_ORDER_ID));

        if (PaymentManager.EVENT_PAYMENT_READY.equals(event)) {
            if (requestNo.isEmpty()) {
                Log.w(TAG, "忽略无效统一支付窗口事件：requestNo为空");
                return;
            }

            TransactionOccupancyManager.Snapshot snapshot =
                    TransactionOccupancyManager.get(context).current();
            if (!isDisplayablePaymentSession(snapshot, requestNo)) {
                Log.i(
                        TAG,
                        "忽略非待支付阶段统一支付窗口：requestNo=" + requestNo
                                + "，owner=" + (snapshot == null ? "NONE" : snapshot.ownerType)
                                + "，phase=" + (snapshot == null ? "NONE" : snapshot.phase)
                );
                return;
            }

            /*
             * DeviceService 会在启动页甚至只有后台服务存活时恢复原支付订单。
             * 此时静态 Receiver 不能用 NEW_TASK 单独拉起支付 Activity，否则支付窗口
             * 结束后任务栈下面没有 MainActivity，界面会直接回到系统桌面。
             * 主界面 onResume 本身会再次恢复同一 requestNo，届时再正常打开支付窗口。
             */
            if (!isMainActivityForeground(context)) {
                Log.i(
                        TAG,
                        "主界面尚未处于前台，暂不打开统一支付窗口：requestNo=" + requestNo
                );
                return;
            }

            long deadline = ensureDeadline(context, requestNo);
            SharedPreferences payment = context.getSharedPreferences(
                    "payment_state_sdk_v1",
                    Context.MODE_PRIVATE
            );
            int beadCount = Math.max(0, payment.getInt("currentBeadCount", 0));
            int priceFen = Math.max(0, payment.getInt("currentPriceFen", 0));
            String qrContent = PaymentManager.get(context).getCurrentScanUrl();

            Intent popup = new Intent(context, PaymentQrActivity.class);
            popup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            popup.putExtra(PaymentQrActivity.EXTRA_REQUEST_NO, requestNo);
            popup.putExtra(PaymentQrActivity.EXTRA_QR_CONTENT, qrContent);
            popup.putExtra(PaymentQrActivity.EXTRA_BEAD_COUNT, beadCount);
            popup.putExtra(PaymentQrActivity.EXTRA_PRICE_FEN, priceFen);
            popup.putExtra(PaymentQrActivity.EXTRA_DEADLINE, deadline);
            try {
                context.startActivity(popup);
            } catch (Throwable error) {
                Log.e(TAG, "打开统一支付窗口失败：requestNo=" + requestNo, error);
            }
            return;
        }

        if (PaymentManager.EVENT_CANCELLING.equals(event)
                || PaymentManager.EVENT_CLOSED.equals(event)
                || PaymentManager.EVENT_SUCCESS.equals(event)
                || PaymentManager.EVENT_FAILED.equals(event)) {
            clearDeadline(context, requestNo);
            Intent close = new Intent(PaymentQrActivity.ACTION_CLOSE);
            close.setPackage(context.getPackageName());
            close.putExtra(PaymentQrActivity.EXTRA_REQUEST_NO, requestNo);
            context.sendBroadcast(close);
        }
    }

    static long ensureDeadline(Context context, String requestNo) {
        SharedPreferences preferences = context.getSharedPreferences(
                POPUP_PREF,
                Context.MODE_PRIVATE
        );
        String storedRequestNo = safe(preferences.getString(KEY_REQUEST_NO, ""));
        long storedDeadline = preferences.getLong(KEY_DEADLINE, 0L);
        if (requestNo.equals(storedRequestNo) && storedDeadline > 0L) {
            return storedDeadline;
        }

        long deadline = System.currentTimeMillis() + PAYMENT_SELECTION_TIMEOUT_MS;
        preferences.edit()
                .putString(KEY_REQUEST_NO, requestNo)
                .putLong(KEY_DEADLINE, deadline)
                .commit();
        return deadline;
    }

    static void clearDeadline(Context context, String requestNo) {
        SharedPreferences preferences = context.getSharedPreferences(
                POPUP_PREF,
                Context.MODE_PRIVATE
        );
        String storedRequestNo = safe(preferences.getString(KEY_REQUEST_NO, ""));
        if (!requestNo.isEmpty() && !requestNo.equals(storedRequestNo)) {
            return;
        }
        preferences.edit().clear().apply();
    }

    private static boolean isDisplayablePaymentSession(
            TransactionOccupancyManager.Snapshot snapshot,
            String requestNo
    ) {
        return snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && requestNo.equals(snapshot.clientRequestNo)
                && (TransactionOccupancyManager.PHASE_PREPARING.equals(snapshot.phase)
                || TransactionOccupancyManager.PHASE_WAITING_PAYMENT.equals(snapshot.phase)
                || TransactionOccupancyManager.PHASE_CANCELLING.equals(snapshot.phase)
                || TransactionOccupancyManager.PHASE_CONFIRMING_CLOSE.equals(snapshot.phase));
    }

    private static boolean isMainActivityForeground(Context context) {
        ActivityManager.RunningAppProcessInfo processInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(processInfo);
        if (processInfo.importance
                != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return false;
        }

        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }
        try {
            for (ActivityManager.AppTask appTask : activityManager.getAppTasks()) {
                ActivityManager.RecentTaskInfo taskInfo = appTask.getTaskInfo();
                ComponentName topActivity = taskInfo == null ? null : taskInfo.topActivity;
                if (topActivity != null
                        && context.getPackageName().equals(topActivity.getPackageName())
                        && MainActivity.class.getName().equals(topActivity.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "判断主界面前台状态失败，暂不打开支付窗口", error);
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
