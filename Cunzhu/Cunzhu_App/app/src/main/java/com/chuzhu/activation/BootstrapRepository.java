package com.chuzhu.activation;

import android.content.Context;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.device.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppClient;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.lang.reflect.Method;

/**
 * 纯存珠机 bootstrap 门禁。
 *
 * <p>正式联调基线要求 bootstrap 明确返回 deviceType=3 后才能开放营业界面。
 * DeviceAppClient 使用当前 MQTT password 作为 HMAC 密钥，因此本方法只能在 reactivate、
 * MQTT 新凭证落盘并完成连接之后调用。</p>
 */
public final class BootstrapRepository {

    private static final String TAG = "CunzhuBootstrap";
    private static volatile String verifiedDeviceNo = "";
    private static volatile int verifiedDeviceType = -1;

    private final Context context;
    private final DeviceAppClient appClient;

    public BootstrapRepository(Context context) {
        this.context = context.getApplicationContext();
        DeviceSdkConfig config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(DeviceUtil.requireDeviceNo(this.context))
                .connectTimeoutMillis(10_000)
                .readTimeoutMillis(15_000)
                .build();
        SdkCredentialStore credentialStore = SdkCredentialStore.get(this.context);
        appClient = new DeviceAppClient(
                config,
                () -> {
                    MqttCredential credential = credentialStore.load();
                    if (credential == null
                            || credential.getPassword() == null
                            || credential.getPassword().trim().isEmpty()) {
                        throw new IllegalStateException("bootstrap 缺少当前 MQTT 鉴权密钥");
                    }
                    return credential.getPassword();
                },
                new HttpUrlConnectionTransport(config)
        );
    }

    /** 当前进程是否已经用最新启动流程确认本机为纯存珠机。 */
    public static boolean isMarbleDepositMachineVerified(Context context) {
        String deviceNo = DeviceUtil.requireDeviceNo(context.getApplicationContext());
        return deviceNo.equals(verifiedDeviceNo)
                && verifiedDeviceType == AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE;
    }

    /** MQTT 凭证重新刷新时清除进程内 bootstrap 缓存，要求重新校验。 */
    public static void invalidate() {
        verifiedDeviceNo = "";
        verifiedDeviceType = -1;
    }

    /**
     * 同步读取 bootstrap 并强校验本机是 MARBLE_DEPOSIT_MACHINE(deviceType=3)。
     * HTTP 调用必须放后台线程。
     */
    public int requireMarbleDepositMachine() throws Exception {
        DeviceAppBootstrapResult bootstrap = appClient.bootstrap(DeviceUtil.getAppVersion(context));
        if (bootstrap == null) {
            throw new IllegalStateException("bootstrap 返回为空");
        }

        /*
         * 交付 JAR 的 bootstrap DeviceInfo 可能随小版本增加字段；这里仅反射读取文档稳定字段
         * getDevice().getDeviceType()，避免把 SDK DTO 直接耦合成本地实体。
         */
        Object device = invokeNoArg(bootstrap, "getDevice");
        if (device == null) {
            throw new IllegalStateException("bootstrap 未返回 device 信息");
        }
        Object rawType = invokeNoArg(device, "getDeviceType");
        if (!(rawType instanceof Number)) {
            throw new IllegalStateException("bootstrap 未返回有效 deviceType");
        }
        int deviceType = ((Number) rawType).intValue();
        if (deviceType != AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE) {
            invalidate();
            throw new IllegalStateException(
                    "设备类型不匹配：bootstrap deviceType=" + deviceType
                            + "，存珠机要求=" + AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE
            );
        }
        verifiedDeviceNo = DeviceUtil.requireDeviceNo(context);
        verifiedDeviceType = deviceType;
        Log.i(TAG, "bootstrap 设备类型校验通过：deviceType=" + deviceType);
        return deviceType;
    }

    private static Object invokeNoArg(Object source, String methodName) throws Exception {
        Method method = source.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(source);
    }
}
