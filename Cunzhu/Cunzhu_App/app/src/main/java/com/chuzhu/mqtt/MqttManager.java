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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存珠机 MQTT 连接管理。
 *
 * <p>SDK 0.3.0 内置 Paho 在部分 Android 环境会出现 tcp 网络模块注册失败，
 * 因此这里使用宿主 Paho 负责传输层，继续复用 SDK 下发的 MqttCredential。</p>
 */
public final class MqttManager {

    private static final String TAG = "CunzhuMqtt";
    private static final int MQTT_MAX_INFLIGHT = 32;
    private static final int MQTT_INFLIGHT_HIGH_WATERMARK = 28;
    private static final int MQTT_REASON_CODE_MAX_INFLIGHT = 32202;
    /** Broker 对某个订阅返回 SUBACK Failure 时，Paho 抛出的 reason code。 */
    private static final int MQTT_REASON_CODE_SUBSCRIBE_FAILED = 128;
    private static final int MQTT_KEEP_ALIVE_MIN_SECONDS = 10;
    private static final int MQTT_KEEP_ALIVE_MAX_SECONDS = 30;
    private static volatile MqttManager instance;

    private final Context context;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean subscribing = new AtomicBoolean(false);
    private volatile MqttClient client;
    private volatile MqttCredential credential;
    private volatile boolean subscribed;
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
            MqttCredential current = credential;
            if (current == null) {
                broadcastStatus("mqtt", "MQTT 凭证不可用");
                return;
            }
            MqttClient existing = client;
            if (existing != null && existing.isConnected()) {
                afterConnected(current, true);
                return;
            }

            closeOldClient();
            subscribed = false;
            broadcastStatus("mqtt", "正在连接 MQTT：" + safe(current.getBrokerUrl()));
            Log.i(TAG, "开始连接 MQTT：broker=" + safe(current.getBrokerUrl())
                    + "，clientId=" + safe(current.getClientId())
                    + "，deviceNo=" + safe(current.getDeviceNo()));

            MqttClient newClient = new MqttClient(
                    current.getBrokerUrl(),
                    current.getClientId(),
                    new MemoryPersistence()
            );
            newClient.setCallback(new CallbackImpl());
            client = newClient;

            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            /*
             * MQTT 对接文档联调建议 KeepAlive 控制在 30 秒以内；
             * heartbeat 上报周期仍继续使用平台凭证中的 heartbeatInterval。
             */
            options.setKeepAliveInterval(normalizeKeepAlive(current.getKeepAliveSeconds()));
            options.setMaxInflight(MQTT_MAX_INFLIGHT);
            if (!blank(current.getUsername())) {
                options.setUserName(current.getUsername());
            }
            if (current.getPassword() != null) {
                options.setPassword(current.getPassword().toCharArray());
            }

            newClient.connect(options);
            afterConnected(current, false);
        } catch (Throwable error) {
            Log.e(TAG, "MQTT 连接失败", error);
            subscribed = false;
            broadcastStatus("mqtt", "MQTT 连接失败：" + messageOf(error));
            closeOldClient();
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    private void afterConnected(MqttCredential current, boolean reconnect) {
        reconnectScheduled.set(false);
        String state = reconnect ? "MQTT 已重连，正在订阅" : "MQTT 已连接，正在订阅";
        Log.i(TAG, state + "：broker=" + safe(current.getBrokerUrl())
                + "，commandTopic=" + safe(current.getCommandSubscribeTopic()));
        broadcastStatus("mqtt", state);
        subscribeAsync(current);
        DepositCommandHandler.get(context).recoverUnfinishedSession();
        flushPending();
        new HeartbeatReporter(context).report();
        new DeviceStatusReporter(context).report();
        startHeartbeatLoop();
    }

    public boolean isConnected() {
        MqttClient current = client;
        return current != null && current.isConnected();
    }

    public boolean isSubscribed() {
        return subscribed;
    }

    public synchronized void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        subscribed = false;
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

    public boolean publish(String topic, String payload) {
        MqttClient currentClient = client;
        if (topic == null || topic.isEmpty() || currentClient == null || !currentClient.isConnected()) {
            return false;
        }
        int qos = credential == null ? 1 : credential.getQos();
        int normalizedQos = normalizeQos(qos);
        try {
            if (normalizedQos > 0) {
                IMqttDeliveryToken[] pendingTokens = currentClient.getPendingDeliveryTokens();
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
            currentClient.publish(topic, message);
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

    private void subscribeAsync(MqttCredential current) {
        if (current == null || !isConnected()) {
            return;
        }
        if (!subscribing.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                ensureSubscribed(current);
            } finally {
                subscribing.set(false);
            }
        });
    }

    private void ensureSubscribed(MqttCredential current) {
        MqttClient currentClient = client;
        if (currentClient == null || !currentClient.isConnected() || current == null) {
            return;
        }
        Set<String> topics = buildSubscribeTopics(current);
        if (topics.isEmpty()) {
            subscribed = false;
            broadcastStatus("mqtt", "MQTT 已连接，但订阅 Topic 为空");
            return;
        }

        int qos = normalizeQos(current.getQos());
        int requiredSuccess = 0;
        int optionalFailed = 0;
        for (String topic : topics) {
            try {
                currentClient.subscribe(topic, qos);
                if (!isOptionalSubscribeTopic(topic)) {
                    requiredSuccess++;
                }
                Log.i(TAG, "MQTT 已订阅：" + topic + "，qos=" + qos);
            } catch (MqttException error) {
                if (isOptionalSubscribeTopic(topic)
                        && error.getReasonCode() == MQTT_REASON_CODE_SUBSCRIBE_FAILED) {
                    optionalFailed++;
                    Log.w(
                            TAG,
                            "MQTT 可选 Topic 被 Broker 拒绝，继续使用已授权命令 Topic："
                                    + topic
                    );
                    continue;
                }
                Log.e(TAG, "MQTT 订阅失败 topic=" + topic, error);
                broadcastStatus("mqtt", "MQTT 订阅失败：" + messageOf(error));
            } catch (Throwable error) {
                Log.e(TAG, "MQTT 订阅失败 topic=" + topic, error);
                broadcastStatus("mqtt", "MQTT 订阅失败：" + messageOf(error));
            }
        }
        subscribed = requiredSuccess > 0;
        if (subscribed) {
            String text = "MQTT 已连接，已订阅 " + requiredSuccess + " 个命令 Topic";
            if (optionalFailed > 0) {
                text += "，" + optionalFailed + " 个可选 Topic 无权限";
            }
            broadcastStatus("mqtt", text);
        } else {
            broadcastStatus("mqtt", "MQTT 已连接，但命令 Topic 均未订阅成功");
        }
    }

    private Set<String> buildSubscribeTopics(MqttCredential current) {
        Set<String> topics = new LinkedHashSet<>();
        addTopic(topics, current.getCommandSubscribeTopic());
        String deviceNo = current.getDeviceNo();
        if (!blank(deviceNo)) {
            /* 按 MQTT 对接文档补足存珠机必须监听的命令 Topic。 */
            addTopic(topics, "pxd/v1/device/" + deviceNo + "/command/control");
            addTopic(topics, "pxd/v1/device/" + deviceNo + "/command/config");
            addTopic(topics, "pxd/v1/device/" + deviceNo + "/command/upgrade");
            addTopic(topics, "pxd/v1/device/" + deviceNo + "/command/query");
            /*
             * reply/ack 在文档中列为平台应答 Topic，但当前联调 Broker 对设备账号返回
             * SUBACK Failure(128)。该 Topic 不承载 collect_marbles 下发，先作为可选
             * 订阅处理，避免单个 ACL 缺口导致整机误判为 MQTT 失败。
             */
            addTopic(topics, "pxd/v1/device/" + deviceNo + "/reply/ack");
        }
        return topics;
    }

    private static void addTopic(Set<String> topics, String topic) {
        if (!blank(topic)) {
            topics.add(topic.trim());
        }
    }

    private static boolean isOptionalSubscribeTopic(String topic) {
        return topic != null && topic.contains("/reply/ack");
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
        MqttClient oldClient = client;
        if (oldClient == null) {
            return;
        }
        try {
            if (oldClient.isConnected()) {
                oldClient.disconnect();
            }
        } catch (Throwable ignored) {
        }
        try {
            oldClient.close();
        } catch (Throwable ignored) {
        }
        if (client == oldClient) {
            client = null;
        }
    }

    private void broadcastStatus(String key, String value) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(context.getPackageName());
        intent.putExtra("key", key);
        intent.putExtra("value", value);
        context.sendBroadcast(intent);
    }

    private static int normalizeQos(int qos) {
        return Math.max(0, Math.min(2, qos));
    }

    private static int normalizeKeepAlive(int keepAliveSeconds) {
        if (keepAliveSeconds <= 0) {
            return MQTT_KEEP_ALIVE_MAX_SECONDS;
        }
        return Math.max(
                MQTT_KEEP_ALIVE_MIN_SECONDS,
                Math.min(MQTT_KEEP_ALIVE_MAX_SECONDS, keepAliveSeconds)
        );
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
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
            if (!reconnect && connecting.get()) {
                return;
            }
            executor.execute(() -> {
                MqttCredential current = credential;
                if (current != null) {
                    afterConnected(current, reconnect);
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            subscribed = false;
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
