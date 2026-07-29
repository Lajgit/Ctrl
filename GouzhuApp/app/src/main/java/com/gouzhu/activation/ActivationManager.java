package com.gouzhu.activation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gouzhu.AppConfig;
import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.util.DeviceUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 新版设备认证状态机。
 *
 * <p>不读取旧签名、旧批次硬编码、旧 MQTT 凭证或旧包名数据。每次 HTTP 请求
 * 都重新生成 nonce、毫秒时间戳和签名。</p>
 */
public final class ActivationManager {

    private static final String TAG = "GouzhuActivation";
    private static final long MIN_POLL_DELAY_MS = 10_000L;
    private static final long MAX_POLL_DELAY_MS = 30_000L;

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

    /**
     * 启动认证。
     *
     * <p>有 MQTT 凭证时先执行 mqttPassword HMAC 后续激活；没有凭证时先尝试
     * 平台授权恢复，恢复窗口不存在时再进入新设备报到和首次激活。</p>
     */
    public void start(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                DeviceIdentityStore.ensureKeyPair(context);
                MqttCredential credential = SecureCredentialStore.load(context);
                if (credential != null) {
                    requestMqttActivation(credential, callback);
                } else {
                    requestCredentialRecovery(callback, true);
                }
            } catch (Exception error) {
                postError(callback, error);
            }
        }, "购珠机-设备认证").start();
    }

    /** 平台已开启恢复窗口时，可由后台设置页主动调用。 */
    public void recoverCredential(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        requestCredentialRecovery(callback, false);
    }

    public void stop() {
        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
    }

    /** 读取新版加密 MQTT 凭证。 */
    public static MqttCredential loadCredential(Context context) {
        return SecureCredentialStore.load(context);
    }

    /** 清理当前测试凭证；不会删除 Android Keystore 中的身份私钥。 */
    public static void clearCredential(Context context) {
        SecureCredentialStore.clear(context);
    }

    /** 导出可交付平台登记的 X.509 公钥。 */
    public static String exportIdentityPublicKey(Context context) throws Exception {
        return DeviceIdentityStore.getPublicKeyBase64(context);
    }

    private void requestCredentialRecovery(Callback callback, boolean fallbackToEnroll) {
        try {
            String deviceNo = DeviceUtil.requireDeviceNo(context);
            DeviceAuthSigner.SignedRequest signed =
                    DeviceAuthSigner.signCredentialRecovery(context, deviceNo);
            DeviceRequest request = buildBaseRequest(deviceNo, signed);
            postJson(
                    "/api/device/credential-recovery",
                    request,
                    new TypeToken<ApiResponse<ActivationData>>() { }.getType(),
                    new ApiCallback<ApiResponse<ActivationData>>() {
                        @Override
                        public void onSuccess(ApiResponse<ActivationData> response) {
                            if (!running.get()) {
                                return;
                            }
                            try {
                                ActivationData data = requireBusinessSuccess(response, "凭证恢复");
                                finishActivation(data, callback);
                            } catch (Exception error) {
                                if (fallbackToEnroll) {
                                    enroll(callback);
                                } else {
                                    postError(callback, error);
                                }
                            }
                        }

                        @Override
                        public void onFailure(Exception error) {
                            if (fallbackToEnroll) {
                                enroll(callback);
                            } else {
                                postError(callback, error);
                            }
                        }
                    }
            );
        } catch (Exception error) {
            if (fallbackToEnroll) {
                enroll(callback);
            } else {
                postError(callback, error);
            }
        }
    }

    private void enroll(Callback callback) {
        try {
            String deviceNo = DeviceUtil.requireDeviceNo(context);
            DeviceAuthSigner.SignedRequest signed =
                    DeviceAuthSigner.signEnrollment(context, deviceNo);
            DeviceRequest request = buildBaseRequest(deviceNo, signed);

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
                            try {
                                EnrollData data = requireBusinessSuccess(response, "设备报到");
                                lastClaimCode = safe(data.claimCode);
                                lastQrContent = safe(data.claimQrContent);
                                mainHandler.post(() -> callback.onWaitingClaim(
                                        lastQrContent,
                                        lastClaimCode
                                ));
                                requestIdentityActivation(callback, true);
                            } catch (Exception error) {
                                postError(callback, error);
                            }
                        }

                        @Override
                        public void onFailure(Exception error) {
                            postError(callback, error);
                        }
                    }
            );
        } catch (Exception error) {
            postError(callback, error);
        }
    }

    private void requestIdentityActivation(Callback callback, boolean startPolling) {
        try {
            String deviceNo = DeviceUtil.requireDeviceNo(context);
            DeviceAuthSigner.SignedRequest signed =
                    DeviceAuthSigner.signActivationWithIdentity(context, deviceNo);
            DeviceRequest request = buildBaseRequest(deviceNo, signed);

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
                            try {
                                ActivationData data = requireBusinessSuccess(response, "首次激活");
                                if (data.claimed && hasMqttCredential(data)) {
                                    finishActivation(data, callback);
                                    return;
                                }
                                mainHandler.post(() -> callback.onWaitingClaim(
                                        lastQrContent,
                                        lastClaimCode
                                ));
                                if (startPolling) {
                                    scheduleIdentityActivation(callback);
                                }
                            } catch (Exception error) {
                                postError(callback, error);
                            }
                        }

                        @Override
                        public void onFailure(Exception error) {
                            if (startPolling) {
                                scheduleIdentityActivation(callback);
                            } else {
                                postError(callback, error);
                            }
                        }
                    }
            );
        } catch (Exception error) {
            postError(callback, error);
        }
    }

    private void scheduleIdentityActivation(Callback callback) {
        if (!running.get()) {
            return;
        }
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
        long delay = ThreadLocalRandom.current().nextLong(
                MIN_POLL_DELAY_MS,
                MAX_POLL_DELAY_MS + 1L
        );
        pollRunnable = () -> requestIdentityActivation(callback, true);
        mainHandler.postDelayed(pollRunnable, delay);
    }

    private void requestMqttActivation(MqttCredential current, Callback callback) {
        try {
            String deviceNo = DeviceUtil.requireDeviceNo(context);
            DeviceAuthSigner.SignedRequest signed =
                    DeviceAuthSigner.signActivationWithMqttPassword(
                            deviceNo,
                            current.password
                    );
            DeviceRequest request = buildBaseRequest(deviceNo, signed);
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
                            try {
                                ActivationData data = requireBusinessSuccess(response, "后续激活");
                                finishActivation(data, callback);
                            } catch (Exception error) {
                                postError(callback, error);
                            }
                        }

                        @Override
                        public void onFailure(Exception error) {
                            postError(callback, error);
                        }
                    }
            );
        } catch (Exception error) {
            postError(callback, error);
        }
    }

    private DeviceRequest buildBaseRequest(
            String deviceNo,
            DeviceAuthSigner.SignedRequest signed
    ) {
        DeviceRequest request = new DeviceRequest();
        request.deviceNo = deviceNo;
        request.firmwareVersion = DeviceUtil.formatBoardVersion(
                new DeviceCommandStore(context).getBoardVersion()
        );
        request.apkVersion = DeviceUtil.getAppVersion(context);
        request.nonce = signed.nonce;
        request.timestamp = signed.timestamp;
        request.signature = signed.signature;
        return request;
    }

    private void finishActivation(ActivationData data, Callback callback) throws Exception {
        MqttCredential credential = MqttCredential.from(data);
        if (!credential.isValid()) {
            throw new IOException("激活响应缺少完整MQTT凭证或Topic");
        }
        if (!SecureCredentialStore.save(context, credential)) {
            throw new IOException("MQTT凭证原子保存失败");
        }

        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
        mainHandler.post(() -> callback.onActivated(credential));
    }

    private static boolean hasMqttCredential(ActivationData data) {
        return data != null
                && !safe(data.mqttBrokerUrl).isEmpty()
                && !safe(data.mqttClientId).isEmpty()
                && !safe(data.mqttUsername).isEmpty()
                && !safe(data.mqttPassword).isEmpty();
    }

    private static <T> T requireBusinessSuccess(
            ApiResponse<T> response,
            String action
    ) throws IOException {
        if (response == null) {
            throw new IOException(action + "返回为空");
        }
        if (!response.success || response.code != 200 || response.data == null) {
            throw new IOException(action + "失败：" + safe(response.msg));
        }
        return response.data;
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
                        callback.onSuccess(gson.fromJson(responseBody.string(), responseType));
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
        if (!running.get()) {
            return;
        }
        running.set(false);
        Log.e(TAG, "设备认证失败", error);
        mainHandler.post(() -> callback.onError(error));
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

    private static class DeviceRequest {
        String deviceNo;
        String firmwareVersion;
        String apkVersion;
        String nonce;
        long timestamp;
        String signature;
    }

    private static final class EnrollData {
        boolean accepted;
        String deviceNo;
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
        long deviceId;
        String deviceNo;
        String deviceSn;
        String deviceName;
        String mqttBrokerUrl;
        String mqttClientId;
        String mqttUsername;
        String mqttPassword;
        int heartbeatInterval;
        int keepAliveSeconds;
        int configVersion;
        MqttTopics mqttTopics;
    }

    private static final class MqttTopics {
        String commandSubscribeTopic;
        Map<String, String> reportTopics;
        int qos;
    }

    /** MQTT 连接参数及服务端下发 Topic。 */
    public static final class MqttCredential {
        public String brokerUrl;
        public String clientId;
        public String username;
        public String password;
        public int heartbeatInterval;
        public int keepAliveSeconds;
        public int configVersion;
        public String commandSubscribeTopic;
        public Map<String, String> reportTopics = new HashMap<>();
        public int qos = 1;

        static MqttCredential from(ActivationData data) {
            MqttCredential credential = new MqttCredential();
            credential.brokerUrl = safe(data.mqttBrokerUrl);
            credential.clientId = safe(data.mqttClientId);
            credential.username = safe(data.mqttUsername);
            credential.password = safe(data.mqttPassword);
            credential.heartbeatInterval = data.heartbeatInterval;
            credential.keepAliveSeconds = data.keepAliveSeconds;
            credential.configVersion = data.configVersion;
            if (data.mqttTopics != null) {
                credential.commandSubscribeTopic =
                        safe(data.mqttTopics.commandSubscribeTopic);
                credential.qos = data.mqttTopics.qos <= 0 ? 1 : data.mqttTopics.qos;
                if (data.mqttTopics.reportTopics != null) {
                    credential.reportTopics.putAll(data.mqttTopics.reportTopics);
                }
            }
            return credential;
        }

        public String getReportTopic(String key) {
            String value = reportTopics == null ? null : reportTopics.get(key);
            return safe(value);
        }

        public Map<String, String> getReportTopics() {
            return reportTopics == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(reportTopics);
        }

        public boolean isValid() {
            return !safe(brokerUrl).isEmpty()
                    && !safe(clientId).isEmpty()
                    && !safe(username).isEmpty()
                    && !safe(password).isEmpty()
                    && !safe(commandSubscribeTopic).isEmpty()
                    && !getReportTopic("heartbeat").isEmpty()
                    && !getReportTopic("status").isEmpty()
                    && !getReportTopic("fault").isEmpty()
                    && !getReportTopic("command-result").isEmpty()
                    && !getReportTopic("upgrade-progress").isEmpty()
                    && !getReportTopic("redemption-request").isEmpty()
                    && !getReportTopic("cash-event").isEmpty();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
