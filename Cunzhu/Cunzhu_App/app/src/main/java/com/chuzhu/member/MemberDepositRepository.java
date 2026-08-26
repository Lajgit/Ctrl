package com.chuzhu.member;

import android.content.Context;

import com.chuzhu.AppConfig;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.device.DeviceUtil;
import com.pinball.xiaoda.device.sdk.client.DeviceSdkConfig;
import com.pinball.xiaoda.device.sdk.client.HttpUrlConnectionTransport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 会员存珠设备屏接口仓库。
 *
 * <p>正式 SDK 文档要求纯存珠机使用 DeviceAppClient 的会员存珠 Session/Start/Status API。
 * 当前工程尚未直接引用这些新类型，为避免 0.3.0 SDK 小版本签名差异导致编译失败，
 * 这里通过反射调用公开方法；若 SDK 缺少对应方法，UI 会显示明确错误，不会绕过流程启动硬件。</p>
 */
public final class MemberDepositRepository {

    private final Context context;
    private final Object appClient;

    public MemberDepositRepository(Context context) throws Exception {
        this.context = context.getApplicationContext();
        DeviceSdkConfig config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(DeviceUtil.requireDeviceNo(this.context))
                .connectTimeoutMillis(10_000)
                .readTimeoutMillis(15_000)
                .build();
        HttpUrlConnectionTransport transport = new HttpUrlConnectionTransport(config);
        appClient = createAppClient(config, transport);
    }

    public MemberDepositStore.Snapshot currentOrCreateSession() throws Exception {
        Object current = invokeNoArg("currentMemberDepositSession", true);
        if (current == null) {
            current = invokeNoArg("createMemberDepositSession", false);
        }
        return toSession(current, "等待会员扫码");
    }

    public MemberDepositStore.Snapshot createSession() throws Exception {
        return toSession(invokeNoArg("createMemberDepositSession", false), "等待会员扫码");
    }

    public void cancelSession(String sessionId) throws Exception {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        invoke("cancelMemberDepositSession", new Object[]{sessionId.trim()}, true);
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

    private static Object createAppClient(
            DeviceSdkConfig config,
            HttpUrlConnectionTransport transport
    ) throws Exception {
        Class<?> type = Class.forName("com.pinball.xiaoda.device.sdk.client.DeviceAppClient");
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2
                    && parameterTypes[0].isInstance(config)
                    && parameterTypes[1].isInstance(transport)) {
                return constructor.newInstance(config, transport);
            }
        }
        throw new NoSuchMethodException("DeviceAppClient(DeviceSdkConfig, HttpUrlConnectionTransport)");
    }

    private Object invokeNoArg(String name, boolean optional) throws Exception {
        return invoke(name, new Object[0], optional);
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
        return method.invoke(appClient, args == null ? new Object[0] : args);
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
