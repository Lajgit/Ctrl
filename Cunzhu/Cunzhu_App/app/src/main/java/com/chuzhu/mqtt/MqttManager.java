package com.chuzhu.mqtt;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.data.PendingOutboxStore;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;
import com.pinball.xiaoda.device.sdk.mqtt.paho.PahoMqttTransport;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存珠机 MQTT 连接管理。
 */
public final class MqttManager {

    private static final String TAG = "CunzhuMqtt";
    private static volatile MqttManager instance;

    private final Context context;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final PahoMqttTransport transport = new PahoMqttTransport();
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

    private void connectInternal() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            transport.connect(credential, new TransportListener());
            transport.subscribe(credential.getCommandSubscribeTopic(), credential.getQos());
            broadcastStatus("mqtt", "MQTT 已连接");
            DepositCommandHandler.get(context).recoverUnfinishedSession();
            flushPending();
            new HeartbeatReporter(context).report();
            new DeviceStatusReporter(context).report();
            startHeartbeatLoop();
        } catch (Throwable error) {
            Log.e(TAG, "MQTT 连接失败", error);
            broadcastStatus("mqtt", "MQTT 连接失败：" + messageOf(error));
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    public synchronized void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        try {
            transport.disconnect();
        } catch (Throwable ignored) {
        }
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

    public boolean publish(String topic, String payload) {
        if (topic == null || topic.isEmpty() || !isConnected()) {
            return false;
        }
        try {
            int qos = credential == null ? 1 : credential.getQos();
            transport.publish(
                    topic,
                    payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8),
                    qos,
                    false
            );
            return true;
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

    private void startHeartbeatLoop() {
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
        executor.schedule(this::connectInternal, 5, TimeUnit.SECONDS);
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
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private final class TransportListener implements com.pinball.xiaoda.device.sdk.client.MqttTransport.MessageListener {
        @Override
        public void onMessage(String topic, byte[] payload) {
            DepositCommandHandler.get(context).handle(topic, payload);
        }

        @Override
        public void onConnectionLost(Throwable cause) {
            broadcastStatus("mqtt", "MQTT 已断开：" + messageOf(cause));
            scheduleReconnect();
        }
    }
}
