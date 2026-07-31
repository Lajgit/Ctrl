package com.gouzhu.sdk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.gouzhu.AppConfig;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppClient;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppMemberWithdrawalResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppNativePurchaseResult;
import com.pinball.xiaoda.device.sdk.client.DeviceCredentialManager;
import com.pinball.xiaoda.device.sdk.client.DeviceLifecycleClient;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpTransport;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端设备 SDK 的 Android 入口。
 *
 * <p>统一创建 lifecycle、credential 和 device-app 客户端。HTTP 方法为同步调用，
 * 因此所有公开异步入口都在单线程执行器中运行，回调切回主线程。</p>
 */
public final class DeviceSdkManager {

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

    /** 获取设备屏动态首页、功能开关、套餐和现金配置。 */
    public void refreshBootstrap(BootstrapCallback callback) {
        executor.execute(() -> {
            try {
                DeviceAppBootstrapResult result = newAppClient().bootstrap(
                        DeviceUtil.getAppVersion(context)
                );
                lastBootstrap = result;
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Throwable error) {
                mainHandler.post(() -> callback.onFailure(error));
            }
        });
    }

    public DeviceAppNativePurchaseResult createNativePurchase(
            String clientRequestNo,
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity
    ) {
        return newAppClient().createNativePurchase(
                clientRequestNo,
                purchaseRuleId,
                priceTierId,
                purchaseQuantity
        );
    }

    public DeviceAppNativePurchaseResult queryNativePurchase(String clientRequestNo) {
        return newAppClient().queryNativePurchase(clientRequestNo);
    }

    public DeviceAppMemberWithdrawalResult createMemberWithdrawal(
            String clientRequestNo,
            String withdrawalCode
    ) {
        return newAppClient().createMemberWithdrawal(clientRequestNo, withdrawalCode);
    }

    public DeviceAppMemberWithdrawalResult queryMemberWithdrawal(String clientRequestNo) {
        return newAppClient().queryMemberWithdrawal(clientRequestNo);
    }

    public DeviceAppInternalRedemptionResult createInternalRedemption(
            String clientRequestNo,
            String pickupCode
    ) {
        return newAppClient().createInternalRedemption(clientRequestNo, pickupCode);
    }

    public DeviceAppInternalRedemptionResult queryInternalRedemption(String clientRequestNo) {
        return newAppClient().queryInternalRedemption(clientRequestNo);
    }

    public interface BootstrapCallback {
        void onSuccess(DeviceAppBootstrapResult result);

        void onFailure(Throwable error);
    }
}
