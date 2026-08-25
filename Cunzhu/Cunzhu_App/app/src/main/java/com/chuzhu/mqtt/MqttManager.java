package com.chuzhu.mqtt;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.data.PendingOutboxStore;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存珠机 MQTT 连接管理。
 *
 * <p>日志中的 “no NetworkModule installed for scheme tcp” 来自 SDK 0.3.0 内置
 * Paho 的 ServiceLoader 网络模块注册缺失。本类改为使用宿主 Paho MqttClient 连接，
 * 避免 tcp:// Broker 初始化失败；命令处理、状态上报和 SDK 数据模型保持不变。</p>
 */
public final class MqttManager {

    private static final String TAG = "CunzhuMqtt";
    private static final int MQTT_MAX_INFLIGHT = 32;
    private static final int MQTT_INFLIGHT_HIGH_WATERMARK = 28;
    private static final int MQTT_REASON_CODE_MAX_INFLIGHT = 32202;
    private static volatile MqttManager instance;

    private final Context context;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private MqttClient client;
    private MqttCredential credential;
    private ScheduledFuture<?> heartbeatTask;

    private MqttManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static MqttManager get(Context context) {
        if (instance == null) {
            synchronized (MqttManager.class) {
                if (instance == null) {
                    instance = new MqttManager(context);
                }
            }
        }
        return instance;
    }

    public void connect(MqttCredential credential) {
        if (credential == null) {
            broadcastStatus("mqtt", "MQTT 凭证为空");
            return;
        }
        this.credential = credential;
        executor.execute(this::connectInternal);
    }

    private synchronized void connectInternal() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            MqttCredential current = credential;
            if (current == null) {
                broadcastStatus("mqtt", "MQTT 凭证不可用");
                return;
            }
            if (client != null && client.isConnected()) {
                afterConnected(current);
                return;
            }

            closeOldClient();
            broadcastStatus("mqtt", "正在连接 MQTT：" + current.getBrokerUrl());
            client = new MqttClient(
                    current.getBrokerUrl(),
                    current.getClientId(),
                    new MemoryPersistence()
            );

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(Math.max(20, current.getHeartbeatIntervalSeconds()));
            options.setMaxInflight(MQTT_MAX_INFLIGHT);
            options.setUserName(current.getUsername());
            String password = current.getPassword();
            if (password != null) {
                options.setPassword(password.toCharArray());
            }

            client.setCallback(new CallbackImpl());
            client.connect(options);
            afterConnected(current);
        } catch (Throwable error) {
            Log.e(TAG, "MQTT 连接失败", error);
            broadcastStatus("mqtt", "MQTT 连接失败：" + messageOf(error));
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    private void afterConnected(MqttCredential current) {
        ensureSubscribed(current);
        broadcastStatus("mqtt", "MQTT 已连接");
        reconnectScheduled.set(false);
        DepositCommandHandler.get(context).recoverUnfinishedSession();
        flushPending();
        new HeartbeatReporter(context).report();
        new DeviceStatusReporter(context).report();
        startHeartbeatLoop();
    }

    public synchronized boolean isConnected() {
        return client != null && client.isConnected();
    }

    public synchronized void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        closeOldClient();
        broadcastStatus("mqtt", "MQTT 已断开");
    }

    public boolean publishReport(String key, String payload) {
        String topic = getReportTopic(key);
        if (topic.isEmpty()) {
            Log.e(TAG, "缺少上报 Topic：" + key);
            return false;
        }
        return publish(topic, payload);
    }

    public boolean reportCommandResult(String payload) {
        return publishReport("command-result", payload);
    }

    public String getReportTopic(String key) {
        MqttCredential current = credential;
        Map<String, String> topics = current == null ? null : current.getReportTopics();
        String topic = topics == null ? null : topics.get(key);
        return topic == null ? "" : topic;
    }

    public synchronized boolean publish(String topic, String payload) {
        if (topic == null || topic.isEmpty() || client == null || !client.isConnected()) {
            return false;
        }
        int qos = credential == null ? 1 : credential.getQos();
        int normalizedQos = Math.max(0, Math.min(2, qos));
        try {
            if (normalizedQos > 0) {
                IMqttDeliveryToken[] pendingTokens = client.getPendingDeliveryTokens();
                int pendingCount = pendingTokens == null ? 0 : pendingTokens.length;
                if (pendingCount >= MQTT_INFLIGHT_HIGH_WATERMARK) {
                    Log.w(TAG, "MQTT 在途消息过多，稍后重放 outbox：pending=" + pendingCount);
                    return false;
                }
            }
            MqttMessage message = new MqttMessage(
                    payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8)
            );
            message.setQos(normalizedQos);
            message.setRetained(false);
            client.publish(topic, message);
            return true;
        } catch (MqttException error) {
            if (error.getReasonCode() == MQTT_REASON_CODE_MAX_INFLIGHT) {
                Log.w(TAG, "MQTT 在途窗口已满，等待下次心跳重放 outbox");
            } else {
                Log.e(TAG, "MQTT 上报失败 topic=" + topic, error);
            }
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "MQTT 上报失败 topic=" + topic, error);
            return false;
        }
    }

    public void flushPending() {
        if (!isConnected()) {
            return;
        }
        for (PendingOutboxStore.Item item : new PendingOutboxStore(context).list()) {
            publish(item.topic, item.payload);
        }
    }

    private synchronized void ensureSubscribed(MqttCredential current) {
        try {
            if (client != null && client.isConnected() && current != null) {
                client.subscribe(current.getCommandSubscribeTopic(), current.getQos());
            }
        } catch (Throwable error) {
            Log.e(TAG, "MQTT 订阅失败", error);
            broadcastStatus("mqtt", "MQTT 订阅失败：" + messageOf(error));
        }
    }

    private synchronized void startHeartbeatLoop() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            return;
        }
        int interval = credential == null
                ? AppConfig.DEFAULT_HEARTBEAT_SECONDS
                : credential.getHeartbeatIntervalSeconds();
        heartbeatTask = executor.scheduleWithFixedDelay(
                () -> {
                    new HeartbeatReporter(context).report();
                    new DeviceStatusReporter(context).report();
                    flushPending();
                },
                interval,
                interval,
                TimeUnit.SECONDS
        );
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            reconnectScheduled.set(false);
            connectInternal();
        }, 5, TimeUnit.SECONDS);
    }

    private synchronized void closeOldClient() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (Throwable ignored) {
        }
        try {
            client.close();
        } catch (Throwable ignored) {
        }
        client = null;
    }

    private void broadcastStatus(String key, String value) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(context.getPackageName());
        intent.putExtra("key", key);
        intent.putExtra("value", value);
        context.sendBroadcast(intent);
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

    private final class CallbackImpl implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            executor.execute(() -> {
                MqttCredential current = credential;
                if (current != null) {
                    afterConnected(current);
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            broadcastStatus("mqtt", "MQTT 已断开：" + messageOf(cause));
            scheduleReconnect();
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            byte[] payload = message == null ? new byte[0] : message.getPayload();
            DepositCommandHandler.get(context).handle(topic, payload);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 第一阶段 outbox 仅做重放保底，业务清理由 command_result_ack 接入后再实现。
        }
    }
}
