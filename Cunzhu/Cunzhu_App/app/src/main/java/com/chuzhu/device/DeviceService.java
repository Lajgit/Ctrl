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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.chuzhu.AppConfig;
import com.chuzhu.R;
import com.chuzhu.activation.ActivationActivity;
import com.chuzhu.activation.ActivationRepository;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.mqtt.MqttManager;
import com.chuzhu.network.NetworkStartupManager;
import com.chuzhu.serial.BoardSerialPort;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存珠机后台服务，统一启动串口、联网门禁、注册激活和 MQTT。
 */
public final class DeviceService extends Service {

    private static final String TAG = "CunzhuService";
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final long NETWORK_RETRY_DELAY_MS = 10_000L;
    private static final int SAVED_WIFI_WAIT_SECONDS = 15;

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

        /*
         * P0：设备激活/重新激活之前必须先确认真正可访问互联网。
         * 有保存 WiFi 时优先自动打开并等待系统重连；失败后只通知前台打开 WiFi，
         * 不能在无网络状态直接调用 enroll/reactivate 造成长时间超时和误报激活失败。
         */
        NetworkStartupManager network = new NetworkStartupManager(this);
        NetworkStartupManager.PrepareResult result =
                network.prepareBeforeActivation(SAVED_WIFI_WAIT_SECONDS);
        if (!result.isOnline()) {
            Log.w(TAG, "激活前联网检查未通过：" + result.message);
            broadcastStatus("network", result.message);
            broadcastNetworkRequired(result.message, result.needsPermission());
            updateNotification("等待 WiFi 联网");
            scheduleRetry(NETWORK_RETRY_DELAY_MS);
            return;
        }

        broadcastStatus("network", result.message);
        startActivation();
    }

    private void startActivation() {
        if (activationRepository != null) {
            activationRepository.stop();
        }
        activationRepository = new ActivationRepository(this);
        MqttCredential storedCredential = activationRepository.loadCredential();
        ActivationStore activationStore = new ActivationStore(this);
        if (storedCredential != null && activationStore.isActivated()) {
            /*
             * MQTT 对接文档要求日常启动必须先 reactivate 刷新正式 MQTT 凭证，
             * 不能直接长期复用本地旧 broker/clientId/password。
             */
            broadcastStatus("activation", "检测到本地凭证，正在重新激活刷新 MQTT 凭证");
            Log.i(TAG, "检测到本地 MQTT 凭证，先执行 reactivate 刷新后再连接 MQTT");
        } else {
            broadcastStatus("activation", "正在执行设备注册激活");
        }
        activationRepository.start(new ActivationRepository.Callback() {
            @Override
            public void onWaitingClaim(String qrContent, String claimCode) {
                broadcastActivation("等待平台认领设备", qrContent, claimCode, null);
                launchActivationActivity(qrContent, claimCode, "等待平台认领设备", null);
            }

            @Override
            public void onActivated(MqttCredential credential) {
                broadcastActivation("设备激活成功，开始连接 MQTT", null, null, null);
                connectMqtt(credential);
            }

            @Override
            public void onError(Exception error) {
                /* 网络在激活过程中掉线时，不显示成身份/平台激活失败。 */
                NetworkStartupManager network = new NetworkStartupManager(DeviceService.this);
                if (!network.hasValidatedInternet()) {
                    String networkMessage = "设备网络已断开，请重新连接 WiFi";
                    Log.w(TAG, networkMessage, error);
                    broadcastStatus("network", networkMessage);
                    broadcastNetworkRequired(networkMessage, network.needsNearbyWifiPermission());
                    updateNotification("等待 WiFi 联网");
                    scheduleRetry(NETWORK_RETRY_DELAY_MS);
                    return;
                }

                String message = messageOf(error);
                broadcastActivation("设备激活失败：" + message, null, null, message);
                launchActivationActivity(null, null, "设备激活失败", message);
                scheduleRetry(RETRY_DELAY_MS);
            }
        });
    }

    private void connectMqtt(MqttCredential credential) {
        MqttManager.get(this).connect(credential);
        broadcastStatus("service", "存珠机设备服务运行中");
        updateNotification("存珠机设备服务运行中");
    }

    private void scheduleRetry(long delayMillis) {
        if (destroyed) {
            return;
        }
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, Math.max(1000L, delayMillis));
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

    private void broadcastNetworkRequired(String reason, boolean permissionRequired) {
        Intent intent = new Intent(AppConfig.ACTION_NETWORK_REQUIRED);
        intent.setPackage(getPackageName());
        intent.putExtra("reason", reason);
        intent.putExtra("permissionRequired", permissionRequired);
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
        Throwable cursor = error;
        String message = "";
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().trim().isEmpty()) {
                message = cursor.getMessage().trim();
            }
            cursor = cursor.getCause();
        }
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
