package com.gouzhu.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.hardware.SerialCashConfigurationAdapter;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.serial.BoardConnectionMonitor;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 现金运行状态协调器。
 *
 * <p>MQTT 下发的 cashAcceptanceEnabled 只表示商家配置事实；真正是否允许当前时刻
 * 打开纸钞机/硬币器，还必须同时满足 MQTT 在线、bootstrap cashSale.available、
 * 设备空闲、控制板在线和本地硬件门禁均正常。本类集中计算这个运行目标，避免页面、
 * 支付、出珠和配置回调分别直接恢复现金硬件。</p>
 */
public final class CashRuntimeCoordinator {

    private static final String TAG = "GouzhuCashRuntime";
    private static final long MIN_CONTROLLER_PROTOCOL_VERSION = 0x02020000L;
    private static final long PERIODIC_BOOTSTRAP_MS = 30_000L;
    private static final String PREFS_NAME = "gouzhu_cash_runtime";
    private static final String KEY_CONFIGURATION_SAFE = "configuration_safe";

    private static volatile CashRuntimeCoordinator instance;

    private final Context context;
    private final DeviceCommandStore store;
    private final SerialCashConfigurationAdapter hardwareAdapter;
    private final SharedPreferences preferences;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-cash-runtime");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean bootstrapRefreshing = new AtomicBoolean(false);
    private final AtomicLong runtimeEpoch = new AtomicLong(0L);

    private volatile boolean mqttOnline;
    private volatile boolean bootstrapKnown;
    private volatile boolean bootstrapAvailable;
    private volatile boolean configurationSafe;
    private volatile String unavailableReason = "bootstrap尚未确认";
    private volatile long lastRequestedVersion = -1L;
    private volatile int lastRequestedMask = -1;
    private volatile int lastInventoryEventCode = -1;
    private volatile long lastInventoryStock = Long.MIN_VALUE;

    private CashRuntimeCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.configurationSafe = preferences.getBoolean(KEY_CONFIGURATION_SAFE, true);
        this.hardwareAdapter = new SerialCashConfigurationAdapter(this.context);
        this.hardwareAdapter.start();
        this.mqttOnline = MqttManager.get(this.context).isConnected();

        executor.scheduleWithFixedDelay(
                () -> {
                    if (mqttOnline && MqttManager.get(this.context).isConnected()) {
                        refreshBootstrap("periodic");
                    }
                },
                PERIODIC_BOOTSTRAP_MS,
                PERIODIC_BOOTSTRAP_MS,
                TimeUnit.MILLISECONDS
        );
    }

    public static CashRuntimeCoordinator get(Context context) {
        if (instance == null) {
            synchronized (CashRuntimeCoordinator.class) {
                if (instance == null) {
                    instance = new CashRuntimeCoordinator(context);
                }
            }
        }
        return instance;
    }

    /** MQTT 状态变化必须立即收敛现金硬件；断线时不沿用旧 available=true。 */
    public void onMqttStatusChanged(String statusText) {
        boolean connected = MqttManager.get(context).isConnected();
        mqttOnline = connected;
        invalidateRuntimeAvailability(
                connected
                        ? "MQTT已连接，等待bootstrap刷新"
                        : "MQTT未连接"
        );
        reconcile(connected
                ? "mqtt_online_wait_bootstrap"
                : "mqtt_offline:" + safe(statusText));
        if (connected) {
            refreshBootstrap("mqtt_online");
        }
    }

    /** 新现金配置开始处理时，旧 bootstrap 版本事实立即失效，现金保持关闭。 */
    public void onConfigurationPending(long configVersion) {
        setConfigurationSafe(false);
        invalidateRuntimeAvailability("现金配置版本正在应用：" + configVersion);
        reconcile("cash_config_pending");
    }

    /** 配置形成终态后重新请求 bootstrap，由服务端版本一致性决定能否重新营业。 */
    public void onConfigurationTerminal(long configVersion, boolean success) {
        setConfigurationSafe(success);
        invalidateRuntimeAvailability(success
                ? "现金配置已应用，等待服务端确认版本"
                : "现金配置应用失败");
        reconcile("cash_config_terminal:" + configVersion + ":" + success);
        if (mqttOnline && MqttManager.get(context).isConnected()) {
            executor.schedule(
                    () -> refreshBootstrap("cash_config_terminal"),
                    700L,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * 一旦任意扫码、现金、存珠或物理出珠占用成立，先作废占用前的 available=true。
     * 这样旧恢复路径即使在交易结束的窄窗口被调用，也不能使用交易前 bootstrap 重开现金。
     */
    public void onTransactionOccupied(String ownerType, String phase) {
        boolean needsHardwareClose = lastRequestedMask != 0;
        runtimeEpoch.incrementAndGet();
        bootstrapKnown = false;
        bootstrapAvailable = false;
        unavailableReason = "设备存在交易占用："
                + safe(ownerType) + "/" + safe(phase);
        if (!needsHardwareClose) {
            return;
        }
        lastRequestedMask = -1;
        lastRequestedVersion = -1L;
        reconcile("transaction_occupied");
    }

    /** 交易释放后先尝试处理延迟配置，再重新读取 bootstrap，最后恢复现金。 */
    public void onTransactionIdle() {
        invalidateRuntimeAvailability("交易已释放，等待bootstrap重新确认");
        reconcile("transaction_idle_wait_bootstrap");
        executor.execute(() -> {
            try {
                DeviceCommandManager.get(context).resumeDeferredCashConfiguration();
            } catch (Throwable error) {
                Log.e(TAG, "恢复交易期间延迟的现金配置失败", error);
            }
        });
        if (mqttOnline && MqttManager.get(context).isConnected()) {
            refreshBootstrap("transaction_idle");
        }
    }

    /** 控制板连接状态变化后，旧 bootstrap 与旧硬件掩码都不再视为可复用。 */
    public void onBoardConnectionChanged(boolean connected) {
        invalidateRuntimeAvailability(
                connected ? "控制板已重连，等待状态确认" : "控制板连接已断开"
        );
        reconcile(connected ? "board_connected_wait_state" : "board_disconnected");
    }

    /** 控制板重连并留出版本状态恢复时间后，重新请求服务端当前运行状态。 */
    public void onBoardRecovered() {
        invalidateRuntimeAvailability("控制板已恢复，等待bootstrap重新确认");
        reconcile("board_recovered_wait_bootstrap");
        if (mqttOnline && MqttManager.get(context).isConnected()) {
            refreshBootstrap("board_recovered");
        }
    }

    /**
     * 本地库存 0、补珠或库存数变化都可能改变服务端库存门控。先关闭现金并作废缓存，
     * 再刷新 bootstrap；若 Account 域尚未完成更新，周期刷新会继续保持故障关闭直至可用。
     */
    public synchronized void onInventoryChanged(int eventCode, long reportedStock) {
        if (eventCode == lastInventoryEventCode && reportedStock == lastInventoryStock) {
            return;
        }
        lastInventoryEventCode = eventCode;
        lastInventoryStock = reportedStock;
        invalidateRuntimeAvailability(
                "库存状态变化：code=0x" + Integer.toHexString(eventCode)
                        + "，stock=" + reportedStock
        );
        reconcile("inventory_changed");
        if (mqttOnline && MqttManager.get(context).isConnected()) {
            executor.schedule(
                    () -> refreshBootstrap("inventory_changed"),
                    700L,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * 严格运行门控。该方法只判断“当前是否允许打开现金输入”，不改变商家配置开关。
     */
    public boolean isCashAcceptanceAllowed() {
        if (!configurationSafe
                || !mqttOnline
                || !MqttManager.get(context).isConnected()
                || !bootstrapKnown
                || !bootstrapAvailable) {
            return false;
        }

        BoardConnectionMonitor boardMonitor = BoardConnectionMonitor.get(context);
        if (!boardMonitor.isStateKnown() || !boardMonitor.isConnected()) {
            return false;
        }
        if (store.getBoardVersion() < MIN_CONTROLLER_PROTOCOL_VERSION) {
            return false;
        }

        return TransactionOccupancyManager.get(context).canStartNewTransaction();
    }

    public boolean isBootstrapAvailable() {
        return bootstrapKnown && bootstrapAvailable;
    }

    public String getUnavailableReason() {
        return safe(unavailableReason);
    }

    public void refreshBootstrap(String trigger) {
        if (!mqttOnline || !MqttManager.get(context).isConnected()) {
            reconcile(trigger + ":mqtt_offline");
            return;
        }
        if (!bootstrapRefreshing.compareAndSet(false, true)) {
            return;
        }

        final long requestEpoch = runtimeEpoch.get();
        DeviceSdkManager.get(context).refreshBootstrap(
                new DeviceSdkManager.BootstrapCallback() {
                    @Override
                    public void onSuccess(DeviceAppBootstrapResult result) {
                        bootstrapRefreshing.set(false);
                        if (requestEpoch != runtimeEpoch.get()) {
                            Log.i(
                                    TAG,
                                    "忽略运行状态变化前发起的bootstrap响应：trigger="
                                            + trigger + "，requestEpoch=" + requestEpoch
                                            + "，currentEpoch=" + runtimeEpoch.get()
                            );
                            retryBootstrapAfterStaleResponse(trigger);
                            return;
                        }
                        applyBootstrap(result, trigger);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        bootstrapRefreshing.set(false);
                        if (requestEpoch != runtimeEpoch.get()) {
                            Log.i(
                                    TAG,
                                    "忽略运行状态变化前发起的bootstrap失败：trigger="
                                            + trigger + "，requestEpoch=" + requestEpoch
                                            + "，currentEpoch=" + runtimeEpoch.get()
                            );
                            retryBootstrapAfterStaleResponse(trigger);
                            return;
                        }
                        bootstrapKnown = false;
                        bootstrapAvailable = false;
                        unavailableReason = "bootstrap请求失败";
                        Log.w(TAG, "现金运行状态bootstrap刷新失败：trigger=" + trigger, error);
                        reconcile(trigger + ":bootstrap_failed");
                    }
                }
        );
    }

    public void reconcile(String trigger) {
        executor.execute(() -> reconcileInternal(trigger));
    }

    private void applyBootstrap(DeviceAppBootstrapResult bootstrap, String trigger) {
        boolean available = false;
        String reason = "现金购珠配置不可用";
        try {
            Object cashSale = invokeOptional(bootstrap, "getCashSale");
            if (cashSale != null) {
                Object availableValue = invokeOptional(
                        cashSale,
                        "isAvailable",
                        "getAvailable"
                );
                available = Boolean.TRUE.equals(availableValue);
                Object reasonValue = invokeOptional(cashSale, "getUnavailableReason");
                reason = available
                        ? ""
                        : safe(reasonValue == null ? null : String.valueOf(reasonValue));
                if (!available && reason.trim().isEmpty()) {
                    reason = "现金购珠当前不可用";
                }
            }
            bootstrapKnown = true;
            bootstrapAvailable = available;
            unavailableReason = reason;
            Log.i(
                    TAG,
                    "更新现金bootstrap运行状态：trigger=" + trigger
                            + "，available=" + available
                            + "，configurationSafe=" + configurationSafe
                            + "，reason=" + reason
            );
        } catch (Throwable error) {
            bootstrapKnown = false;
            bootstrapAvailable = false;
            unavailableReason = "bootstrap现金状态无法解析";
            Log.e(TAG, "解析bootstrap.cashSale失败", error);
        }
        reconcile(trigger + ":bootstrap_updated");
    }

    private void retryBootstrapAfterStaleResponse(String trigger) {
        if (!configurationSafe
                || !mqttOnline
                || !MqttManager.get(context).isConnected()
                || !TransactionOccupancyManager.get(context).isIdle()) {
            return;
        }
        executor.schedule(
                () -> refreshBootstrap(trigger + ":stale_retry"),
                100L,
                TimeUnit.MILLISECONDS
        );
    }

    private void reconcileInternal(String trigger) {
        DeviceCommandStore.CashConfigurationRecord record = store.loadCashConfiguration();
        long version = record == null || record.configVersion <= 0
                ? Math.max(1, store.getCashConfigVersion())
                : record.configVersion;
        int configuredMask = configuredMask(record);
        boolean occupied = !TransactionOccupancyManager.get(context).isIdle();
        boolean targetEnabled = configuredMask != 0 && isCashAcceptanceAllowed();
        int targetMask = targetEnabled ? configuredMask : 0;

        if (lastRequestedVersion == version && lastRequestedMask == targetMask) {
            return;
        }

        hardwareAdapter.setProtocolV22Ready(
                store.getBoardVersion() >= MIN_CONTROLLER_PROTOCOL_VERSION
        );
        CashConfigurationResult result = hardwareAdapter.applyRuntimeMask(version, targetMask);
        if (result != null && result.isApplied()) {
            lastRequestedVersion = version;
            lastRequestedMask = targetMask;
        } else {
            lastRequestedVersion = -1L;
            lastRequestedMask = -1;
            if (targetMask != 0) {
                // 运行恢复失败时只做故障关闭，不使用旧 available=true 继续收现。
                hardwareAdapter.applyRuntimeMask(version, 0);
            }
        }

        Log.i(
                TAG,
                "现金运行协调：trigger=" + trigger
                        + "，configVersion=" + version
                        + "，mqttOnline=" + mqttOnline
                        + "，bootstrapKnown=" + bootstrapKnown
                        + "，bootstrapAvailable=" + bootstrapAvailable
                        + "，configurationSafe=" + configurationSafe
                        + "，transactionOccupied=" + occupied
                        + "，configuredMask=0x" + Integer.toHexString(configuredMask)
                        + "，targetEnabled=" + targetEnabled
                        + "，targetMask=0x" + Integer.toHexString(targetMask)
                        + "，hardwareApplied=" + (result != null && result.isApplied())
                        + "，reason=" + safe(unavailableReason)
        );
    }

    private int configuredMask(DeviceCommandStore.CashConfigurationRecord record) {
        if (record == null || !record.enabled || record.changeEnabled || record.configVersion <= 0) {
            return 0;
        }
        try {
            JSONObject envelope = new JSONObject(safe(record.snapshotJson));
            JSONObject data = envelope.optJSONObject("data");
            JSONArray items = data == null ? null : data.optJSONArray("cashSaleItems");
            if (items == null || items.length() == 0) {
                return 0;
            }
            int mask = 0;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) {
                    return 0;
                }
                String medium = item.optString("cashMediumType", "");
                if ("banknote".equals(medium)) {
                    mask |= 0x01;
                } else if ("coin".equals(medium)) {
                    mask |= 0x02;
                } else {
                    return 0;
                }
            }
            return mask;
        } catch (Throwable error) {
            Log.e(TAG, "读取本地现金配置快照失败，保持现金关闭", error);
            return 0;
        }
    }

    private void invalidateRuntimeAvailability(String reason) {
        runtimeEpoch.incrementAndGet();
        bootstrapKnown = false;
        bootstrapAvailable = false;
        unavailableReason = safe(reason);
        lastRequestedMask = -1;
        lastRequestedVersion = -1L;
    }

    private void setConfigurationSafe(boolean safe) {
        configurationSafe = safe;
        preferences.edit().putBoolean(KEY_CONFIGURATION_SAFE, safe).apply();
    }

    private static Object invokeOptional(Object target, String... methodNames)
            throws Exception {
        if (target == null || methodNames == null) {
            return null;
        }
        NoSuchMethodException last = null;
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
