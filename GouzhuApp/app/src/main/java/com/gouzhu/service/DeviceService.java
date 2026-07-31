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

import com.gouzhu.AppConfig;
import com.gouzhu.R;
import com.gouzhu.activation.ActivationLogStore;
import com.gouzhu.activation.ActivationManager;
import com.gouzhu.mqtt.DeviceCommandManager;
import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.upgrade.UpgradeManager;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * com.gouzhu 单应用后台服务。
 *
 * <p>统一管理 ttyS5、网络、服务端 SDK 生命周期、MQTT 和升级，不读取或守护
 * 旧包名、旧认证流程或旧凭证。</p>
 */
public class DeviceService extends Service {

    private static final String TAG = "GouzhuService";
    private static final long RETRY_DELAY_MS = 30_000L;

    private final AtomicBoolean initializing = new AtomicBoolean(false);
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
        broadcastStatus("service", "正在连接控制板");
        SerialManager.get(this).open();
        DeviceCommandManager.get(this).start();

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
                String text = claimCode == null || claimCode.trim().isEmpty()
                        ? "等待平台完成设备认领"
                        : "等待设备认领，认领码：" + claimCode;
                broadcastStatus("activation", text);
                updateNotification(text);
            }

            @Override
            public void onActivated(MqttCredential credential) {
                broadcastStatus("activation", "设备SDK认证成功");
                updateNotification("设备认证成功");
                connectMqtt(credential);
            }

            @Override
            public void onError(Exception error) {
                ActivationLogStore.appendError(this, "设备SDK认证失败", error);
                broadcastStatus("activation", "设备认证失败：" + messageOf(error));
                updateNotification("设备认证失败");
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
        channel.setDescription("维持购珠机串口、网络、SDK认证、MQTT和升级任务");
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
        SerialManager.get(this).close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
