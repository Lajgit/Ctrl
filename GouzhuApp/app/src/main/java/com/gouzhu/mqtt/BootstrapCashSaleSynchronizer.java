package com.gouzhu.mqtt;

import android.content.Context;
import android.util.Log;

import com.gouzhu.hardware.SerialCashConfigurationAdapter;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;
import com.pinball.xiaoda.device.sdk.hardware.CashTier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将 bootstrap 返回的已应用现金配置快照恢复到控制板。
 *
 * <p>服务端协议规定 cashSale.available=true 表示当前设备现金购珠可用。
 * 对本机硬件而言，这一状态固定映射为纸钞机和三线硬币器同时开启，即
 * 控制板现金掩码 0x03。cashSale.available=false 时只关闭可控纸钞机，
 * 三线硬币器的脉冲输入仍保持有效。</p>
 */
public final class BootstrapCashSaleSynchronizer {

    private static final String TAG = "GouzhuBootstrapCash";

    private static volatile BootstrapCashSaleSynchronizer instance;

    private final DeviceCommandStore store;
    private final SerialCashConfigurationAdapter cashAdapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "购珠机-bootstrap现金配置");
        thread.setDaemon(true);
        return thread;
    });

    private BootstrapCashSaleSynchronizer(Context context) {
        Context appContext = context.getApplicationContext();
        store = new DeviceCommandStore(appContext);
        cashAdapter = new SerialCashConfigurationAdapter(appContext);
        cashAdapter.start();
    }

    public static BootstrapCashSaleSynchronizer get(Context context) {
        if (instance == null) {
            synchronized (BootstrapCashSaleSynchronizer.class) {
                if (instance == null) {
                    instance = new BootstrapCashSaleSynchronizer(context);
                }
            }
        }
        return instance;
    }

    /** 异步解析并应用 bootstrap.cashSale，不阻塞首页刷新。 */
    public void synchronize(DeviceAppBootstrapResult bootstrap) {
        executor.execute(() -> synchronizeInternal(bootstrap));
    }

    private void synchronizeInternal(DeviceAppBootstrapResult bootstrap) {
        if (bootstrap == null) {
            Log.e(TAG, "bootstrap为空，无法恢复现金配置");
            disableBanknoteWithStoredVersion("bootstrap为空");
            return;
        }

        try {
            Object cashSale = invokeRequired(bootstrap, "getCashSale");
            if (cashSale == null) {
                Log.w(TAG, "bootstrap.cashSale为空，关闭纸钞机并保留硬币脉冲");
                disableBanknoteWithStoredVersion("cashSale为空");
                return;
            }

            boolean available = booleanValue(
                    invokeFirst(cashSale, "isAvailable", "getAvailable")
            );
            Long configurationVersion = nullableLong(
                    invokeFirst(cashSale, "getConfigurationVersion")
            );
            List<CashTier> tiers = readTiers(cashSale);

            Log.i(
                    TAG,
                    "读取bootstrap现金配置：available=" + available
                            + "，configurationVersion=" + configurationVersion
                            + "，tierCount=" + tiers.size()
            );

            if (!available) {
                long version = validVersionOrFallback(configurationVersion);
                store.setCashBlocked(true);
                CashConfigurationResult result = cashAdapter.applyDisabled(version);
                logApplyResult(false, version, result);
                return;
            }

            if (configurationVersion == null
                    || configurationVersion <= 0L
                    || configurationVersion > 0x00FFFFFFL) {
                Log.e(
                        TAG,
                        "cashSale.available=true但configurationVersion无效："
                                + configurationVersion
                );
                store.setCashBlocked(true);
                disableBanknoteWithStoredVersion("配置版本无效");
                return;
            }
            if (tiers.isEmpty()) {
                Log.e(TAG, "cashSale.available=true但tiers为空，拒绝开启纸钞机");
                store.setCashBlocked(true);
                cashAdapter.applyDisabled(configurationVersion);
                return;
            }

            String snapshot = buildSnapshot(configurationVersion, tiers).toString();
            if (!store.saveCashConfiguration(
                    configurationVersion.intValue(),
                    true,
                    false,
                    snapshot
            )) {
                Log.e(
                        TAG,
                        "保存bootstrap现金配置快照失败：configurationVersion="
                                + configurationVersion
                );
                store.setCashBlocked(true);
                cashAdapter.applyDisabled(configurationVersion);
                return;
            }

            /* available=true 固定同时开启纸钞和硬币，由适配器下发0x03。 */
            CashConfigurationResult result = cashAdapter.apply(
                    configurationVersion,
                    tiers
            );
            boolean applied = result != null && result.isApplied();
            store.setCashBlocked(!applied);
            logApplyResult(true, configurationVersion, result);
        } catch (Throwable error) {
            store.setCashBlocked(true);
            Log.e(TAG, "解析或应用bootstrap现金配置失败", error);
            disableBanknoteWithStoredVersion("解析或应用异常");
        }
    }

    private List<CashTier> readTiers(Object cashSale) throws Exception {
        Object value = invokeFirst(cashSale, "getTiers");
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }

        List<?> source = (List<?>) value;
        List<CashTier> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Object item = source.get(index);
            if (item == null) {
                Log.e(TAG, "cashSale.tiers存在空项：index=" + index);
                continue;
            }

            String mediumCode = stringValue(invokeFirst(
                    item,
                    "getCashMediumCode",
                    "getCashMediumType"
            ));
            int amount = intValue(invokeFirst(item, "getDenominationAmount"));
            int quantity = intValue(invokeFirst(item, "getMarbleQuantity"));
            String tierNo = stringValue(invokeFirst(
                    item,
                    "getTierNo",
                    "getCashSaleTierNo"
            ));

            if (("banknote".equals(mediumCode) || "coin".equals(mediumCode))
                    && amount > 0
                    && quantity > 0
                    && !tierNo.trim().isEmpty()) {
                result.add(new CashTier(
                        mediumCode,
                        amount,
                        quantity,
                        tierNo
                ));
            } else {
                Log.e(
                        TAG,
                        "cashSale.tiers字段无效：index=" + index
                                + "，mediumCode=" + mediumCode
                                + "，amount=" + amount
                                + "，quantity=" + quantity
                                + "，tierNo=" + tierNo
                );
            }
        }
        return result;
    }

    private JSONObject buildSnapshot(long version, List<CashTier> tiers) throws Exception {
        JSONArray items = new JSONArray();
        for (CashTier tier : tiers) {
            JSONObject item = new JSONObject();
            item.put("cashMediumType", tier.getMediumType());
            item.put("denominationAmount", tier.getDenominationAmount());
            item.put("marbleQuantity", tier.getMarbleQuantity());
            item.put("cashSaleTierNo", tier.getTierNo());
            items.put(item);
        }

        JSONObject data = new JSONObject();
        data.put("configVersion", version);
        data.put("cashAcceptanceEnabled", true);
        data.put("changeEnabled", false);
        data.put("cashSaleItems", items);

        JSONObject root = new JSONObject();
        root.put("messageId", "bootstrap-cash-config-" + version);
        root.put("commandType", "sync_cash_configuration");
        root.put("data", data);
        return root;
    }

    private void disableBanknoteWithStoredVersion(String reason) {
        long version = validVersionOrFallback(null);
        Log.w(
                TAG,
                "关闭纸钞机并保留硬币脉冲：version=" + version
                        + "，reason=" + reason
        );
        cashAdapter.applyDisabled(version);
    }

    private long validVersionOrFallback(Long version) {
        if (version != null && version > 0L && version <= 0x00FFFFFFL) {
            return version;
        }
        int storedVersion = store.getCashConfigVersion();
        return storedVersion > 0 ? storedVersion : 1L;
    }

    private void logApplyResult(
            boolean available,
            long version,
            CashConfigurationResult result
    ) {
        boolean applied = result != null && result.isApplied();
        String message = result == null ? "适配器返回null" : result.getMessage();
        if (applied) {
            Log.i(
                    TAG,
                    "bootstrap现金配置应用成功：available=" + available
                            + "，version=" + version
                            + "，expectedMask=" + (available ? "0x03" : "0x02")
            );
        } else {
            Log.e(
                    TAG,
                    "bootstrap现金配置应用失败：available=" + available
                            + "，version=" + version
                            + "，expectedMask=" + (available ? "0x03" : "0x02")
                            + "，reason=" + message
            );
        }
    }

    private static Object invokeRequired(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Object invokeFirst(Object target, String... methodNames) throws Exception {
        NoSuchMethodException last = null;
        for (String methodName : methodNames) {
            try {
                return invokeRequired(target, methodName);
            } catch (NoSuchMethodException error) {
                last = error;
            }
        }
        throw last == null
                ? new NoSuchMethodException("没有可调用的方法")
                : last;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
