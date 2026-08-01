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

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * V2 平台统一业务状态机。
 *
 * <p>现金接收只产生现金事实，不产生本地珠数。所有出珠（现金、扫码、会员、内部核销）
 * 都必须来自通过新版 SDK DeviceMqttCommandCodec 校验的 dispense_marbles。
 * 本类先持久化，再 ACK，再启动硬件；重复 messageId 只重发已保存结果。</p>
 */
public final class DeviceCommandManager {

    public static final String EXTRA_COLLECTION_EVENT = "collectionEvent";
    public static final String EXTRA_COLLECTION_MESSAGE = "collectionMessage";
    public static final String COLLECTION_READY = "ready";
    public static final String COLLECTION_STARTED = "started";
    public static final String COLLECTION_PROGRESS = "progress";
    public static final String COLLECTION_FINISHED = "finished";
    public static final String COLLECTION_FAILED = "failed";

    private static final String TAG = "GouzhuCommandV2";
    private static final long CASH_CONFIG_APPLY_TIMEOUT_MS = 5_000L;

    /* Android -> 控制板 V2。 */
    private static final int CMD_VERSION = 0x00;
    private static final int CMD_DISPENSE_START = 0x01;
    private static final int CMD_COLLECT_START = 0x02;
    private static final int CMD_COLLECT_STOP = 0x03;
    private static final int CMD_CASH_APPLY = 0x18;
    private static final int CMD_CASH_EVENT_STORED = 0x1A;
    private static final int CMD_BOARD_EVENT_STORED = 0x1B;
    private static final int CMD_HARDWARE_STATUS = 0x20;
    private static final int CMD_EMERGENCY_STOP = 0xFF;

    /* 控制板 -> Android V2。 */
    private static final int EVT_VERSION = 0x00;
    private static final int EVT_DISPENSE_STARTED = 0x01;
    private static final int EVT_DISPENSE_PROGRESS = 0x02;
    private static final int EVT_DISPENSE_COMPLETED = 0x03;
    private static final int EVT_DISPENSE_FAILED = 0x04;
    private static final int EVT_COLLECT_STARTED = 0x05;
    private static final int EVT_COLLECT_PROGRESS = 0x06;
    private static final int EVT_COLLECT_COMPLETED = 0x07;
    private static final int EVT_COLLECT_FAILED = 0x08;
    private static final int EVT_CASH_ACCEPTED = 0x10;
    private static final int EVT_CASH_ACCEPTANCE_STATUS = 0x11;
    private static final int EVT_CASH_DEVICE_STATUS = 0x12;
    private static final int EVT_BEAD_STOCK = 0x20;
    private static final int EVT_BEAD_LOW = 0x21;
    private static final int EVT_BEAD_EMPTY = 0x22;
    private static final int EVT_BEAD_REFILLED = 0x23;

    private static final int CASH_BANKNOTE_MASK = 1;
    private static final int CASH_COIN_MASK = 2;
    private static final int MAX_BOARD_QUANTITY = 0xFFFF;

    private static volatile DeviceCommandManager instance;

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder commandDecoder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean receiverRegistered;
    private Runnable collectionTimeoutRunnable;
    private Runnable cashConfigTimeoutRunnable;

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                return;
            }
            handleBoardEvent(
                    intent.getIntExtra("code2", -1),
                    intent.getLongExtra("data", 0L),
                    intent.getIntExtra("expandCode", 0)
            );
        }
    };

    private DeviceCommandManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.commandDecoder = new SdkCommandDecoder();
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
        serial.sendCommand(CMD_HARDWARE_STATUS, 0L, false);
        recoverActiveOperationsWithoutRestartingHardware();
        reapplyStoredCashConfiguration();
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
        cancelCashConfigTimeout();
    }

    /** 所有控制/配置命令先经过新版 SDK Codec 校验。 */
    public synchronized void handleCommand(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = commandDecoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.e(TAG, "拒绝未通过SDK协议校验的MQTT命令", error);
            MqttManager.get(context).reportFault(
                    "COMMAND_PROTOCOL_INVALID",
                    "平台命令校验失败",
                    3,
                    messageOf(error)
            );
            return;
        }

        JSONObject envelope = decoded.envelope;
        String messageId = envelope.optString("messageId", "").trim();
        String commandType = envelope.optString("commandType", "").trim();
        if (messageId.isEmpty() || commandType.isEmpty()) {
            return;
        }

        JSONObject data = envelope.optJSONObject("data");
        if ("command_result_ack".equals(commandType)) {
            handleResultAck(data);
            return;
        }
        if ("cash_event_response".equals(commandType)) {
            handleCashEventResponse(decoded, data);
            return;
        }
        if ("redemption_response".equals(commandType)) {
            handleRedemptionResponse(data);
            return;
        }

        if (store.hasCommand(messageId)) {
            resendResults(messageId);
            SerialManager.get(context).sendCommand(CMD_HARDWARE_STATUS, 0L, false);
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
                if (store.saveCommand(envelope)) {
                    publishTerminal(
                            envelope,
                            false,
                            0,
                            "UNSUPPORTED_COMMAND",
                            "不支持的指令类型：" + commandType,
                            null
                    );
                }
                break;
        }
    }

    private void handleDispense(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        String error = validateQuantityOperation(data, "quantity");
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

        int quantity = data.optInt("quantity", 0);
        int token = operationToken(envelope.optString("messageId", ""));
        try {
            data.put("boardToken", token);
            data.put("deviceActualQuantity", 0);
            data.put("deviceStartRequested", true);
            data.put("deviceStarted", false);
            data.put("deviceTerminal", false);
            data.put("deviceStartRequestedAt", System.currentTimeMillis());
        } catch (Throwable errorPut) {
            return;
        }

        /* 在发送串口之前持久化“可能启动”，进程恢复时绝不盲目重发。 */
        if (!store.saveCommand(envelope)) {
            reportStorageFault("出珠指令无法持久化");
            return;
        }
        store.setActiveDispense(envelope.optString("messageId", ""));
        publishAck(envelope);
        disableCashAcceptanceOnBoard();

        long packed = ((long) token << 24) | (quantity & 0x00FFFFFFL);
        if (!SerialManager.get(context).sendCommand(CMD_DISPENSE_START, packed, true)) {
            publishTerminal(envelope, false, 0,
                    "CONTROLLER_OFFLINE", "控制板未连接，物理动作未确认", null);
            store.clearActiveDispense();
            store.setCashBlocked(true);
        }
    }

    private void handleCollect(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        String error = validateQuantityOperation(data, "maximumQuantity");
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

        int token = operationToken(envelope.optString("messageId", ""));
        try {
            data.put("boardToken", token);
            data.put("deviceActualQuantity", 0);
            data.put("deviceStartRequested", false);
            data.put("deviceStarted", false);
            data.put("deviceTerminal", false);
        } catch (Throwable errorPut) {
            return;
        }
        if (!store.saveCommand(envelope)) {
            reportStorageFault("存珠指令无法持久化");
            return;
        }
        store.setActiveCollect(envelope.optString("messageId", ""));
        publishAck(envelope);
        disableCashAcceptanceOnBoard();
        broadcastCollection(COLLECTION_READY, "请倒入珠子，完成后点击开始存珠");
    }

    public synchronized boolean startPendingCollection() {
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null) {
            broadcastCollection(COLLECTION_FAILED, "没有可启动的存珠任务");
            return false;
        }
        if (data.optBoolean("deviceStartRequested", false)
                || data.optBoolean("deviceStarted", false)) {
            broadcastCollection(COLLECTION_FAILED, "该存珠任务已请求过硬件，禁止重复启动");
            return false;
        }

        int maximum = data.optInt("maximumQuantity", 0);
        int actual = data.optInt("deviceActualQuantity", 0);
        int remaining = maximum - actual;
        int token = data.optInt("boardToken", 0);
        if (remaining <= 0 || token <= 0) {
            return false;
        }
        try {
            data.put("deviceStartRequested", true);
            data.put("deviceStartRequestedAt", System.currentTimeMillis());
            if (!store.saveCommand(envelope)) {
                throw new IllegalStateException("存珠启动状态保存失败");
            }
        } catch (Throwable error) {
            broadcastCollection(COLLECTION_FAILED, messageOf(error));
            return false;
        }

        long packed = ((long) token << 24) | (remaining & 0x00FFFFFFL);
        if (!SerialManager.get(context).sendCommand(CMD_COLLECT_START, packed, true)) {
            broadcastCollection(COLLECTION_FAILED, "控制板未连接，物理动作状态未知");
            store.setCashBlocked(true);
            return false;
        }
        scheduleCollectionTimeout(data.optInt("sessionTimeoutSeconds", 300));
        broadcastCollection(COLLECTION_STARTED, "存珠启动请求已发送，等待控制板确认");
        return true;
    }

    public synchronized boolean finishPendingCollection() {
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null) {
            return false;
        }
        int token = data.optInt("boardToken", 0);
        if (token <= 0) {
            return false;
        }
        long packed = (long) token << 24;
        return SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, packed, true);
    }

    public boolean hasPendingCollection() {
        return !store.getActiveCollect().isEmpty();
    }

    public int getRunningStatus() {
        return store.getActiveDispense().isEmpty() && store.getActiveCollect().isEmpty()
                ? 0 : 1;
    }

    /** MQTT 重连后只补发持久化 outbox，不重启任何物理动作。 */
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
        String validationError = validateCashConfiguration(data);
        if (validationError != null) {
            disableCashAcceptanceOnBoard();
            store.setCashBlocked(true);
            store.saveCommand(envelope);
            publishTerminal(
                    envelope,
                    false,
                    0,
                    "CASH_CONFIGURATION_INVALID",
                    validationError,
                    null
            );
            return;
        }

        int version = data.optInt("configVersion", 0);
        boolean enabled = data.optBoolean("cashAcceptanceEnabled", false);
        boolean changeEnabled = data.optBoolean("changeEnabled", false);
        int expectedMask = expectedCashMask(data);
        try {
            data.put("expectedBoardMask", expectedMask);
            data.put("deviceTerminal", false);
            data.put("configApplyRequestedAt", System.currentTimeMillis());
        } catch (Throwable error) {
            return;
        }

        /* 先关闭入口，再原子保存完整配置快照，最后应用期望掩码。 */
        sendCashApply(0, version);
        if (!store.saveCashConfiguration(
                version,
                enabled,
                changeEnabled,
                envelope.toString()
        ) || !store.saveCommand(envelope)) {
            store.setCashBlocked(true);
            publishTerminal(envelope, false, 0,
                    "LOCAL_STORAGE_ERROR", "完整现金配置持久化失败", null);
            return;
        }

        store.setPendingConfigMessageId(envelope.optString("messageId", ""));
        store.setCashBlocked(false);
        publishAck(envelope);
        sendCashApply(expectedMask, version);
        scheduleCashConfigTimeout();
    }

    private String validateCashConfiguration(JSONObject data) {
        if (data == null) {
            return "现金配置data为空";
        }
        int version = data.optInt("configVersion", 0);
        if (version <= 0 || version > 0x00FFFFFF) {
            return "configVersion必须为1..16777215";
        }
        if (data.optBoolean("changeEnabled", false)) {
            return "当前设备不支持找零，changeEnabled必须为false";
        }
        boolean enabled = data.optBoolean("cashAcceptanceEnabled", false);
        JSONArray items = data.optJSONArray("cashSaleItems");
        if (!enabled) {
            return null;
        }
        if (items == null || items.length() == 0) {
            return "启用现金时cashSaleItems不能为空";
        }

        Set<String> unique = new HashSet<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                return "现金档位存在空项";
            }
            String medium = item.optString("cashMediumType", "");
            int amount = item.optInt("denominationAmount", 0);
            int quantity = item.optInt("marbleQuantity", 0);
            String tierNo = item.optString("cashSaleTierNo", "");
            if (!"banknote".equals(medium) && !"coin".equals(medium)) {
                return "cashMediumType仅支持banknote或coin";
            }
            if (amount <= 0 || amount > 0xFFFF) {
                return "denominationAmount必须为1..65535分";
            }
            if (quantity <= 0 || quantity > MAX_BOARD_QUANTITY) {
                return "marbleQuantity必须为1..65535";
            }
            if (tierNo.trim().isEmpty()) {
                return "cashSaleTierNo不能为空";
            }
            if (!unique.add(medium + "|" + amount)) {
                return "同介质同面额现金档位重复";
            }
        }
        return null;
    }

    private int expectedCashMask(JSONObject data) {
        if (data == null || !data.optBoolean("cashAcceptanceEnabled", false)) {
            return 0;
        }
        int mask = 0;
        JSONArray items = data.optJSONArray("cashSaleItems");
        if (items == null) {
            return 0;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            String medium = item == null ? "" : item.optString("cashMediumType", "");
            if ("banknote".equals(medium)) {
                mask |= CASH_BANKNOTE_MASK;
            } else if ("coin".equals(medium)) {
                mask |= CASH_COIN_MASK;
            }
        }
        return mask;
    }

    private synchronized void handleBoardEvent(int code2, long data, int expandCode) {
        switch (code2) {
            case EVT_VERSION:
                store.saveBoardVersion(data);
                MqttManager.get(context).reportStatus();
                break;
            case EVT_DISPENSE_STARTED:
            case EVT_DISPENSE_PROGRESS:
            case EVT_DISPENSE_COMPLETED:
            case EVT_DISPENSE_FAILED:
                handleDispenseBoardEvent(code2, data, expandCode);
                break;
            case EVT_COLLECT_STARTED:
            case EVT_COLLECT_PROGRESS:
            case EVT_COLLECT_COMPLETED:
            case EVT_COLLECT_FAILED:
                handleCollectBoardEvent(code2, data, expandCode);
                break;
            case EVT_CASH_ACCEPTED:
                handleCashAccepted(data, expandCode);
                break;
            case EVT_CASH_ACCEPTANCE_STATUS:
                handleCashAcceptanceStatus(data);
                break;
            case EVT_CASH_DEVICE_STATUS:
                Log.i(TAG, "现金设备诊断状态已更新");
                break;
            case EVT_BEAD_STOCK:
                broadcastHardwareStatus("库存：" + data + " 珠");
                break;
            case EVT_BEAD_LOW:
                broadcastHardwareStatus("库存偏低：" + data + " 珠");
                break;
            case EVT_BEAD_EMPTY:
                store.setCashBlocked(true);
                disableCashAcceptanceOnBoard();
                broadcastHardwareStatus("无珠，现金接收已关闭");
                break;
            case EVT_BEAD_REFILLED:
                broadcastHardwareStatus("已补珠，库存：" + data + " 珠；等待重新应用现金配置");
                store.setCashBlocked(false);
                reapplyStoredCashConfiguration();
                break;
            default:
                break;
        }
    }

    private void handleDispenseBoardEvent(int eventCode, long packed, int resultCode) {
        int token = (int) ((packed >>> 24) & 0xFF);
        int actual = (int) (packed & 0x00FFFFFFL);
        String messageId = store.getActiveDispense();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null || token == 0 || token != data.optInt("boardToken", -1)) {
            MqttManager.get(context).reportFault(
                    "CONTROLLER_OPERATION_MISMATCH",
                    "控制板出珠事件无法关联",
                    3,
                    "token=" + token + "，actual=" + actual
            );
            return;
        }

        try {
            data.put("deviceActualQuantity", actual);
            if (eventCode == EVT_DISPENSE_STARTED) {
                data.put("deviceStarted", true);
                data.put("deviceStartedAt", System.currentTimeMillis());
            }
            if (eventCode == EVT_DISPENSE_COMPLETED || eventCode == EVT_DISPENSE_FAILED) {
                data.put("deviceTerminal", true);
                data.put("boardResultCode", resultCode);
                data.put("deviceTerminalAt", System.currentTimeMillis());
            }
            if (!store.saveCommand(envelope)) {
                reportStorageFault("出珠硬件结果保存失败");
                return;
            }
        } catch (Throwable error) {
            return;
        }

        if (eventCode == EVT_DISPENSE_STARTED) {
            confirmBoardEventStored(eventCode, token);
            return;
        }
        if (eventCode == EVT_DISPENSE_PROGRESS) {
            return;
        }

        boolean success = eventCode == EVT_DISPENSE_COMPLETED && resultCode == 0;
        if (publishTerminal(
                envelope,
                success,
                actual,
                success ? "DISPENSE_COMPLETED" : boardResultName(resultCode),
                success ? "控制板按真实光眼计数完成出珠" : "控制板出珠失败或部分完成",
                null
        )) {
            confirmBoardEventStored(eventCode, token);
            store.clearActiveDispense();
            restoreCashAcceptance();
        }
    }

    private void handleCollectBoardEvent(int eventCode, long packed, int resultCode) {
        int token = (int) ((packed >>> 24) & 0xFF);
        int actual = (int) (packed & 0x00FFFFFFL);
        String messageId = store.getActiveCollect();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null || token == 0 || token != data.optInt("boardToken", -1)) {
            return;
        }

        try {
            data.put("deviceActualQuantity", actual);
            if (eventCode == EVT_COLLECT_STARTED) {
                data.put("deviceStarted", true);
                data.put("deviceStartedAt", System.currentTimeMillis());
            }
            if (eventCode == EVT_COLLECT_COMPLETED || eventCode == EVT_COLLECT_FAILED) {
                data.put("deviceTerminal", true);
                data.put("boardResultCode", resultCode);
                data.put("deviceTerminalAt", System.currentTimeMillis());
            }
            if (!store.saveCommand(envelope)) {
                reportStorageFault("存珠硬件结果保存失败");
                return;
            }
        } catch (Throwable error) {
            return;
        }

        if (eventCode == EVT_COLLECT_STARTED) {
            confirmBoardEventStored(eventCode, token);
            broadcastCollection(COLLECTION_STARTED, "控制板已确认启动存珠");
            return;
        }
        if (eventCode == EVT_COLLECT_PROGRESS) {
            broadcastCollection(COLLECTION_PROGRESS, "已存入 " + actual + " 珠");
            return;
        }

        cancelCollectionTimeout();
        boolean success = eventCode == EVT_COLLECT_COMPLETED && resultCode == 0;
        if (publishTerminal(
                envelope,
                success,
                actual,
                success ? "COLLECT_COMPLETED" : boardResultName(resultCode),
                success ? "控制板按真实光眼计数完成存珠" : "控制板存珠失败或部分完成",
                null
        )) {
            confirmBoardEventStored(eventCode, token);
            store.clearActiveCollect();
            restoreCashAcceptance();
            broadcastCollection(
                    success ? COLLECTION_FINISHED : COLLECTION_FAILED,
                    (success ? "存珠完成" : "存珠失败") + "，实际数量：" + actual
            );
        }
    }

    private void handleCashAccepted(long packed, int sequenceLow) {
        int mediumCode = (int) ((packed >>> 24) & 0xFF);
        int amountFen = (int) ((packed >>> 8) & 0xFFFF);
        int sequence = (((int) packed & 0xFF) << 8) | (sequenceLow & 0xFF);
        if (sequence <= 0 || amountFen <= 0
                || (mediumCode != 0 && mediumCode != 1)) {
            return;
        }

        DeviceCommandStore.CashEventRecord existing =
                store.findCashEventBySequence(sequence);
        if (existing != null) {
            confirmCashEventStored(sequence);
            MqttManager.get(context).reportCashEvent(existing.payload);
            return;
        }

        String medium = mediumCode == 0 ? "coin" : "banknote";
        DeviceCommandStore.CashTier tier = store.findCashTier(medium, amountFen);
        String eventNo = newCashEventNo(sequence);
        try {
            JSONObject payload = new JSONObject();
            payload.put("eventNo", eventNo);
            payload.put("eventType", "accepted");
            payload.put("cashMediumType", medium);
            payload.put("denominationAmount", amountFen);
            payload.put("cashCount", 1);
            payload.put("cashSaleTierNo", tier == null ? "" : tier.cashSaleTierNo);
            payload.put("configVersion",
                    tier == null ? store.getCashConfigVersion() : tier.configVersion);
            payload.put("timestamp", System.currentTimeMillis());

            if (!store.saveCashEvent(eventNo, sequence, payload.toString())) {
                reportStorageFault("现金事实持久化失败，控制板将继续重发同一事件");
                return;
            }
            confirmCashEventStored(sequence);
            if (tier == null) {
                store.setCashBlocked(true);
                MqttManager.get(context).reportFault(
                        "CASH_TIER_NOT_FOUND",
                        "现金档位不匹配",
                        3,
                        medium + "，面额=" + amountFen + "分"
                );
            }
            MqttManager.get(context).reportCashEvent(payload.toString());
        } catch (Throwable error) {
            reportStorageFault("现金事件组装失败：" + messageOf(error));
        }
    }

    private void handleCashAcceptanceStatus(long packed) {
        int actualMask = (int) ((packed >>> 24) & 0xFF);
        int actualVersion = (int) (packed & 0x00FFFFFFL);
        String pendingMessageId = store.getPendingConfigMessageId();
        JSONObject envelope = store.loadCommand(pendingMessageId);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (data == null) {
            return;
        }
        int expectedMask = data.optInt("expectedBoardMask", -1);
        int expectedVersion = data.optInt("configVersion", -1);
        if (actualMask != expectedMask || actualVersion != expectedVersion) {
            return;
        }

        cancelCashConfigTimeout();
        try {
            JSONObject extra = new JSONObject();
            extra.put("configVersion", actualVersion);
            extra.put("cashAcceptanceMask", actualMask);
            if (publishTerminal(
                    envelope,
                    true,
                    0,
                    "CASH_CONFIGURATION_APPLIED",
                    "完整现金配置已持久化并由控制板应用",
                    extra
            )) {
                store.clearPendingConfigMessageId();
            }
        } catch (Throwable error) {
            reportStorageFault("现金配置终态组装失败");
        }
    }

    private void handleCashEventResponse(
            SdkCommandDecoder.DecodedCommand decoded,
            JSONObject data
    ) {
        if (data == null) {
            return;
        }
        String eventNo = firstNonBlank(
                data.optString("eventNo", ""),
                data.optString("sourceEventNo", "")
        );
        DeviceCommandStore.CashEventRecord record = store.findCashEvent(eventNo);
        if (record == null) {
            return;
        }

        if (decoded.invokeCashStatus("isUnknown")) {
            store.updateCashEventStatus(eventNo, "unknown");
            MqttManager.get(context).reportCashEvent(record.payload);
            return;
        }

        /* 除 unknown 外，平台已明确接收原现金事实，可删除现金 outbox。 */
        store.removeCashOutbox(eventNo);
        if (decoded.invokeCashStatus("isPending")) {
            store.updateCashEventStatus(eventNo, "pending");
            return;
        }
        if (decoded.invokeCashStatus("isProcessing")) {
            store.updateCashEventStatus(eventNo, "processing");
            return;
        }
        if (decoded.invokeCashStatus("isCompleted")) {
            store.updateCashEventStatus(eventNo, "completed");
            restoreCashAcceptance();
            return;
        }
        if (decoded.invokeCashStatus("isManualReview")) {
            store.updateCashEventStatus(eventNo, "manual_review");
            store.setCashBlocked(true);
            disableCashAcceptanceOnBoard();
            return;
        }
        if (decoded.invokeCashStatus("isRejected")) {
            store.updateCashEventStatus(eventNo, "rejected");
            store.setCashBlocked(true);
            disableCashAcceptanceOnBoard();
        }
    }

    private void handleResultAck(JSONObject data) {
        if (data == null) {
            return;
        }
        String receiptStatus = data.optString("receiptStatus", "");
        if (!"recorded".equals(receiptStatus)) {
            return;
        }
        String sourceMessageId = firstNonBlank(
                data.optString("sourceMessageId", ""),
                data.optString("messageId", "")
        );
        String eventNo = data.optString("eventNo", "");
        String resultStatus = data.optString("resultStatus", data.optString("status", ""));
        if (!sourceMessageId.isEmpty() && !eventNo.isEmpty() && !resultStatus.isEmpty()) {
            store.removeCommandResult(sourceMessageId, eventNo, resultStatus);
        }
    }

    private void handleRedemptionResponse(JSONObject data) {
        if (data == null) {
            return;
        }
        String status = data.optString("status", "unknown");
        String message;
        if ("accepted".equals(status)) {
            message = "核销已受理，等待平台下发出珠指令";
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

    private boolean publishAck(JSONObject envelope) {
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
            if (!store.saveCommandResult(
                    messageId,
                    messageId + "-ack",
                    "ack",
                    result.toString()
            )) {
                return false;
            }
            MqttManager.get(context).reportCommandResult(result.toString());
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private boolean publishTerminal(
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
            String status = success ? "success" : "failed";
            result.put("status", status);
            result.put("eventNo", messageId + "-result");
            result.put("operationNo", data == null ? "" : data.optString("operationNo", ""));
            if ("dispense_marbles".equals(commandType)
                    || "collect_marbles".equals(commandType)) {
                result.put("actualQuantity", Math.max(0, actualQuantity));
                result.put("operationToken",
                        data == null ? "" : data.optString("operationToken", ""));
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
            if (!store.saveCommandResult(
                    messageId,
                    messageId + "-result",
                    status,
                    result.toString()
            )) {
                return false;
            }
            MqttManager.get(context).reportCommandResult(result.toString());
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "生成硬件终态失败", error);
            return false;
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

    private void recoverActiveOperationsWithoutRestartingHardware() {
        String dispense = store.getActiveDispense();
        String collect = store.getActiveCollect();
        if (!dispense.isEmpty() || !collect.isEmpty()) {
            store.setCashBlocked(true);
            disableCashAcceptanceOnBoard();
            MqttManager.get(context).reportFault(
                    "PHYSICAL_RESULT_UNKNOWN",
                    "检测到进程重启前已请求的物理动作",
                    3,
                    "禁止自动重启电机，请核对现场并人工处理"
            );
        }
        if (!collect.isEmpty()) {
            broadcastCollection(COLLECTION_FAILED, "检测到中断的存珠动作，已禁止自动重启");
        }
    }

    private void reapplyStoredCashConfiguration() {
        DeviceCommandStore.CashConfigurationRecord config = store.loadCashConfiguration();
        if (config == null || config.changeEnabled || store.isCashBlocked()
                || !store.getActiveDispense().isEmpty()
                || !store.getActiveCollect().isEmpty()) {
            disableCashAcceptanceOnBoard();
            return;
        }
        JSONObject envelope = parseObject(config.snapshotJson);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        int mask = config.enabled ? expectedCashMask(data) : 0;
        sendCashApply(mask, config.configVersion);
    }

    private void restoreCashAcceptance() {
        if (store.isCashEnabled()
                && store.getActiveDispense().isEmpty()
                && store.getActiveCollect().isEmpty()) {
            reapplyStoredCashConfiguration();
        } else {
            disableCashAcceptanceOnBoard();
        }
    }

    private void disableCashAcceptanceOnBoard() {
        DeviceCommandStore.CashConfigurationRecord config = store.loadCashConfiguration();
        int version = config == null ? 1 : Math.max(1, config.configVersion);
        sendCashApply(0, version);
    }

    private void sendCashApply(int mask, int configVersion) {
        long packed = ((long) (mask & 0xFF) << 24)
                | (configVersion & 0x00FFFFFFL);
        SerialManager.get(context).sendCommand(CMD_CASH_APPLY, packed, true);
    }

    private void confirmCashEventStored(int sequence) {
        SerialManager.get(context).sendCommand(
                CMD_CASH_EVENT_STORED,
                sequence & 0xFFFFL,
                true
        );
    }

    private void confirmBoardEventStored(int eventCode, int token) {
        long packed = ((long) (eventCode & 0xFF) << 24)
                | ((long) (token & 0xFF) << 16);
        SerialManager.get(context).sendCommand(CMD_BOARD_EVENT_STORED, packed, true);
    }

    private String validateQuantityOperation(JSONObject data, String field) {
        if (data == null) {
            return "data为空";
        }
        int quantity = data.optInt(field, 0);
        if (quantity <= 0 || quantity > MAX_BOARD_QUANTITY) {
            return field + "必须为1..65535";
        }
        if (data.optString("operationNo", "").trim().isEmpty()) {
            return "operationNo为空";
        }
        if (data.optString("operationToken", "").trim().isEmpty()) {
            return "operationToken为空";
        }
        return null;
    }

    private static int operationToken(String messageId) {
        int hash = messageId == null ? 1 : messageId.hashCode();
        return Math.floorMod(hash, 255) + 1;
    }

    private void scheduleCollectionTimeout(int seconds) {
        cancelCollectionTimeout();
        int safeSeconds = seconds <= 0 ? 300 : seconds;
        collectionTimeoutRunnable = () -> {
            synchronized (DeviceCommandManager.this) {
                finishPendingCollection();
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

    private void scheduleCashConfigTimeout() {
        cancelCashConfigTimeout();
        cashConfigTimeoutRunnable = () -> {
            synchronized (DeviceCommandManager.this) {
                String messageId = store.getPendingConfigMessageId();
                JSONObject envelope = store.loadCommand(messageId);
                if (envelope == null) {
                    return;
                }
                store.setCashBlocked(true);
                disableCashAcceptanceOnBoard();
                if (publishTerminal(
                        envelope,
                        false,
                        0,
                        "CASH_CONFIGURATION_APPLY_TIMEOUT",
                        "控制板未在限定时间确认完整现金配置",
                        null
                )) {
                    store.clearPendingConfigMessageId();
                }
            }
        };
        mainHandler.postDelayed(cashConfigTimeoutRunnable, CASH_CONFIG_APPLY_TIMEOUT_MS);
    }

    private void cancelCashConfigTimeout() {
        if (cashConfigTimeoutRunnable != null) {
            mainHandler.removeCallbacks(cashConfigTimeoutRunnable);
            cashConfigTimeoutRunnable = null;
        }
    }

    private void broadcastCollection(String event, String message) {
        Intent intent = new Intent(AppConfig.ACTION_COLLECTION_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_COLLECTION_EVENT, event);
        intent.putExtra(EXTRA_COLLECTION_MESSAGE, message);
        context.sendBroadcast(intent);
    }

    private void broadcastHardwareStatus(String value) {
        Intent intent = new Intent(AppConfig.ACTION_SERVICE_STATUS);
        intent.setPackage(context.getPackageName());
        intent.putExtra("key", "hardware");
        intent.putExtra("value", value);
        context.sendBroadcast(intent);
    }

    private void reportStorageFault(String message) {
        MqttManager.get(context).reportFault(
                "LOCAL_STORAGE_ERROR",
                "本地业务数据库异常",
                3,
                message
        );
    }

    private String newCashEventNo(int sequence) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return "CE-A-" + format.format(new Date()) + "-"
                + String.format(Locale.ROOT, "%04X", sequence) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String boardResultName(int code) {
        switch (code) {
            case 1:
                return "CONTROLLER_BUSY";
            case 2:
                return "NO_MARBLES";
            case 3:
                return "INVALID_QUANTITY";
            case 4:
                return "SENSOR_TIMEOUT";
            case 5:
                return "ABORTED";
            case 6:
                return "NOT_ACTIVE";
            default:
                return "CONTROLLER_ERROR_" + code;
        }
    }

    private static JSONObject parseObject(String value) {
        try {
            return value == null || value.isEmpty() ? null : new JSONObject(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
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
