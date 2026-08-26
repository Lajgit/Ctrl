package com.chuzhu.member;

import android.content.Context;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.activation.SdkCredentialStore;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.device.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceAppClient;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import java.lang.reflect.Method;

/**
 * 会员存珠设备屏接口仓库。
 *
 * <p>DeviceAppClient 必须使用当前 MQTT password 作为 HMAC 密钥。之前的实现错误地寻找
 * 两参数构造函数 DeviceAppClient(config, transport)，而当前 0.3.0 SDK 与设备接入文档实际
 * 使用的是 DeviceAppClient(config, DeviceSecretProvider, transport)，会导致会员 Session
 * 接口在运行时初始化失败。</p>
 *
 * <p>会员存珠响应 DTO 继续通过只读反射映射，避免把 SDK DTO 直接当作 APP 本地实体；
 * 但客户端构造与鉴权不再使用反射猜测。</p>
 */
public final class MemberDepositRepository {

    private static final String TAG = "CunzhuMemberApi";

    private final Context context;
    private final DeviceAppClient appClient;

    public MemberDepositRepository(Context context) throws Exception {
        this.context = context.getApplicationContext();
        DeviceSdkConfig config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(DeviceUtil.requireDeviceNo(this.context))
                .connectTimeoutMillis(10_000)
                .readTimeoutMillis(15_000)
                .build();
        HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(config);
        SdkCredentialStore credentialStore = SdkCredentialStore.get(this.context);

        appClient = new DeviceAppClient(
                config,
                () -> {
                    MqttCredential credential = credentialStore.load();
                    if (credential == null
                            || credential.getPassword() == null
                            || credential.getPassword().trim().isEmpty()) {
                        throw new IllegalStateException("缺少设备屏 API 所需 MQTT 鉴权密钥");
                    }
                    return credential.getPassword();
                },
                transport
        );
        verifyMemberDepositApi();
    }

    public MemberDepositStore.Snapshot currentOrCreateSession() throws Exception {
        Object current = invoke("currentMemberDepositSession", new Object[0], false);
        if (current == null) {
            current = invoke("createMemberDepositSession", new Object[0], false);
        }
        return toSession(current, "等待会员扫码");
    }

    public MemberDepositStore.Snapshot createSession() throws Exception {
        return toSession(invoke("createMemberDepositSession", new Object[0], false), "等待会员扫码");
    }

    public void cancelSession(String sessionId) throws Exception {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        invoke("cancelMemberDepositSession", new Object[]{sessionId.trim()}, false);
    }

    public OperationSnapshot startMemberDeposit(String clientRequestNo, String sessionId) throws Exception {
        Object operation = invoke(
                "startMemberDeposit",
                new Object[]{clientRequestNo, sessionId},
                false
        );
        return new OperationSnapshot(
                stringValue(operation, "getOperationNo", "operationNo"),
                stringValue(operation, "getReferenceNo", "referenceNo"),
                stringValue(operation, "getOperationId", "operationId"),
                stringValue(operation, "getStatus", "status")
        );
    }

    public OperationSnapshot queryMemberDeposit(String clientRequestNo) throws Exception {
        Object operation = invoke(
                "queryMemberDeposit",
                new Object[]{clientRequestNo},
                false
        );
        return new OperationSnapshot(
                stringValue(operation, "getOperationNo", "operationNo"),
                stringValue(operation, "getReferenceNo", "referenceNo"),
                stringValue(operation, "getOperationId", "operationId"),
                stringValue(operation, "getStatus", "status")
        );
    }

    private void verifyMemberDepositApi() throws Exception {
        requireMethod("currentMemberDepositSession", 0);
        requireMethod("createMemberDepositSession", 0);
        requireMethod("cancelMemberDepositSession", 1);
        requireMethod("startMemberDeposit", 2);
        requireMethod("queryMemberDeposit", 1);
        Log.i(TAG, "会员存珠 DeviceAppClient API 校验通过");
    }

    private void requireMethod(String name, int argCount) throws Exception {
        if (findMethod(appClient.getClass(), name, argCount) == null) {
            throw new NoSuchMethodException(
                    "当前 xiaoda-device-sdk-0.3.0.jar 缺少会员存珠 API："
                            + name + "，请核对 2026-08-26 正式联调 SDK 交付包"
            );
        }
    }

    private Object invoke(String name, Object[] args, boolean optional) throws Exception {
        Method method = findMethod(appClient.getClass(), name, args == null ? 0 : args.length);
        if (method == null) {
            if (optional) {
                return null;
            }
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

    private static Method findMethod(Class<?> type, String name, int argCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == argCount) {
                return method;
            }
        }
        return null;
    }

    private static MemberDepositStore.Snapshot toSession(Object source, String message) {
        if (source == null) {
            return null;
        }
        return new MemberDepositStore.Snapshot(
                stringValue(source, "getSessionId", "sessionId"),
                stringValue(source, "getQrContent", "qrContent"),
                normalizeStatus(stringValue(source, "getStatus", "status")),
                stringValue(source, "getMemberNo", "getMemberCode", "getMemberNumber", "memberNo"),
                stringValue(source, "getMemberNickname", "getNickname", "getMemberName", "memberNickname"),
                stringValue(source, "getAvailableQuantity", "getAvailableMarbleQuantity", "availableQuantity"),
                stringValue(source, "getItemId", "itemId"),
                stringValue(source, "getItemName", "itemName"),
                stringValue(source, "getUnitName", "unitName"),
                stringValue(source, "getMaximumDepositQuantity", "getMaximumQuantity", "maximumDepositQuantity"),
                stringValue(source, "getExpireTime", "getExpiredAt", "expireTime"),
                intValue(source, "getRefreshAfterSeconds", "refreshAfterSeconds"),
                "",
                "",
                "",
                message,
                System.currentTimeMillis()
        );
    }

    private static String normalizeStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return MemberDepositStore.STATUS_WAITING_SCAN;
        }
        String value = raw.trim();
        if ("BOUND".equalsIgnoreCase(value)) {
            return MemberDepositStore.STATUS_BOUND;
        }
        if ("WAITING_SCAN".equalsIgnoreCase(value)) {
            return MemberDepositStore.STATUS_WAITING_SCAN;
        }
        return value;
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
                Object value = method.invoke(source);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static final class OperationSnapshot {
        public final String operationNo;
        public final String referenceNo;
        public final String operationId;
        public final String status;

        OperationSnapshot(String operationNo, String referenceNo, String operationId, String status) {
            this.operationNo = operationNo == null ? "" : operationNo;
            this.referenceNo = referenceNo == null ? "" : referenceNo;
            this.operationId = operationId == null ? "" : operationId;
            this.status = status == null ? "" : status;
        }
    }
}
