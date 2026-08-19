package com.gouzhu.payment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.transaction.TransactionOccupancyPolicy;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppMemberWithdrawalResult;

import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 设备屏统一购珠、会员取珠和内部核销入口。
 *
 * <p>购珠只创建一个服务端订单：屏幕 scanUrl 主扫和 ttyS6 付款码反扫共用同一个
 * clientRequestNo/orderId。设备端只做本地门禁，真实支付方式竞争由服务端原子裁决。
 * HTTP 支付成功绝不直接驱动控制板，真实出珠仍只执行验签通过的 MQTT dispense_marbles。</p>
 */
public final class PaymentManager {

    public static final String ACTION_PAYMENT_EVENT = "com.gouzhu.action.PAYMENT_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_ORDER_ID = "orderId";
    public static final String EXTRA_QR_CONTENT = "qrContent";
    public static final String EXTRA_PURCHASE_STATUS = "purchaseStatus";
    public static final String EXTRA_PAYMENT_STATUS = "paymentStatus";
    public static final String EXTRA_SELECTED_PAYMENT_MODE = "selectedPaymentMode";
    public static final String EXTRA_PAY_CHANNEL = "payChannel";

    public static final String EVENT_REQUEST_CREATED = "requestCreated";
    public static final String EVENT_PAYMENT_READY = "paymentReady";
    public static final String EVENT_QR_READY = "qrReady";
    public static final String EVENT_FAILED = "failed";
    public static final String EVENT_WAITING = "waiting";
    public static final String EVENT_SUCCESS = "success";
    public static final String EVENT_CANCELLING = "cancelling";
    public static final String EVENT_CLOSED = "closed";
    public static final String EVENT_SCANNER_REPORTED = "scannerReported";

    private static final String TAG = "GouzhuPayment";
    private static final String PREF = "payment_state_sdk_v1";
    private static final String KEY_CURRENT_REQUEST_NO = "currentRequestNo";
    private static final String KEY_CURRENT_BEAD_COUNT = "currentBeadCount";
    private static final String KEY_CURRENT_PRICE_FEN = "currentPriceFen";
    private static final String KEY_CURRENT_RULE_ID = "currentRuleId";
    private static final String KEY_CURRENT_TIER_ID = "currentTierId";
    private static final String KEY_CURRENT_PURCHASE_QUANTITY = "currentPurchaseQuantity";
    private static final String KEY_CURRENT_STATUS = "currentPurchaseStatus";
    private static final String KEY_CURRENT_PAYMENT_STATUS = "currentPaymentStatus";
    private static final String KEY_CURRENT_SELECTED_MODE = "currentSelectedPaymentMode";
    private static final String KEY_CURRENT_PAY_CHANNEL = "currentPayChannel";
    private static final String KEY_CURRENT_SUPPORTED_CHANNELS = "currentSupportedChannels";
    private static final String KEY_CURRENT_SCAN_URL = "currentScanUrl";
    private static final String KEY_CURRENT_EXPIRE_TIME = "currentExpireTime";
    private static final String KEY_CURRENT_STAGE = "currentStage";
    private static final String KEY_AUTH_CODE_SUBMITTED = "authCodeSubmitted";
    private static final String KEY_CANCEL_PENDING = "cancelPending";
    private static final String KEY_QUERY_DEADLINE = "queryDeadline";
    private static final String KEY_LAST_REQUEST_JSON = "lastRequestJson";
    private static final String KEY_LAST_SCANNER_JSON = "lastScannerJson";
    private static final String KEY_SCANNER_REQUEST_NO = "scannerRequestNo";

    private static final String STAGE_PREPARING = "PREPARING";
    private static final String STAGE_CREATING = "CREATING";
    private static final String STAGE_CREATE_UNKNOWN = "CREATE_UNKNOWN";
    private static final String STAGE_WAITING_PAYMENT = "WAITING_PAYMENT";
    private static final String STAGE_AUTH_SUBMITTING = "AUTH_SUBMITTING";
    private static final String STAGE_CONFIRMING = "CONFIRMING";
    private static final String STAGE_CANCELLING = "CANCELLING";
    private static final String STAGE_PAID = "PAID";
    private static final String STAGE_BLOCKED = "BLOCKED";

    private static volatile PaymentManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> purchaseQueryTask;
    private int consecutiveNetworkFailures;
    private boolean paymentReadyBroadcasted;
    private boolean qrBroadcasted;

    private PaymentManager(Context context) {
        this.context = context.getApplicationContext();
        this.sdkManager = DeviceSdkManager.get(this.context);
        this.occupancy = TransactionOccupancyManager.get(this.context);
    }

    public static PaymentManager get(Context context) {
        if (instance == null) {
            synchronized (PaymentManager.class) {
                if (instance == null) {
                    instance = new PaymentManager(context);
                }
            }
        }
        return instance;
    }

    public PaymentRequest startPayment(
            long purchaseRuleId,
            long priceTierId,
            int beadCount,
            int priceFen
    ) {
        return startPayment(
                purchaseRuleId,
                priceTierId,
                null,
                beadCount,
                priceFen
        );
    }

    /** 创建唯一的统一购珠会话；不再区分 Native 和 AUTH_CODE 订单。 */
    public synchronized PaymentRequest startPayment(
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity,
            int beadCount,
            int priceFen
    ) {
        boolean hasTier = priceTierId != null && priceTierId > 0L;
        boolean hasQuantity = purchaseQuantity != null && purchaseQuantity > 0;
        if (purchaseRuleId <= 0L || hasTier == hasQuantity) {
            throw new IllegalArgumentException(
                    "购珠规则ID必须有效，档位ID和自定义数量必须二选一"
            );
        }
        if (beadCount <= 0 || priceFen <= 0) {
            throw new IllegalArgumentException("套餐数量和金额必须大于0");
        }
        if (!getCurrentOrderId().isEmpty()) {
            throw new IllegalStateException("当前购珠会话尚未结束");
        }

        cancelPurchaseQuery();
        consecutiveNetworkFailures = 0;
        String requestNo = newRequestNo();
        TransactionOccupancyManager.AcquireResult acquired = occupancy.tryAcquireQr(requestNo);
        if (!acquired.success || acquired.snapshot == null) {
            String reason = acquired.snapshot == null
                    ? acquired.reason
                    : occupancy.displayMessage(acquired.snapshot);
            throw new IllegalStateException(
                    reason == null || reason.isEmpty()
                            ? "设备正在处理其他交易，请稍后再试"
                            : reason
            );
        }

        JSONObject json = new JSONObject();
        try {
            json.put("clientRequestNo", requestNo);
            json.put("purchaseRuleId", purchaseRuleId);
            if (hasTier) {
                json.put("priceTierId", priceTierId);
            } else {
                json.put("purchaseQuantity", purchaseQuantity);
            }
            json.put("beadCount", beadCount);
            json.put("priceFen", priceFen);
        } catch (Throwable error) {
            occupancy.release(acquired.snapshot.sessionId, "request encode failed", true);
            throw new IllegalStateException("组装SDK购珠请求失败", error);
        }

        if (!preferences().edit()
                .putString(KEY_CURRENT_REQUEST_NO, requestNo)
                .putInt(KEY_CURRENT_BEAD_COUNT, beadCount)
                .putInt(KEY_CURRENT_PRICE_FEN, priceFen)
                .putLong(KEY_CURRENT_RULE_ID, purchaseRuleId)
                .putLong(KEY_CURRENT_TIER_ID, hasTier ? priceTierId : 0L)
                .putInt(KEY_CURRENT_PURCHASE_QUANTITY, hasQuantity ? purchaseQuantity : 0)
                .putString(KEY_CURRENT_STATUS, STAGE_PREPARING)
                .putString(KEY_CURRENT_PAYMENT_STATUS, "")
                .putString(KEY_CURRENT_SELECTED_MODE, "")
                .putString(KEY_CURRENT_PAY_CHANNEL, "DEVICE_PURCHASE")
                .putString(KEY_CURRENT_SUPPORTED_CHANNELS, "")
                .putString(KEY_CURRENT_SCAN_URL, "")
                .putLong(KEY_CURRENT_EXPIRE_TIME, 0L)
                .putString(KEY_CURRENT_STAGE, STAGE_PREPARING)
                .putBoolean(KEY_AUTH_CODE_SUBMITTED, false)
                .putBoolean(KEY_CANCEL_PENDING, false)
                .putLong(KEY_QUERY_DEADLINE, 0L)
                .putString(KEY_LAST_REQUEST_JSON, json.toString())
                .commit()) {
            occupancy.release(acquired.snapshot.sessionId, "request persistence failed", true);
            throw new IllegalStateException("购珠请求持久化失败");
        }

        paymentReadyBroadcasted = false;
        qrBroadcasted = false;
        broadcast(
                EVENT_REQUEST_CREATED,
                "正在关闭现金入口并创建统一购珠订单",
                requestNo,
                null,
                STAGE_PREPARING
        );

        final String occupancySessionId = acquired.snapshot.sessionId;
        executor.execute(() -> createPaymentAfterCashIsolation(
                occupancySessionId,
                requestNo,
                purchaseRuleId,
                hasTier ? priceTierId : null,
                hasQuantity ? purchaseQuantity : null
        ));

        return new PaymentRequest(
                requestNo,
                purchaseRuleId,
                hasTier ? priceTierId : null,
                hasQuantity ? purchaseQuantity : null,
                beadCount,
                priceFen,
                json.toString()
        );
    }

    private void createPaymentAfterCashIsolation(
            String occupancySessionId,
            String requestNo,
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity
    ) {
        boolean hasTier = priceTierId != null && priceTierId > 0L;
        boolean hasQuantity = purchaseQuantity != null && purchaseQuantity > 0;
        if (purchaseRuleId <= 0L || hasTier == hasQuantity) {
            occupancy.markBlocked("PAYMENT_RECOVERY_METADATA_INVALID");
            setStage(STAGE_BLOCKED);
            broadcast(
                    EVENT_FAILED,
                    "统一购珠订单恢复资料不完整，请联系工作人员",
                    requestNo,
                    null,
                    "RECOVERY_INVALID"
            );
            return;
        }
        if (!occupancy.prepareQrCashIsolation(occupancySessionId)) {
            synchronized (this) {
                if (requestNo.equals(getCurrentOrderId())) {
                    clearCurrentPaymentState();
                }
            }
            occupancy.release(
                    occupancySessionId,
                    "cash devices did not confirm disabled",
                    true
            );
            broadcast(
                    EVENT_FAILED,
                    "现金入口未确认关闭，未创建统一购珠订单",
                    requestNo,
                    null,
                    "PREPARE_FAILED"
            );
            return;
        }

        setStage(STAGE_CREATING);
        preferences().edit().putString(KEY_CURRENT_STATUS, STAGE_CREATING).commit();
        try {
            Object result = sdkManager.createPurchase(
                    requestNo,
                    purchaseRuleId,
                    priceTierId,
                    purchaseQuantity
            );
            synchronized (this) {
                consecutiveNetworkFailures = 0;
            }
            handlePurchaseResult(requestNo, result);
        } catch (Throwable error) {
            // createPurchase 按 clientRequestNo 幂等，结果未知时只允许重试同一个请求号。
            Log.w(TAG, "统一购珠创建结果未知，将使用原请求号重试创建", error);
            setStage(STAGE_CREATE_UNKNOWN);
            preferences().edit().putString(KEY_CURRENT_STATUS, STAGE_CREATE_UNKNOWN).commit();
            broadcast(
                    EVENT_WAITING,
                    "创建结果暂时未知，正在使用原请求号恢复订单",
                    requestNo,
                    null,
                    STAGE_CREATE_UNKNOWN
            );
            scheduleCreateRetry(requestNo);
        }
    }

    private synchronized void scheduleCreateRetry(String requestNo) {
        if (!requestNo.equals(getCurrentOrderId())) {
            return;
        }
        long deadline = ensureQueryDeadline(requestNo);
        if (deadline <= 0L) {
            return;
        }
        if (purchaseQueryTask != null && !purchaseQueryTask.isDone()) {
            return;
        }
        consecutiveNetworkFailures++;
        long delaySeconds = UnifiedPurchasePolicy.queryRetryDelaySeconds(
                consecutiveNetworkFailures
        );
        purchaseQueryTask = executor.schedule(() -> {
            synchronized (PaymentManager.this) {
                purchaseQueryTask = null;
            }
            if (!requestNo.equals(getCurrentOrderId())) {
                return;
            }
            if (System.currentTimeMillis() >= getQueryDeadline()) {
                onQueryTimeout(requestNo);
                return;
            }
            long ruleId = preferences().getLong(KEY_CURRENT_RULE_ID, 0L);
            long tierId = preferences().getLong(KEY_CURRENT_TIER_ID, 0L);
            int quantity = preferences().getInt(KEY_CURRENT_PURCHASE_QUANTITY, 0);
            TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
            if (snapshot == null
                    || !TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                    || !requestNo.equals(snapshot.clientRequestNo)) {
                occupancy.markBlocked("PAYMENT_CREATE_RECOVERY_OWNERSHIP_LOST");
                setStage(STAGE_BLOCKED);
                return;
            }
            createPaymentAfterCashIsolation(
                    snapshot.sessionId,
                    requestNo,
                    ruleId,
                    tierId > 0L ? tierId : null,
                    quantity > 0 ? quantity : null
            );
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 统一处理 create/query/pay/cancel 返回。处理优先级与服务端联调手册一致。
     */
    private synchronized void handlePurchaseResult(String requestNo, Object result) {
        if (!requestNo.equals(getCurrentOrderId()) || result == null) {
            return;
        }

        String previousStatus = getCurrentPurchaseStatus();
        String purchaseStatus = normalize(readString(result, "getPurchaseStatus"));
        if (purchaseStatus.isEmpty()) {
            purchaseStatus = normalize(previousStatus);
        }
        String paymentStatus = normalize(readString(result, "getPaymentStatus"));
        String selectedMode = normalize(readString(result, "getSelectedPaymentMode"));
        if (selectedMode.isEmpty()) {
            selectedMode = getCurrentSelectedPaymentMode();
        }
        String payChannel = normalize(readString(result, "getPayChannel"));
        if (payChannel.isEmpty()) {
            payChannel = getCurrentPayChannel();
        }
        String supportedChannels = readSupportedChannels(result);
        if (supportedChannels.isEmpty()) {
            supportedChannels = preferences().getString(KEY_CURRENT_SUPPORTED_CHANNELS, "");
        }
        String qrContent = safe(readString(result, "getScanUrl"));
        long expireTime = readLong(result, "getExpireTime", "getExpireAt");
        boolean terminal = readBoolean(result, "isTerminal", "getTerminal");
        String message = firstNonBlank(
                readString(result, "getPaymentMessage"),
                readString(result, "getMessage"),
                paymentStatus,
                purchaseStatus
        );

        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_CURRENT_STATUS, purchaseStatus)
                .putString(KEY_CURRENT_PAYMENT_STATUS, paymentStatus)
                .putString(KEY_CURRENT_SELECTED_MODE, selectedMode)
                .putString(KEY_CURRENT_PAY_CHANNEL, payChannel)
                .putString(KEY_CURRENT_SUPPORTED_CHANNELS, supportedChannels);
        if (!qrContent.isEmpty()) {
            editor.putString(KEY_CURRENT_SCAN_URL, qrContent);
        }
        if (expireTime > 0L) {
            editor.putLong(KEY_CURRENT_EXPIRE_TIME, expireTime);
        }
        if (!editor.commit()) {
            occupancy.markBlocked("PAYMENT_STATE_PERSISTENCE_FAILED");
            setStage(STAGE_BLOCKED);
            broadcast(
                    EVENT_FAILED,
                    "支付状态无法可靠保存，设备已停止新交易",
                    requestNo,
                    null,
                    purchaseStatus
            );
            return;
        }

        occupancy.onQrPurchaseStatus(requestNo, purchaseStatus);

        // 1/2. 业务支付状态优先于普通 purchaseStatus。
        if ("ORDER_CLOSED".equals(paymentStatus)) {
            finishClosedByPaymentStatus(requestNo, message, purchaseStatus);
            return;
        }
        if ("ORDER_ALREADY_PAID".equals(paymentStatus)) {
            preferences().edit().putBoolean(KEY_CANCEL_PENDING, false).commit();
            setStage(STAGE_PAID);
            broadcast(
                    EVENT_SUCCESS,
                    message.isEmpty() ? "订单已支付，等待平台出珠指令" : message,
                    requestNo,
                    null,
                    purchaseStatus
            );
            schedulePurchaseQuery(requestNo);
            return;
        }
        if ("PAYMENT_METHOD_ALREADY_SELECTED".equals(paymentStatus)) {
            setStage(STAGE_CONFIRMING);
            broadcast(
                    EVENT_WAITING,
                    message.isEmpty() ? "订单已选择其他支付方式，正在确认结果" : message,
                    requestNo,
                    null,
                    purchaseStatus
            );
            schedulePurchaseQuery(requestNo);
            return;
        }

        // 3. purchaseStatus 负责订单和物理占用推进。
        switch (purchaseStatus) {
            case "CANCELED":
            case "CLOSED":
                cancelPurchaseQuery();
                setStage(STAGE_CANCELLING);
                if (!occupancy.isQrOwned(requestNo)) {
                    clearCurrentPaymentState();
                }
                broadcast(
                        EVENT_CLOSED,
                        message.isEmpty() ? "当前购珠订单已关闭" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                return;
            case "COMPLETED":
                cancelPurchaseQuery();
                setStage(STAGE_PAID);
                if (!occupancy.isQrOwned(requestNo)) {
                    clearCurrentPaymentState();
                }
                broadcast(
                        EVENT_SUCCESS,
                        message.isEmpty() ? "购珠订单已完成" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                return;
            case "REFUNDING":
                setStage(STAGE_CONFIRMING);
                broadcast(
                        EVENT_WAITING,
                        message.isEmpty() ? "退款处理中" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                schedulePurchaseQuery(requestNo);
                return;
            case "REFUNDED":
                cancelPurchaseQuery();
                if (!occupancy.isQrOwned(requestNo)) {
                    clearCurrentPaymentState();
                }
                broadcast(
                        EVENT_FAILED,
                        message.isEmpty() ? "订单已退款" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                return;
            case "DISPENSING":
                cancelPurchaseQuery();
                preferences().edit().putBoolean(KEY_CANCEL_PENDING, false).commit();
                setStage(STAGE_PAID);
                broadcast(
                        EVENT_SUCCESS,
                        message.isEmpty() ? "支付成功，等待平台出珠指令" : message,
                        requestNo,
                        null,
                        purchaseStatus
                );
                return;
            case "EXPIRED":
                setStage(STAGE_CONFIRMING);
                broadcast(
                        EVENT_WAITING,
                        "订单已过期，正在确认最终支付/关单结果",
                        requestNo,
                        null,
                        purchaseStatus
                );
                schedulePurchaseQuery(requestNo);
                return;
            default:
                break;
        }

        // 4. 未识别终态必须 fail-closed，不能开放下一笔。
        if (terminal) {
            cancelPurchaseQuery();
            setStage(STAGE_BLOCKED);
            occupancy.markBlocked("PAYMENT_UNKNOWN_TERMINAL_" + safe(purchaseStatus));
            broadcast(
                    EVENT_FAILED,
                    message.isEmpty() ? "支付订单进入未知终态，请联系工作人员" : message,
                    requestNo,
                    null,
                    purchaseStatus
            );
            return;
        }

        // 5. WAITING_PAYMENT / PROCESSING 保持同一订单并继续 queryPurchase。
        if (isCancelPending()) {
            setStage(STAGE_CONFIRMING);
            broadcast(
                    EVENT_WAITING,
                    "正在确认当前订单关闭结果",
                    requestNo,
                    null,
                    purchaseStatus
            );
            schedulePurchaseQuery(requestNo);
            return;
        }
        if ("SUCCESS".equals(paymentStatus)) {
            setStage(STAGE_PAID);
            broadcast(
                    EVENT_SUCCESS,
                    message.isEmpty() ? "支付成功，等待订单进入出珠" : message,
                    requestNo,
                    null,
                    purchaseStatus
            );
            schedulePurchaseQuery(requestNo);
            return;
        }
        if ("PROCESSING".equals(paymentStatus)
                || UnifiedPurchasePolicy.blocksNewPayment(paymentStatus, selectedMode)) {
            setStage(STAGE_CONFIRMING);
            broadcast(
                    EVENT_WAITING,
                    displayProcessingMessage(selectedMode, message),
                    requestNo,
                    null,
                    purchaseStatus
            );
            schedulePurchaseQuery(requestNo);
            return;
        }

        if ("WAITING_PAYMENT".equals(purchaseStatus)) {
            setStage(STAGE_WAITING_PAYMENT);
            publishPaymentReady(requestNo, purchaseStatus);
            broadcast(
                    EVENT_WAITING,
                    message.isEmpty() ? "请扫码支付或出示付款码" : message,
                    requestNo,
                    null,
                    purchaseStatus
            );
            schedulePurchaseQuery(requestNo);
            return;
        }

        setStage(STAGE_CONFIRMING);
        broadcast(
                EVENT_WAITING,
                message.isEmpty() ? "正在确认统一购珠订单状态" : message,
                requestNo,
                null,
                purchaseStatus
        );
        schedulePurchaseQuery(requestNo);
    }

    private void publishPaymentReady(String requestNo, String purchaseStatus) {
        String qrContent = getCurrentScanUrl();
        if (!paymentReadyBroadcasted) {
            paymentReadyBroadcasted = true;
            broadcast(
                    EVENT_PAYMENT_READY,
                    "请扫码支付或出示微信/支付宝付款码",
                    requestNo,
                    qrContent,
                    purchaseStatus
            );
        }
        if (!qrBroadcasted && !qrContent.isEmpty()) {
            qrBroadcasted = true;
            // 保留旧事件供 MainActivity 的兼容显示代码使用；主支付窗口由 PAYMENT_READY 打开。
            broadcast(
                    EVENT_QR_READY,
                    "请扫码支付，也可直接出示付款码",
                    requestNo,
                    qrContent,
                    purchaseStatus
            );
        }
    }

    /** ttyS6 扫到付款码时调用；无付款码特征的业务码继续走原核销路由。 */
    public ScanSubmission handleAuthCodeScan(String scanContent) {
        String channel = PaymentAuthCodePolicy.classify(scanContent);
        if (channel.isEmpty()) {
            return ScanSubmission.notHandled();
        }

        final String requestNo;
        final char[] sensitiveCode;
        synchronized (this) {
            requestNo = getCurrentOrderId();
            if (requestNo.isEmpty()) {
                return ScanSubmission.handled(
                        false,
                        "请先选择购珠套餐，再出示微信或支付宝付款码",
                        channel
                );
            }
            if (!supportsAuthCodeChannel(channel)) {
                return ScanSubmission.handled(
                        false,
                        "当前订单未开放此付款码支付渠道，请使用屏幕二维码支付",
                        channel
                );
            }
            if (!canSubmitAuthCode(channel)) {
                return ScanSubmission.handled(
                        false,
                        authCodeBlockedMessage(),
                        channel
                );
            }

            // 在任何网络调用之前先持久化一次性门禁。接口超时后绝不再次提交付款码。
            if (!preferences().edit()
                    .putBoolean(KEY_AUTH_CODE_SUBMITTED, true)
                    .putString(KEY_CURRENT_STAGE, STAGE_AUTH_SUBMITTING)
                    .putString(KEY_CURRENT_PAYMENT_STATUS, "SUBMITTING")
                    .commit()) {
                occupancy.markBlocked("AUTH_CODE_SUBMIT_STATE_PERSISTENCE_FAILED");
                setStage(STAGE_BLOCKED);
                return ScanSubmission.handled(
                        false,
                        "付款状态无法可靠保存，设备已停止本次交易",
                        channel
                );
            }
            cancelPurchaseQuery();
            sensitiveCode = scanContent.toCharArray();
        }

        String acceptedMessage = PaymentAuthCodePolicy.CHANNEL_WECHAT.equals(channel)
                ? "已识别微信付款码，正在确认支付"
                : "已识别支付宝付款码，正在确认支付";
        broadcast(
                EVENT_WAITING,
                acceptedMessage,
                requestNo,
                null,
                getCurrentPurchaseStatus()
        );
        executor.execute(() -> submitAuthCodeOnce(requestNo, sensitiveCode));
        return ScanSubmission.handled(true, acceptedMessage, channel);
    }

    private void submitAuthCodeOnce(String requestNo, char[] sensitiveCode) {
        String authCode = null;
        try {
            if (!requestNo.equals(getCurrentOrderId())) {
                return;
            }
            authCode = new String(sensitiveCode);
            Object result = sdkManager.payByAuthCode(requestNo, authCode);
            synchronized (this) {
                consecutiveNetworkFailures = 0;
            }
            handlePurchaseResult(requestNo, result);
        } catch (Throwable error) {
            // 付款码可能已经到达服务端，结果未知时只查原订单，绝不重发 payByAuthCode。
            preferences().edit()
                    .putString(KEY_CURRENT_STAGE, STAGE_CONFIRMING)
                    .putString(KEY_CURRENT_PAYMENT_STATUS, "PROCESSING")
                    .commit();
            Log.w(TAG, "付款码提交结果未知，将查询原统一购珠订单");
            broadcast(
                    EVENT_WAITING,
                    "付款结果正在确认，请勿重复出示付款码",
                    requestNo,
                    null,
                    getCurrentPurchaseStatus()
            );
            schedulePurchaseQueryAfterFailure(requestNo);
        } finally {
            for (int index = 0; index < sensitiveCode.length; index++) {
                sensitiveCode[index] = '\0';
            }
            authCode = null;
        }
    }

    /** X、空白遮罩或首页取消均走同一个 cancelPurchase；两个支付入口立即同步停止。 */
    public synchronized boolean cancelCurrentPayment() {
        String requestNo = getCurrentOrderId();
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (requestNo.isEmpty()
                || snapshot == null
                || !TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                || !requestNo.equals(snapshot.clientRequestNo)
                || TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)
                || TransactionOccupancyManager.PHASE_REFUNDING.equals(snapshot.phase)
                || TransactionOccupancyManager.PHASE_BLOCKED.equals(snapshot.phase)
                || isCancelPending()
                || !occupancy.markQrCancelling(requestNo)) {
            return false;
        }
        if (!preferences().edit()
                .putBoolean(KEY_CANCEL_PENDING, true)
                .putString(KEY_CURRENT_STAGE, STAGE_CANCELLING)
                .commit()) {
            occupancy.markBlocked("PAYMENT_CANCEL_STATE_PERSISTENCE_FAILED");
            setStage(STAGE_BLOCKED);
            return false;
        }
        cancelPurchaseQuery();
        broadcast(
                EVENT_CANCELLING,
                "正在同步关闭扫码和付款码入口，并确认订单状态",
                requestNo,
                null,
                "CANCELLING"
        );
        executor.execute(() -> {
            try {
                Object result = sdkManager.cancelPurchase(requestNo);
                synchronized (PaymentManager.this) {
                    consecutiveNetworkFailures = 0;
                }
                handlePurchaseResult(requestNo, result);
            } catch (Throwable error) {
                // 取消与支付可能并发，结果未知时必须查询原订单，不能本地直接释放占用。
                Log.w(TAG, "取消统一购珠订单结果未知，将继续查询原订单", error);
                setStage(STAGE_CONFIRMING);
                broadcast(
                        EVENT_WAITING,
                        "取消结果暂时未知，正在确认原订单最终状态",
                        requestNo,
                        null,
                        "CANCEL_UNKNOWN"
                );
                schedulePurchaseQueryAfterFailure(requestNo);
            }
        });
        return true;
    }

    /**
     * 现金在关闭窗口内已经被控制板接收时，尝试关闭被抢占的统一购珠订单。
     * 此时新的现金 owner 已接管占用，因此本方法绝不能释放现金 owner。
     */
    public void cancelDisplacedPayment(String requestNo) {
        if (safe(requestNo).isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                Object result = sdkManager.cancelPurchase(requestNo);
                String status = normalize(readString(result, "getPurchaseStatus"));
                String paymentStatus = normalize(readString(result, "getPaymentStatus"));
                if (TransactionOccupancyPolicy.isCancellationSuccess(status)
                        || "ORDER_CLOSED".equals(paymentStatus)) {
                    synchronized (PaymentManager.this) {
                        if (requestNo.equals(getCurrentOrderId())) {
                            clearCurrentPaymentState();
                        }
                    }
                    broadcast(
                            EVENT_CLOSED,
                            "检测到现金投入，原统一购珠订单已安全关闭",
                            requestNo,
                            null,
                            status
                    );
                    return;
                }

                occupancy.markBlocked(
                        "UNIFIED_PURCHASE_CASH_CONFLICT_"
                                + firstNonBlank(paymentStatus, status, "UNKNOWN")
                );
                broadcast(
                        EVENT_FAILED,
                        "现金投入与线上支付发生竞态，请联系工作人员处理",
                        requestNo,
                        null,
                        status
                );
            } catch (Throwable error) {
                occupancy.markBlocked("UNIFIED_PURCHASE_CASH_CONFLICT_CANCEL_UNKNOWN");
                Log.e(TAG, "关闭被现金抢占的统一购珠订单失败", error);
                broadcast(
                        EVENT_FAILED,
                        "现金投入与线上订单状态冲突，请联系工作人员处理",
                        requestNo,
                        null,
                        "CANCEL_UNKNOWN"
                );
            }
        });
    }

    /** APP/Activity 重建后只恢复原 clientRequestNo，先恢复占用再查询，不生成新订单。 */
    public synchronized void resumePendingPayment() {
        String requestNo = getCurrentOrderId();
        if (requestNo.isEmpty()) {
            return;
        }
        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverQr(requestNo);
        if (!recovered.success || recovered.snapshot == null) {
            Log.w(TAG, "统一购珠会话无法恢复占用，保留原请求等待人工处理：" + requestNo);
            return;
        }
        cancelPurchaseQuery();
        consecutiveNetworkFailures = 0;

        String stage = getCurrentStage();
        if (STAGE_PREPARING.equals(stage)
                || STAGE_CREATING.equals(stage)
                || STAGE_CREATE_UNKNOWN.equals(stage)) {
            long ruleId = preferences().getLong(KEY_CURRENT_RULE_ID, 0L);
            long tierId = preferences().getLong(KEY_CURRENT_TIER_ID, 0L);
            int purchaseQuantity = preferences().getInt(KEY_CURRENT_PURCHASE_QUANTITY, 0);
            executor.execute(() -> createPaymentAfterCashIsolation(
                    recovered.snapshot.sessionId,
                    requestNo,
                    ruleId,
                    tierId > 0L ? tierId : null,
                    purchaseQuantity > 0 ? purchaseQuantity : null
            ));
            return;
        }

        String status = normalize(getCurrentPurchaseStatus());
        if ("WAITING_PAYMENT".equals(status) && !isCancelPending()) {
            paymentReadyBroadcasted = true;
            qrBroadcasted = !getCurrentScanUrl().isEmpty();
            broadcast(
                    EVENT_PAYMENT_READY,
                    getDisplayMessage(),
                    requestNo,
                    getCurrentScanUrl(),
                    status
            );
        }

        executor.execute(() -> {
            try {
                Object result = sdkManager.queryPurchase(requestNo);
                synchronized (PaymentManager.this) {
                    consecutiveNetworkFailures = 0;
                }
                handlePurchaseResult(requestNo, result);
            } catch (Throwable error) {
                Log.w(TAG, "恢复统一购珠订单查询失败，将按退避继续查询", error);
                schedulePurchaseQueryAfterFailure(requestNo);
            }
        });
    }

    /** Called by occupancy manager after physical completion or authoritative release. */
    public synchronized void onOccupancyReleased(String requestNo) {
        if (requestNo != null && requestNo.equals(getCurrentOrderId())) {
            cancelPurchaseQuery();
            clearCurrentPaymentState();
        }
    }

    private synchronized void schedulePurchaseQuery(String requestNo) {
        schedulePurchaseQuery(
                requestNo,
                UnifiedPurchasePolicy.NORMAL_QUERY_INTERVAL_SECONDS
        );
    }

    private synchronized void schedulePurchaseQuery(String requestNo, long delaySeconds) {
        if (!requestNo.equals(getCurrentOrderId())) {
            return;
        }
        long deadline = ensureQueryDeadline(requestNo);
        if (deadline <= 0L) {
            return;
        }
        if (purchaseQueryTask != null && !purchaseQueryTask.isDone()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= deadline) {
            onQueryTimeout(requestNo);
            return;
        }
        long delayMs = Math.min(
                Math.max(0L, delaySeconds) * 1000L,
                Math.max(0L, deadline - now)
        );
        purchaseQueryTask = executor.schedule(() -> {
            synchronized (PaymentManager.this) {
                purchaseQueryTask = null;
            }
            if (!requestNo.equals(getCurrentOrderId())) {
                return;
            }
            if (System.currentTimeMillis() >= getQueryDeadline()) {
                onQueryTimeout(requestNo);
                return;
            }
            try {
                Object result = sdkManager.queryPurchase(requestNo);
                synchronized (PaymentManager.this) {
                    consecutiveNetworkFailures = 0;
                }
                handlePurchaseResult(requestNo, result);
            } catch (Throwable error) {
                Log.w(TAG, "统一购珠订单查询失败，将按退避继续查询原请求号", error);
                broadcast(
                        EVENT_WAITING,
                        "网络波动，正在继续确认原支付订单",
                        requestNo,
                        null,
                        "QUERY_RETRY"
                );
                schedulePurchaseQueryAfterFailure(requestNo);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void schedulePurchaseQueryAfterFailure(String requestNo) {
        if (!requestNo.equals(getCurrentOrderId())) {
            return;
        }
        consecutiveNetworkFailures++;
        long delay = UnifiedPurchasePolicy.queryRetryDelaySeconds(consecutiveNetworkFailures);
        Log.w(TAG, "统一购珠查单连续失败=" + consecutiveNetworkFailures
                + "，" + delay + "秒后继续查询原请求号");
        schedulePurchaseQuery(requestNo, delay);
    }

    private synchronized long ensureQueryDeadline(String requestNo) {
        if (!requestNo.equals(getCurrentOrderId())) {
            return 0L;
        }
        long deadline = getQueryDeadline();
        if (deadline > 0L) {
            return deadline;
        }
        deadline = System.currentTimeMillis()
                + UnifiedPurchasePolicy.MAX_UNKNOWN_QUERY_DURATION_MS;
        if (!preferences().edit().putLong(KEY_QUERY_DEADLINE, deadline).commit()) {
            setStage(STAGE_BLOCKED);
            occupancy.markBlocked("PAYMENT_QUERY_DEADLINE_PERSISTENCE_FAILED");
            broadcast(
                    EVENT_FAILED,
                    "支付查单超时状态无法可靠保存，请联系工作人员",
                    requestNo,
                    null,
                    getCurrentPurchaseStatus()
            );
            return 0L;
        }
        return deadline;
    }

    private long getQueryDeadline() {
        return preferences().getLong(KEY_QUERY_DEADLINE, 0L);
    }

    private synchronized void onQueryTimeout(String requestNo) {
        if (!requestNo.equals(getCurrentOrderId())) {
            return;
        }
        cancelPurchaseQuery();
        setStage(STAGE_BLOCKED);
        occupancy.markBlocked("PAYMENT_QUERY_TIMEOUT");
        broadcast(
                EVENT_FAILED,
                "支付状态长时间无法确认，请联系工作人员",
                requestNo,
                null,
                "QUERY_TIMEOUT"
        );
    }

    private synchronized void cancelPurchaseQuery() {
        if (purchaseQueryTask != null) {
            purchaseQueryTask.cancel(false);
            purchaseQueryTask = null;
        }
    }

    private void finishClosedByPaymentStatus(
            String requestNo,
            String message,
            String purchaseStatus
    ) {
        cancelPurchaseQuery();
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && requestNo.equals(snapshot.clientRequestNo)
                && !TransactionOccupancyPolicy.isPhysicalPhase(snapshot.phase)) {
            occupancy.release(snapshot.sessionId, "paymentStatus ORDER_CLOSED", true);
        } else if (snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && requestNo.equals(snapshot.clientRequestNo)) {
            occupancy.markBlocked("ORDER_CLOSED_WITH_PHYSICAL_PHASE");
        }
        if (requestNo.equals(getCurrentOrderId()) && !occupancy.isQrOwned(requestNo)) {
            clearCurrentPaymentState();
        }
        broadcast(
                EVENT_CLOSED,
                message.isEmpty() ? "当前购珠订单已关闭" : message,
                requestNo,
                null,
                firstNonBlank(purchaseStatus, "CLOSED")
        );
    }

    /** Staff-entered internal pickup code; hardware still only follows signed MQTT dispense. */
    public String submitScannerQrString(String scanContent) {
        if (!occupancy.isIdle()) {
            broadcast(
                    EVENT_FAILED,
                    "设备正在处理其他交易，请稍后再扫码",
                    getCurrentOrderId(),
                    null,
                    "DEVICE_TRANSACTION_OCCUPIED"
            );
            return "";
        }
        String pickupCode = scanContent == null ? "" : scanContent.trim();
        if (pickupCode.isEmpty()) {
            broadcast(EVENT_FAILED, "平台取珠码不能为空", getCurrentOrderId(), null, "");
            return "";
        }

        String requestNo = newRequestNo("redeem");
        if (!saveScannerRequestMetadata(requestNo, "internal", pickupCode)) {
            broadcast(EVENT_FAILED, "保存核销请求元数据失败", getCurrentOrderId(), null, "");
            return "";
        }

        executor.execute(() -> {
            try {
                DeviceAppInternalRedemptionResult result = sdkManager.createInternalRedemption(
                        requestNo,
                        pickupCode
                );
                String message = firstNonBlank(
                        result.getMessage(),
                        result.getRedemptionStatus(),
                        "取珠码已受理，等待平台处理"
                );
                broadcast(EVENT_SCANNER_REPORTED, message, requestNo, null, "");
            } catch (Throwable error) {
                Log.e(TAG, "平台取珠码提交失败，requestNo=" + requestNo, error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null, "");
            }
        });
        return requestNo;
    }

    public String submitMemberWithdrawal(String withdrawalCode) {
        if (!occupancy.isIdle()) {
            broadcast(
                    EVENT_FAILED,
                    "设备正在处理其他交易，请稍后再取珠",
                    getCurrentOrderId(),
                    null,
                    "DEVICE_TRANSACTION_OCCUPIED"
            );
            return "";
        }
        String code = withdrawalCode == null ? "" : withdrawalCode.trim();
        if (code.isEmpty()) {
            broadcast(EVENT_FAILED, "会员取珠码不能为空", getCurrentOrderId(), null, "");
            return "";
        }
        String requestNo = newRequestNo("withdraw");
        if (!saveScannerRequestMetadata(requestNo, "member", code)) {
            broadcast(EVENT_FAILED, "保存会员取珠请求元数据失败", requestNo, null, "");
            return "";
        }
        executor.execute(() -> {
            try {
                DeviceAppMemberWithdrawalResult result = sdkManager.createMemberWithdrawal(
                        requestNo,
                        code
                );
                String message = firstNonBlank(
                        result.getMessage(),
                        result.getWithdrawalStatus(),
                        "会员取珠请求已受理，等待平台处理"
                );
                broadcast(EVENT_SCANNER_REPORTED, message, requestNo, null, "");
            } catch (Throwable error) {
                Log.e(TAG, "会员取珠请求提交失败，requestNo=" + requestNo, error);
                broadcast(EVENT_FAILED, messageOf(error), requestNo, null, "");
            }
        });
        return requestNo;
    }

    private boolean saveScannerRequestMetadata(
            String requestNo,
            String routeType,
            String businessCode
    ) {
        try {
            JSONObject json = new JSONObject();
            json.put("clientRequestNo", requestNo);
            json.put("routeType", routeType);
            json.put("codeLength", businessCode == null ? 0 : businessCode.length());
            json.put("maskedCode", maskCode(businessCode));
            return preferences().edit()
                    .putString(KEY_SCANNER_REQUEST_NO, requestNo)
                    .putString(KEY_LAST_SCANNER_JSON, json.toString())
                    .commit();
        } catch (Throwable error) {
            Log.e(TAG, "保存扫码请求脱敏元数据失败", error);
            return false;
        }
    }

    public boolean handleServerMessage(String payload) {
        return false;
    }

    public String getCurrentOrderId() {
        return safe(preferences().getString(KEY_CURRENT_REQUEST_NO, ""));
    }

    public String getCurrentPurchaseStatus() {
        return normalize(preferences().getString(KEY_CURRENT_STATUS, ""));
    }

    public String getCurrentPaymentStatus() {
        return normalize(preferences().getString(KEY_CURRENT_PAYMENT_STATUS, ""));
    }

    public String getCurrentSelectedPaymentMode() {
        return normalize(preferences().getString(KEY_CURRENT_SELECTED_MODE, ""));
    }

    public String getCurrentPayChannel() {
        return normalize(preferences().getString(KEY_CURRENT_PAY_CHANNEL, ""));
    }

    public String getCurrentScanUrl() {
        return safe(preferences().getString(KEY_CURRENT_SCAN_URL, ""));
    }

    public boolean isAuthCodeSubmitted() {
        return preferences().getBoolean(KEY_AUTH_CODE_SUBMITTED, false);
    }

    public boolean isCancelPending() {
        return preferences().getBoolean(KEY_CANCEL_PENDING, false);
    }

    public boolean canSubmitAuthCode(String channel) {
        return UnifiedPurchasePolicy.canSubmitAuthCode(
                getCurrentPurchaseStatus(),
                getCurrentPaymentStatus(),
                getCurrentSelectedPaymentMode(),
                isCancelPending(),
                isAuthCodeSubmitted(),
                supportsAuthCodeChannel(channel)
        ) && occupancy.isQrOwned(getCurrentOrderId());
    }

    public boolean canAutoCancelForUserTimeout() {
        return UnifiedPurchasePolicy.canAutoCancelForUserTimeout(
                getCurrentPurchaseStatus(),
                getCurrentPaymentStatus(),
                getCurrentSelectedPaymentMode(),
                isCancelPending(),
                isAuthCodeSubmitted()
        ) && occupancy.isQrOwned(getCurrentOrderId());
    }

    public boolean shouldShowQrCode() {
        String selectedMode = getCurrentSelectedPaymentMode();
        return !getCurrentScanUrl().isEmpty()
                && !isCancelPending()
                && !isAuthCodeSubmitted()
                && !"AUTH_CODE".equals(selectedMode)
                && !"SUCCESS".equals(getCurrentPaymentStatus())
                && !"ORDER_ALREADY_PAID".equals(getCurrentPaymentStatus())
                && !"ORDER_CLOSED".equals(getCurrentPaymentStatus());
    }

    public boolean hasAnyAuthCodeChannel() {
        return supportsAuthCodeChannel(PaymentAuthCodePolicy.CHANNEL_WECHAT)
                || supportsAuthCodeChannel(PaymentAuthCodePolicy.CHANNEL_ALIPAY);
    }

    public String getDisplayMessage() {
        if (isCancelPending()) {
            return "正在关闭当前购珠订单";
        }
        String paymentStatus = getCurrentPaymentStatus();
        String mode = getCurrentSelectedPaymentMode();
        if ("ORDER_ALREADY_PAID".equals(paymentStatus) || "SUCCESS".equals(paymentStatus)) {
            return "支付成功，等待平台出珠";
        }
        if ("AUTH_CODE".equals(mode) || isAuthCodeSubmitted()) {
            return "付款码已提交，正在确认支付结果";
        }
        if ("SCAN".equals(mode)) {
            return "已选择扫码支付，正在确认支付结果";
        }
        if ("PROCESSING".equals(paymentStatus)
                || "PAYMENT_METHOD_ALREADY_SELECTED".equals(paymentStatus)) {
            return "支付方式已确定，正在确认支付结果";
        }
        if (hasAnyAuthCodeChannel() && !getCurrentScanUrl().isEmpty()) {
            return "请扫码支付，或出示微信/支付宝付款码";
        }
        if (hasAnyAuthCodeChannel()) {
            return "请出示微信或支付宝付款码";
        }
        if (!getCurrentScanUrl().isEmpty()) {
            return "请使用微信或支付宝扫码支付";
        }
        return "当前支付渠道暂不可用，可关闭订单后重试";
    }

    public String getLastPaymentRequestJson() {
        return preferences().getString(KEY_LAST_REQUEST_JSON, "");
    }

    public String getLastScannerReportJson() {
        return preferences().getString(KEY_LAST_SCANNER_JSON, "");
    }

    private String authCodeBlockedMessage() {
        if (isCancelPending()) {
            return "当前订单正在关闭，请勿继续付款";
        }
        String mode = getCurrentSelectedPaymentMode();
        if ("SCAN".equals(mode)) {
            return "订单已选择扫码支付，请勿重复付款";
        }
        if ("AUTH_CODE".equals(mode) || isAuthCodeSubmitted()) {
            return "付款码已提交，正在确认结果，请勿重复出示";
        }
        String paymentStatus = getCurrentPaymentStatus();
        if ("ORDER_ALREADY_PAID".equals(paymentStatus) || "SUCCESS".equals(paymentStatus)) {
            return "订单已支付，正在等待出珠";
        }
        if ("ORDER_CLOSED".equals(paymentStatus)) {
            return "订单已关闭，请重新选择套餐";
        }
        return "当前订单暂不能接收新的付款码";
    }

    private boolean supportsAuthCodeChannel(String channel) {
        String target = normalize(channel);
        if (target.isEmpty()) {
            return false;
        }
        String channels = preferences().getString(KEY_CURRENT_SUPPORTED_CHANNELS, "");
        if (channels == null || channels.trim().isEmpty()) {
            return false;
        }
        String[] parts = channels.split(",");
        for (String part : parts) {
            if (target.equals(normalize(part))) {
                return true;
            }
        }
        return false;
    }

    private String getCurrentStage() {
        return normalize(preferences().getString(KEY_CURRENT_STAGE, ""));
    }

    private boolean setStage(String stage) {
        return preferences().edit()
                .putString(KEY_CURRENT_STAGE, safe(stage))
                .commit();
    }

    private void clearCurrentPaymentState() {
        preferences().edit()
                .remove(KEY_CURRENT_REQUEST_NO)
                .remove(KEY_CURRENT_BEAD_COUNT)
                .remove(KEY_CURRENT_PRICE_FEN)
                .remove(KEY_CURRENT_RULE_ID)
                .remove(KEY_CURRENT_TIER_ID)
                .remove(KEY_CURRENT_PURCHASE_QUANTITY)
                .remove(KEY_CURRENT_STATUS)
                .remove(KEY_CURRENT_PAYMENT_STATUS)
                .remove(KEY_CURRENT_SELECTED_MODE)
                .remove(KEY_CURRENT_PAY_CHANNEL)
                .remove(KEY_CURRENT_SUPPORTED_CHANNELS)
                .remove(KEY_CURRENT_SCAN_URL)
                .remove(KEY_CURRENT_EXPIRE_TIME)
                .remove(KEY_CURRENT_STAGE)
                .remove(KEY_AUTH_CODE_SUBMITTED)
                .remove(KEY_CANCEL_PENDING)
                .remove(KEY_QUERY_DEADLINE)
                .commit();
        paymentReadyBroadcasted = false;
        qrBroadcasted = false;
        consecutiveNetworkFailures = 0;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void broadcast(
            String event,
            String message,
            String orderId,
            String qrContent,
            String purchaseStatus
    ) {
        Intent intent = new Intent(ACTION_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_EVENT, safe(event));
        intent.putExtra(EXTRA_MESSAGE, safe(message));
        intent.putExtra(EXTRA_ORDER_ID, safe(orderId));
        intent.putExtra(EXTRA_PURCHASE_STATUS, safe(purchaseStatus));
        intent.putExtra(EXTRA_PAYMENT_STATUS, getCurrentPaymentStatus());
        intent.putExtra(EXTRA_SELECTED_PAYMENT_MODE, getCurrentSelectedPaymentMode());
        intent.putExtra(EXTRA_PAY_CHANNEL, getCurrentPayChannel());
        if (qrContent != null) {
            intent.putExtra(EXTRA_QR_CONTENT, qrContent);
        }
        context.sendBroadcast(intent);
    }

    private static String displayProcessingMessage(String selectedMode, String message) {
        if (!safe(message).isEmpty()) {
            return safe(message);
        }
        if ("AUTH_CODE".equals(normalize(selectedMode))) {
            return "付款码支付处理中，请勿重复出示付款码";
        }
        if ("SCAN".equals(normalize(selectedMode))) {
            return "扫码支付处理中，正在确认结果";
        }
        return "支付结果正在确认，请勿重复付款";
    }

    private static String readSupportedChannels(Object target) {
        Object value = invokeOptional(target, "getSupportedChannels");
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                appendChannel(builder, item);
            }
            return builder.toString();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                appendChannel(builder, Array.get(value, index));
            }
            return builder.toString();
        }
        appendChannel(builder, value);
        return builder.toString();
    }

    private static void appendChannel(StringBuilder builder, Object value) {
        String channel = normalize(value == null ? "" : String.valueOf(value));
        if (channel.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(channel);
    }

    private static Object invokeOptional(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String name : methodNames) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readString(Object target, String... methodNames) {
        Object value = invokeOptional(target, methodNames);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long readLong(Object target, String... methodNames) {
        Object value = invokeOptional(target, methodNames);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static boolean readBoolean(Object target, String... methodNames) {
        Object value = invokeOptional(target, methodNames);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String newRequestNo() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "APPREQ-" + System.currentTimeMillis() + "-" + uuid.substring(0, 12);
    }

    private static String newRequestNo(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "-" + System.currentTimeMillis() + "-" + uuid.substring(0, 12);
    }

    private static String maskCode(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int visible = Math.min(4, content.length());
        return "***" + content.substring(content.length() - visible);
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

    private static String normalize(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
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

    public static final class ScanSubmission {
        public final boolean handled;
        public final boolean accepted;
        public final String message;
        public final String channel;

        private ScanSubmission(boolean handled, boolean accepted, String message, String channel) {
            this.handled = handled;
            this.accepted = accepted;
            this.message = safe(message);
            this.channel = safe(channel);
        }

        static ScanSubmission notHandled() {
            return new ScanSubmission(false, false, "", "");
        }

        static ScanSubmission handled(boolean accepted, String message, String channel) {
            return new ScanSubmission(true, accepted, message, channel);
        }
    }

    public static final class PaymentRequest {
        public final String orderId;
        public final long purchaseRuleId;
        public final Long priceTierId;
        public final Integer purchaseQuantity;
        public final int beadCount;
        public final int priceFen;
        public final String requestJson;

        PaymentRequest(
                String orderId,
                long purchaseRuleId,
                Long priceTierId,
                Integer purchaseQuantity,
                int beadCount,
                int priceFen,
                String requestJson
        ) {
            this.orderId = orderId;
            this.purchaseRuleId = purchaseRuleId;
            this.priceTierId = priceTierId;
            this.purchaseQuantity = purchaseQuantity;
            this.beadCount = beadCount;
            this.priceFen = priceFen;
            this.requestJson = requestJson;
        }
    }
}
