package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.hardware.SerialCashConfigurationAdapter;
import com.gouzhu.hardware.SerialMarbleHardwareAdapter;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;
import com.pinball.xiaoda.device.sdk.hardware.CashTier;
import com.pinball.xiaoda.device.sdk.hardware.CollectRequest;
import com.pinball.xiaoda.device.sdk.hardware.DispenseRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.CashConfigurationCommandData;
import com.pinball.xiaoda.device.sdk.protocol.CashEventResponseCommandData;
import com.pinball.xiaoda.device.sdk.protocol.CommandResultAcknowledgement;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 平台统一现金和物理操作状态机。
 *
 * <p>每一张纸币或每一个投币脉冲都作为独立现金事实上报。设备不在本地按金额
 * 计算珠数，也不等待上一笔现金业务完成后才接收下一笔。平台按设备现金会话累计
 * 金额，并另行下发 dispense_marbles；只有该命令能够启动出珠电机。</p>
 */
final class PlatformCommandRuntime {

    private static final String TAG = "GouzhuPlatformV2";

    private static final int CMD_VERSION = 0x00;
    private static final int CMD_CASH_EVENT_STORED = 0x1A;
    private static final int CMD_HARDWARE_STATUS = 0x20;

    private static final int EVT_VERSION = 0x00;
    private static final int EVT_DISPENSE_STARTED = 0x01;
    private static final int EVT_DISPENSE_COMPLETED = 0x03;
    private static final int EVT_DISPENSE_FAILED = 0x04;
    private static final int EVT_COLLECT_STARTED = 0x05;
    private static final int EVT_COLLECT_PROGRESS = 0x06;
    private static final int EVT_COLLECT_COMPLETED = 0x07;
    private static final int EVT_COLLECT_FAILED = 0x08;
    private static final int EVT_CASH_ACCEPTED = 0x10;
    private static final int EVT_CASH_DEVICE_STATUS = 0x12;
    private static final int EVT_BEAD_STOCK = 0x20;
    private static final int EVT_BEAD_LOW = 0x21;
    private static final int EVT_BEAD_EMPTY = 0x22;
    private static final int EVT_BEAD_REFILLED = 0x23;

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder decoder;
    private final SerialMarbleHardwareAdapter marbleAdapter;
    private final SerialCashConfigurationAdapter cashAdapter;
    private final ExecutorService hardwareExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "购珠机-平台硬件V2");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentHashMap<String, SdkCommandDecoder.DecodedCommand>
            liveCommands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CollectRequest>
            pendingCollectRequests = new ConcurrentHashMap<>();

    private boolean receiverRegistered;

    private final SerialMarbleHardwareAdapter.Observer hardwareObserver =
            new SerialMarbleHardwareAdapter.Observer() {
                @Override
                public boolean onStarted(
                        String messageId,
                        int eventCode,
                        int token,
                        int requested
                ) {
                    JSONObject envelope = store.loadCommand(messageId);
                    JSONObject data = envelope == null
                            ? null
                            : envelope.optJSONObject("data");
                    if (data == null) {
                        return false;
                    }
                    try {
                        data.put("deviceStarted", true);
                        data.put("deviceStartedAt", System.currentTimeMillis());
                        data.put("deviceRequestedQuantity", requested);
                        return store.saveCommand(envelope);
                    } catch (Throwable error) {
                        reportStorageFault("硬件启动状态保存失败：" + messageOf(error));
                        return false;
                    }
                }

                @Override
                public void onProgress(
                        String messageId,
                        int eventCode,
                        int token,
                        int actual
                ) {
                    JSONObject envelope = store.loadCommand(messageId);
                    JSONObject data = envelope == null
                            ? null
                            : envelope.optJSONObject("data");
                    if (data != null) {
                        try {
                            data.put("deviceActualQuantity", actual);
                            store.saveCommand(envelope);
                        } catch (Throwable error) {
                            reportStorageFault("硬件进度保存失败：" + messageOf(error));
                        }
                    }
                    if (eventCode == EVT_COLLECT_PROGRESS) {
                        broadcastCollection(
                                DeviceCommandManager.COLLECTION_PROGRESS,
                                "已存入 " + actual + " 珠"
                        );
                    }
                }
            };

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

    PlatformCommandRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.decoder = new SdkCommandDecoder();
        this.marbleAdapter = new SerialMarbleHardwareAdapter(this.context);
        this.cashAdapter = new SerialCashConfigurationAdapter(this.context);
    }

    synchronized void start() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_EVENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        boardReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                context.registerReceiver(boardReceiver, filter);
            }
            receiverRegistered = true;
        }

        marbleAdapter.start(hardwareObserver);
        cashAdapter.start();
        SerialManager.get(context).sendCommand(CMD_VERSION, 0L, false);
        SerialManager.get(context).sendCommand(CMD_HARDWARE_STATUS, 0L, false);
        recoverWithoutRepeatingPhysicalAction();
        reapplyCashConfiguration();
    }

    synchronized void stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(boardReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        cashAdapter.stop();
        marbleAdapter.stop();
    }

    void handleCommand(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            Log.e(TAG, "拒绝未通过SDK校验的平台命令", error);
            MqttManager.get(context).reportFault(
                    "COMMAND_PROTOCOL_INVALID",
                    "平台命令校验失败",
                    3,
                    messageOf(error)
            );
            return;
        }

        String messageId = decoded.sdkCommand.getMessageId();
        String commandType = decoded.sdkCommand.getCommandType();
        if (blank(messageId) || blank(commandType)) {
            return;
        }

        if ("command_result_ack".equals(commandType)) {
            handleCommandResultAcknowledgement(decoded);
            return;
        }
        if ("cash_event_response".equals(commandType)) {
            handleCashEventResponse(decoded);
            return;
        }
        if ("redemption_response".equals(commandType)) {
            handleRedemptionResponse(decoded.envelope.optJSONObject("data"));
            return;
        }

        if (store.hasCommand(messageId)) {
            resendCommandResults(messageId);
            return;
        }

        switch (commandType) {
            case "dispense_marbles":
                acceptDispense(decoded);
                break;
            case "collect_marbles":
                acceptCollect(decoded);
                break;
            case "sync_cash_configuration":
                acceptCashConfiguration(decoded);
                break;
            default:
                if (store.saveCommand(decoded.envelope)) {
                    publishSdkGenericTerminal(
                            decoded,
                            false,
                            "UNSUPPORTED_COMMAND",
                            "不支持的指令类型：" + commandType
                    );
                }
                break;
        }
    }

    private void acceptDispense(SdkCommandDecoder.DecodedCommand decoded) {
        final DispenseRequest request;
        try {
            request = decoded.toDispenseRequest(System.currentTimeMillis());
        } catch (Throwable error) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "SDK_HARDWARE_MAPPING_FAILED",
                    messageOf(error)
            );
            return;
        }

        if (request.getQuantity() <= 0 || request.getQuantity() > 0xFFFF) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "PARAM_INVALID",
                    "quantity必须为1..65535"
            );
            return;
        }
        if (hasActiveOperation()) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "DEVICE_BUSY",
                    "设备存在未完成物理任务"
            );
            return;
        }

        String messageId = request.getMessageId();
        int token = SerialMarbleHardwareAdapter.tokenForMessageId(messageId);
        if (!preparePhysicalCommand(decoded.envelope, token, true)) {
            reportStorageFault("出珠指令无法持久化");
            return;
        }

        store.setActiveDispense(messageId);
        liveCommands.put(messageId, decoded);
        if (!publishSdkAck(decoded)) {
            liveCommands.remove(messageId);
            store.clearActiveDispense();
            reportStorageFault("出珠ACK无法写入outbox，未启动电机");
            return;
        }

        hardwareExecutor.execute(() -> {
            HardwareExecutionResult result = marbleAdapter.dispense(request);
            finishPhysicalOperation(
                    decoded,
                    result,
                    false,
                    result.isSuccessful()
                            ? EVT_DISPENSE_COMPLETED
                            : EVT_DISPENSE_FAILED
            );
        });
    }

    private void acceptCollect(SdkCommandDecoder.DecodedCommand decoded) {
        final CollectRequest request;
        try {
            request = decoded.toCollectRequest(System.currentTimeMillis());
        } catch (Throwable error) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "SDK_HARDWARE_MAPPING_FAILED",
                    messageOf(error)
            );
            return;
        }

        if (request.getMaximumQuantity() <= 0
                || request.getMaximumQuantity() > 0xFFFF) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "PARAM_INVALID",
                    "maximumQuantity必须为1..65535"
            );
            return;
        }
        if (hasActiveOperation()) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "DEVICE_BUSY",
                    "设备存在未完成物理任务"
            );
            return;
        }

        String messageId = request.getMessageId();
        int token = SerialMarbleHardwareAdapter.tokenForMessageId(messageId);
        if (!preparePhysicalCommand(decoded.envelope, token, false)) {
            reportStorageFault("存珠指令无法持久化");
            return;
        }

        store.setActiveCollect(messageId);
        liveCommands.put(messageId, decoded);
        pendingCollectRequests.put(messageId, request);
        if (!publishSdkAck(decoded)) {
            pendingCollectRequests.remove(messageId);
            liveCommands.remove(messageId);
            store.clearActiveCollect();
            reportStorageFault("存珠ACK无法写入outbox，未启动电机");
            return;
        }

        broadcastCollection(
                DeviceCommandManager.COLLECTION_READY,
                "请倒入珠子，再点击开始存珠"
        );
    }

    private boolean preparePhysicalCommand(
            JSONObject envelope,
            int token,
            boolean startRequested
    ) {
        JSONObject data = envelope.optJSONObject("data");
        if (data == null || token <= 0) {
            return false;
        }
        try {
            data.put("boardToken", token);
            data.put("deviceStartRequested", startRequested);
            data.put("deviceStarted", false);
            data.put("deviceTerminal", false);
            data.put("deviceActualQuantity", 0);
            if (startRequested) {
                data.put("deviceStartRequestedAt", System.currentTimeMillis());
            }
            return store.saveCommand(envelope);
        } catch (Throwable error) {
            return false;
        }
    }

    synchronized boolean startPendingCollection() {
        String messageId = store.getActiveCollect();
        CollectRequest request = pendingCollectRequests.get(messageId);
        SdkCommandDecoder.DecodedCommand decoded = liveCommands.get(messageId);
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null
                ? null
                : envelope.optJSONObject("data");
        if (request == null || decoded == null || data == null) {
            broadcastCollection(
                    DeviceCommandManager.COLLECTION_FAILED,
                    "存珠任务缺少当前SDK上下文，禁止恢复或重复启动硬件"
            );
            return false;
        }
        if (data.optBoolean("deviceStartRequested", false)
                || data.optBoolean("deviceStarted", false)) {
            broadcastCollection(
                    DeviceCommandManager.COLLECTION_FAILED,
                    "该存珠任务已经请求过硬件，禁止重复启动"
            );
            return false;
        }

        try {
            data.put("deviceStartRequested", true);
            data.put("deviceStartRequestedAt", System.currentTimeMillis());
            if (!store.saveCommand(envelope)) {
                throw new IllegalStateException("存珠启动状态保存失败");
            }
        } catch (Throwable error) {
            broadcastCollection(
                    DeviceCommandManager.COLLECTION_FAILED,
                    messageOf(error)
            );
            return false;
        }

        hardwareExecutor.execute(() -> {
            HardwareExecutionResult result = marbleAdapter.collect(request);
            finishPhysicalOperation(
                    decoded,
                    result,
                    true,
                    result.isSuccessful()
                            ? EVT_COLLECT_COMPLETED
                            : EVT_COLLECT_FAILED
            );
        });
        broadcastCollection(
                DeviceCommandManager.COLLECTION_STARTED,
                "存珠启动请求已发送，等待控制板真实计数"
        );
        return true;
    }

    synchronized boolean finishPendingCollection() {
        String messageId = store.getActiveCollect();
        return !blank(messageId) && marbleAdapter.stopCollect(messageId);
    }

    boolean hasPendingCollection() {
        return !blank(store.getActiveCollect());
    }

    int getRunningStatus() {
        return hasActiveOperation() ? 1 : 0;
    }

    void flushPending() {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            MqttManager.get(context).reportCommandResult(item.payload);
        }
        for (DeviceCommandStore.OutboxItem item : store.listCashEvents()) {
            MqttManager.get(context).reportCashEvent(item.payload);
        }
    }

    private void acceptCashConfiguration(SdkCommandDecoder.DecodedCommand decoded) {
        final CashConfigurationCommandData config;
        try {
            config = decoded.sdkCommand.requireData(
                    CashConfigurationCommandData.class
            );
        } catch (Throwable error) {
            store.saveCommand(decoded.envelope);
            publishSdkGenericTerminal(
                    decoded,
                    false,
                    "CASH_CONFIGURATION_INVALID",
                    messageOf(error)
            );
            return;
        }

        String validationError = validateCashConfiguration(config);
        if (validationError != null) {
            store.saveCommand(decoded.envelope);
            publishSdkConfigurationTerminal(
                    decoded,
                    false,
                    safeConfigVersion(config),
                    "CASH_CONFIGURATION_INVALID",
                    validationError
            );
            return;
        }

        long configVersion = config.getConfigVersion();
        boolean enabled = config.isCashAcceptanceEnabled();
        List<CashTier> tiers = toCashTiers(config);

        if (!store.saveCashConfiguration(
                (int) configVersion,
                enabled,
                config.isChangeEnabled(),
                decoded.envelope.toString()
        ) || !store.saveCommand(decoded.envelope)) {
            publishSdkConfigurationTerminal(
                    decoded,
                    false,
                    configVersion,
                    "LOCAL_STORAGE_ERROR",
                    "完整现金配置持久化失败"
            );
            return;
        }

        String messageId = decoded.sdkCommand.getMessageId();
        store.setPendingConfigMessageId(messageId);
        liveCommands.put(messageId, decoded);
        if (!publishSdkAck(decoded)) {
            liveCommands.remove(messageId);
            store.clearPendingConfigMessageId();
            reportStorageFault("现金配置ACK无法写入outbox");
            return;
        }

        hardwareExecutor.execute(() -> {
            CashConfigurationResult result = enabled
                    ? cashAdapter.apply(configVersion, tiers)
                    : cashAdapter.applyDisabled(configVersion);
            boolean success = result.isApplied();
            publishSdkConfigurationTerminal(
                    decoded,
                    success,
                    configVersion,
                    success
                            ? "CASH_CONFIGURATION_APPLIED"
                            : "CASH_CONFIGURATION_REJECTED",
                    success
                            ? "现金配置已完整持久化并由控制板应用"
                            : safe(result.getMessage())
            );
            store.clearPendingConfigMessageId();
            liveCommands.remove(messageId);
        });
    }

    private String validateCashConfiguration(CashConfigurationCommandData config) {
        if (config == null || config.getConfigVersion() == null
                || config.getConfigVersion() <= 0L
                || config.getConfigVersion() > 0x00FFFFFFL) {
            return "configVersion必须为1..16777215";
        }
        if (config.isChangeEnabled()) {
            return "当前设备不支持找零，changeEnabled必须为false";
        }
        if (!config.isCashAcceptanceEnabled()) {
            return null;
        }

        List<CashConfigurationCommandData.CashSaleItem> items =
                config.getCashSaleItems();
        if (items == null || items.isEmpty()) {
            return "启用现金时cashSaleItems不能为空";
        }

        Set<String> unique = new HashSet<>();
        for (CashConfigurationCommandData.CashSaleItem item : items) {
            if (item == null
                    || item.getDenominationAmount() == null
                    || item.getMarbleQuantity() == null) {
                return "现金档位字段不完整";
            }
            String medium = safe(item.getCashMediumType());
            int amount = item.getDenominationAmount();
            int quantity = item.getMarbleQuantity();
            String tierNo = safe(item.getCashSaleTierNo());
            if (!"banknote".equals(medium) && !"coin".equals(medium)) {
                return "cashMediumType仅支持banknote或coin";
            }
            if (amount <= 0 || amount > 0xFFFF) {
                return "denominationAmount必须为1..65535分";
            }
            if (quantity <= 0 || quantity > 0xFFFF) {
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

    private List<CashTier> toCashTiers(CashConfigurationCommandData config) {
        List<CashTier> result = new ArrayList<>();
        List<CashConfigurationCommandData.CashSaleItem> items =
                config.getCashSaleItems();
        if (items == null) {
            return result;
        }
        for (CashConfigurationCommandData.CashSaleItem item : items) {
            result.add(new CashTier(
                    item.getCashMediumType(),
                    item.getDenominationAmount(),
                    item.getMarbleQuantity(),
                    item.getCashSaleTierNo()
            ));
        }
        return result;
    }

    private void finishPhysicalOperation(
            SdkCommandDecoder.DecodedCommand decoded,
            HardwareExecutionResult hardwareResult,
            boolean collect,
            int terminalEventCode
    ) {
        String messageId = decoded.sdkCommand.getMessageId();
        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null
                ? null
                : envelope.optJSONObject("data");
        int actual = hardwareResult == null
                ? 0
                : Math.max(0, hardwareResult.getActualQuantity());
        boolean success = hardwareResult != null && hardwareResult.isSuccessful();
        String resultCode = hardwareResult == null
                ? "HARDWARE_RESULT_MISSING"
                : safe(hardwareResult.getResultCode());
        String resultMessage = hardwareResult == null
                ? "硬件适配器未返回结果"
                : safe(hardwareResult.getResultMessage());
        int token = SerialMarbleHardwareAdapter.tokenForMessageId(messageId);

        if (data == null) {
            reportPhysicalUnknown(messageId, actual, "物理终态无法关联持久化命令");
            return;
        }

        try {
            data.put("deviceActualQuantity", actual);
            data.put("deviceTerminal", true);
            data.put("deviceTerminalAt", System.currentTimeMillis());
            data.put("deviceResultCode", resultCode);
            data.put("deviceResultMessage", resultMessage);
            if (!store.saveCommand(envelope)) {
                throw new IllegalStateException("物理终态保存失败");
            }
        } catch (Throwable error) {
            reportStorageFault(messageOf(error));
            return;
        }

        if (!publishSdkPhysicalTerminal(
                decoded,
                success,
                actual,
                resultCode,
                resultMessage
        )) {
            return;
        }

        marbleAdapter.confirmBoardEventStored(terminalEventCode, token);
        if (collect) {
            store.clearActiveCollect();
            pendingCollectRequests.remove(messageId);
            broadcastCollection(
                    success
                            ? DeviceCommandManager.COLLECTION_FINISHED
                            : DeviceCommandManager.COLLECTION_FAILED,
                    (success ? "存珠完成" : "存珠失败或部分完成")
                            + "，真实数量：" + actual
            );
        } else {
            store.clearActiveDispense();
        }
        liveCommands.remove(messageId);
    }

    private synchronized void handleBoardEvent(
            int code2,
            long packed,
            int expandCode
    ) {
        switch (code2) {
            case EVT_VERSION:
                store.saveBoardVersion(packed);
                MqttManager.get(context).reportStatus();
                break;
            case EVT_CASH_ACCEPTED:
                persistCashFact(packed, expandCode);
                break;
            case EVT_CASH_DEVICE_STATUS:
                Log.i(TAG, "现金设备诊断=0x" + Long.toHexString(packed));
                break;
            case EVT_BEAD_STOCK:
                broadcastHardwareStatus("库存：" + packed + " 珠");
                break;
            case EVT_BEAD_LOW:
                broadcastHardwareStatus("库存偏低：" + packed + " 珠");
                break;
            case EVT_BEAD_EMPTY:
                broadcastHardwareStatus("无珠；现金仍按硬件事实上报，等待平台处理");
                break;
            case EVT_BEAD_REFILLED:
                broadcastHardwareStatus("已补珠，库存：" + packed + " 珠");
                break;
            case EVT_DISPENSE_STARTED:
            case EVT_DISPENSE_COMPLETED:
            case EVT_DISPENSE_FAILED:
            case EVT_COLLECT_STARTED:
            case EVT_COLLECT_COMPLETED:
            case EVT_COLLECT_FAILED:
                persistOrphanBoardEvent(code2, packed, expandCode);
                break;
            default:
                break;
        }
    }

    private void persistCashFact(long packed, int sequenceLow) {
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
            confirmCashStored(sequence);
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
            payload.put("boardSequence", sequence);
            payload.put("cashSaleTierNo", tier == null ? "" : tier.cashSaleTierNo);
            payload.put(
                    "configVersion",
                    tier == null ? store.getCashConfigVersion() : tier.configVersion
            );
            payload.put("timestamp", System.currentTimeMillis());

            if (!store.saveCashEvent(eventNo, sequence, payload.toString())) {
                reportStorageFault("现金事实写入SQLite/outbox失败");
                return;
            }

            /* 先持久化，再确认控制板；确认后控制板可立即发送队列中的下一笔现金。 */
            confirmCashStored(sequence);
            MqttManager.get(context).reportCashEvent(payload.toString());

            if (tier == null) {
                MqttManager.get(context).reportFault(
                        "CASH_TIER_NOT_FOUND",
                        "现金档位不匹配",
                        3,
                        medium + "，面额=" + amountFen + "分"
                );
            }
        } catch (Throwable error) {
            reportStorageFault("现金事件组装失败：" + messageOf(error));
        }
    }

    private void confirmCashStored(int sequence) {
        SerialManager.get(context).sendCommand(
                CMD_CASH_EVENT_STORED,
                sequence & 0xFFFFL,
                true
        );
    }

    private void handleCashEventResponse(SdkCommandDecoder.DecodedCommand decoded) {
        CashEventResponseCommandData response = decoded.requireCashEventResponse();
        String eventNo = response.getEventNo();
        DeviceCommandStore.CashEventRecord record = store.findCashEvent(eventNo);
        if (record == null) {
            return;
        }

        if (response.isUnknown()) {
            store.updateCashEventStatus(eventNo, "unknown");
            MqttManager.get(context).reportCashEvent(record.payload);
            return;
        }

        store.removeCashOutbox(eventNo);
        store.updateCashEventStatus(eventNo, safe(response.getStatus()));
        if (response.isManualReview() || response.isRejected()) {
            MqttManager.get(context).reportFault(
                    "CASH_EVENT_" + safe(response.getStatus()).toUpperCase(Locale.ROOT),
                    "现金事件需要处理",
                    2,
                    safe(response.getMessage())
            );
        }
        /* 不读取 requestedQuantity；继续接收后续现金，等待平台统一下发出珠。 */
    }

    private void handleCommandResultAcknowledgement(
            SdkCommandDecoder.DecodedCommand decoded
    ) {
        CommandResultAcknowledgement acknowledgement =
                decoded.sdkCommand.requireData(CommandResultAcknowledgement.class);
        if (!acknowledgement.isRecorded()) {
            return;
        }
        store.removeCommandResult(
                acknowledgement.getSourceMessageId(),
                acknowledgement.getEventNo(),
                acknowledgement.getResultStatus()
        );
    }

    private void handleRedemptionResponse(JSONObject data) {
        String status = data == null
                ? "unknown"
                : data.optString("status", "unknown");
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

    private boolean publishSdkAck(SdkCommandDecoder.DecodedCommand decoded) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            return saveAndPublish(decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            ));
        } catch (Throwable error) {
            Log.e(TAG, "SDK ACK生成失败", error);
            return false;
        }
    }

    private boolean publishSdkPhysicalTerminal(
            SdkCommandDecoder.DecodedCommand decoded,
            boolean success,
            int actual,
            String resultCode,
            String resultMessage
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            return saveAndPublish(decoded.physicalTerminal(
                    messageId + "-result",
                    success,
                    Math.max(0, actual),
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            ));
        } catch (Throwable error) {
            Log.e(TAG, "SDK物理终态生成失败", error);
            return false;
        }
    }

    private boolean publishSdkConfigurationTerminal(
            SdkCommandDecoder.DecodedCommand decoded,
            boolean success,
            long configVersion,
            String resultCode,
            String resultMessage
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            return saveAndPublish(decoded.configurationTerminal(
                    messageId + "-result",
                    success,
                    configVersion,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            ));
        } catch (Throwable error) {
            Log.e(TAG, "SDK现金配置终态生成失败", error);
            return false;
        }
    }

    private boolean publishSdkGenericTerminal(
            SdkCommandDecoder.DecodedCommand decoded,
            boolean success,
            String resultCode,
            String resultMessage
    ) {
        try {
            String messageId = decoded.sdkCommand.getMessageId();
            return saveAndPublish(decoded.genericTerminal(
                    messageId + "-result",
                    success,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            ));
        } catch (Throwable error) {
            Log.e(TAG, "SDK通用终态生成失败", error);
            return false;
        }
    }

    private boolean saveAndPublish(SdkCommandDecoder.EncodedResult result) {
        if (result == null || !store.saveCommandResult(
                result.sourceMessageId,
                result.eventNo,
                result.resultStatus,
                result.payload
        )) {
            return false;
        }
        MqttManager.get(context).reportCommandResult(result.payload);
        return true;
    }

    private void resendCommandResults(String messageId) {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (messageId.equals(item.sourceMessageId)) {
                MqttManager.get(context).reportCommandResult(item.payload);
            }
        }
    }

    private void recoverWithoutRepeatingPhysicalAction() {
        if (!hasActiveOperation()) {
            return;
        }
        MqttManager.get(context).reportFault(
                "PHYSICAL_RESULT_UNKNOWN",
                "检测到进程重启前已经请求的物理动作",
                3,
                "禁止自动重启电机；等待控制板终态或人工核实"
        );
        if (hasPendingCollection()) {
            broadcastCollection(
                    DeviceCommandManager.COLLECTION_FAILED,
                    "检测到中断的存珠动作，已禁止自动重启"
            );
        }
    }

    private void persistOrphanBoardEvent(
            int code2,
            long packed,
            int expandCode
    ) {
        int token = (int) ((packed >>> 24) & 0xFF);
        int actual = (int) (packed & 0x00FFFFFFL);
        boolean collect = code2 >= EVT_COLLECT_STARTED
                && code2 <= EVT_COLLECT_FAILED;
        String messageId = collect
                ? store.getActiveCollect()
                : store.getActiveDispense();
        if (blank(messageId) || liveCommands.containsKey(messageId)) {
            return;
        }

        JSONObject envelope = store.loadCommand(messageId);
        JSONObject data = envelope == null
                ? null
                : envelope.optJSONObject("data");
        if (data == null || token != data.optInt("boardToken", -1)) {
            return;
        }

        try {
            data.put("deviceActualQuantity", actual);
            data.put("orphanBoardEventCode", code2);
            data.put("orphanBoardResultCode", expandCode);
            data.put("requiresManualReview", true);
            boolean terminal = code2 == EVT_DISPENSE_COMPLETED
                    || code2 == EVT_DISPENSE_FAILED
                    || code2 == EVT_COLLECT_COMPLETED
                    || code2 == EVT_COLLECT_FAILED;
            data.put("deviceTerminal", terminal);
            data.put("deviceStarted", !terminal);
            if (!store.saveCommand(envelope)) {
                throw new IllegalStateException("恢复控制板事件保存失败");
            }
            marbleAdapter.confirmBoardEventStored(code2, token);

            if (terminal) {
                if (collect) {
                    store.clearActiveCollect();
                } else {
                    store.clearActiveDispense();
                }
                reportPhysicalUnknown(
                        messageId,
                        actual,
                        "进程恢复后收到控制板物理终态，需人工核对"
                );
            }
        } catch (Throwable error) {
            reportStorageFault("恢复控制板事件失败：" + messageOf(error));
        }
    }

    private void reapplyCashConfiguration() {
        DeviceCommandStore.CashConfigurationRecord record =
                store.loadCashConfiguration();
        if (record == null || record.changeEnabled) {
            return;
        }

        JSONObject envelope = parseObject(record.snapshotJson);
        JSONObject data = envelope == null
                ? null
                : envelope.optJSONObject("data");
        JSONArray items = data == null
                ? null
                : data.optJSONArray("cashSaleItems");
        if (data == null || items == null) {
            return;
        }

        List<CashTier> tiers = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                return;
            }
            tiers.add(new CashTier(
                    item.optString("cashMediumType", ""),
                    item.optInt("denominationAmount", 0),
                    item.optInt("marbleQuantity", 0),
                    item.optString("cashSaleTierNo", "")
            ));
        }

        hardwareExecutor.execute(() -> {
            CashConfigurationResult result = record.enabled
                    ? cashAdapter.apply(record.configVersion, tiers)
                    : cashAdapter.applyDisabled(record.configVersion);
            if (!result.isApplied()) {
                MqttManager.get(context).reportFault(
                        "CASH_CONFIGURATION_REAPPLY_FAILED",
                        "现金配置恢复失败",
                        2,
                        safe(result.getMessage())
                );
            }
        });
    }

    private boolean hasActiveOperation() {
        return !blank(store.getActiveDispense())
                || !blank(store.getActiveCollect());
    }

    private void broadcastCollection(String event, String message) {
        Intent intent = new Intent(AppConfig.ACTION_COLLECTION_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(DeviceCommandManager.EXTRA_COLLECTION_EVENT, event);
        intent.putExtra(DeviceCommandManager.EXTRA_COLLECTION_MESSAGE, message);
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

    private void reportPhysicalUnknown(
            String messageId,
            int actual,
            String description
    ) {
        MqttManager.get(context).reportFault(
                "PHYSICAL_RESULT_REQUIRES_MANUAL_REVIEW",
                "物理结果需要人工核对",
                3,
                description + "；messageId=" + messageId + "；actual=" + actual
        );
    }

    private String newCashEventNo(int sequence) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyyMMddHHmmssSSS",
                Locale.ROOT
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return "CE-A-" + format.format(new Date()) + "-"
                + String.format(Locale.ROOT, "%04X", sequence) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static long safeConfigVersion(CashConfigurationCommandData config) {
        return config == null || config.getConfigVersion() == null
                ? 0L
                : config.getConfigVersion();
    }

    private static JSONObject parseObject(String value) {
        try {
            return blank(value) ? null : new JSONObject(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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
