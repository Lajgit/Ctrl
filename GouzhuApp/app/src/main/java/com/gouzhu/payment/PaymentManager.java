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
     * 店员手工输入平台六位取珠码时调用原始内部核销接口。
     *
     * <p>反扫二维码不得调用本方法，必须通过 ScannerBusinessRouter 使用
     * bootstrap.redemptionRouting。业务码只在本次内存调用中使用，不写日志、不落盘。</p>
     */
    public String submitScannerQrString(String scanContent) {
        String pickupCode = scanContent == null ? "" : scanContent.trim();
        if (pickupCode.isEmpty()) {
            broadcast(EVENT_FAILED, "平台取珠码不能为空", getCurrentOrderId(), null);
            return "";
        }

        String requestNo = newRequestNo("redeem");
        if (!saveScannerRequestMetadata(requestNo, "internal", pickupCode)) {
            broadcast(EVENT_FAILED, "保存核销请求元数据失败", getCurrentOrderId(), null);
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
                broadcast(EVENT_SCANNER_REPORTED, message, requestNo, null);
            } catch (Throwable error) {
                Log.e(TAG, "平台取珠码提交失败，requestNo=" + requestNo, error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null);
            }
        });
        return requestNo;
    }

    /**
     * 已经由可信业务流程取得会员原始取珠码时调用。
     *
     * <p>本方法不再自行判断 W 等前缀，格式边界由 SDK 和服务端负责。反扫二维码
     * 仍必须使用 bootstrap 动态路由。</p>
     */
    public String submitMemberWithdrawal(String withdrawalCode) {
        String code = withdrawalCode == null ? "" : withdrawalCode.trim();
        if (code.isEmpty()) {
            broadcast(EVENT_FAILED, "会员取珠码不能为空", getCurrentOrderId(), null);
            return "";
        }
        String requestNo = newRequestNo("withdraw");
        if (!saveScannerRequestMetadata(requestNo, "member", code)) {
            broadcast(EVENT_FAILED, "保存会员取珠请求元数据失败", requestNo, null);
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
                broadcast(EVENT_SCANNER_REPORTED, message, requestNo, null);
            } catch (Throwable error) {
                Log.e(TAG, "会员取珠请求提交失败，requestNo=" + requestNo, error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null);
            }
        });
        return requestNo;
    }

    /** 只保存请求号、类型、长度和脱敏尾号，禁止持久化完整业务码。 */
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
