package com.gouzhu.payment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.sdk.DeviceSdkManager;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppMemberWithdrawalResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppNativePurchaseResult;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 设备屏购珠、会员取珠和内部套餐核销。
 *
 * <p>HTTP 创建/查询统一调用服务端 SDK。支付或核销接口只更新界面状态，绝不
 * 直接发送串口；真实出珠仍必须等待 MQTT dispense_marbles 指令。</p>
 */
public final class PaymentManager {

    public static final String ACTION_PAYMENT_EVENT = "com.gouzhu.action.PAYMENT_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_ORDER_ID = "orderId";
    public static final String EXTRA_QR_CONTENT = "qrContent";

    public static final String EVENT_REQUEST_CREATED = "requestCreated";
    public static final String EVENT_QR_READY = "qrReady";
    public static final String EVENT_FAILED = "failed";
    public static final String EVENT_WAITING = "waiting";
    public static final String EVENT_SUCCESS = "success";
    public static final String EVENT_SCANNER_REPORTED = "scannerReported";

    private static final String TAG = "GouzhuPayment";
    private static final String PREF = "payment_state_sdk_v1";
    private static final String KEY_CURRENT_REQUEST_NO = "currentRequestNo";
    private static final String KEY_CURRENT_BEAD_COUNT = "currentBeadCount";
    private static final String KEY_CURRENT_PRICE_FEN = "currentPriceFen";
    private static final String KEY_CURRENT_RULE_ID = "currentRuleId";
    private static final String KEY_CURRENT_TIER_ID = "currentTierId";
    private static final String KEY_LAST_REQUEST_JSON = "lastRequestJson";
    private static final String KEY_LAST_SCANNER_JSON = "lastScannerJson";
    private static final String KEY_SCANNER_REQUEST_NO = "scannerRequestNo";

    private static final long QUERY_INTERVAL_SECONDS = 2L;
    private static final long MAX_QUERY_DURATION_MS = 5L * 60L * 1000L;

    private static volatile PaymentManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> purchaseQueryTask;
    private long purchaseQueryDeadline;
    private boolean qrBroadcasted;

    private PaymentManager(Context context) {
        this.context = context.getApplicationContext();
        this.sdkManager = DeviceSdkManager.get(this.context);
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

    /** 使用 bootstrap 返回的规则 ID 和档位 ID 创建聚合扫码订单。 */
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

        cancelPurchaseQuery();
        String requestNo = newRequestNo("pay");
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
            throw new IllegalStateException("组装SDK购珠请求失败", error);
        }

        if (!preferences().edit()
                .putString(KEY_CURRENT_REQUEST_NO, requestNo)
                .putInt(KEY_CURRENT_BEAD_COUNT, beadCount)
                .putInt(KEY_CURRENT_PRICE_FEN, priceFen)
                .putLong(KEY_CURRENT_RULE_ID, purchaseRuleId)
                .putLong(KEY_CURRENT_TIER_ID, hasTier ? priceTierId : 0L)
                .putString(KEY_LAST_REQUEST_JSON, json.toString())
                .commit()) {
            throw new IllegalStateException("购珠请求持久化失败");
        }

        qrBroadcasted = false;
        purchaseQueryDeadline = System.currentTimeMillis() + MAX_QUERY_DURATION_MS;
        broadcast(EVENT_REQUEST_CREATED, "正在向服务端创建付款订单", requestNo, null);

        executor.execute(() -> {
            try {
                DeviceAppNativePurchaseResult result = sdkManager.createNativePurchase(
                        requestNo,
                        purchaseRuleId,
                        hasTier ? priceTierId : null,
                        hasQuantity ? purchaseQuantity : null
                );
                handlePurchaseResult(requestNo, result);
            } catch (Throwable error) {
                Log.e(TAG, "SDK创建购珠订单失败", error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null);
            }
        });

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

    private synchronized void handlePurchaseResult(
            String requestNo,
            DeviceAppNativePurchaseResult result
    ) {
        if (!requestNo.equals(getCurrentOrderId()) || result == null) {
            return;
        }

        String qrContent = firstNonBlank(result.getScanUrl(), result.getCodeUrl());
        if (!qrBroadcasted && !qrContent.isEmpty()) {
            qrBroadcasted = true;
            broadcast(EVENT_QR_READY, "请扫码完成支付", requestNo, qrContent);
        }

        String status = safe(result.getPurchaseStatus());
        String message = firstNonBlank(result.getMessage(), status);
        if (!result.isTerminal()) {
            if (!message.isEmpty()) {
                broadcast(EVENT_WAITING, message, requestNo, null);
            }
            schedulePurchaseQuery(requestNo);
            return;
        }

        cancelPurchaseQuery();
        if (isFailureStatus(status)) {
            broadcast(
                    EVENT_FAILED,
                    message.isEmpty() ? "订单未支付成功" : message,
                    requestNo,
                    null
            );
        } else {
            // 这里只表示服务端业务进入非失败终态；物理出珠仍等待 MQTT 指令。
            broadcast(
                    EVENT_SUCCESS,
                    message.isEmpty() ? "支付已确认，等待平台下发出珠指令" : message,
                    requestNo,
                    null
            );
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
                broadcast(EVENT_FAILED, "支付状态查询超时", requestNo, null);
                return;
            }
            try {
                handlePurchaseResult(
                        requestNo,
                        sdkManager.queryNativePurchase(requestNo)
                );
            } catch (Throwable error) {
                Log.w(TAG, "SDK查询购珠订单失败，将继续重试", error);
                broadcast(EVENT_WAITING, "网络波动，正在继续查询支付状态", requestNo, null);
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
     * 扫码器读取六位内部套餐取珠码后，调用 SDK 创建核销。
     * 创建结果只表示后端受理，真实出珠仍等待 MQTT 指令。
     */
    public String submitScannerQrString(String scanContent) {
        String pickupCode = scanContent == null ? "" : scanContent.trim();
        if (!pickupCode.matches("\\d{6}")) {
            broadcast(EVENT_FAILED, "内部取珠码必须为6位数字", getCurrentOrderId(), null);
            return "";
        }

        String requestNo = preferences().getString(KEY_SCANNER_REQUEST_NO, "");
        if (requestNo.isEmpty()) {
            requestNo = newRequestNo("redeem");
            preferences().edit().putString(KEY_SCANNER_REQUEST_NO, requestNo).commit();
        }

        final String finalRequestNo = requestNo;
        JSONObject json = new JSONObject();
        try {
            json.put("clientRequestNo", finalRequestNo);
            json.put("pickupCode", pickupCode);
        } catch (Throwable error) {
            return "";
        }
        preferences().edit().putString(KEY_LAST_SCANNER_JSON, json.toString()).commit();

        executor.execute(() -> {
            try {
                DeviceAppInternalRedemptionResult result = sdkManager.createInternalRedemption(
                        finalRequestNo,
                        pickupCode
                );
                String message = firstNonBlank(
                        result.getMessage(),
                        result.getRedemptionStatus(),
                        "取珠码已受理，等待平台处理"
                );
                broadcast(EVENT_SCANNER_REPORTED, message, getCurrentOrderId(), null);
            } catch (Throwable error) {
                broadcast(EVENT_FAILED, messageOf(error), getCurrentOrderId(), null);
            }
        });
        return json.toString();
    }

    /** 创建会员取珠请求；取珠码由会员流程提供，必须以 W 开头且其余为数字。 */
    public String submitMemberWithdrawal(String withdrawalCode) {
        String code = withdrawalCode == null ? "" : withdrawalCode.trim();
        if (!code.matches("W\\d+")) {
            broadcast(EVENT_FAILED, "会员取珠码格式无效", getCurrentOrderId(), null);
            return "";
        }
        String requestNo = newRequestNo("withdraw");
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
                broadcast(EVENT_SCANNER_REPORTED, message, getCurrentOrderId(), null);
            } catch (Throwable error) {
                broadcast(EVENT_FAILED, messageOf(error), getCurrentOrderId(), null);
            }
        });
        return requestNo;
    }

    /** 旧的临时服务器消息入口不再使用，保留签名避免其他代码编译中断。 */
    public boolean handleServerMessage(String payload) {
        return false;
    }

    public String getCurrentOrderId() {
        return preferences().getString(KEY_CURRENT_REQUEST_NO, "");
    }

    public String getLastPaymentRequestJson() {
        return preferences().getString(KEY_LAST_REQUEST_JSON, "");
    }

    public String getLastScannerReportJson() {
        return preferences().getString(KEY_LAST_SCANNER_JSON, "");
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void broadcast(String event, String message, String orderId, String qrContent) {
        Intent intent = new Intent(ACTION_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_ORDER_ID, orderId);
        if (qrContent != null) {
            intent.putExtra(EXTRA_QR_CONTENT, qrContent);
        }
        context.sendBroadcast(intent);
    }

    private static boolean isFailureStatus(String status) {
        String value = safe(status).toUpperCase(Locale.ROOT);
        return value.contains("FAIL")
                || value.contains("CANCEL")
                || value.contains("CLOSE")
                || value.contains("EXPIRE")
                || value.contains("REFUND")
                || value.contains("REJECT");
    }

    private static String newRequestNo(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "-" + System.currentTimeMillis() + "-" + uuid.substring(0, 12);
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
