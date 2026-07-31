package com.gouzhu.activation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.sdk.SdkCredentialStore;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceActivationResult;
import com.pinball.xiaoda.device.sdk.client.DeviceCredentialManager;
import com.pinball.xiaoda.device.sdk.client.DeviceEnrollResult;
import com.pinball.xiaoda.device.sdk.client.DeviceLifecycleClient;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.security.PrivateKey;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端设备 SDK 生命周期状态机。
 *
 * <p>首次无凭证时执行 enroll -> identity activation；日常启动使用当前 MQTT
 * password 执行 reactivate。凭证丢失时不会自动恢复，必须由平台开启一次性恢复
 * 窗口后显式调用 recoverCredential。</p>
 */
public final class ActivationManager {

    private static final String TAG = "GouzhuActivation";
    private static final long MIN_POLL_DELAY_MS = 10_000L;
    private static final long MAX_POLL_DELAY_MS = 30_000L;
    private static final String PREF_LIFECYCLE = "device_sdk_lifecycle_v1";
    private static final String KEY_ENROLLED = "enrolled";
    private static final String KEY_CLAIM_CODE = "claimCode";
    private static final String KEY_CLAIM_QR = "claimQr";
    private static final String KEY_ACTIVATED_ONCE = "activatedOnce";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final DeviceSdkManager sdkManager;
    private final DeviceLifecycleClient lifecycleClient;
    private final DeviceCredentialManager credentialManager;

    private Runnable pollRunnable;
    private String lastClaimCode = "";
    private String lastQrContent = "";

    public ActivationManager(Context context) {
        this.context = context.getApplicationContext();
        this.sdkManager = DeviceSdkManager.get(this.context);
        this.lifecycleClient = sdkManager.getLifecycleClient();
        this.credentialManager = sdkManager.getCredentialManager();
    }

    public interface Callback {
        void onWaitingClaim(String qrContent, String claimCode);

        void onActivated(MqttCredential credential);

        void onError(Exception error);
    }

    /** 启动首次报到/激活或日常凭证刷新。 */
    public void start(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> runStartup(callback), "购珠机-SDK认证").start();
    }

    /**
     * 平台管理员已开启一次性凭证恢复窗口后，由后台页面显式调用。
     * 恢复成功会使旧 MQTT password 立即失效。
     */
    public void recoverCredential(Callback callback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                DeviceIdentityStore.ensureKeyPair(context);
                DeviceActivationResult recovered = lifecycleClient.recoverCredential(
                        DeviceIdentityStore.getPrivateKey(context),
                        firmwareVersion(),
                        apkVersion()
                );
                finishActivation(recovered, callback);
            } catch (Throwable error) {
                postError(callback, asException(error));
            }
        }, "购珠机-SDK凭证恢复").start();
    }

    public void stop() {
        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    public static MqttCredential loadCredential(Context context) {
        return SdkCredentialStore.get(context).load();
    }

    /** 仅供重新初始化测试使用，同时清除已激活标记。 */
    public static void clearCredential(Context context) {
        Context appContext = context.getApplicationContext();
        SdkCredentialStore.get(appContext).clear();
        appContext.getSharedPreferences(PREF_LIFECYCLE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    public static String exportIdentityPublicKey(Context context) throws Exception {
        return DeviceIdentityStore.getPublicKeyBase64(context);
    }

    private void runStartup(Callback callback) {
        try {
            DeviceIdentityStore.ensureKeyPair(context);
            MqttCredential current = SdkCredentialStore.get(context).load();
            if (current != null) {
                DeviceActivationResult refreshed = lifecycleClient.reactivate(
                        current.getPassword(),
                        firmwareVersion(),
                        apkVersion()
                );
                finishActivation(refreshed, callback);
                return;
            }

            SharedPreferences lifecycle = lifecyclePreferences();
            if (lifecycle.getBoolean(KEY_ACTIVATED_ONCE, false)) {
                throw new IllegalStateException(
                        "本机曾完成激活但MQTT凭证已丢失，请平台开启恢复窗口后执行凭证恢复"
                );
            }
            if (lifecycle.getBoolean(KEY_ENROLLED, false)) {
                lastClaimCode = lifecycle.getString(KEY_CLAIM_CODE, "");
                lastQrContent = lifecycle.getString(KEY_CLAIM_QR, "");
                postWaitingClaim(callback);
                activateWithIdentity(callback, true);
            } else {
                enrollAndActivate(callback);
            }
        } catch (Throwable error) {
            postError(callback, asException(error));
        }
    }

    private void enrollAndActivate(Callback callback) throws Exception {
        PrivateKey privateKey = DeviceIdentityStore.getPrivateKey(context);
        DeviceEnrollResult enroll = lifecycleClient.enroll(
                privateKey,
                firmwareVersion(),
                apkVersion()
        );
        if (!enroll.isAccepted()) {
            throw new IllegalStateException(
                    "设备报到未被平台接受：" + safe(enroll.getMessage())
            );
        }

        lastClaimCode = safe(enroll.getClaimCode());
        lastQrContent = safe(enroll.getClaimQrContent());
        if (!lifecyclePreferences().edit()
                .putBoolean(KEY_ENROLLED, true)
                .putString(KEY_CLAIM_CODE, lastClaimCode)
                .putString(KEY_CLAIM_QR, lastQrContent)
                .commit()) {
            throw new IllegalStateException("设备报到状态保存失败");
        }
        if (!enroll.isClaimed()) {
            postWaitingClaim(callback);
        }
        activateWithIdentity(callback, true);
    }

    private void activateWithIdentity(Callback callback, boolean scheduleOnPending) {
        if (!running.get()) {
            return;
        }

        try {
            DeviceActivationResult activation = lifecycleClient.activateWithIdentity(
                    DeviceIdentityStore.getPrivateKey(context),
                    firmwareVersion(),
                    apkVersion()
            );
            if (activation.isClaimed() && activation.hasMqttCredential()) {
                finishActivation(activation, callback);
                return;
            }

            postWaitingClaim(callback);
            if (scheduleOnPending) {
                scheduleIdentityActivation(callback);
            }
        } catch (Throwable error) {
            if (scheduleOnPending && running.get()) {
                Log.w(TAG, "等待认领/首次激活暂时失败，将继续轮询", error);
                scheduleIdentityActivation(callback);
            } else {
                postError(callback, asException(error));
            }
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
        pollRunnable = () -> new Thread(
                () -> activateWithIdentity(callback, true),
                "购珠机-SDK认领轮询"
        ).start();
        mainHandler.postDelayed(pollRunnable, delay);
    }

    private void finishActivation(
            DeviceActivationResult activation,
            Callback callback
    ) {
        if (activation == null
                || !activation.isClaimed()
                || !activation.hasMqttCredential()) {
            throw new IllegalStateException("激活响应未包含完整MQTT凭证");
        }

        // SDK 先校验完整字段和 Topic，再由 CredentialStore 原子替换整组快照。
        MqttCredential credential = credentialManager.replaceFrom(activation);
        if (!lifecyclePreferences().edit()
                .putBoolean(KEY_ACTIVATED_ONCE, true)
                .remove(KEY_ENROLLED)
                .remove(KEY_CLAIM_CODE)
                .remove(KEY_CLAIM_QR)
                .commit()) {
            throw new IllegalStateException("设备激活状态保存失败");
        }
        running.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
        mainHandler.post(() -> callback.onActivated(credential));
    }

    private void postWaitingClaim(Callback callback) {
        mainHandler.post(() -> callback.onWaitingClaim(lastQrContent, lastClaimCode));
    }

    private void postError(Callback callback, Exception error) {
        if (!running.getAndSet(false)) {
            return;
        }
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
        Log.e(TAG, "设备SDK认证失败", error);
        mainHandler.post(() -> callback.onError(error));
    }

    private SharedPreferences lifecyclePreferences() {
        return context.getSharedPreferences(PREF_LIFECYCLE, Context.MODE_PRIVATE);
    }

    private String firmwareVersion() {
        return DeviceUtil.formatBoardVersion(
                new DeviceCommandStore(context).getBoardVersion()
        );
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
}
