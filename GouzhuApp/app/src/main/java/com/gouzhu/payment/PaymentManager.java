package com.gouzhu.payment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.transaction.TransactionOccupancyPolicy;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppMemberWithdrawalResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppNativePurchaseResult;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Device-screen QR purchase, member withdrawal and internal redemption entry.
 *
 * <p>The QR purchase session owns the global device transaction lock from before order
 * creation until authoritative cancellation/closure, physical completion, or refund terminal.
 * Hiding the QR locally never releases the session.</p>
 */
public final class PaymentManager {

    public static final String ACTION_PAYMENT_EVENT = "com.gouzhu.action.PAYMENT_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_ORDER_ID = "orderId";
    public static final String EXTRA_QR_CONTENT = "qrContent";
    public static final String EXTRA_PURCHASE_STATUS = "purchaseStatus";

    public static final String EVENT_REQUEST_CREATED = "requestCreated";
    public static final String EVENT_QR_READY = "qrReady";
    public static final String EVENT_FAILED = "failed";
    public static final String EVENT_WAITING = "waiting";
    public static final String EVENT_SUCCESS = "success";
    public static final String EVENT_CANCELLING = "cancelling";
    public static final String EVENT_CLOSED = "closed";
    public static final String EVENT_SCANNER_REPORTED = "scannerReported";

    private static final String TAG = "GouzhuPayment";
    private static final String PREF = "payment_state_sdk_v1";
    private static final String KEY_CURRENT_REQUEST_NO = "currentRequestNo";
    private static final String KEY_CURRENT_BEAD_COUNT = "currentBeadCount";
    private static final String KEY_CURRENT_PRICE_FEN = "currentPriceFen";
    private static final String KEY_CURRENT_RULE_ID = "currentRuleId";
    private static final String KEY_CURRENT_TIER_ID = "currentTierId";
    private static final String KEY_CURRENT_PURCHASE_QUANTITY = "currentPurchaseQuantity";
    private static final String KEY_CURRENT_STATUS = "currentPurchaseStatus";
    private static final String KEY_CURRENT_SCAN_URL = "currentScanUrl";
    private static final String KEY_CURRENT_EXPIRE_TIME = "currentExpireTime";
    private static final String KEY_LAST_REQUEST_JSON = "lastRequestJson";
    private static final String KEY_LAST_SCANNER_JSON = "lastScannerJson";
    private static final String KEY_SCANNER_REQUEST_NO = "scannerRequestNo";

    private static final long QUERY_INTERVAL_SECONDS = 2L;
    private static final long MAX_QUERY_DURATION_MS = 10L * 60L * 1000L;

    private static volatile PaymentManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> purchaseQueryTask;
    private long purchaseQueryDeadline;
    private boolean qrBroadcasted;

    private PaymentManager(Context context) {
        this.context = context.getApplicationContext();
        this.sdkManager = DeviceSdkManager.get(this.context);
        this.occupancy = TransactionOccupancyManager.get(this.context);
    }

    public static PaymentManager get(Context context) {
        if (instance == null) {
            synchronized (PaymentManager.class) {
                if (instance == null) {
                    instance = new PaymentManager(context);
                }
            }
        }
        return instance;
    }

    /** Uses bootstrap rule and tier IDs to create one exclusive native QR purchase. */
    public PaymentRequest startPayment(
            long purchaseRuleId,
            long priceTierId,
            int beadCount,
            int priceFen
    ) {
        return startPayment(
                purchaseRuleId,
                priceTierId,
                null,
                beadCount,
                priceFen
        );
    }

    public synchronized PaymentRequest startPayment(
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity,
            int beadCount,
            int priceFen
    ) {
        boolean hasTier = priceTierId != null && priceTierId > 0L;
        boolean hasQuantity = purchaseQuantity != null && purchaseQuantity > 0;
        if (purchaseRuleId <= 0L || hasTier == hasQuantity) {
            throw new IllegalArgumentException(
                    "购珠规则ID必须有效，档位ID和自定义数量必须二选一"
            );
        }
        if (beadCount <= 0 || priceFen <= 0) {
            throw new IllegalArgumentException("套餐数量和金额必须大于0");
        }
        if (!getCurrentOrderId().isEmpty()) {
            throw new IllegalStateException("当前扫码购珠会话尚未结束");
        }

        cancelPurchaseQuery();
        String requestNo = newRequestNo("pay");
        TransactionOccupancyManager.AcquireResult acquired =
                occupancy.tryAcquireQr(requestNo);
        if (!acquired.success || acquired.snapshot == null) {
            String reason = acquired.snapshot == null
                    ? acquired.reason
                    : occupancy.displayMessage(acquired.snapshot);
            throw new IllegalStateException(
                    reason == null || reason.isEmpty()
                            ? "设备正在处理其他交易，请稍后再试"
                            : reason
            );
        }

        JSONObject json = new JSONObject();
        try {
            json.put("clientRequestNo", requestNo);
            json.put("purchaseRuleId", purchaseRuleId);
            if (hasTier) {
                json.put("priceTierId", priceTierId);
            } else {
                json.put("purchaseQuantity", purchaseQuantity);
            }
            json.put("beadCount", beadCount);
            json.put("priceFen", priceFen);
        } catch (Throwable error) {
            occupancy.release(acquired.snapshot.sessionId, "request encode failed", true);
            throw new IllegalStateException("组装SDK购珠请求失败", error);
        }

        if (!preferences().edit()
                .putString(KEY_CURRENT_REQUEST_NO, requestNo)
                .putInt(KEY_CURRENT_BEAD_COUNT, beadCount)
                .putInt(KEY_CURRENT_PRICE_FEN, priceFen)
                .putLong(KEY_CURRENT_RULE_ID, purchaseRuleId)
                .putLong(KEY_CURRENT_TIER_ID, hasTier ? priceTierId : 0L)
                .putInt(KEY_CURRENT_PURCHASE_QUANTITY,
                        hasQuantity ? purchaseQuantity : 0)
                .putString(KEY_CURRENT_STATUS, "PREPARING")
                .putString(KEY_CURRENT_SCAN_URL, "")
                .putLong(KEY_CURRENT_EXPIRE_TIME, 0L)
                .putString(KEY_LAST_REQUEST_JSON, json.toString())
                .commit()) {
            occupancy.release(acquired.snapshot.sessionId, "request persistence failed", true);
            throw new IllegalStateException("购珠请求持久化失败");
        }

        qrBroadcasted = false;
        purchaseQueryDeadline = System.currentTimeMillis() + MAX_QUERY_DURATION_MS;
        broadcast(
                EVENT_REQUEST_CREATED,
                "正在关闭现金入口并创建付款订单",
                requestNo,
                null,
                "PREPARING"
        );

        final String occupancySessionId = acquired.snapshot.sessionId;
        executor.execute(() -> createPaymentAfterCashIsolation(
                occupancySessionId,
                requestNo,
                purchaseRuleId,
                hasTier ? priceTierId : null,
                hasQuantity ? purchaseQuantity : null
        ));

        return new PaymentRequest(
                requestNo,
                purchaseRuleId,
                hasTier ? priceTierId : null,
                hasQuantity ? purchaseQuantity : null,
                beadCount,
                priceFen,
                json.toString()
        );
    }

    private void createPaymentAfterCashIsolation(
            String occupancySessionId,
            String requestNo,
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity
    ) {
        boolean hasTier = priceTierId != null && priceTierId > 0L;
        boolean hasQuantity = purchaseQuantity != null && purchaseQuantity > 0;
        if (purchaseRuleId <= 0L || hasTier == hasQuantity) {
            occupancy.markBlocked("PAYMENT_RECOVERY_METADATA_INVALID");
            broadcast(
                    EVENT_FAILED,
                    "扫码订单恢复资料不完整，请联系工作人员",
                    requestNo,
                    null,
                    "RECOVERY_INVALID"
            );
            return;
        }
        if (!occupancy.prepareQrCashIsolation(occupancySessionId)) {
            synchronized (this) {
                if (requestNo.equals(getCurrentOrderId())) {
                    clearCurrentPaymentState();
                }
            }
            occupancy.release(
                    occupancySessionId,
                    "cash devices did not confirm disabled",
                    true
            );
            broadcast(
                    EVENT_FAILED,
                    "现金入口未确认关闭，未创建扫码订单",
                    requestNo,
                    null,
                    "PREPARE_FAILED"
            );
            return;
        }

        preferences().edit()
                .putString(KEY_CURRENT_STATUS, "CREATING")
                .commit();
        try {
            DeviceAppNativePurchaseResult result = sdkManager.createNativePurchase(
                    requestNo,
                    purchaseRuleId,
                    priceTierId,
                    purchaseQuantity
            );
            handlePurchaseResult(requestNo, result);
        } catch (Throwable error) {
            // The server may have created the order before the network failed. Keep the lock and
            // recover by querying the same clientRequestNo rather than creating a new order.
            Log.e(TAG, "SDK创建购珠订单结果未知，将使用原请求号查单", error);
            broadcast(
                    EVENT_WAITING,
                    "创建结果暂时未知，正在使用原请求号确认支付状态",
                    requestNo,
                    null,
                    "CREATE_UNKNOWN"
            );
            schedulePurchaseQuery(requestNo);
        }
    }

    private synchronized void handlePurchaseResult(
            String requestNo,
            DeviceAppNativePurchaseResult result
    ) {
        if (!requestNo.equals(getCurrentOrderId()) || result == null) {
            return;
        }

        String qrContent = firstNonBlank(result.getScanUrl(), result.getCodeUrl());
        long expireTime = readLong(result, "getExpireTime", "getExpireAt");
        String status = TransactionOccupancyPolicy.normalize(result.getPurchaseStatus());
        String message = firstNonBlank(result.getMessage(), status);

        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_CURRENT_STATUS, status);
        if (!qrContent.isEmpty()) {
            editor.putString(KEY_CURRENT_SCAN_URL, qrContent);
        }
        if (expireTime > 0L) {
            editor.putLong(KEY_CURRENT_EXPIRE_TIME, expireTime);
        }
        if (!editor.commit()) {
            occupancy.markBlocked("PAYMENT_STATE_PERSISTENCE_FAILED");
            broadcast(
                    EVENT_FAILED,
                    "支付状态无法可靠保存，设备已停止新交易",
                    requestNo,
                    null,
                    status
            );
            return;
        }

        occupancy.onQrPurchaseStatus(requestNo, status);

        if (!qrBroadcasted && !qrContent.isEmpty()) {
            qrBroadcasted = true;
            broadcast(EVENT_QR_READY, "请扫码完成支付", requestNo, qrContent, status);
        }

        switch (status) {
            case "WAITING_PAYMENT":
            case "":
                broadcast(
                        EVENT_WAITING,
                        message.isEmpty() ? "等待扫码支付" : message,
                        requestNo,
                        null,
                        status
                );
                schedulePurchaseQuery(requestNo);
                return;
            case "EXPIRED":
                broadcast(
                        EVENT_WAITING,
                        "二维码已到期，正在确认渠道支付结果并关单",
                        requestNo,
                        null,
                        status
                );
                schedulePurchaseQuery(requestNo);
                return;
            case "CANCELED":
            case "CLOSED":
                cancelPurchaseQuery();
                if (!occupancy.isQrOwned(requestNo)) {
                    clearCurrentPaymentState();
                    broadcast(
                            EVENT_CLOSED,
                            message.isEmpty() ? "当前付款二维码已关闭" : message,
                            requestNo,
                            null,
                            status
                    );
                } else {
                    broadcast(
                            EVENT_FAILED,
                            "支付已关闭但物理出珠仍在执行，设备已转人工处理",
                            requestNo,
                            null,
                            status
                    );
                }
                return;
            case "DISPENSING":
                cancelPurchaseQuery();
                broadcast(
                        EVENT_SUCCESS,
                        message.isEmpty() ? "支付成功，等待平台出珠指令" : message,
                        requestNo,
                        null,
                        status
                );
                return;
            case "COMPLETED":
                cancelPurchaseQuery();
                if (!occupancy.isQrOwned(requestNo)) {
                    clearCurrentPaymentState();
                }
                broadcast(
                        EVENT_SUCCESS,
                        message.isEmpty() ? "购珠订单已完成" : message,
                        requestNo,
                        null,
                        status
                );
                return;
            case "REFUNDING":
                broadcast(
                        EVENT_WAITING,
                        message.isEmpty() ? "退款处理中" : message,
                        requestNo,
                        null,
                        status
                );
                schedulePurchaseQuery(requestNo);
                return;
            case "REFUNDED":
                cancelPurchaseQuery();
                if (!occupancy.isQrOwned(requestNo)) {
                    clearCurrentPaymentState();
                }
                broadcast(
                        EVENT_FAILED,
                        message.isEmpty() ? "订单已退款" : message,
                        requestNo,
                        null,
                        status
                );
                return;
            default:
                if (!result.isTerminal()) {
                    broadcast(
                            EVENT_WAITING,
                            message.isEmpty() ? "正在确认支付状态" : message,
                            requestNo,
                            null,
                            status
                    );
                    schedulePurchaseQuery(requestNo);
                    return;
                }
                if (isFailureStatus(status)) {
                    cancelPurchaseQuery();
                    occupancy.markBlocked("PAYMENT_TERMINAL_" + status);
                    broadcast(
                            EVENT_FAILED,
                            message.isEmpty() ? "支付订单异常，等待人工确认" : message,
                            requestNo,
                            null,
                            status
                    );
                } else {
                    broadcast(
                            EVENT_SUCCESS,
                            message.isEmpty() ? "支付已确认，等待平台出珠指令" : message,
                            requestNo,
                            null,
                            status
                    );
                }
        }
    }

    /** User-confirmed close action for the current QR payment session. */
    public synchronized boolean cancelCurrentPayment() {
        String requestNo = getCurrentOrderId();
        if (requestNo.isEmpty() || !occupancy.markQrCancelling(requestNo)) {
            return false;
        }
        cancelPurchaseQuery();
        broadcast(
                EVENT_CANCELLING,
                "正在确认支付结果并关闭当前二维码",
                requestNo,
                null,
                "CANCELLING"
        );
        executor.execute(() -> {
            try {
                handlePurchaseResult(
                        requestNo,
                        cancelNativePurchase(requestNo)
                );
            } catch (Throwable error) {
                Log.w(TAG, "取消扫码订单结果未知，将继续查单", error);
                broadcast(
                        EVENT_WAITING,
                        "取消结果暂时未知，正在继续确认原订单状态",
                        requestNo,
                        null,
                        "CANCEL_UNKNOWN"
                );
                schedulePurchaseQuery(requestNo);
            }
        });
        return true;
    }

    /**
     * Called only when accepted cash physically preempts a QR session during the cash-disable race.
     * This method attempts to close the displaced QR order but never releases the new cash owner.
     */
    public void cancelDisplacedPayment(String requestNo) {
        if (requestNo == null || requestNo.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                DeviceAppNativePurchaseResult result =
                        cancelNativePurchase(requestNo);
                String status = TransactionOccupancyPolicy.normalize(
                        result == null ? "" : result.getPurchaseStatus()
                );
                if (TransactionOccupancyPolicy.isCancellationSuccess(status)) {
                    synchronized (PaymentManager.this) {
                        if (requestNo.equals(getCurrentOrderId())) {
                            clearCurrentPaymentState();
                        }
                    }
                    broadcast(
                            EVENT_CLOSED,
                            "检测到现金投入，原付款二维码已安全关闭",
                            requestNo,
                            null,
                            status
                    );
                } else {
                    occupancy.markBlocked("QR_CASH_CONFLICT_" + status);
                    broadcast(
                            EVENT_FAILED,
                            "现金投入与扫码支付并发，请联系工作人员处理",
                            requestNo,
                            null,
                            status
                    );
                }
            } catch (Throwable error) {
                occupancy.markBlocked("QR_CASH_CONFLICT_CANCEL_UNKNOWN");
                Log.e(TAG, "关闭被现金抢占的扫码订单失败", error);
                broadcast(
                        EVENT_FAILED,
                        "现金投入与扫码订单状态冲突，请联系工作人员处理",
                        requestNo,
                        null,
                        "CANCEL_UNKNOWN"
                );
            }
        });
    }

    /** Restores a persisted QR session after Activity/service recreation. */
    public synchronized void resumePendingPayment() {
        String requestNo = getCurrentOrderId();
        if (requestNo.isEmpty()) {
            return;
        }
        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverQr(requestNo);
        if (!recovered.success || recovered.snapshot == null) {
            Log.w(TAG, "支付会话无法恢复占用，保留原请求等待人工处理：" + requestNo);
            return;
        }
        String scanUrl = preferences().getString(KEY_CURRENT_SCAN_URL, "");
        if (!scanUrl.isEmpty()) {
            qrBroadcasted = true;
            broadcast(
                    EVENT_QR_READY,
                    "已恢复上次付款二维码，正在查询原订单",
                    requestNo,
                    scanUrl,
                    getCurrentPurchaseStatus()
            );
        }
        purchaseQueryDeadline = System.currentTimeMillis() + MAX_QUERY_DURATION_MS;
        cancelPurchaseQuery();

        String status = TransactionOccupancyPolicy.normalize(getCurrentPurchaseStatus());
        if ("PREPARING".equals(status) || "CREATING".equals(status)) {
            long ruleId = preferences().getLong(KEY_CURRENT_RULE_ID, 0L);
            long tierId = preferences().getLong(KEY_CURRENT_TIER_ID, 0L);
            int purchaseQuantity = preferences().getInt(
                    KEY_CURRENT_PURCHASE_QUANTITY,
                    0
            );
            executor.execute(() -> createPaymentAfterCashIsolation(
                    recovered.snapshot.sessionId,
                    requestNo,
                    ruleId,
                    tierId > 0L ? tierId : null,
                    purchaseQuantity > 0 ? purchaseQuantity : null
            ));
            return;
        }

        executor.execute(() -> {
            try {
                handlePurchaseResult(
                        requestNo,
                        sdkManager.queryNativePurchase(requestNo)
                );
            } catch (Throwable error) {
                Log.w(TAG, "恢复扫码订单查询失败，将继续重试", error);
                schedulePurchaseQuery(requestNo);
            }
        });
    }

    /** Called by the occupancy manager after physical completion or authoritative release. */
    public synchronized void onOccupancyReleased(String requestNo) {
        if (requestNo != null && requestNo.equals(getCurrentOrderId())) {
            cancelPurchaseQuery();
            clearCurrentPaymentState();
        }
    }

    private synchronized void schedulePurchaseQuery(String requestNo) {
        if (purchaseQueryTask != null && !purchaseQueryTask.isDone()) {
            return;
        }
        purchaseQueryTask = executor.schedule(() -> {
            synchronized (PaymentManager.this) {
                purchaseQueryTask = null;
            }
            if (!requestNo.equals(getCurrentOrderId())) {
                return;
            }
            if (System.currentTimeMillis() >= purchaseQueryDeadline) {
                // Do not release the lock: the authoritative state is still unknown.
                occupancy.markBlocked("PAYMENT_QUERY_TIMEOUT");
                broadcast(
                        EVENT_FAILED,
                        "支付状态长时间无法确认，请联系工作人员",
                        requestNo,
                        null,
                        "QUERY_TIMEOUT"
                );
                return;
            }
            try {
                handlePurchaseResult(
                        requestNo,
                        sdkManager.queryNativePurchase(requestNo)
                );
            } catch (Throwable error) {
                Log.w(TAG, "SDK查询购珠订单失败，将继续重试", error);
                broadcast(
                        EVENT_WAITING,
                        "网络波动，正在继续查询原支付状态",
                        requestNo,
                        null,
                        "QUERY_RETRY"
                );
                schedulePurchaseQuery(requestNo);
            }
        }, QUERY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelPurchaseQuery() {
        if (purchaseQueryTask != null) {
            purchaseQueryTask.cancel(false);
            purchaseQueryTask = null;
        }
    }

    /**
     * Staff-entered internal pickup code. It does not itself move hardware; the trusted
     * dispense_marbles command will reserve a generic physical transaction.
     */
    public String submitScannerQrString(String scanContent) {
        if (!occupancy.isIdle()) {
            broadcast(
                    EVENT_FAILED,
                    "设备正在处理其他交易，请稍后再扫码",
                    getCurrentOrderId(),
                    null,
                    "DEVICE_TRANSACTION_OCCUPIED"
            );
            return "";
        }
        String pickupCode = scanContent == null ? "" : scanContent.trim();
        if (pickupCode.isEmpty()) {
            broadcast(EVENT_FAILED, "平台取珠码不能为空", getCurrentOrderId(), null, "");
            return "";
        }

        String requestNo = newRequestNo("redeem");
        if (!saveScannerRequestMetadata(requestNo, "internal", pickupCode)) {
            broadcast(EVENT_FAILED, "保存核销请求元数据失败", getCurrentOrderId(), null, "");
            return "";
        }

        executor.execute(() -> {
            try {
                DeviceAppInternalRedemptionResult result = sdkManager.createInternalRedemption(
                        requestNo,
                        pickupCode
                );
                String message = firstNonBlank(
                        result.getMessage(),
                        result.getRedemptionStatus(),
                        "取珠码已受理，等待平台处理"
                );
                broadcast(EVENT_SCANNER_REPORTED, message, requestNo, null, "");
            } catch (Throwable error) {
                Log.e(TAG, "平台取珠码提交失败，requestNo=" + requestNo, error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null, "");
            }
        });
        return requestNo;
    }

    public String submitMemberWithdrawal(String withdrawalCode) {
        if (!occupancy.isIdle()) {
            broadcast(
                    EVENT_FAILED,
                    "设备正在处理其他交易，请稍后再取珠",
                    getCurrentOrderId(),
                    null,
                    "DEVICE_TRANSACTION_OCCUPIED"
            );
            return "";
        }
        String code = withdrawalCode == null ? "" : withdrawalCode.trim();
        if (code.isEmpty()) {
            broadcast(EVENT_FAILED, "会员取珠码不能为空", getCurrentOrderId(), null, "");
            return "";
        }
        String requestNo = newRequestNo("withdraw");
        if (!saveScannerRequestMetadata(requestNo, "member", code)) {
            broadcast(EVENT_FAILED, "保存会员取珠请求元数据失败", requestNo, null, "");
            return "";
        }
        executor.execute(() -> {
            try {
                DeviceAppMemberWithdrawalResult result = sdkManager.createMemberWithdrawal(
                        requestNo,
                        code
                );
                String message = firstNonBlank(
                        result.getMessage(),
                        result.getWithdrawalStatus(),
                        "会员取珠请求已受理，等待平台处理"
                );
                broadcast(EVENT_SCANNER_REPORTED, message, requestNo, null, "");
            } catch (Throwable error) {
                Log.e(TAG, "会员取珠请求提交失败，requestNo=" + requestNo, error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null, "");
            }
        });
        return requestNo;
    }

    private boolean saveScannerRequestMetadata(
            String requestNo,
            String routeType,
            String businessCode
    ) {
        try {
            JSONObject json = new JSONObject();
            json.put("clientRequestNo", requestNo);
            json.put("routeType", routeType);
            json.put("codeLength", businessCode == null ? 0 : businessCode.length());
            json.put("maskedCode", maskCode(businessCode));
            return preferences().edit()
                    .putString(KEY_SCANNER_REQUEST_NO, requestNo)
                    .putString(KEY_LAST_SCANNER_JSON, json.toString())
                    .commit();
        } catch (Throwable error) {
            Log.e(TAG, "保存扫码请求脱敏元数据失败", error);
            return false;
        }
    }

    public boolean handleServerMessage(String payload) {
        return false;
    }

    public String getCurrentOrderId() {
        return preferences().getString(KEY_CURRENT_REQUEST_NO, "");
    }

    public String getCurrentPurchaseStatus() {
        return preferences().getString(KEY_CURRENT_STATUS, "");
    }

    public String getLastPaymentRequestJson() {
        return preferences().getString(KEY_LAST_REQUEST_JSON, "");
    }

    public String getLastScannerReportJson() {
        return preferences().getString(KEY_LAST_SCANNER_JSON, "");
    }

    private void clearCurrentPaymentState() {
        preferences().edit()
                .remove(KEY_CURRENT_REQUEST_NO)
                .remove(KEY_CURRENT_BEAD_COUNT)
                .remove(KEY_CURRENT_PRICE_FEN)
                .remove(KEY_CURRENT_RULE_ID)
                .remove(KEY_CURRENT_TIER_ID)
                .remove(KEY_CURRENT_PURCHASE_QUANTITY)
                .remove(KEY_CURRENT_STATUS)
                .remove(KEY_CURRENT_SCAN_URL)
                .remove(KEY_CURRENT_EXPIRE_TIME)
                .commit();
        qrBroadcasted = false;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void broadcast(
            String event,
            String message,
            String orderId,
            String qrContent,
            String purchaseStatus
    ) {
        Intent intent = new Intent(ACTION_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_ORDER_ID, orderId);
        intent.putExtra(EXTRA_PURCHASE_STATUS, safe(purchaseStatus));
        if (qrContent != null) {
            intent.putExtra(EXTRA_QR_CONTENT, qrContent);
        }
        context.sendBroadcast(intent);
    }


    private DeviceAppNativePurchaseResult cancelNativePurchase(String requestNo) {
        try {
            Object client = sdkManager.newAppClient();
            Method method = client.getClass().getMethod(
                    "cancelNativePurchase",
                    String.class
            );
            Object result = method.invoke(client, requestNo);
            if (!(result instanceof DeviceAppNativePurchaseResult)) {
                throw new IllegalStateException("SDK取消接口返回类型无效");
            }
            return (DeviceAppNativePurchaseResult) result;
        } catch (Throwable error) {
            Throwable cause = error.getCause();
            throw new IllegalStateException(
                    "当前设备SDK不支持或无法执行cancelNativePurchase",
                    cause == null ? error : cause
            );
        }
    }

    private static boolean isFailureStatus(String status) {
        String value = safe(status).toUpperCase(Locale.ROOT);
        return value.contains("FAIL")
                || value.contains("REJECT")
                || value.contains("ERROR");
    }

    private static long readLong(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return 0L;
        }
        for (String name : methodNames) {
            try {
                Method method = target.getClass().getMethod(name);
                Object value = method.invoke(target);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                if (value != null) {
                    return Long.parseLong(String.valueOf(value));
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String newRequestNo(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "-" + System.currentTimeMillis() + "-" + uuid.substring(0, 12);
    }

    private static String maskCode(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int visible = Math.min(4, content.length());
        return "***" + content.substring(content.length() - visible);
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    public static final class PaymentRequest {
        public final String orderId;
        public final long purchaseRuleId;
        public final Long priceTierId;
        public final Integer purchaseQuantity;
        public final int beadCount;
        public final int priceFen;
        public final String requestJson;

        PaymentRequest(
                String orderId,
                long purchaseRuleId,
                Long priceTierId,
                Integer purchaseQuantity,
                int beadCount,
                int priceFen,
                String requestJson
        ) {
            this.orderId = orderId;
            this.purchaseRuleId = purchaseRuleId;
            this.priceTierId = priceTierId;
            this.purchaseQuantity = purchaseQuantity;
            this.beadCount = beadCount;
            this.priceFen = priceFen;
            this.requestJson = requestJson;
        }
    }
}
