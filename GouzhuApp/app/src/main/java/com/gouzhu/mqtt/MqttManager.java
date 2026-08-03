package com.gouzhu.mqtt;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.upgrade.UpgradeManager;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.MqttTransport;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT 传输层。
 *
 * <p>本类只负责连接、订阅和发布。控制/配置 payload 不在此处裸解析，统一交给
 * DeviceCommandManager，并由新版 SDK DeviceMqttCommandCodec 完成 Topic、deviceNo、
 * 类型、令牌和有效期校验。</p>
 */
public final class MqttManager implements MqttTransport {

    private static final String TAG = "GouzhuMqtt";
    private static final int MQTT_MAX_INFLIGHT = 32;
    private static final int MQTT_INFLIGHT_HIGH_WATERMARK = 28;
    private static final int MQTT_REASON_CODE_MAX_INFLIGHT = 32202;
    private static final long OUTBOX_FLUSH_DELAY_MS = 200L;
    private static volatile MqttManager instance;

    private final Context context;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean outboxFlushScheduled = new AtomicBoolean(false);

    private MqttClient client;
    private MqttCredential credential;
    private MessageListener sdkListener;
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
        connect(credential, null);
    }

    @Override
    public void connect(MqttCredential credential, MessageListener listener) {
        if (credential == null) {
            broadcastStatus("mqtt", "MQTT凭证为空");
            return;
        }
        this.credential = credential;
        this.sdkListener = listener;
        executor.execute(this::connectInternal);
    }

    private synchronized void connectInternal() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            MqttCredential current = credential;
            if (current == null) {
                broadcastStatus("mqtt", "MQTT凭证不可用");
                return;
            }
            if (client != null && client.isConnected()) {
                ensureSubscribed();
                schedulePendingOutboxFlush();
                startHeartbeatLoop();
                return;
            }

            closeOldClient();
            client = new MqttClient(
                    current.getBrokerUrl(),
                    current.getClientId(),
                    new MemoryPersistence()
            );

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(current.getKeepAliveSeconds());
            options.setMaxInflight(MQTT_MAX_INFLIGHT);
            options.setUserName(current.getUsername());
            options.setPassword(current.getPassword().toCharArray());

            client.setCallback(new CallbackImpl());
            client.connect(options);
        } catch (Throwable error) {
            Log.e(TAG, "MQTT连接失败", error);
            broadcastStatus("mqtt", "MQTT连接失败");
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    public synchronized boolean publish(String topic, String payload) {
        MqttCredential current = credential;
        int qos = current == null ? 1 : current.getQos();
        return publishInternal(
                topic,
                payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8),
                qos,
                false
        );
    }

    @Override
    public synchronized void publish(String topic, byte[] payload, int qos, boolean retained) {
        publishInternal(topic, payload == null ? new byte[0] : payload, qos, retained);
    }

    private boolean publishInternal(String topic, byte[] payload, int qos, boolean retained) {
        if (topic == null || topic.isEmpty() || client == null || !client.isConnected()) {
            return false;
        }
        int normalizedQos = Math.max(0, Math.min(2, qos));
        try {
            if (normalizedQos > 0) {
                IMqttDeliveryToken[] pendingTokens = client.getPendingDeliveryTokens();
                int pendingCount = pendingTokens == null ? 0 : pendingTokens.length;
                if (pendingCount >= MQTT_INFLIGHT_HIGH_WATERMARK) {
                    Log.w(
                            TAG,
                            "MQTT发布暂缓，等待在途消息确认：pending="
                                    + pendingCount + "，topic=" + topic
                    );
                    schedulePendingOutboxFlush();
                    return false;
                }
            }

            MqttMessage message = new MqttMessage(payload);
            message.setQos(normalizedQos);
            message.setRetained(retained);
            client.publish(topic, message);
            return true;
        } catch (MqttException error) {
            if (error.getReasonCode() == MQTT_REASON_CODE_MAX_INFLIGHT) {
                Log.w(TAG, "MQTT在途窗口已满，稍后重放outbox：topic=" + topic);
                schedulePendingOutboxFlush();
            } else {
                Log.e(TAG, "MQTT发布失败，topic=" + topic, error);
            }
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "MQTT发布失败，topic=" + topic, error);
            return false;
        }
    }

    @Override
    public synchronized void subscribe(String topic, int qos) {
        try {
            if (client != null && client.isConnected() && topic != null && !topic.isEmpty()) {
                client.subscribe(topic, Math.max(0, Math.min(2, qos)));
            }
        } catch (Throwable error) {
            Log.e(TAG, "MQTT订阅失败", error);
        }
    }

    public boolean reportCommandResult(String payload) {
        return publishReport("command-result", payload);
    }

    public boolean reportCashEvent(String payload) {
        return publishReport("cash-event", payload);
    }

    public boolean reportRedemptionRequest(String requestId, String pickupCode) {
        if (requestId == null || requestId.isEmpty()
                || pickupCode == null || pickupCode.isEmpty()) {
            return false;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("requestId", requestId);
            json.put("pickupCode", pickupCode);
            return publishReport("redemption-request", json.toString());
        } catch (Throwable error) {
            return false;
        }
    }

    public boolean reportFault(
            String faultCode,
            String faultName,
            int faultLevel,
            String faultDesc
    ) {
        try {
            JSONObject json = new JSONObject();
            json.put("faultCode", faultCode);
            json.put("faultName", faultName);
            json.put("faultLevel", faultLevel);
            json.put("faultDesc", faultDesc);
            json.put("timestamp", System.currentTimeMillis());
            return publishReport("fault", json.toString());
        } catch (Throwable error) {
            return false;
        }
    }

    public boolean reportUpgradeProgress(
            String messageId,
            String status,
            int progress,
            String currentVersion,
            String targetVersion,
            long recordId,
            long taskId,
            String type,
            String errorCode,
            String errorMessage
    ) {
        try {
            JSONObject json = new JSONObject();
            json.put("messageId", safe(messageId));
            json.put("deviceNo", DeviceUtil.requireDeviceNo(context));
            json.put("status", status);
            json.put("progress", progress);
            json.put("currentVersion", safe(currentVersion));
            json.put("targetVersion", safe(targetVersion));
            json.put("recordId", recordId);
            json.put("taskId", taskId);
            json.put("type", type);
            json.put("errorCode", errorCode == null ? JSONObject.NULL : errorCode);
            json.put("errorMessage", errorMessage == null ? JSONObject.NULL : errorMessage);
            json.put("timestamp", System.currentTimeMillis());
            return publishReport("upgrade-progress", json.toString());
        } catch (Throwable error) {
            Log.e(TAG, "组装升级进度失败", error);
            return false;
        }
    }

    public boolean reportStatus() {
        try {
            DeviceCommandStore commandStore = new DeviceCommandStore(context);
            String boardVersion = DeviceUtil.formatBoardVersion(commandStore.getBoardVersion());
            JSONObject json = new JSONObject();
            json.put("runningStatus", DeviceCommandManager.get(context).getRunningStatus());
            json.put("publicIp", "");
            json.put("privateIp", getPrivateIp());
            json.put("networkType", getNetworkType());
            json.put("privateHttpBaseUrl", "");
            json.put("apkVersion", DeviceUtil.getAppVersion(context));
            json.put("apkVersionCode", DeviceUtil.getAppVersionCode(context));
            json.put("firmwareVersion", boardVersion);
            json.put("firmwareVersionCode", DeviceUtil.parseBoardVersionCode(boardVersion));
            json.put("timestamp", System.currentTimeMillis());
            return publishReport("status", json.toString());
        } catch (Throwable error) {
            Log.e(TAG, "状态上报失败", error);
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public synchronized void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        closeOldClient();
    }

    @Override
    public void disconnect() {
        close();
    }

    private synchronized void ensureSubscribed() throws Exception {
        MqttCredential current = credential;
        if (client == null || !client.isConnected() || current == null) {
            return;
        }
        client.subscribe(current.getCommandSubscribeTopic(), current.getQos());
    }

    private void afterConnected(boolean reconnect) throws Exception {
        /* 必须先订阅，再恢复本地状态和分批重放 durable outbox。 */
        ensureSubscribed();
        DeviceCommandManager.get(context).start();
        schedulePendingOutboxFlush();
        UpgradeManager.get(context).resumePendingResult();
        reportHeartbeat();
        reportStatus();
        startHeartbeatLoop();
        broadcastStatus("mqtt", reconnect ? "MQTT已重连" : "MQTT已连接");
        reconnectScheduled.set(false);
    }

    private synchronized void startHeartbeatLoop() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            return;
        }
        MqttCredential current = credential;
        int interval = current == null
                ? AppConfig.DEFAULT_HEARTBEAT_SECONDS
                : current.getHeartbeatIntervalSeconds();
        heartbeatTask = executor.scheduleWithFixedDelay(
                this::reportHeartbeat,
                interval,
                interval,
                TimeUnit.SECONDS
        );
    }

    private void reportHeartbeat() {
        publishReport("heartbeat", "{}");
        schedulePendingOutboxFlush();
    }

    private void schedulePendingOutboxFlush() {
        if (!outboxFlushScheduled.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            outboxFlushScheduled.set(false);
            if (isConnected()) {
                DeviceCommandManager.get(context).flushPending();
            }
        }, OUTBOX_FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private boolean publishReport(String key, String payload) {
        MqttCredential current = credential;
        if (current == null) {
            return false;
        }
        Map<String, String> topics = current.getReportTopics();
        String topic = topics == null ? null : topics.get(key);
        if (topic == null || topic.isEmpty()) {
            Log.e(TAG, "SDK凭证缺少上报Topic：" + key);
            return false;
        }
        return publish(topic, payload);
    }

    private void handleMessage(String topic, byte[] payloadBytes) {
        try {
            if (topic.contains("/command/upgrade")) {
                UpgradeManager.get(context).handleMqttCommand(
                        new String(payloadBytes, StandardCharsets.UTF_8)
                );
                return;
            }
            if (topic.contains("/command/control") || topic.contains("/command/config")) {
                DeviceCommandManager.get(context).handleCommand(topic, payloadBytes);
                return;
            }
            if (topic.contains("/command/task")) {
                Log.i(TAG, "收到异步任务，当前仅保留订阅，不打印完整payload");
                return;
            }
            Log.w(TAG, "收到未知MQTT Topic：" + topic);
        } catch (Throwable error) {
            Log.e(TAG, "处理MQTT消息失败", error);
        }
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

    private String getPrivateIp() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            LinkProperties properties = manager == null || network == null
                    ? null
                    : manager.getLinkProperties(network);
            if (properties != null) {
                for (LinkAddress address : properties.getLinkAddresses()) {
                    if (!address.getAddress().isLoopbackAddress()
                            && address.getAddress().getHostAddress() != null
                            && !address.getAddress().getHostAddress().contains(":")) {
                        return address.getAddress().getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String getNetworkType() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager == null || network == null
                    ? null
                    : manager.getNetworkCapabilities(network);
            if (capabilities == null) {
                return "unknown";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "wifi";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "ethernet";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "cellular";
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private final class CallbackImpl implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            executor.execute(() -> {
                try {
                    afterConnected(reconnect);
                } catch (Throwable error) {
                    Log.e(TAG, "MQTT连接后恢复失败", error);
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            broadcastStatus("mqtt", "MQTT连接已断开");
            MessageListener listener = sdkListener;
            if (listener != null) {
                listener.onConnectionLost(cause);
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            byte[] payload = message == null ? new byte[0] : message.getPayload();
            MessageListener listener = sdkListener;
            if (listener != null) {
                listener.onMessage(topic, payload);
            }
            handleMessage(topic, payload);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            schedulePendingOutboxFlush();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
