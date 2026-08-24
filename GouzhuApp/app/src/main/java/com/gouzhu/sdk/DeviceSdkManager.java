package com.gouzhu.sdk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.gouzhu.AppConfig;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppClient;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppMemberWithdrawalResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppPurchaseResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;
import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyRedemptionPrepareResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceCredentialManager;
import com.pinball.xiaoda.device.sdk.client.DeviceLifecycleClient;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpTransport;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端设备 SDK 的 Android 入口。
 *
 * <p>统一创建生命周期、凭证和设备屏客户端。HTTP 方法为同步调用，因此所有公开
 * 异步入口都在单线程执行器中运行，回调切回主线程。</p>
 */
public final class DeviceSdkManager {

    private static final String TAG = "GouzhuSdkBootstrap";
    private static final int MAX_CAUSE_DEPTH = 8;

    private static volatile DeviceSdkManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DeviceSdkConfig config;
    private final HttpTransport httpTransport;
    private final DeviceCredentialManager credentialManager;
    private final DeviceLifecycleClient lifecycleClient;

    private volatile DeviceAppBootstrapResult lastBootstrap;

    private DeviceSdkManager(Context context) {
        this.context = context.getApplicationContext();
        this.config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(DeviceUtil.requireDeviceNo(this.context))
                .connectTimeoutMillis(10_000)
                .readTimeoutMillis(15_000)
                .build();
        this.httpTransport = new HttpUrlConnectionTransport(config);
        this.credentialManager = new DeviceCredentialManager(
                SdkCredentialStore.get(this.context)
        );
        this.lifecycleClient = new DeviceLifecycleClient(config, httpTransport);
    }

    public static DeviceSdkManager get(Context context) {
        if (instance == null) {
            synchronized (DeviceSdkManager.class) {
                if (instance == null) {
                    instance = new DeviceSdkManager(context);
                }
            }
        }
        return instance;
    }

    public DeviceSdkConfig getConfig() {
        return config;
    }

    public DeviceCredentialManager getCredentialManager() {
        return credentialManager;
    }

    public DeviceLifecycleClient getLifecycleClient() {
        return lifecycleClient;
    }

    public DeviceAppBootstrapResult getLastBootstrap() {
        return lastBootstrap;
    }

    public DeviceAppClient newAppClient() {
        return new DeviceAppClient(
                config,
                () -> credentialManager.loadRequired().getPassword(),
                httpTransport
        );
    }

    /** 获取设备屏动态首页、功能开关、套餐、扫码路由和现金状态快照。 */
    public void refreshBootstrap(BootstrapCallback callback) {
        final String appVersion = DeviceUtil.getAppVersion(context);
        final String deviceNo = DeviceUtil.requireDeviceNo(context);
        Log.i(
                TAG,
                "开始读取设备屏bootstrap：deviceNo=" + deviceNo
                        + "，appVersion=" + appVersion
                        + "，apiBaseUrl=" + AppConfig.ACTIVATION_BASE_URL
        );

        executor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                DeviceAppBootstrapResult result = newAppClient().bootstrap(appVersion);
                lastBootstrap = result;

                Gson gson = new GsonBuilder()
                        .serializeNulls()
                        .setPrettyPrinting()
                        .create();

                String bootstrapJson = gson.toJson(result);
                logLong(TAG, "bootstrap完整内容：\n" + bootstrapJson);
                lastBootstrap = result;

                /*
                 * SDK只读模型的toString会对支付链接、Token、券码和长文本进行安全脱敏。
                 * 禁止使用Gson反射打印原始字段，否则会绕过SDK的日志脱敏策略。
                 */
                Log.d(TAG, "bootstrap安全摘要=" + String.valueOf(result));
                Log.i(
                        TAG,
                        "设备屏bootstrap读取成功：deviceNo=" + deviceNo
                                + "，耗时=" + (System.currentTimeMillis() - startedAt) + "ms"
                                + "，resultNull=" + (result == null)
                );

                // cashSale只是当前运行状态和已应用快照，不能代替MQTT现金配置命令。
                logCashSaleSnapshot(result);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Throwable error) {
                long elapsed = System.currentTimeMillis() - startedAt;
                Log.e(
                        TAG,
                        "设备屏bootstrap读取失败：deviceNo=" + deviceNo
                                + "，appVersion=" + appVersion
                                + "，apiBaseUrl=" + AppConfig.ACTIVATION_BASE_URL
                                + "，耗时=" + elapsed + "ms"
                                + "，异常链=" + describeThrowable(error),
                        error
                );
                mainHandler.post(() -> callback.onFailure(error));
            }
        });
    }

    private static void logLong(String tag, String content) {
        if (content == null) {
            Log.i(tag, "null");
            return;
        }

        final int chunkSize = 3000;
        int length = content.length();

        for (int start = 0, index = 1; start < length; start += chunkSize, index++) {
            int end = Math.min(length, start + chunkSize);
            Log.i(
                    tag,
                    "bootstrap[" + index + "] "
                            + content.substring(start, end)
            );
        }
    }

    /**
     * 统一购珠：主扫和付款码反扫共用一个 clientRequestNo / orderId。
     * 使用 SDK 的 DeviceAppPurchaseResult 强类型结果，避免字段名变化被反射静默吞掉。
     */
    public DeviceAppPurchaseResult createPurchase(
            String clientRequestNo,
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity
    ) {
        return newAppClient().createPurchase(
                clientRequestNo,
                purchaseRuleId,
                priceTierId,
                purchaseQuantity
        );
    }

    public DeviceAppPurchaseResult queryPurchase(String clientRequestNo) {
        return newAppClient().queryPurchase(clientRequestNo);
    }

    public DeviceAppPurchaseResult cancelPurchase(String clientRequestNo) {
        return newAppClient().cancelPurchase(clientRequestNo);
    }

    public DeviceAppPurchaseResult payByAuthCode(String clientRequestNo, String authCode) {
        return newAppClient().payByAuthCode(clientRequestNo, authCode);
    }

    public DeviceAppMemberWithdrawalResult createMemberWithdrawal(
            String clientRequestNo,
            String withdrawalCode
    ) {
        return newAppClient().createMemberWithdrawal(clientRequestNo, withdrawalCode);
    }

    public DeviceAppMemberWithdrawalResult createMemberWithdrawalFromRoutedCode(
            String clientRequestNo,
            DeviceAppRedemptionRouting routing,
            String scannedCode
    ) {
        return newAppClient().createMemberWithdrawalFromRoutedCode(
                clientRequestNo,
                routing,
                scannedCode
        );
    }

    public DeviceAppMemberWithdrawalResult queryMemberWithdrawal(String clientRequestNo) {
        return newAppClient().queryMemberWithdrawal(clientRequestNo);
    }

    /** 抖音/美团团购核销统一使用 SDK 强类型接口，设备端不自行拼签名或第三方协议。 */
    public DeviceAppThirdPartyRedemptionPrepareResult
    prepareThirdPartyRedemptionForSelectedChannel(
            String clientRequestNo,
            DeviceAppRedemptionRouting routing,
            String selectedChannelCode,
            String scannedRawCode
    ) {
        return newAppClient().prepareThirdPartyRedemptionForSelectedChannel(
                clientRequestNo,
                routing,
                selectedChannelCode,
                scannedRawCode
        );
    }

    public DeviceAppThirdPartyRedemptionResult confirmThirdPartyRedemption(
            String clientRequestNo,
            String certificateId
    ) {
        return newAppClient().confirmThirdPartyRedemption(clientRequestNo, certificateId);
    }

    public DeviceAppThirdPartyRedemptionResult queryThirdPartyRedemption(String clientRequestNo) {
        return newAppClient().queryThirdPartyRedemption(clientRequestNo);
    }

    public DeviceAppInternalRedemptionResult createInternalRedemption(
            String clientRequestNo,
            String pickupCode
    ) {
        return newAppClient().createInternalRedemption(clientRequestNo, pickupCode);
    }

    /** 官方小程序套餐券使用 bootstrap.redemptionRouting 的路由规则，设备端不写死券码前缀。 */
    public DeviceAppInternalRedemptionResult createInternalRedemptionFromRoutedCode(
            String clientRequestNo,
            DeviceAppRedemptionRouting routing,
            String scannedCode
    ) {
        return newAppClient().createInternalRedemptionFromRoutedCode(
                clientRequestNo,
                routing,
                scannedCode
        );
    }

    public DeviceAppInternalRedemptionResult queryInternalRedemption(String clientRequestNo) {
        return newAppClient().queryInternalRedemption(clientRequestNo);
    }

    private static void logCashSaleSnapshot(DeviceAppBootstrapResult bootstrap) {
        if (bootstrap == null) {
            Log.w(TAG, "bootstrap为空，无法读取cashSale状态快照");
            return;
        }
        try {
            Object cashSale = invokeOptional(bootstrap, "getCashSale");
            if (cashSale == null) {
                Log.w(
                        TAG,
                        "bootstrap.cashSale为空；该结果只用于状态展示，不下发现金硬件配置"
                );
                return;
            }

            Object available = invokeOptional(cashSale, "isAvailable", "getAvailable");
            Object configurationVersion = invokeOptional(
                    cashSale,
                    "getConfigurationVersion"
            );
            Object unavailableReason = invokeOptional(
                    cashSale,
                    "getUnavailableReason"
            );
            Object tiers = invokeOptional(cashSale, "getTiers");
            int tierCount = tiers instanceof List ? ((List<?>) tiers).size() : -1;

            Log.i(
                    TAG,
                    "bootstrap.cashSale只读状态：available=" + available
                            + "，configurationVersion=" + configurationVersion
                            + "，tierCount=" + tierCount
                            + "，unavailableReason=" + unavailableReason
                            + "；现金硬件只接受MQTT sync_cash_configuration"
            );
        } catch (Throwable error) {
            Log.w(TAG, "读取bootstrap.cashSale状态快照失败", error);
        }
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

    private static String describeThrowable(Throwable error) {
        if (error == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (depth > 0) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                builder.append(": ").append(message.trim());
            }
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    public interface BootstrapCallback {
        void onSuccess(DeviceAppBootstrapResult result);

        void onFailure(Throwable error);
    }
}
