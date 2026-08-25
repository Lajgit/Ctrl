package com.chuzhu.activation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.chuzhu.AppConfig;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.device.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceActivationResult;
import com.pinball.xiaoda.device.sdk.client.DeviceCredentialManager;
import com.pinball.xiaoda.device.sdk.client.DeviceEnrollResult;
import com.pinball.xiaoda.device.sdk.client.DeviceLifecycleClient;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.security.KeyPair;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存珠机注册激活仓库。
 */
public final class ActivationRepository {

    private static final long MIN_POLL_DELAY_MS = 10_000L;
    private static final long MAX_POLL_DELAY_MS = 30_000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ActivationStore activationStore;
    private final SdkCredentialStore credentialStore;
    private final DeviceLifecycleClient lifecycleClient;
    private final DeviceCredentialManager credentialManager;

    private Runnable pollRunnable;
    private String lastClaimCode = "";
    private String lastClaimQr = "";

    public ActivationRepository(Context context) {
        this.context = context.getApplicationContext();
        activationStore = new ActivationStore(this.context);
        credentialStore = SdkCredentialStore.get(this.context);
        DeviceSdkConfig config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(DeviceUtil.requireDeviceNo(this.context))
                .connectTimeoutMillis(10_000)
                .readTimeoutMillis(15_000)
                .build();
        lifecycleClient = new DeviceLifecycleClient(
                config,
                new HttpUrlConnectionTransport(config)
        );
        credentialManager = new DeviceCredentialManager(credentialStore);
    }

    public void start(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> runStartup(callback), "存珠机SDK注册激活").start();
    }

    public void stop() {
        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    public MqttCredential loadCredential() {
        return credentialStore.load();
    }

    public static String exportIdentityPublicKey(Context context) throws Exception {
        return DeviceIdentityManager.getPublicKeyBase64(context);
    }

    private void runStartup(Callback callback) {
        try {
            DeviceIdentityManager.ensureKeyPair(context);
            MqttCredential current = credentialStore.load();
            if (current != null) {
                DeviceActivationResult refreshed = lifecycleClient.reactivate(
                        current.getPassword(),
                        firmwareVersion(),
                        apkVersion()
                );
                finishActivation(refreshed, callback);
                return;
            }
            enrollAndActivate(callback);
        } catch (Throwable error) {
            postError(callback, asException(error));
        }
    }

    private void enrollAndActivate(Callback callback) throws Exception {
        KeyPair keyPair = DeviceIdentityManager.getKeyPair(context);
        /*
         * 当前 0.3.0 SDK 的 DeviceLifecycleClient.enroll 公开签名没有 deviceType 入参。
         * 本地固定存珠机 deviceType=3，实际 HTTP enroll 是否携带设备类型需正式 SDK 确认。
         */
        DeviceEnrollResult enroll = lifecycleClient.enroll(
                keyPair,
                firmwareVersion(),
                apkVersion()
        );
        if (!enroll.isAccepted()) {
            throw new IllegalStateException("设备 enroll 未被平台接受：" + safe(enroll.getMessage()));
        }
        lastClaimCode = safe(enroll.getClaimCode());
        lastClaimQr = safe(enroll.getClaimQrContent());
        activationStore.saveClaim(lastClaimCode, lastClaimQr);
        if (!enroll.isClaimed()) {
            postWaiting(callback);
        }
        activateWithIdentity(callback, true);
    }

    private void activateWithIdentity(Callback callback, boolean scheduleOnPending) {
        if (!running.get()) {
            return;
        }
        try {
            DeviceActivationResult activation = lifecycleClient.activateWithIdentity(
                    DeviceIdentityManager.getPrivateKey(context),
                    firmwareVersion(),
                    apkVersion()
            );
            if (activation.isClaimed() && activation.hasMqttCredential()) {
                finishActivation(activation, callback);
                return;
            }
            postWaiting(callback);
            if (scheduleOnPending) {
                schedulePoll(callback);
            }
        } catch (Throwable error) {
            if (scheduleOnPending && running.get()) {
                postWaiting(callback);
                schedulePoll(callback);
            } else {
                postError(callback, asException(error));
            }
        }
    }

    private void schedulePoll(Callback callback) {
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
        pollRunnable = () -> new Thread(
                () -> activateWithIdentity(callback, true),
                "存珠机激活轮询"
        ).start();
        mainHandler.postDelayed(pollRunnable, delay);
    }

    private void finishActivation(DeviceActivationResult activation, Callback callback) {
        if (activation == null || !activation.isClaimed() || !activation.hasMqttCredential()) {
            throw new IllegalStateException("激活响应未包含完整 MQTT 凭证");
        }
        MqttCredential credential = credentialManager.replaceFrom(activation);
        activationStore.markActivated(credential.getDeviceNo());
        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
        mainHandler.post(() -> callback.onActivated(credential));
    }

    private void postWaiting(Callback callback) {
        mainHandler.post(() -> callback.onWaitingClaim(lastClaimQr, lastClaimCode));
    }

    private void postError(Callback callback, Exception error) {
        activationStore.saveError(error.getMessage());
        if (!running.getAndSet(false)) {
            return;
        }
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
        mainHandler.post(() -> callback.onError(error));
    }

    private String firmwareVersion() {
        return DeviceUtil.getBoardVersion();
    }

    private String apkVersion() {
        return DeviceUtil.getAppVersion(context);
    }

    private static Exception asException(Throwable error) {
        if (error instanceof Exception) {
            return (Exception) error;
        }
        return new IllegalStateException(error == null ? "未知错误" : error.getMessage(), error);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public interface Callback {
        void onWaitingClaim(String qrContent, String claimCode);

        void onActivated(MqttCredential credential);

        void onError(Exception error);
    }
}
