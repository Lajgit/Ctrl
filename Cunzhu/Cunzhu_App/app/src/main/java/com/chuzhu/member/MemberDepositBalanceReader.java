package com.chuzhu.member;

import android.content.Context;

import com.chuzhu.AppConfig;
import com.chuzhu.activation.SdkCredentialStore;
import com.chuzhu.device.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceAppClient;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.lang.reflect.Method;

/**
 * 存珠 terminal 提交后读取 Server 权威 Operation 的最新会员可用数量。
 *
 * <p>正式联调基线明确要求 Start 后使用 queryMemberDeposit(clientRequestNo) 恢复/查询
 * 数据库 Operation；这里不重新创建二维码 Session，也不依赖 Redis Session 继续存在。</p>
 */
public final class MemberDepositBalanceReader {

    private final DeviceAppClient appClient;

    public MemberDepositBalanceReader(Context context) {
        Context appContext = context.getApplicationContext();
        DeviceSdkConfig config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(DeviceUtil.requireDeviceNo(appContext))
                .connectTimeoutMillis(10_000)
                .readTimeoutMillis(15_000)
                .build();
        HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(config);
        SdkCredentialStore credentialStore = SdkCredentialStore.get(appContext);
        appClient = new DeviceAppClient(
                config,
                () -> {
                    MqttCredential credential = credentialStore.load();
                    if (credential == null
                            || credential.getPassword() == null
                            || credential.getPassword().trim().isEmpty()) {
                        throw new IllegalStateException("缺少查询会员余额所需 MQTT 鉴权密钥");
                    }
                    return credential.getPassword();
                },
                transport
        );
    }

    public BalanceSnapshot query(String clientRequestNo) throws Exception {
        if (clientRequestNo == null || clientRequestNo.trim().isEmpty()) {
            throw new IllegalArgumentException("clientRequestNo 为空，无法查询存珠结算结果");
        }
        Object operation = invoke("queryMemberDeposit", new Object[]{clientRequestNo.trim()});
        String available = stringValue(
                operation,
                "getLatestAvailableQuantity",
                "getAvailableQuantity",
                "getMemberAvailableQuantity",
                "getAvailableMarbleQuantity",
                "getBalanceQuantity",
                "getAfterAvailableQuantity"
        );
        String status = stringValue(operation, "getStatus", "status");
        int actualQuantity = intValue(
                operation,
                "getActualQuantity",
                "getSettledQuantity",
                "getDepositQuantity",
                "actualQuantity"
        );

        /*
         * 某些 Server 版本会在 Operation 已完成后仍短时间保留绑定 Session。
         * Operation DTO 没暴露最新余额时，仅做只读 current 查询作为兼容回退；绝不 create。
         */
        if (available.isEmpty()) {
            Object current = invokeOptional("currentMemberDepositSession", new Object[0]);
            available = stringValue(
                    current,
                    "getAvailableQuantity",
                    "getAvailableMarbleQuantity",
                    "getBalanceQuantity"
            );
        }
        return new BalanceSnapshot(status, available, actualQuantity);
    }

    private Object invoke(String name, Object[] args) throws Exception {
        Method method = findMethod(appClient.getClass(), name, args == null ? 0 : args.length);
        if (method == null) {
            throw new NoSuchMethodException(name);
        }
        method.setAccessible(true);
        try {
            return method.invoke(appClient, args == null ? new Object[0] : args);
        } catch (Throwable error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error instanceof Exception
                    ? (Exception) error
                    : new IllegalStateException(error);
        }
    }

    private Object invokeOptional(String name, Object[] args) {
        try {
            return invoke(name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int argCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == argCount) {
                return method;
            }
        }
        return null;
    }

    private static String stringValue(Object source, String... names) {
        Object value = value(source, names);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object source, String... names) {
        Object value = value(source, names);
        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Object value(Object source, String... names) {
        if (source == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            try {
                Method method = source.getClass().getMethod(name);
                Object result = method.invoke(source);
                if (result != null) {
                    return result;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static final class BalanceSnapshot {
        public final String status;
        public final String availableQuantity;
        public final int actualQuantity;

        BalanceSnapshot(String status, String availableQuantity, int actualQuantity) {
            this.status = status == null ? "" : status.trim();
            this.availableQuantity = availableQuantity == null ? "" : availableQuantity.trim();
            this.actualQuantity = Math.max(0, actualQuantity);
        }
    }
}
