package com.gouzhu.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.gouzhu.ActivationActivity;
import com.gouzhu.AppConfig;
import com.gouzhu.R;
import com.gouzhu.SplashActivity;
import com.gouzhu.activation.ActivationLogStore;
import com.gouzhu.activation.ActivationManager;
import com.gouzhu.mqtt.DeviceCommandManager;
import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.scanner.ReverseScannerManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.upgrade.UpgradeManager;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * com.gouzhu 单应用后台服务。
 *
 * <p>统一管理 ttyS5 控制板、ttyS6 反扫模块、网络、服务端 SDK 生命周期、
 * MQTT 和升级，不读取或守护旧包名、旧认证流程或旧凭证。</p>
 */
public class DeviceService extends Service {

    private static final String TAG = "GouzhuService";
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final int MAX_ERROR_DEPTH = 6;

    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private final AtomicBoolean activationScreenLaunchRequested = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivationManager activationManager;
    private volatile boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        ActivationLogStore.append(this, "服务", "设备服务进程已创建");
        startForeground(
                AppConfig.SERVICE_NOTIFICATION_ID,
                buildNotification("设备服务正在启动")
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInitialization();
        return START_STICKY;
    }

    private void startInitialization() {
        if (destroyed || !initializing.compareAndSet(false, true)) {
            return;
        }

        new Thread(() -> {
            try {
                initializeDevice();
            } catch (Throwable error) {
                Log.e(TAG, "设备服务初始化异常", error);
                ActivationLogStore.appendError(this, "服务初始化失败", error);
                broadcastStatus("service", "设备服务初始化异常：" + messageOf(error));
                scheduleRetry();
            } finally {
                initializing.set(false);
            }
        }, "购珠机-设备初始化").start();
    }

    private void initializeDevice() {
        broadcastStatus("service", "正在连接控制板和反扫模块");
        SerialManager.get(this).open();
        DeviceCommandManager.get(this).start();

        // 反扫串口独立于 ttyS5。打开失败只影响扫码功能，不阻断设备联网和激活。
        ReverseScannerManager.get(this).open();

        if (!WifiSupport.hasSavedWifi(this)) {
            broadcastStatus("network", "尚未配置WiFi");
            updateNotification("等待配置WiFi");
            return;
        }

        if (!WifiSupport.isInternetConnected(this)) {
            broadcastStatus("network", "正在连接已保存的WiFi");
            WifiSupport.connectSavedWifi(this);
        }

        if (!WifiSupport.waitForInternet(this, 30)) {
            broadcastStatus("network", "网络连接失败，稍后重试");
            updateNotification("网络连接失败");
            scheduleRetry();
            return;
        }

        broadcastStatus("network", "网络已连接：" + WifiSupport.getCurrentSsid(this));
        updateNotification("网络已连接");
        startActivation();
    }

    private void startActivation() {
        if (activationManager != null) {
            activationManager.stop();
        }

        MqttCredential storedCredential = ActivationManager.loadCredential(this);
        if (storedCredential == null) {
            ActivationLogStore.append(
                    this,
                    "认证入口",
                    "未检测到完整MQTT凭证，进入首次报到/身份激活流程"
            );
        } else {
            ActivationLogStore.append(
                    this,
                    "认证入口",
                    "检测到完整MQTT凭证，进入日常reactivate流程"
            );
        }

        broadcastStatus("activation", "正在执行设备SDK认证");
        activationManager = new ActivationManager(this);
        activationManager.start(new ActivationManager.Callback() {
            @Override
            public void onWaitingClaim(String qrContent, String claimCode) {
                String text = "等待设备认领，请扫描屏幕二维码";
                broadcastActivationWaiting(text, qrContent, claimCode);
                updateNotification("等待扫码认领设备");
                launchActivationScreen(
                        qrContent,
                        claimCode,
                        text,
                        false,
                        null,
                        null
                );
            }

            @Override
            public void onActivated(MqttCredential credential) {
                activationScreenLaunchRequested.set(false);
                broadcastStatus("activation", "设备SDK认证成功");
                updateNotification("设备认证成功");
                connectMqtt(credential);
            }

            @Override
            public void onError(Exception error) {
                ActivationLogStore.appendError(
                        DeviceService.this,
                        "设备SDK认证失败",
                        error
                );

                String errorMessage = messageOf(error);
                String errorDetail = errorDetailOf(error);
                if (isIdentityNotRegistered(error)) {
                    showIdentityRegistrationRequired(errorMessage, errorDetail);
                } else {
                    String status = "设备认证失败：" + errorMessage;
                    broadcastActivationError(status, errorDetail);
                    updateNotification("设备认证失败");
                    launchActivationScreen(
                            null,
                            null,
                            status,
                            false,
                            null,
                            errorDetail
                    );
                }
                scheduleRetry();
            }
        });
    }

    private void connectMqtt(MqttCredential credential) {
        ActivationLogStore.append(this, "MQTT", "认证凭证已保存，开始连接服务端MQTT");
        MqttManager.get(this).connect(credential);
        UpgradeManager.get(this).resumePendingResult();
        broadcastStatus("service", "购珠机设备服务运行中");
        updateNotification("购珠机设备服务运行中");
    }

    private void scheduleRetry() {
        if (destroyed) {
            return;
        }
        ActivationLogStore.append(this, "重试", "30秒后重新执行设备初始化和认证");
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private final Runnable retryRunnable = this::startInitialization;

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                AppConfig.SERVICE_CHANNEL_ID,
                "购珠机设备服务",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("维持控制板、反扫串口、网络、SDK认证、MQTT和升级任务");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, AppConfig.SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_status)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(AppConfig.SERVICE_NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void broadcastActivationWaiting(
            String value,
            String qrContent,
            String claimCode
    ) {
        // 调试日志只记录流程状态，不记录二维码原文和一次性认领码。
        ActivationLogStore.append(this, "注册/激活", value);

        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("key", "activation");
        intent.putExtra("value", value);
        intent.putExtra(ActivationActivity.EXTRA_CLAIM_QR_CONTENT, qrContent);
        intent.putExtra(ActivationActivity.EXTRA_CLAIM_CODE, claimCode);
        sendBroadcast(intent);
    }

    private void showIdentityRegistrationRequired(
            String errorMessage,
            String errorDetail
    ) {
        String status = "首次身份自动登记未成功，服务端仍返回“设备身份未登记”";
        String publicKey = "";
        try {
            publicKey = ActivationManager.exportIdentityPublicKey(this);
        } catch (Throwable keyError) {
            Log.e(TAG, "读取设备身份公钥失败", keyError);
            ActivationLogStore.appendError(this, "读取设备身份公钥失败", keyError);
        }

        ActivationLogStore.append(
                this,
                "注册/激活",
                status + "；请检查服务端自动登记开关、服务端版本和请求环境；服务端错误："
                        + errorMessage
        );
        broadcastIdentityRegistrationRequired(status, publicKey, errorDetail);
        updateNotification("首次身份自动登记失败");
        launchActivationScreen(
                null,
                null,
                status,
                true,
                publicKey,
                errorDetail
        );
    }

    private void broadcastIdentityRegistrationRequired(
            String value,
            String publicKey,
            String errorDetail
    ) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("key", "activation");
        intent.putExtra("value", value);
        intent.putExtra(ActivationActivity.EXTRA_IDENTITY_REGISTRATION_REQUIRED, true);
        intent.putExtra(ActivationActivity.EXTRA_IDENTITY_PUBLIC_KEY, publicKey);
        intent.putExtra(ActivationActivity.EXTRA_ACTIVATION_ERROR_DETAIL, errorDetail);
        sendBroadcast(intent);
    }

    private void broadcastActivationError(String value, String errorDetail) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("key", "activation");
        intent.putExtra("value", value);
        intent.putExtra(ActivationActivity.EXTRA_ACTIVATION_ERROR_DETAIL, errorDetail);
        sendBroadcast(intent);
    }

    private void launchActivationScreen(
            String qrContent,
            String claimCode,
            String status,
            boolean identityRegistrationRequired,
            String identityPublicKey,
            String errorDetail
    ) {
        // 启动加载页正在显示时，由加载页在最短5秒后接管界面跳转。
        if (SplashActivity.isStartupVisible()) {
            return;
        }
        if (!activationScreenLaunchRequested.compareAndSet(false, true)) {
            return;
        }

        try {
            Intent intent = new Intent(this, ActivationActivity.class);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            intent.putExtra(ActivationActivity.EXTRA_CLAIM_QR_CONTENT, qrContent);
            intent.putExtra(ActivationActivity.EXTRA_CLAIM_CODE, claimCode);
            intent.putExtra(ActivationActivity.EXTRA_ACTIVATION_STATUS, status);
            intent.putExtra(
                    ActivationActivity.EXTRA_IDENTITY_REGISTRATION_REQUIRED,
                    identityRegistrationRequired
            );
            intent.putExtra(
                    ActivationActivity.EXTRA_IDENTITY_PUBLIC_KEY,
                    identityPublicKey
            );
            intent.putExtra(
                    ActivationActivity.EXTRA_ACTIVATION_ERROR_DETAIL,
                    errorDetail
            );
            startActivity(intent);
        } catch (Throwable error) {
            activationScreenLaunchRequested.set(false);
            Log.e(TAG, "打开设备注册激活界面失败", error);
            ActivationLogStore.appendError(this, "打开注册激活界面失败", error);
        }
    }

    private void broadcastStatus(String key, String value) {
        if ("activation".equals(key)
                || "network".equals(key)
                || "mqtt".equals(key)
                || "service".equals(key)) {
            ActivationLogStore.append(this, stageName(key), value);
        }

        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("key", key);
        intent.putExtra("value", value);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        ActivationLogStore.append(this, "服务", "设备服务正在停止");
        mainHandler.removeCallbacksAndMessages(null);
        if (activationManager != null) {
            activationManager.stop();
        }
        DeviceCommandManager.get(this).stop();
        MqttManager.get(this).close();
        ReverseScannerManager.get(this).close();
        SerialManager.get(this).close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static boolean isIdentityNotRegistered(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("设备身份未登记")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String errorDetailOf(Throwable error) {
        if (error == null) {
            return "未知异常";
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_ERROR_DEPTH) {
            if (depth > 0) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                builder.append(": ").append(message.trim());
            }
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    private static String stageName(String key) {
        if ("activation".equals(key)) {
            return "注册/激活";
        }
        if ("network".equals(key)) {
            return "网络";
        }
        if ("mqtt".equals(key)) {
            return "MQTT";
        }
        return "服务";
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
}
