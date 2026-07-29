package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.util.DeviceUtil;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * 售珠机 MQTT 业务指令管理器。
 *
 * <p>现金购珠由控制板独立计费并吐珠；安卓只上报现金事实。扫码、支付和核销
 * 页面均不能直接驱动出珠，只有合法 dispense_marbles 指令可以启动平台出珠。</p>
 */
public final class DeviceCommandManager {

    public static final String EXTRA_COLLECTION_EVENT = "collectionEvent";
    public static final String EXTRA_COLLECTION_MESSAGE = "collectionMessage";
    public static final String COLLECTION_READY = "ready";
    public static final String COLLECTION_STARTED = "started";
    public static final String COLLECTION_PROGRESS = "progress";
    public static final String COLLECTION_FINISHED = "finished";
    public static final String COLLECTION_FAILED = "failed";

    private static final String TAG = "GouzhuCommand";

    private static final int CMD_VERSION = 0x00;
    private static final int CMD_COLLECT_START = 0x02;
    private static final int CMD_COLLECT_STOP = 0x03;
    private static final int CMD_BILL_ENABLE = 0x1A;
    private static final int CMD_BILL_DISABLE = 0x1B;
    private static final int CMD_COIN_ENABLE = 0x1D;
    private static final int CMD_COIN_DISABLE = 0x1E;
    private static final int CMD_STATUS = 0x21;
    private static final int CMD_PLATFORM_DISPENSE = 0x27;

    private static final int EVT_VERSION = 0x00;
    private static final int EVT_DISPENSE_PULSE = 0x01;
    private static final int EVT_COLLECT_PULSE = 0x03;
    private static final int EVT_DISPENSE_TIMEOUT = 0x07;
    private static final int EVT_COLLECT_TIMEOUT = 0x08;
    private static final int EVT_CASH_ACCEPTED = 0x28;
    private static final int EVT_CASH_RETURNED = 0x29;
    private static final int EVT_CASH_RETURN_FAILED = 0x2A;

    private static volatile DeviceCommandManager instance;

    private final Context context;
    private final DeviceCommandStore store;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private Runnable collectionTimeoutRunnable;

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                return;
            }
            handleBoardEvent(
                    intent.getIntExtra("code2", -1),
                    intent.getLongExtra("data", 0L)
            );
        }
    };

    private DeviceCommandManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
    }

    public static DeviceCommandManager get(Context context) {
        if (instance == null) {
            synchronized (DeviceCommandManager.class) {
                if (instance == null) {
                    instance = new DeviceCommandManager(context);
                }
            }
        }
        return instance;
    }

    /** 注册控制板事件并恢复未完成任务。 */
    public synchronized void start() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_EVENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(boardReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(boardReceiver, filter);
            }
            receiverRegistered = true;
        }

        SerialManager serial = SerialManager.get(context);
        serial.sendCommand(CMD_VERSION, 0L, false);
        serial.sendCommand(CMD_STATUS, 0L, false);

        if (!store.getActiveCollect().isEmpty()) {
            // 重启后先停止存珠电机，只恢复任务和计数，不擅自重新启动物理动作。
            serial.sendCommand(CMD_COLLECT_STOP, 0L, false);
            broadcastCollection(COLLECTION_READY, "检测到未完成存珠任务，请核对现场后继续");
        }
    }

    public synchronized void stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(boardReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        cancelCollectionTimeout();
    }

    /** 处理平台统一指令信封。 */
    public synchronized void handleCommand(JSONObject envelope) {
        String messageId = envelope.optString("messageId", "");
        String commandType = envelope.optString("commandType", "");
        String target = DeviceUtil.normalizeDeviceNo(envelope.optString("deviceNo", ""));
        String local = DeviceUtil.requireDeviceNo(context);

        if (messageId.isEmpty() || commandType.isEmpty()) {
            Log.w(TAG, "忽略缺少messageId或commandType的指令");
            return;
        }
        if (!target.isEmpty() && !local.equals(target)) {
            Log.w(TAG, "忽略非本机指令");
            return;
        }

        JSONObject data = envelope.optJSONObject("data");
        if ("command_result_ack".equals(commandType)) {
            handleResultAck(data);
            return;
        }
        if ("cash_event_response".equals(commandType)) {
            handleCashAck(data);
            return;
        }
        if ("redemption_response".equals(commandType)) {
            handleRedemptionResponse(data);
            return;
        }

        if (store.hasCommand(messageId)) {
            resendResults(messageId);
            return;
        }

        switch (commandType) {
            case "dispense_marbles":
                handleDispense(envelope);
                break;
            case "collect_marbles":
                handleCollect(envelope);
                break;
            case "sync_cash_configuration":
                handleCashConfiguration(envelope);
                break;
            default:
                store.saveCommand(envelope);
                publishTerminal(envelope, false, 0,
                        "UNSUPPORTED_COMMAND", "不支持的指令类型：" + commandType, null);
                break;
        }
    }

    private void handleDispense(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        String error = validateOperation(data, "quantity");
        if (error != null) {
            store.saveCommand(envelope);
            publishTerminal(envelope, false, 0, "PARAM_INVALID", error, null);
            return;
        }
        if (!store.getActiveDispense().isEmpty() || !store.getActiveCollect().isEmpty()) {
            store.saveCommand(envelope);
            publishTerminal(envelope, false, 0, "DEVICE_BUSY", "设备存在未完成物理任务", null);
            return;
        }

        try {
            data.put("deviceActualQuantity", 0);
            data.put("deviceStarted", false);
        } catch (Throwable ignored) {
        }
        if (!store.saveCommand(envelope)) {
            MqttManager.get(context).reportFault(
                    "LOCAL_STORAGE_ERROR", "本地存储异常", 3, "出珠指令无法持久化");
            return;
        }
        publishAck(envelope);
        setCashAcceptance(false);

        int quantity = data.optInt("quantity", 0);
        boolean sent = SerialManager.get(context).sendCommand(
                CMD_PLATFORM_DISPENSE,
                Integer.toUnsignedLong(quantity),
                true
        );
        if (!sent) {
            restoreCashAcceptance();
            publishTerminal(envelope, false, 0,
                    "CONTROLLER_OFFLINE", "控制板未连接", null);
            return;
        }
        try {
            data.put("deviceStarted", true);
            data.put("deviceStartedAt", System.currentTimeMillis());
            store.saveCommand(envelope);
        } catch (Throwable ignored) {
        }
        store.setActiveDispense(envelope.optString("messageId", ""));
    }

    private void handleCollect(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        String error = validateOperation(data, "maximumQuantity");
        if (error != null) {
            store.saveCommand(envelope);
            publishTerminal(envelope, false, 0, "PARAM_INVALID", error, null);
            return;
        }
        if (!store.getActiveDispense().isEmpty() || !store.getActiveCollect().isEmpty()) {
            store.saveCommand(envelope);
            publishTerminal(envelope, false, 0, "DEVICE_BUSY", "设备存在未完成物理任务", null);
            return;
        }

        try {
            data.put("deviceActualQuantity", 0);
            data.put("deviceStarted", false);
        } catch (Throwable ignored) {
        }
        if (!store.saveCommand(envelope)) {
            MqttManager.get(context).reportFault(
                    "LOCAL_STORAGE_ERROR", "本地存储异常", 3, "存珠指令无法持久化");
            return;
        }
        publishAck(envelope);
        store.setActiveCollect(envelope.optString("messageId", ""));
        setCashAcceptance(false);
        broadcastCollection(COLLECTION_READY, "请倒入珠子，完成后点击开始存珠");
    }

    /** 用户倒完珠子后点击按钮，才启动存珠电机。 */
    public synchronized boolean startPendingCollection() {
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        if (envelope == null) {
            broadcastCollection(COLLECTION_FAILED, "没有可启动的存珠任务");
            return false;
        }
        JSONObject data = envelope.optJSONObject("data");
        int maximum = data == null ? 0 : data.optInt("maximumQuantity", 0);
        int actual = data == null ? 0 : data.optInt("deviceActualQuantity", 0);
        int remaining = maximum - actual;
        if (remaining <= 0) {
            finishCollect(messageId, true, actual, "OK", "已达到存珠上限");
            return true;
        }
        if (!SerialManager.get(context).sendCommand(
                CMD_COLLECT_START,
                Integer.toUnsignedLong(Math.min(remaining, 0xFFFF)),
                true
        )) {
            broadcastCollection(COLLECTION_FAILED, "控制板未连接，无法启动存珠");
            return false;
        }
        try {
            data.put("deviceStarted", true);
            data.put("deviceStartedAt", System.currentTimeMillis());
            store.saveCommand(envelope);
        } catch (Throwable error) {
            SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, false);
            broadcastCollection(COLLECTION_FAILED, "存珠状态保存失败，已停止电机");
            return false;
        }
        scheduleCollectionTimeout(data.optInt("sessionTimeoutSeconds", 300));
        broadcastCollection(COLLECTION_STARTED, "存珠电机已启动，正在统计光眼数量");
        return true;
    }

    /** 用户点击完成存珠，停止电机并按真实光眼数量生成终态。 */
    public synchronized boolean finishPendingCollection() {
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        if (envelope == null) {
            return false;
        }
        SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, true);
        JSONObject data = envelope.optJSONObject("data");
        int actual = data == null ? 0 : data.optInt("deviceActualQuantity", 0);
        finishCollect(
                messageId,
                actual > 0,
                actual,
                actual > 0 ? "OK" : "NO_MARBLES_COLLECTED",
                actual > 0 ? "用户确认存珠结束" : "未检测到存珠光眼脉冲"
        );
        return true;
    }

    public boolean hasPendingCollection() {
        return !store.getActiveCollect().isEmpty();
    }

    public int getRunningStatus() {
        return store.getActiveDispense().isEmpty() && store.getActiveCollect().isEmpty() ? 0 : 1;
    }

    /** MQTT 重连后补发未确认回执及现金事件。 */
    public synchronized void flushPending() {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            MqttManager.get(context).reportCommandResult(item.payload);
        }
        for (DeviceCommandStore.OutboxItem item : store.listCashEvents()) {
            MqttManager.get(context).reportCashEvent(item.payload);
        }
    }

    private void handleCashConfiguration(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        int version = data == null ? 0 : data.optInt("configVersion", 0);
        boolean enabled = data != null && data.optBoolean("cashAcceptanceEnabled", false);
        if (version <= 0) {
            store.saveCommand(envelope);
            publishTerminal(envelope, false, 0,
                    "PARAM_INVALID", "现金配置版本无效", null);
            return;
        }
        store.saveCommand(envelope);
        store.saveCashConfiguration(version, enabled);
        setCashAcceptance(enabled);
        publishAck(envelope);
        try {
            JSONObject extra = new JSONObject();
            extra.put("configVersion", version);
            publishTerminal(envelope, true, 0,
                    "CASH_CONFIGURATION_APPLIED", "现金配置已应用", extra);
        } catch (Throwable error) {
            publishTerminal(envelope, false, 0,
                    "LOCAL_STORAGE_ERROR", "现金配置结果组装失败", null);
        }
    }

    private synchronized void handleBoardEvent(int code2, long data) {
        switch (code2) {
            case EVT_VERSION:
                store.saveBoardVersion(data);
                MqttManager.get(context).reportStatus();
                break;
            case EVT_DISPENSE_PULSE:
                onDispensePulse();
                break;
            case EVT_COLLECT_PULSE:
                onCollectPulse();
                break;
            case EVT_DISPENSE_TIMEOUT:
                finishActiveDispense(false, "DISPENSER_SENSOR_ERROR", "出珠超时");
                break;
            case EVT_COLLECT_TIMEOUT:
                finishActiveCollect(false, "COLLECTOR_SENSOR_ERROR", "存珠超时");
                break;
            case EVT_CASH_ACCEPTED:
                reportCashAccepted(data);
                break;
            case EVT_CASH_RETURNED:
                reportCashReturned(data);
                break;
            case EVT_CASH_RETURN_FAILED:
                MqttManager.get(context).reportFault(
                        "BANKNOTE_ACCEPTOR_ERROR", "验钞器异常", 3, "控制板执行真实退币失败");
                break;
            default:
                break;
        }
    }

    private void onDispensePulse() {
        String messageId = store.getActiveDispense();
        if (messageId.isEmpty()) {
            return; // 现金购珠由控制板独立执行，脉冲不进入平台任务。
        }
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null) {
            return;
        }
        int actual = data.optInt("deviceActualQuantity", 0) + 1;
        try {
            data.put("deviceActualQuantity", actual);
            if (!store.saveCommand(envelope)) {
                MqttManager.get(context).reportFault(
                        "LOCAL_STORAGE_ERROR", "本地存储异常", 3, "出珠计数保存失败");
                return;
            }
        } catch (Throwable error) {
            return;
        }
        if (actual >= data.optInt("quantity", 0)) {
            finishActiveDispense(true, "DISPENSE_COMPLETED", "出珠完成");
        }
    }

    private void onCollectPulse() {
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null || !data.optBoolean("deviceStarted", false)) {
            return;
        }
        int actual = data.optInt("deviceActualQuantity", 0) + 1;
        try {
            data.put("deviceActualQuantity", actual);
            if (!store.saveCommand(envelope)) {
                SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, false);
                MqttManager.get(context).reportFault(
                        "LOCAL_STORAGE_ERROR", "本地存储异常", 3, "存珠计数保存失败");
                return;
            }
        } catch (Throwable error) {
            return;
        }
        broadcastCollection(COLLECTION_PROGRESS, "已存入 " + actual + " 珠");
        if (actual >= data.optInt("maximumQuantity", 0)) {
            SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, false);
            finishCollect(messageId, true, actual, "OK", "达到存珠上限");
        }
    }

    private void finishActiveDispense(boolean success, String code, String message) {
        String messageId = store.getActiveDispense();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        int actual = data == null ? 0 : data.optInt("deviceActualQuantity", 0);
        if (envelope != null) {
            publishTerminal(envelope, success, actual, code, message, null);
        }
        store.clearActiveDispense();
        restoreCashAcceptance();
    }

    private void finishActiveCollect(boolean success, String code, String message) {
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        int actual = data == null ? 0 : data.optInt("deviceActualQuantity", 0);
        SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, 0L, false);
        finishCollect(messageId, success, actual, code, message);
    }

    private void finishCollect(
            String messageId,
            boolean success,
            int actual,
            String code,
            String message
    ) {
        cancelCollectionTimeout();
        JSONObject envelope = store.loadCommand(messageId);
        if (envelope != null) {
            publishTerminal(envelope, success, actual, code, message, null);
        }
        store.clearActiveCollect();
        restoreCashAcceptance();
        broadcastCollection(success ? COLLECTION_FINISHED : COLLECTION_FAILED,
                message + "，实际数量：" + actual);
    }

    private void scheduleCollectionTimeout(int seconds) {
        cancelCollectionTimeout();
        int safeSeconds = seconds <= 0 ? 300 : seconds;
        collectionTimeoutRunnable = () -> {
            synchronized (DeviceCommandManager.this) {
                finishActiveCollect(false, "SESSION_TIMEOUT", "存珠会话超时");
            }
        };
        mainHandler.postDelayed(collectionTimeoutRunnable, safeSeconds * 1000L);
    }

    private void cancelCollectionTimeout() {
        if (collectionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(collectionTimeoutRunnable);
            collectionTimeoutRunnable = null;
        }
    }

    private void reportCashAccepted(long packed) {
        int medium = (int) ((packed >>> 24) & 0xFF);
        int amountYuan = (int) (packed & 0x00FFFFFFL);
        if (amountYuan <= 0) {
            return;
        }
        String eventNo = "CE-A-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        try {
            String mediumType = medium == 0 ? "coin" : "banknote";
            JSONObject payload = new JSONObject();
            payload.put("eventNo", eventNo);
            payload.put("eventType", "accepted");
            payload.put("cashMediumType", mediumType);
            // 本项目控制板与安卓现金金额约定为整数元。
            payload.put("denominationAmount", amountYuan);
            payload.put("cashCount", 1);
            payload.put("configVersion", store.getCashConfigVersion());
            payload.put("timestamp", System.currentTimeMillis());
            if (store.saveCashEvent(eventNo, payload.toString())) {
                store.appendAcceptedCashEvent(mediumType, amountYuan, eventNo);
                MqttManager.get(context).reportCashEvent(payload.toString());
            }
        } catch (Throwable error) {
            MqttManager.get(context).reportFault(
                    "LOCAL_STORAGE_ERROR", "本地存储异常", 3, "现金事件保存失败");
        }
    }

    private void reportCashReturned(long packed) {
        int medium = (int) ((packed >>> 24) & 0xFF);
        int amountYuan = (int) (packed & 0x00FFFFFFL);
        if (amountYuan <= 0) {
            return;
        }
        String eventNo = "CE-R-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        try {
            String mediumType = medium == 0 ? "coin" : "banknote";
            JSONObject payload = new JSONObject();
            payload.put("eventNo", eventNo);
            payload.put("eventType", "returned");
            payload.put("cashMediumType", mediumType);
            payload.put("denominationAmount", amountYuan);
            payload.put("cashCount", 1);
            String related = store.popAcceptedCashEvent(mediumType, amountYuan);
            if (!related.isEmpty()) {
                payload.put("relatedEventNo", related);
            }
            payload.put("timestamp", System.currentTimeMillis());
            if (store.saveCashEvent(eventNo, payload.toString())) {
                MqttManager.get(context).reportCashEvent(payload.toString());
            }
        } catch (Throwable error) {
            MqttManager.get(context).reportFault(
                    "LOCAL_STORAGE_ERROR", "本地存储异常", 3, "退币事件保存失败");
        }
    }

    private void handleResultAck(JSONObject data) {
        if (data != null && "recorded".equals(data.optString("receiptStatus", ""))) {
            store.removeCommandResult(data.optString("eventNo", ""));
        }
    }

    private void handleCashAck(JSONObject data) {
        if (data == null) {
            return;
        }
        String eventNo = data.optString("eventNo", data.optString("sourceEventNo", ""));
        String status = data.optString("receiptStatus", data.optString("status", ""));
        if (!eventNo.isEmpty() && ("recorded".equals(status) || "accepted".equals(status))) {
            store.removeCashEvent(eventNo);
        }
    }

    private void handleRedemptionResponse(JSONObject data) {
        if (data == null) {
            return;
        }
        String status = data.optString("status", "unknown");
        String message;
        if ("accepted".equals(status)) {
            message = "核销已受理，等待平台出珠指令";
        } else if ("rejected".equals(status)) {
            message = data.optString("message", "核销失败");
        } else {
            message = "核销结果未知，请联系工作人员";
        }
        Intent intent = new Intent(PaymentManager.ACTION_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(PaymentManager.EXTRA_EVENT, PaymentManager.EVENT_WAITING);
        intent.putExtra(PaymentManager.EXTRA_MESSAGE, message);
        context.sendBroadcast(intent);
    }

    private void publishAck(JSONObject envelope) {
        try {
            String messageId = envelope.optString("messageId", "");
            JSONObject data = envelope.optJSONObject("data");
            JSONObject result = new JSONObject();
            result.put("messageId", messageId);
            result.put("commandType", envelope.optString("commandType", ""));
            result.put("status", "ack");
            result.put("eventNo", messageId + "-ack");
            result.put("operationNo", data == null ? "" : data.optString("operationNo", ""));
            result.put("operationToken", data == null ? "" : data.optString("operationToken", ""));
            result.put("timestamp", System.currentTimeMillis());
            store.saveCommandResult(messageId, messageId + "-ack", "ack", result.toString());
            MqttManager.get(context).reportCommandResult(result.toString());
        } catch (Throwable error) {
            Log.e(TAG, "生成ACK失败", error);
        }
    }

    private void publishTerminal(
            JSONObject envelope,
            boolean success,
            int actualQuantity,
            String resultCode,
            String resultMessage,
            JSONObject extra
    ) {
        try {
            String messageId = envelope.optString("messageId", "");
            String commandType = envelope.optString("commandType", "");
            JSONObject data = envelope.optJSONObject("data");
            JSONObject result = new JSONObject();
            result.put("messageId", messageId);
            result.put("commandType", commandType);
            result.put("status", success ? "success" : "failed");
            result.put("eventNo", messageId + "-result");
            result.put("operationNo", data == null ? "" : data.optString("operationNo", ""));
            if ("dispense_marbles".equals(commandType) || "collect_marbles".equals(commandType)) {
                result.put("actualQuantity", Math.max(0, actualQuantity));
                result.put("operationToken", data == null ? "" : data.optString("operationToken", ""));
            }
            result.put("resultCode", resultCode);
            result.put("resultMessage", resultMessage);
            result.put("timestamp", System.currentTimeMillis());
            if (extra != null) {
                java.util.Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    result.put(key, extra.opt(key));
                }
            }
            String status = success ? "success" : "failed";
            store.saveCommandResult(messageId, messageId + "-result", status, result.toString());
            MqttManager.get(context).reportCommandResult(result.toString());
        } catch (Throwable error) {
            Log.e(TAG, "生成终态失败", error);
        }
    }

    private void resendResults(String messageId) {
        List<DeviceCommandStore.OutboxItem> items = store.listCommandResults();
        for (DeviceCommandStore.OutboxItem item : items) {
            if (messageId.equals(item.sourceMessageId)) {
                MqttManager.get(context).reportCommandResult(item.payload);
            }
        }
    }

    private String validateOperation(JSONObject data, String quantityField) {
        if (data == null) {
            return "data为空";
        }
        if (data.optString("operationNo", "").isEmpty()) {
            return "operationNo为空";
        }
        if (data.optString("operationToken", "").isEmpty()) {
            return "operationToken为空";
        }
        if (data.optInt(quantityField, 0) <= 0) {
            return quantityField + "必须为正整数";
        }
        String expireTime = data.optString("expireTime", "");
        if (expireTime.isEmpty() || isExpired(expireTime)) {
            return "指令已过期或expireTime格式错误";
        }
        return null;
    }

    private boolean isExpired(String expireTime) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            Date expires = format.parse(expireTime);
            return expires == null || System.currentTimeMillis() >= expires.getTime();
        } catch (Throwable error) {
            return true;
        }
    }

    private void setCashAcceptance(boolean enabled) {
        SerialManager serial = SerialManager.get(context);
        serial.sendCommand(enabled ? CMD_BILL_ENABLE : CMD_BILL_DISABLE, 0L, true);
        serial.sendCommand(enabled ? CMD_COIN_ENABLE : CMD_COIN_DISABLE, 0L, true);
    }

    private void restoreCashAcceptance() {
        setCashAcceptance(store.isCashEnabled());
    }

    private void broadcastCollection(String event, String message) {
        Intent intent = new Intent(AppConfig.ACTION_COLLECTION_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_COLLECTION_EVENT, event);
        intent.putExtra(EXTRA_COLLECTION_MESSAGE, message);
        context.sendBroadcast(intent);
    }
}
