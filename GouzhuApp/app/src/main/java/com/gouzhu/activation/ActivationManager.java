package com.gouzhu.activation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gouzhu.AppConfig;
import com.gouzhu.util.DeviceUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 设备注册、自动认领和 MQTT 凭据保存。
 *
 * <p>接口、批次号和签名方式移植自 OTA_XLH3566，包名及版本获取方式改为
 * 单应用 com.gouzhu。</p>
 */
public final class ActivationManager {

    private static final String TAG = "GouzhuActivation";

    /** OTA_XLH3566 当前自动认领批次。 */
    private static final String BATCH_NO = "ACBFSGS2YACC6IO";

    /** OTA_XLH3566 当前批次签名密钥。 */
    private static final String BATCH_SECRET =
            "pevx7513ZxRWLFlXFw_2WepJOTWtusjA";

    private static final String PREF_CLAIM = "claim";
    private static final String KEY_CLAIMED = "claimed";
    private static final String PREF_MQTT = "mqtt_credential";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    private Runnable pollRunnable;
    private String lastClaimCode = "";
    private String lastQrContent = "";

    public ActivationManager(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public interface Callback {
        void onWaitingClaim(String qrContent, String claimCode);

        void onActivated(MqttCredential credential);

        void onError(Exception error);
    }

    /** 启动注册激活流程。重复调用不会启动多条轮询线程。 */
    public void start(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        enroll(callback);
    }

    /** 停止轮询。 */
    public void stop() {
        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
    }

    public static boolean isClaimed(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_CLAIM, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLAIMED, false);
    }

    public static void clearActivation(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREF_CLAIM, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
        context.getApplicationContext()
                .getSharedPreferences(PREF_MQTT, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    /** 读取激活接口保存的 MQTT 凭据。 */
    public static MqttCredential loadCredential(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_MQTT, Context.MODE_PRIVATE);

        MqttCredential credential = new MqttCredential();
        credential.brokerUrl = preferences.getString("brokerUrl", "");
        credential.clientId = preferences.getString("clientId", "");
        credential.username = preferences.getString("username", "");
        credential.password = preferences.getString("password", "");
        credential.heartbeatInterval = preferences.getInt("heartbeatInterval", 0);
        credential.keepAliveSeconds = preferences.getInt("keepAliveSeconds", 0);

        return credential.isValid() ? credential : null;
    }

    private void enroll(Callback callback) {
        EnrollData request = buildEnrollRequest();

        postJson(
                "/api/device/enroll",
                request,
                new TypeToken<ApiResponse<EnrollData>>() { }.getType(),
                new ApiCallback<ApiResponse<EnrollData>>() {
                    @Override
                    public void onSuccess(ApiResponse<EnrollData> response) {
                        if (!running.get()) {
                            return;
                        }

                        if (response == null || response.data == null) {
                            postError(callback, new IOException("注册接口返回数据为空"));
                            return;
                        }

                        EnrollData data = response.data;
                        lastClaimCode = safe(data.claimCode);
                        lastQrContent = safe(data.claimQrContent);
                        mainHandler.post(() -> callback.onWaitingClaim(
                                lastQrContent,
                                lastClaimCode
                        ));

                        // MQTT 凭据只由 activation 接口返回，因此注册后必须继续请求激活。
                        requestActivation(request, callback, true);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        postError(callback, error);
                    }
                }
        );
    }

    private EnrollData buildEnrollRequest() {
        EnrollData request = new EnrollData();
        request.deviceNo = DeviceUtil.getDeviceId(context);
        request.firmwareVersion = DeviceUtil.getAppVersion(context);
        request.apkVersion = DeviceUtil.getAppVersion(context);
        request.nonce = UUID.randomUUID().toString().replace("-", "");
        request.timestamp = System.currentTimeMillis();
        request.signature = "";
        fillBatchSignature(request);
        return request;
    }

    private void requestActivation(
            EnrollData request,
            Callback callback,
            boolean startPollingWhenWaiting
    ) {
        request.timestamp = System.currentTimeMillis();
        fillBatchSignature(request);

        postJson(
                "/api/device/activation",
                request,
                new TypeToken<ApiResponse<ActivationData>>() { }.getType(),
                new ApiCallback<ApiResponse<ActivationData>>() {
                    @Override
                    public void onSuccess(ApiResponse<ActivationData> response) {
                        if (!running.get()) {
                            return;
                        }

                        if (response != null
                                && response.data != null
                                && handleActivation(response.data, callback)) {
                            return;
                        }

                        if (startPollingWhenWaiting) {
                            startPolling(request, callback);
                        }
                    }

                    @Override
                    public void onFailure(Exception error) {
                        Log.w(TAG, "首次激活请求失败，进入轮询", error);
                        if (startPollingWhenWaiting) {
                            startPolling(request, callback);
                        }
                    }
                }
        );
    }

    private void startPolling(EnrollData request, Callback callback) {
        if (!running.get()) {
            return;
        }

        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }

        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running.get()) {
                    return;
                }

                mainHandler.post(() -> callback.onWaitingClaim(
                        lastQrContent,
                        lastClaimCode
                ));

                requestActivation(request, callback, false);
                if (running.get()) {
                    mainHandler.postDelayed(this, 5000L);
                }
            }
        };
        mainHandler.postDelayed(pollRunnable, 5000L);
    }

    private boolean handleActivation(ActivationData data, Callback callback) {
        if (!data.claimed) {
            return false;
        }

        MqttCredential credential = new MqttCredential();
        credential.brokerUrl = data.mqttBrokerUrl;
        credential.clientId = data.mqttClientId;
        credential.username = data.mqttUsername;
        credential.password = data.mqttPassword;
        credential.heartbeatInterval = data.heartbeatInterval;
        credential.keepAliveSeconds = data.keepAliveSeconds;

        if (!credential.isValid()) {
            postError(callback, new IOException("设备已激活，但 MQTT 凭据不完整"));
            return false;
        }

        if (!saveCredential(credential)) {
            postError(callback, new IOException("保存 MQTT 凭据失败"));
            return false;
        }

        boolean claimSaved = context.getSharedPreferences(PREF_CLAIM, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CLAIMED, true)
                .commit();
        if (!claimSaved) {
            postError(callback, new IOException("保存设备激活状态失败"));
            return false;
        }

        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
        mainHandler.post(() -> callback.onActivated(credential));
        return true;
    }

    private boolean saveCredential(MqttCredential credential) {
        return context.getSharedPreferences(PREF_MQTT, Context.MODE_PRIVATE)
                .edit()
                .putString("brokerUrl", credential.brokerUrl)
                .putString("clientId", credential.clientId)
                .putString("username", credential.username)
                .putString("password", credential.password)
                .putInt("heartbeatInterval", credential.heartbeatInterval)
                .putInt("keepAliveSeconds", credential.keepAliveSeconds)
                .commit();
    }

    private void fillBatchSignature(EnrollData request) {
        request.batchNo = BATCH_NO;
        String source = request.deviceNo
                + "|" + request.batchNo
                + "|" + request.nonce
                + "|" + request.timestamp;
        request.batchSignature = hmacSha256Base64Url(source, BATCH_SECRET);
    }

    private String hmacSha256Base64Url(String text, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] result = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(
                    result,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
            );
        } catch (Throwable error) {
            Log.e(TAG, "计算批次签名失败", error);
            return "";
        }
    }

    private <T> void postJson(
            String path,
            Object body,
            Type responseType,
            ApiCallback<T> callback
    ) {
        try {
            String json = gson.toJson(body);
            RequestBody requestBody = RequestBody.create(
                    json,
                    MediaType.parse("application/json; charset=utf-8")
            );
            Request request = new Request.Builder()
                    .url(AppConfig.ACTIVATION_BASE_URL + path)
                    .post(requestBody)
                    .build();

            httpClient.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    callback.onFailure(error);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful() || responseBody == null) {
                            callback.onFailure(new IOException("HTTP " + response.code()));
                            return;
                        }
                        T parsed = gson.fromJson(responseBody.string(), responseType);
                        callback.onSuccess(parsed);
                    } catch (Exception error) {
                        callback.onFailure(error);
                    }
                }
            });
        } catch (Exception error) {
            callback.onFailure(error);
        }
    }

    private void postError(Callback callback, Exception error) {
        Log.e(TAG, "设备注册激活失败", error);
        mainHandler.post(() -> callback.onError(error));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private interface ApiCallback<T> {
        void onSuccess(T response);

        void onFailure(Exception error);
    }

    private static final class ApiResponse<T> {
        int code;
        String msg;
        T data;
        boolean success;
    }

    private static final class EnrollData {
        String deviceNo;
        String firmwareVersion;
        String apkVersion;
        String nonce;
        long timestamp;
        String signature;
        String batchNo;
        String batchSignature;

        boolean accepted;
        String claimToken;
        String claimCode;
        String claimQrContent;
        int claimStatus;
        String claimStatusDesc;
        boolean claimed;
        String message;
    }

    private static final class ActivationData {
        boolean accepted;
        boolean claimed;
        String deviceNo;
        String mqttBrokerUrl;
        String mqttClientId;
        String mqttUsername;
        String mqttPassword;
        int heartbeatInterval;
        int keepAliveSeconds;
        int configVersion;
    }

    /** MQTT 连接参数。 */
    public static final class MqttCredential {
        public String brokerUrl;
        public String clientId;
        public String username;
        public String password;
        public int heartbeatInterval;
        public int keepAliveSeconds;

        public boolean isValid() {
            return !isEmpty(brokerUrl)
                    && !isEmpty(clientId)
                    && !isEmpty(username)
                    && !isEmpty(password);
        }

        private static boolean isEmpty(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}
