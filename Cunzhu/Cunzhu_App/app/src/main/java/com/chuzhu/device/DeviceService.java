package com.chuzhu.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.chuzhu.AppConfig;
import com.chuzhu.R;
import com.chuzhu.activation.ActivationActivity;
import com.chuzhu.activation.ActivationRepository;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.mqtt.MqttManager;
import com.chuzhu.serial.BoardSerialPort;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存珠机后台服务，统一启动串口、注册激活和 MQTT。
 */
public final class DeviceService extends Service {

    private static final long RETRY_DELAY_MS = 30_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private ActivationRepository activationRepository;
    private boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(
                AppConfig.SERVICE_NOTIFICATION_ID,
                buildNotification("存珠机服务正在启动")
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
            } finally {
                initializing.set(false);
            }
        }, "存珠机设备初始化").start();
    }

    private void initializeDevice() {
        broadcastStatus("service", "正在初始化存珠机");
        DeviceStateRepository.get(this).reconcileFromStoredSession();
        BoardSerialPort.get(this).open();
        startActivation();
    }

    private void startActivation() {
        if (activationRepository != null) {
            activationRepository.stop();
        }
        activationRepository = new ActivationRepository(this);
        MqttCredential storedCredential = activationRepository.loadCredential();
        if (storedCredential != null && new ActivationStore(this).isActivated()) {
            broadcastStatus("activation", "已检测到本地激活凭证，开始连接 MQTT");
            connectMqtt(storedCredential);
            return;
        }
        broadcastStatus("activation", "正在执行设备注册激活");
        activationRepository.start(new ActivationRepository.Callback() {
            @Override
            public void onWaitingClaim(String qrContent, String claimCode) {
                broadcastActivation("等待平台认领设备", qrContent, claimCode, null);
                launchActivationActivity(qrContent, claimCode, "等待平台认领设备", null);
            }

            @Override
            public void onActivated(MqttCredential credential) {
                broadcastActivation("设备激活成功", null, null, null);
                connectMqtt(credential);
            }

            @Override
            public void onError(Exception error) {
                String message = messageOf(error);
                broadcastActivation("设备激活失败：" + message, null, null, message);
                launchActivationActivity(null, null, "设备激活失败", message);
                scheduleRetry();
            }
        });
    }

    private void connectMqtt(MqttCredential credential) {
        MqttManager.get(this).connect(credential);
        broadcastStatus("service", "存珠机设备服务运行中");
        updateNotification("存珠机设备服务运行中");
    }

    private void scheduleRetry() {
        if (destroyed) {
            return;
        }
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private final Runnable retryRunnable = this::startInitialization;

    private void launchActivationActivity(
            String qrContent,
            String claimCode,
            String status,
            String error
    ) {
        Intent intent = new Intent(this, ActivationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(ActivationActivity.EXTRA_CLAIM_QR_CONTENT, qrContent);
        intent.putExtra(ActivationActivity.EXTRA_CLAIM_CODE, claimCode);
        intent.putExtra(ActivationActivity.EXTRA_STATUS, status);
        intent.putExtra(ActivationActivity.EXTRA_ERROR, error);
        startActivity(intent);
    }

    private void broadcastActivation(
            String status,
            String qrContent,
            String claimCode,
            String error
    ) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("key", "activation");
        intent.putExtra("value", status);
        intent.putExtra(ActivationActivity.EXTRA_CLAIM_QR_CONTENT, qrContent);
        intent.putExtra(ActivationActivity.EXTRA_CLAIM_CODE, claimCode);
        intent.putExtra(ActivationActivity.EXTRA_ERROR, error);
        sendBroadcast(intent);
    }

    private void broadcastStatus(String key, String value) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("key", key);
        intent.putExtra("value", value);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                AppConfig.SERVICE_CHANNEL_ID,
                "存珠机设备服务",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, AppConfig.SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
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

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (activationRepository != null) {
            activationRepository.stop();
        }
        MqttManager.get(this).close();
        BoardSerialPort.get(this).close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
