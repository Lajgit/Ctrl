package com.gouzhu.payment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.serial.SerialManager;
import com.gouzhu.util.DeviceUtil;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 支付流程预留管理器。
 *
 * <p>当前没有正式服务器接口。本类固定了 App 内部调用边界：
 * 创建支付请求、接收服务器二维码字符串、接收服务器支付结果、
 * 接收扫码模块解析出的字符串。正式接口接入时只替换网络调用，不改顾客页面和控制板协议。</p>
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
    public static final String EVENT_SUCCESS = "success";
    public static final String EVENT_SCANNER_RESERVED = "scannerReserved";

    private static final String TAG = "GouzhuPayment";
    private static final String PREF = "payment_state";
    private static final String KEY_CURRENT_ORDER_ID = "currentOrderId";
    private static final String KEY_CURRENT_BEAD_COUNT = "currentBeadCount";
    private static final String KEY_CURRENT_PRICE_FEN = "currentPriceFen";
    private static final String KEY_COMPLETED_ORDER_ID = "completedOrderId";
    private static final String KEY_LAST_REQUEST_JSON = "lastRequestJson";
    private static final String KEY_LAST_SCANNER_JSON = "lastScannerJson";

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

        String orderId = DeviceUtil.getDeviceId(context)
                + "-"
                + System.currentTimeMillis()
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        JSONObject json = new JSONObject();
        try {
            json.put("type", "paymentRequest");
            json.put("deviceNo", DeviceUtil.getDeviceId(context));
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
                .apply();

        Log.i(TAG, "支付请求接口待接入，request=" + json);
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

        String completedOrderId = preferences().getString(KEY_COMPLETED_ORDER_ID, "");
        if (orderId.equals(completedOrderId)) {
            Log.w(TAG, "忽略重复支付结果，orderId=" + orderId);
            return true;
        }

        boolean sent = SerialManager.get(context).sendCommand(
                0x27,
                Integer.toUnsignedLong(beadCount),
                true
        );
        if (!sent) {
            broadcast(EVENT_FAILED, "控制板未连接，支付结果尚未转发", orderId, null);
            return true;
        }

        preferences().edit()
                .putString(KEY_COMPLETED_ORDER_ID, orderId)
                .apply();

        broadcast(
                EVENT_SUCCESS,
                "支付成功，正在吐出 " + beadCount + " 珠",
                orderId,
                null
        );
        return true;
    }

    public String submitScannerQrString(String scanContent) {
        if (scanContent == null || scanContent.trim().isEmpty()) {
            return "";
        }

        JSONObject json = new JSONObject();
        try {
            json.put("type", "scannerQr");
            json.put("deviceNo", DeviceUtil.getDeviceId(context));
            json.put("scanContent", scanContent);
            json.put("timestamp", System.currentTimeMillis());
        } catch (Throwable error) {
            Log.e(TAG, "组装扫码结果失败", error);
            return "";
        }

        preferences().edit()
                .putString(KEY_LAST_SCANNER_JSON, json.toString())
                .apply();

        Log.i(TAG, "扫码结果上报接口待接入，payload=" + json);
        broadcast(
                EVENT_SCANNER_RESERVED,
                "已读取扫码内容，等待接入服务器上报接口",
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
