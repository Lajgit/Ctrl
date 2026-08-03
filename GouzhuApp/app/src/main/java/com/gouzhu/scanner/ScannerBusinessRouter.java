package com.gouzhu.scanner;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.payment.PaymentManager;
import com.gouzhu.sdk.DeviceSdkManager;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 根据 bootstrap.redemptionRouting 分发反扫二维码。
 *
 * <p>设备端不再写死会员码、平台套餐码或第三方券码前缀。每次扫码都使用最近一次
 * bootstrap 返回的完整路由配置进行精确匹配，再调用新版 SDK 的 routed-code 方法。
 * 扫码成功只表示业务请求已经提交，真实出珠仍必须等待 MQTT dispense_marbles。</p>
 */
public final class ScannerBusinessRouter {

    private static final String TAG = "GouzhuScannerRoute";

    private static final String FEATURE_MEMBER_WITHDRAW = "MEMBER_WITHDRAW";
    private static final String FEATURE_INTERNAL_REDEMPTION = "INTERNAL_REDEMPTION";
    private static final String FEATURE_THIRD_PARTY_REDEMPTION = "THIRD_PARTY_REDEMPTION";

    private static volatile ScannerBusinessRouter instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "购珠机-反扫业务路由");
        thread.setDaemon(true);
        return thread;
    });

    private ScannerBusinessRouter(Context context) {
        this.context = context.getApplicationContext();
        this.sdkManager = DeviceSdkManager.get(this.context);
    }

    public static ScannerBusinessRouter get(Context context) {
        if (instance == null) {
            synchronized (ScannerBusinessRouter.class) {
                if (instance == null) {
                    instance = new ScannerBusinessRouter(context);
                }
            }
        }
        return instance;
    }

    /**
     * 解析路由并异步提交业务请求。
     *
     * @param scanContent 反扫模块输出的完整二维码字符串
     * @return 只包含脱敏信息的提交结果，不包含原始二维码内容
     */
    public Submission submit(String scanContent) {
        String content = scanContent == null ? "" : scanContent.trim();
        if (content.isEmpty()) {
            return Submission.unsupported("扫码内容为空");
        }

        DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
        if (bootstrap == null) {
            return Submission.failed("扫码路由尚未加载，请等待设备首页配置同步完成");
        }

        final RouteMatch match;
        try {
            match = resolveRoute(bootstrap, content);
        } catch (Throwable error) {
            Log.e(TAG, "读取bootstrap扫码路由失败", error);
            return Submission.failed("读取服务端扫码路由失败：" + messageOf(error));
        }

        if (match == null) {
            return Submission.unsupported("二维码未匹配服务端下发的扫码路由");
        }
        if (!isFeatureAvailable(bootstrap, match.featureCode)) {
            return Submission.unsupported(
                    match.displayName + "当前不可用，请按设备界面提示操作"
            );
        }

        String requestNo = newRequestNo(match.requestPrefix);
        executor.execute(() -> invokeRoutedRequest(match, requestNo, content));
        return Submission.accepted(
                match.codeType,
                "已识别" + match.displayName + "并提交服务端，等待业务处理"
        );
    }

    private RouteMatch resolveRoute(
            DeviceAppBootstrapResult bootstrap,
            String content
    ) throws Exception {
        Object routing = invokeRequired(bootstrap, "getRedemptionRouting");
        if (routing == null) {
            throw new IllegalStateException("bootstrap.redemptionRouting为空");
        }

        List<RouteMatch> matches = new ArrayList<>();

        Object memberRule = invokeOptional(routing, "getMemberWithdrawal");
        addPrefixMatch(
                matches,
                content,
                memberRule,
                "getCodePrefix",
                RouteMatch.member(routing)
        );

        Object internalRule = invokeOptional(routing, "getInternalRedemption");
        addPrefixMatch(
                matches,
                content,
                internalRule,
                "getCodePrefix",
                RouteMatch.internal(routing)
        );

        Object channelsValue = invokeOptional(routing, "getThirdPartyChannels");
        List<?> channels = channelsValue instanceof List
                ? (List<?>) channelsValue
                : Collections.emptyList();
        for (Object channel : channels) {
            if (channel == null) {
                continue;
            }
            String channelCode = stringValue(invokeOptional(channel, "getChannelCode"));
            String channelName = stringValue(invokeOptional(channel, "getChannelName"));
            RouteMatch thirdParty = RouteMatch.thirdParty(
                    routing,
                    channelCode,
                    channelName
            );
            addPrefixMatch(
                    matches,
                    content,
                    channel,
                    "getVoucherCodePrefix",
                    thirdParty
            );
        }

        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("二维码同时匹配多个服务端路由，已拒绝提交");
        }
        return matches.get(0);
    }

    private void addPrefixMatch(
            List<RouteMatch> matches,
            String content,
            Object rule,
            String prefixMethod,
            RouteMatch match
    ) throws Exception {
        if (rule == null) {
            return;
        }
        String prefix = stringValue(invokeOptional(rule, prefixMethod));
        if (prefix.isEmpty() || !content.startsWith(prefix)) {
            return;
        }
        // 前缀后必须仍有业务码主体，只有协议头不构成有效二维码。
        if (content.length() <= prefix.length()) {
            return;
        }
        matches.add(match);
    }

    private boolean isFeatureAvailable(
            DeviceAppBootstrapResult bootstrap,
            String featureCode
    ) {
        try {
            Object value = invokeOptional(bootstrap, "getFeatures");
            if (!(value instanceof List)) {
                Log.w(TAG, "bootstrap.features缺失，拒绝开放扫码入口：" + featureCode);
                return false;
            }
            for (Object feature : (List<?>) value) {
                if (feature == null) {
                    continue;
                }
                String code = stringValue(invokeOptional(feature, "getCode"));
                if (!featureCode.equals(code)) {
                    continue;
                }
                Object visible = invokeOptional(feature, "isVisible", "getVisible");
                Object available = invokeOptional(feature, "isAvailable", "getAvailable");
                return booleanValue(visible, true) && booleanValue(available, false);
            }
        } catch (Throwable error) {
            Log.e(TAG, "读取扫码功能可用状态失败：" + featureCode, error);
        }
        return false;
    }

    private void invokeRoutedRequest(
            RouteMatch match,
            String requestNo,
            String content
    ) {
        try {
            DeviceAppClient client = sdkManager.newAppClient();
            Method method = findThreeArgumentMethod(
                    client.getClass(),
                    match.sdkMethodName
            );
            Object result = method.invoke(client, requestNo, match.routing, content);
            String message = firstNonBlank(
                    stringValue(invokeOptional(result, "getMessage")),
                    stringValue(invokeOptional(
                            result,
                            "getWithdrawalStatus",
                            "getRedemptionStatus"
                    )),
                    match.displayName + "请求已受理，等待平台处理"
            );
            broadcastPayment(PaymentManager.EVENT_SCANNER_REPORTED, message, requestNo);
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            Log.e(
                    TAG,
                    match.displayName + "提交失败，requestNo=" + requestNo,
                    cause
            );
            broadcastPayment(
                    PaymentManager.EVENT_FAILED,
                    match.displayName + "提交失败：" + messageOf(cause),
                    requestNo
            );
        }
    }

    private void broadcastPayment(String event, String message, String requestNo) {
        Intent intent = new Intent(PaymentManager.ACTION_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(PaymentManager.EXTRA_EVENT, event);
        intent.putExtra(PaymentManager.EXTRA_MESSAGE, message);
        intent.putExtra(PaymentManager.EXTRA_ORDER_ID, requestNo);
        context.sendBroadcast(intent);
    }

    private static Method findThreeArgumentMethod(Class<?> type, String methodName)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (methodName.equals(method.getName())
                    && method.getParameterTypes().length == 3) {
                return method;
            }
        }
        throw new NoSuchMethodException(
                "当前SDK缺少方法：" + methodName + "(requestNo, routing, code)"
        );
    }

    private static Object invokeRequired(Object target, String methodName)
            throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("调用目标为空：" + methodName);
        }
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Object invokeOptional(Object target, String... methodNames)
            throws Exception {
        if (target == null || methodNames == null) {
            return null;
        }
        NoSuchMethodException last = null;
        for (String methodName : methodNames) {
            try {
                return invokeRequired(target, methodName);
            } catch (NoSuchMethodException error) {
                last = error;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static String newRequestNo(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "-" + System.currentTimeMillis() + "-" + uuid.substring(0, 12);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
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

    private static final class RouteMatch {
        final Object routing;
        final String sdkMethodName;
        final String featureCode;
        final String codeType;
        final String displayName;
        final String requestPrefix;

        private RouteMatch(
                Object routing,
                String sdkMethodName,
                String featureCode,
                String codeType,
                String displayName,
                String requestPrefix
        ) {
            this.routing = routing;
            this.sdkMethodName = sdkMethodName;
            this.featureCode = featureCode;
            this.codeType = codeType;
            this.displayName = displayName;
            this.requestPrefix = requestPrefix;
        }

        static RouteMatch member(Object routing) {
            return new RouteMatch(
                    routing,
                    "createMemberWithdrawalFromRoutedCode",
                    FEATURE_MEMBER_WITHDRAW,
                    ReverseScannerManager.TYPE_MEMBER_WITHDRAWAL,
                    "会员取珠码",
                    "withdraw"
            );
        }

        static RouteMatch internal(Object routing) {
            return new RouteMatch(
                    routing,
                    "createInternalRedemptionFromRoutedCode",
                    FEATURE_INTERNAL_REDEMPTION,
                    ReverseScannerManager.TYPE_INTERNAL_REDEMPTION,
                    "平台套餐核销码",
                    "redeem"
            );
        }

        static RouteMatch thirdParty(
                Object routing,
                String channelCode,
                String channelName
        ) {
            String display = channelName.isEmpty()
                    ? "第三方团购核销码"
                    : channelName + "核销码";
            String requestPrefix = channelCode.isEmpty()
                    ? "third"
                    : "third-" + channelCode.toLowerCase(java.util.Locale.ROOT);
            return new RouteMatch(
                    routing,
                    "createThirdPartyRedemptionFromRoutedCode",
                    FEATURE_THIRD_PARTY_REDEMPTION,
                    ReverseScannerManager.TYPE_THIRD_PARTY_REDEMPTION,
                    display,
                    requestPrefix
            );
        }
    }

    public static final class Submission {
        public final boolean accepted;
        public final boolean unsupported;
        public final String codeType;
        public final String message;

        private Submission(
                boolean accepted,
                boolean unsupported,
                String codeType,
                String message
        ) {
            this.accepted = accepted;
            this.unsupported = unsupported;
            this.codeType = codeType;
            this.message = message;
        }

        static Submission accepted(String codeType, String message) {
            return new Submission(true, false, codeType, message);
        }

        static Submission unsupported(String message) {
            return new Submission(
                    false,
                    true,
                    ReverseScannerManager.TYPE_UNSUPPORTED,
                    message
            );
        }

        static Submission failed(String message) {
            return new Submission(false, false, "", message);
        }
    }
}
