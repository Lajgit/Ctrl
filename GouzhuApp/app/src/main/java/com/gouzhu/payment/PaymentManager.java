package com.gouzhu.payment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.util.DeviceUtil;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 顾客支付和扫码入口。
 *
 * <p>支付页面或支付结果本身不得直接驱动控制板。正式物理出珠只由平台通过
 * MQTT 下发 dispense_marbles，再由设备指令管理器执行。</p>
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
    private static final String PREF = "payment_state";
    private static final String KEY_CURRENT_ORDER_ID = "currentOrderId";
    private static final String KEY_CURRENT_BEAD_COUNT = "currentBeadCount";
    private static final String KEY_CURRENT_PRICE_FEN = "currentPriceFen";
    private static final String KEY_PAYMENT_CONFIRMED_ORDER_ID = "paymentConfirmedOrderId";
    private static final String KEY_LAST_REQUEST_JSON = "lastRequestJson";
    private static final String KEY_LAST_SCANNER_JSON = "lastScannerJson";
    private static final String KEY_SCANNER_REQUEST_ID = "scannerRequestId";

    private static volatile PaymentManager instance;
    private final Context context;

    private PaymentManager(Context context) {
        this.context = context.getApplicationContext();
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

    public PaymentRequest startPayment(int beadCount, int priceFen) {
        if (beadCount <= 0 || priceFen <= 0) {
            throw new IllegalArgumentException("套餐数量和金额必须大于0");
        }

        String orderId = DeviceUtil.requireDeviceNo(context)
                + "-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8);

        JSONObject json = new JSONObject();
        try {
            json.put("type", "paymentRequest");
            json.put("deviceNo", DeviceUtil.requireDeviceNo(context));
            json.put("orderId", orderId);
            json.put("beadCount", beadCount);
            json.put("amountFen", priceFen);
            json.put("timestamp", System.currentTimeMillis());
        } catch (Throwable error) {
            throw new IllegalStateException("组装支付请求失败", error);
        }

        preferences().edit()
                .putString(KEY_CURRENT_ORDER_ID, orderId)
                .putInt(KEY_CURRENT_BEAD_COUNT, beadCount)
                .putInt(KEY_CURRENT_PRICE_FEN, priceFen)
                .putString(KEY_LAST_REQUEST_JSON, json.toString())
                .commit();

        broadcast(EVENT_REQUEST_CREATED, "等待服务器返回付款二维码", orderId, null);
        return new PaymentRequest(orderId, beadCount, priceFen, json.toString());
    }

    public boolean handleServerQrString(String orderId, String qrContent) {
        if (!isCurrentOrder(orderId) || qrContent == null || qrContent.trim().isEmpty()) {
            return false;
        }
        broadcast(EVENT_QR_READY, "请扫码完成支付", orderId, qrContent);
        return true;
    }

    /**
     * 支付结果只更新页面状态，不能直接发串口吐珠命令。
     */
    public synchronized boolean handleServerPaymentResult(
            String orderId,
            boolean success,
            int beadCount,
            String message
    ) {
        if (!isCurrentOrder(orderId)) {
            return false;
        }

        if (!success) {
            broadcast(
                    EVENT_FAILED,
                    message == null || message.trim().isEmpty() ? "支付未成功" : message,
                    orderId,
                    null
            );
            return true;
        }

        if (beadCount <= 0) {
            broadcast(EVENT_FAILED, "服务器返回的吐珠数量无效", orderId, null);
            return true;
        }

        String confirmed = preferences().getString(KEY_PAYMENT_CONFIRMED_ORDER_ID, "");
        if (orderId.equals(confirmed)) {
            return true;
        }
        preferences().edit().putString(KEY_PAYMENT_CONFIRMED_ORDER_ID, orderId).commit();

        broadcast(
                EVENT_SUCCESS,
                "支付已确认，等待平台下发出珠指令",
                orderId,
                null
        );
        return true;
    }

    /**
     * 扫码模块读取六位取珠码后，上报 redemption-request。
     * 同一次失败重试复用相同 requestId。
     */
    public String submitScannerQrString(String scanContent) {
        if (scanContent == null || scanContent.trim().isEmpty()) {
            return "";
        }
        String pickupCode = scanContent.trim();
        String requestId = preferences().getString(KEY_SCANNER_REQUEST_ID, "");
        if (requestId.isEmpty()) {
            requestId = "scan-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().substring(0, 6);
            preferences().edit().putString(KEY_SCANNER_REQUEST_ID, requestId).commit();
        }

        JSONObject json = new JSONObject();
        try {
            json.put("requestId", requestId);
            json.put("pickupCode", pickupCode);
        } catch (Throwable error) {
            Log.e(TAG, "组装扫码核销请求失败", error);
            return "";
        }

        preferences().edit().putString(KEY_LAST_SCANNER_JSON, json.toString()).commit();
        boolean sent = MqttManager.get(context).reportRedemptionRequest(requestId, pickupCode);
        broadcast(
                sent ? EVENT_SCANNER_REPORTED : EVENT_FAILED,
                sent ? "取珠码已上报，等待平台处理" : "网络未连接，取珠码将在重试时复用",
                getCurrentOrderId(),
                null
        );
        return json.toString();
    }

    public boolean handleServerMessage(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String type = json.optString("type", "");
            String orderId = json.optString("orderId", "");
            if ("paymentQr".equals(type)) {
                return handleServerQrString(orderId, json.optString("qrContent", ""));
            }
            if ("paymentResult".equals(type)) {
                return handleServerPaymentResult(
                        orderId,
                        json.optBoolean("success", false),
                        json.optInt("beadCount", 0),
                        json.optString("message", "")
                );
            }
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "解析服务器支付消息失败", error);
            return false;
        }
    }

    public String getCurrentOrderId() {
        return preferences().getString(KEY_CURRENT_ORDER_ID, "");
    }

    public String getLastPaymentRequestJson() {
        return preferences().getString(KEY_LAST_REQUEST_JSON, "");
    }

    public String getLastScannerReportJson() {
        return preferences().getString(KEY_LAST_SCANNER_JSON, "");
    }

    private boolean isCurrentOrder(String orderId) {
        return orderId != null
                && !orderId.trim().isEmpty()
                && orderId.equals(getCurrentOrderId());
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

    public static final class PaymentRequest {
        public final String orderId;
        public final int beadCount;
        public final int priceFen;
        public final String requestJson;

        PaymentRequest(String orderId, int beadCount, int priceFen, String requestJson) {
            this.orderId = orderId;
            this.beadCount = beadCount;
            this.priceFen = priceFen;
            this.requestJson = requestJson;
        }
    }
}
