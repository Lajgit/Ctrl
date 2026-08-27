package com.chuzhu.member;

import android.content.Context;
import android.util.Log;

import com.chuzhu.AppConfig;
import com.chuzhu.activation.SdkCredentialStore;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.device.DeviceStateRepository;
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
    private static final long CREATE_SESSION_RETRY_INTERVAL_MS = 30_000L;
    private static final long COOLDOWN_LOG_INTERVAL_MS = 5_000L;
    private static final Object CREATE_SESSION_LOCK = new Object();

    private static volatile boolean apiVerifiedLogged;
    private static volatile long nextCreateSessionAllowedAt;
    private static volatile long lastCooldownLogAt;
    private static volatile String lastCreateSessionError = "";

    private final Context context;
    private final String deviceNo;
    private final DeviceAppClient appClient;

    public MemberDepositRepository(Context context) throws Exception {
        this.context = context.getApplicationContext();
        this.deviceNo = DeviceUtil.requireDeviceNo(this.context);
        DeviceSdkConfig config = DeviceSdkConfig.builder()
                .apiBaseUrl(AppConfig.ACTIVATION_BASE_URL)
                .deviceNo(deviceNo)
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
            current = createSessionRaw();
        }
        return toSession(current, "等待会员扫码");
    }

    public MemberDepositStore.Snapshot createSession() throws Exception {
        return toSession(createSessionRaw(), "等待会员扫码");
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

    private Object createSessionRaw() throws Exception {
        /*
         * 本地还有上一笔收珠或故障时，不再向平台反复创建新的二维码 Session。
         * 先等待控制板恢复流程把物理状态确认成 IDLE，避免日志中每 30 秒请求一次并被平台拒绝。
         */
        requireLocalDeviceIdle();
        synchronized (CREATE_SESSION_LOCK) {
            waitForCreateSessionRetryWindow();
            requireLocalDeviceIdle();
            Log.i(TAG, "请求创建会员存珠 Session：deviceNo=" + deviceNo
                    + "，baseUrl=" + AppConfig.ACTIVATION_BASE_URL
                    + "，appVersion=" + DeviceUtil.getAppVersion(context)
                    + "，boardVersion=" + DeviceUtil.getBoardVersion());
            try {
                Object session = invoke("createMemberDepositSession", new Object[0], false);
                nextCreateSessionAllowedAt = 0L;
                lastCreateSessionError = "";
                return session;
            } catch (Exception error) {
                lastCreateSessionError = diagnosticMessage(error);
                nextCreateSessionAllowedAt = System.currentTimeMillis() + CREATE_SESSION_RETRY_INTERVAL_MS;
                Log.e(TAG, "创建会员存珠 Session 被平台拒绝：deviceNo=" + deviceNo
                        + "，error=" + lastCreateSessionError, error);
                throw error;
            }
        }
    }

    private void requireLocalDeviceIdle() {
        int runningStatus = DeviceStateRepository.get(context).getRunningStatus();
        if (runningStatus != AppConfig.STATUS_IDLE) {
            throw new IllegalStateException(
                    "存珠机当前正在处理上一笔收珠或故障，暂不创建会员二维码，runningStatus="
                            + runningStatus
            );
        }
    }

    private void waitForCreateSessionRetryWindow() throws InterruptedException {
        long retryAt = nextCreateSessionAllowedAt;
        long now = System.currentTimeMillis();
        if (retryAt <= now) {
            return;
        }
        long waitMs = retryAt - now;
        long waitSeconds = Math.max(1L, (waitMs + 999L) / 1000L);
        if (now - lastCooldownLogAt >= COOLDOWN_LOG_INTERVAL_MS) {
            lastCooldownLogAt = now;
            Log.w(TAG, "会员存珠二维码创建冷却中：" + waitSeconds
                    + " 秒后再请求平台；上次错误="
                    + safe(lastCreateSessionError, "平台暂不可用"));
        }
        /*
         * MainActivity 可能因状态广播排队多个自动创建任务。
         * 这里不再快速抛本地异常，而是让后台线程等待冷却窗口，保证最多约 30 秒请求一次平台。
         */
        Thread.sleep(waitMs);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("会员存珠二维码创建任务已取消");
        }
    }

    private void verifyMemberDepositApi() throws Exception {
        requireMethod("currentMemberDepositSession", 0);
        requireMethod("createMemberDepositSession", 0);
        requireMethod("cancelMemberDepositSession", 1);
        requireMethod("startMemberDeposit", 2);
        requireMethod("queryMemberDeposit", 1);
        if (!apiVerifiedLogged) {
            apiVerifiedLogged = true;
            Log.i(TAG, "会员存珠 DeviceAppClient API 校验通过");
        }
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

    private static String diagnosticMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        StringBuilder builder = new StringBuilder(error.getClass().getSimpleName());
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            builder.append(": ").append(message.trim());
        }
        Throwable cause = error.getCause();
        while (cause != null && cause != error) {
            builder.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
                builder.append(": ").append(cause.getMessage().trim());
            }
            cause = cause.getCause();
        }
        return builder.toString();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
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
