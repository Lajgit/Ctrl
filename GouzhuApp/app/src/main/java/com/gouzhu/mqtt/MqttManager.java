package com.gouzhu.mqtt;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.activation.ActivationManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.upgrade.UpgradeManager;
import com.gouzhu.util.DeviceUtil;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 购珠机 MQTT 管理器。
 *
 * <p>连接、自动重连、订阅和心跳逻辑移植自 OTA_XLH3566；删除了第二个主 App
 * 的守护与游戏配置分发，只保留购珠机单应用需要的升级和控制入口。</p>
 */
public final class MqttManager {

    private static final String TAG = "GouzhuMqtt";
    private static volatile MqttManager instance;

    private final Context context;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private MqttClient client;
    private ActivationManager.MqttCredential credential;
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

    /** 异步连接 MQTT。 */
    public void connect(ActivationManager.MqttCredential credential) {
        if (credential == null || !credential.isValid()) {
            broadcastStatus("mqtt", "MQTT凭据无效");
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
            if (client != null && client.isConnected()) {
                ensureSubscribed();
                startHeartbeatLoop();
                UpgradeManager.get(context).resumePendingResult();
                return;
            }

            closeOldClient();
            client = new MqttClient(
                    credential.brokerUrl,
                    credential.clientId,
                    new MemoryPersistence()
            );

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(
                    credential.keepAliveSeconds > 0
                            ? credential.keepAliveSeconds
                            : AppConfig.DEFAULT_MQTT_KEEP_ALIVE_SECONDS
            );
            options.setUserName(credential.username);
            options.setPassword(credential.password.toCharArray());

            client.setCallback(new CallbackImpl());
            client.connect(options);
            ensureSubscribed();
            reportHeartbeat();
            startHeartbeatLoop();
            UpgradeManager.get(context).resumePendingResult();
            broadcastStatus("mqtt", "MQTT已连接");
            reconnectScheduled.set(false);
        } catch (Throwable error) {
            Log.e(TAG, "MQTT连接失败", error);
            broadcastStatus("mqtt", "MQTT连接失败");
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    /** 发布 QoS 1 非保留消息。 */
    public synchronized boolean publish(String topic, String payload) {
        if (client == null || !client.isConnected()) {
            return false;
        }

        try {
            MqttMessage message = new MqttMessage(
                    payload.getBytes(StandardCharsets.UTF_8)
            );
            message.setQos(1);
            message.setRetained(false);
            client.publish(topic, message);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "MQTT发布失败，topic=" + topic, error);
            return false;
        }
    }

    /** 上报升级进度，字段保持 OTA_XLH3566 的后台格式。 */
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
            json.put("deviceNo", DeviceUtil.getDeviceId(context));
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
            return publish(
                    getUpgradeProgressTopic(DeviceUtil.getDeviceId(context)),
                    json.toString()
            );
        } catch (Throwable error) {
            Log.e(TAG, "组装升级进度失败", error);
            return false;
        }
    }

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

    private synchronized void ensureSubscribed() throws Exception {
        if (client == null || !client.isConnected()) {
            return;
        }

        String deviceId = DeviceUtil.getDeviceId(context);
        client.subscribe(
                new String[]{
                        getUpgradeTopic(deviceId),
                        getControlTopic(deviceId),
                        getConfigTopic(deviceId),
                        getTaskTopic(deviceId)
                },
                new int[]{1, 1, 1, 1}
        );
    }

    private synchronized void startHeartbeatLoop() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            return;
        }

        int interval = credential != null && credential.heartbeatInterval > 0
                ? credential.heartbeatInterval
                : AppConfig.DEFAULT_HEARTBEAT_SECONDS;
        heartbeatTask = executor.scheduleWithFixedDelay(
                this::reportHeartbeat,
                interval,
                interval,
                TimeUnit.SECONDS
        );
    }

    private void reportHeartbeat() {
        try {
            JSONObject json = new JSONObject();
            json.put("machineId", DeviceUtil.getDeviceId(context));
            json.put("deviceNo", DeviceUtil.getDeviceId(context));
            json.put("status", "online");
            json.put("appVersion", DeviceUtil.getAppVersion(context));
            json.put("boardConnected", SerialManager.get(context).isOpen());
            json.put("timestamp", System.currentTimeMillis());
            publish(getHeartbeatTopic(DeviceUtil.getDeviceId(context)), json.toString());
        } catch (Throwable error) {
            Log.e(TAG, "发送MQTT心跳失败", error);
        }
    }

    private void handleMessage(String topic, String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String targetDevice = json.optString("deviceNo", "");
            String localDevice = DeviceUtil.getDeviceId(context);
            if (!targetDevice.isEmpty() && !localDevice.equals(targetDevice)) {
                return;
            }

            if (topic.contains("/command/upgrade")) {
                UpgradeManager.get(context).handleMqttCommand(payload);
                return;
            }

            if (topic.contains("/command/control")) {
                handleControlCommand(json);
                return;
            }

            // 配置和任务 topic 暂时保留订阅，未定义的业务不擅自执行。
            Log.i(TAG, "收到暂未实现的MQTT消息，topic=" + topic);
        } catch (Throwable error) {
            Log.e(TAG, "处理MQTT消息失败", error);
        }
    }

    private void handleControlCommand(JSONObject json) {
        String action = json.optString("action", json.optString("command", ""));
        SerialManager serial = SerialManager.get(context);

        switch (action) {
            case "stop":
            case "stopAll":
                serial.sendCommand(0xFF, 0, true);
                break;
            case "unlock":
                serial.sendCommand(0x10, 0, true);
                break;
            case "setPrice":
                int priceCent = json.optInt("priceCent", 0);
                if (priceCent >= 1 && priceCent <= 10000) {
                    serial.sendCommand(0x20, priceCent, true);
                }
                break;
            case "requestStatus":
                serial.sendCommand(0x21, 0, false);
                break;
            default:
                Log.w(TAG, "未知远程控制命令=" + action);
                break;
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

    private final class CallbackImpl implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            executor.execute(() -> {
                try {
                    ensureSubscribed();
                    reportHeartbeat();
                    startHeartbeatLoop();
                    UpgradeManager.get(context).resumePendingResult();
                    broadcastStatus("mqtt", reconnect ? "MQTT已重连" : "MQTT已连接");
                } catch (Throwable error) {
                    Log.e(TAG, "MQTT重连后恢复订阅失败", error);
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            broadcastStatus("mqtt", "MQTT连接已断开");
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            handleMessage(topic, message.toString());
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String getUpgradeTopic(String deviceId) {
        return "pxd/v1/device/" + deviceId + "/command/upgrade";
    }

    private static String getControlTopic(String deviceId) {
        return "pxd/v1/device/" + deviceId + "/command/control";
    }

    private static String getConfigTopic(String deviceId) {
        return "pxd/v1/device/" + deviceId + "/command/config";
    }

    private static String getTaskTopic(String deviceId) {
        return "pxd/v1/device/" + deviceId + "/command/task";
    }

    private static String getHeartbeatTopic(String deviceId) {
        return "pxd/v1/device/" + deviceId + "/report/heartbeat";
    }

    private static String getUpgradeProgressTopic(String deviceId) {
        return "pxd/v1/device/" + deviceId + "/report/upgrade-progress";
    }
}
