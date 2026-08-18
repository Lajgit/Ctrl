package com.gouzhu.payment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.transaction.TransactionOccupancyPolicy;
import com.pinball.xiaoda.device.sdk.client.DeviceAppNativePurchaseResult;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 微信/支付宝付款码被扫支付管理器。
 *
 * <p>本链与现有聚合主扫 PaymentManager 分离，只复用设备级交易占用和 MQTT 出珠链。
 * 完整付款码只在一次 SDK pay 调用所需的短生命周期内存在，不写日志、偏好、数据库、
 * 广播或 MQTT；支付成功也绝不直接驱动控制板。</p>
 */
public final class AuthCodePaymentManager {

    public static final String ACTION_AUTH_CODE_PAYMENT_EVENT =
            "com.gouzhu.action.AUTH_CODE_PAYMENT_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_REQUEST_NO = "requestNo";
    public static final String EXTRA_PURCHASE_STATUS = "purchaseStatus";
    public static final String EXTRA_PAYMENT_STATUS = "paymentStatus";
    public static final String EXTRA_CHANNEL = "channel";

    public static final String EVENT_PREPARING = "preparing";
    public static final String EVENT_READY = "ready";
    public static final String EVENT_CODE_ACCEPTED = "codeAccepted";
    public static final String EVENT_CODE_REJECTED = "codeRejected";
    public static final String EVENT_PROCESSING = "processing";
    public static final String EVENT_SUCCESS = "success";
    public static final String EVENT_CANCELLING = "cancelling";
    public static final String EVENT_CLOSED = "closed";
    public static final String EVENT_FINISHED = "finished";
    public static final String EVENT_FAILED = "failed";

    private static final String TAG = "GouzhuAuthCodePay";
    private static final String PREF = "auth_code_payment_state_v1";
    private static final String KEY_REQUEST_NO = "requestNo";
    private static final String KEY_RULE_ID = "purchaseRuleId";
    private static final String KEY_TIER_ID = "priceTierId";
    private static final String KEY_PURCHASE_QUANTITY = "purchaseQuantity";
    private static final String KEY_PURCHASE_STATUS = "purchaseStatus";
    private static final String KEY_PAYMENT_STATUS = "paymentStatus";
    private static final String KEY_STAGE = "stage";

    private static final String STAGE_PREPARING = "PREPARING";
    private static final String STAGE_CREATING = "CREATING";
    private static final String STAGE_WAITING_CODE = "WAITING_CODE";
    private static final String STAGE_SUBMITTING = "SUBMITTING";
    private static final String STAGE_PROCESSING = "PROCESSING";
    private static final String STAGE_PAID = "PAID";
    private static final String STAGE_CANCELLING = "CANCELLING";
    private static final String STAGE_BLOCKED = "BLOCKED";

    private static final int CMD_CASH_APPLY_V22 = 0x33;
    private static final int EVT_CASH_ACCEPTANCE_STATUS = 0x11;
    private static final long CASH_DISABLE_TIMEOUT_MS = 3500L;
    private static final long CASH_DISABLE_STABILIZE_MS = 350L;
    private static final Object CASH_PREFLIGHT_LOCK = new Object();
    private static final long QUERY_INTERVAL_SECONDS = 2L;
    private static final long MAX_QUERY_DURATION_MS = 10L * 60L * 1000L;

    private static volatile AuthCodePaymentManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> queryTask;
    private long queryDeadline;

    private final BroadcastReceiver occupancyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent != null
                    && TransactionOccupancyManager.ACTION_CHANGED.equals(intent.getAction())) {
                onOccupancyChanged();
            }
        }
    };

    private AuthCodePaymentManager(Context context) {
        this.context = context.getApplicationContext();
        this.sdkManager = DeviceSdkManager.get(this.context);
        this.occupancy = TransactionOccupancyManager.get(this.context);
        // 单例只持有 Application Context，可覆盖 Activity 退出后的物理完成事件。
        this.context.registerReceiver(
                occupancyReceiver,
                new IntentFilter(TransactionOccupancyManager.ACTION_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
        );
    }

    public static AuthCodePaymentManager get(Context context) {
        if (instance == null) {
            synchronized (AuthCodePaymentManager.class) {
                if (instance == null) {
                    instance = new AuthCodePaymentManager(context);
                }
            }
        }
        return instance;
    }

    /** 创建 AUTH_CODE 会话；现有聚合主扫调用点不会进入本方法。 */
    public synchronized String startPayment(
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity,
            int beadCount,
            int priceFen
    ) {
        boolean hasTier = priceTierId != null && priceTierId > 0L;
        boolean hasQuantity = purchaseQuantity != null && purchaseQuantity > 0;
        if (purchaseRuleId <= 0L || hasTier == hasQuantity) {
            throw new IllegalArgumentException("购珠规则无效，档位和自定义数量必须二选一");
        }
        if (beadCount <= 0 || priceFen <= 0) {
            throw new IllegalArgumentException("套餐数量和金额必须大于0");
        }
        if (isActive()) {
            throw new IllegalStateException("当前付款码支付会话尚未结束");
        }
        if (!PaymentManager.get(context).getCurrentOrderId().isEmpty()) {
            throw new IllegalStateException("当前聚合扫码支付会话尚未结束");
        }
        if (!occupancy.canStartNewTransaction()) {
            throw new IllegalStateException("设备正在处理其他交易，请稍后再试");
        }

        String requestNo = newRequestNo();
        if (!preferences().edit()
                .putString(KEY_REQUEST_NO, requestNo)
                .putLong(KEY_RULE_ID, purchaseRuleId)
                .putLong(KEY_TIER_ID, hasTier ? priceTierId : 0L)
                .putInt(KEY_PURCHASE_QUANTITY, hasQuantity ? purchaseQuantity : 0)
                .putString(KEY_PURCHASE_STATUS, "")
                .putString(KEY_PAYMENT_STATUS, "")
                .putString(KEY_STAGE, STAGE_PREPARING)
                .commit()) {
            throw new IllegalStateException("付款码支付请求持久化失败");
        }

        queryDeadline = System.currentTimeMillis() + MAX_QUERY_DURATION_MS;
        broadcast(EVENT_PREPARING, "正在关闭现金入口并创建付款码订单",
                requestNo, "", "", "");
        executor.execute(() -> prepareAndCreate(
                requestNo,
                purchaseRuleId,
                hasTier ? priceTierId : null,
                hasQuantity ? purchaseQuantity : null
        ));
        return requestNo;
    }

    /**
     * 只有明确处于等待付款码状态时才消费 ttyS6 扫码；否则原会员/核销路由不变。
     */
    public ScanSubmission handleScanIfArmed(String scanContent) {
        final String requestNo;
        final String channel;
        final char[] sensitiveCode;
        synchronized (this) {
            requestNo = getCurrentRequestNo();
            if (requestNo.isEmpty()) {
                return ScanSubmission.notHandled();
            }
            String stage = getStage();
            String paymentStatus = getPaymentStatus();
            if (!STAGE_WAITING_CODE.equals(stage)
                    || !PaymentAuthCodePolicy.canSubmit(paymentStatus)) {
                String message = "付款结果正在确认，请勿重复出示付款码";
                broadcast(EVENT_PROCESSING, message, requestNo,
                        getPurchaseStatus(), paymentStatus, "");
                return ScanSubmission.handled(false, message, "");
            }

            channel = PaymentAuthCodePolicy.classify(scanContent);
            if (channel.isEmpty()) {
                String message = "未识别为有效微信或支付宝付款码，请刷新后重试";
                broadcast(EVENT_CODE_REJECTED, message, requestNo,
                        getPurchaseStatus(), paymentStatus, "");
                return ScanSubmission.handled(false, message, "");
            }

            // 先持久化“已提交/结果未知”门禁，再进行一次性 pay；HTTP 异常也绝不重发 pay。
            if (!preferences().edit()
                    .putString(KEY_STAGE, STAGE_SUBMITTING)
                    .putString(KEY_PAYMENT_STATUS, "SUBMITTING")
                    .commit()) {
                occupancy.markBlocked("AUTH_CODE_SUBMIT_STATE_PERSISTENCE_FAILED");
                String message = "付款状态无法可靠保存，设备已停止本次交易";
                broadcast(EVENT_FAILED, message, requestNo,
                        getPurchaseStatus(), "", channel);
                return ScanSubmission.handled(false, message, channel);
            }
            cancelQuery();
            sensitiveCode = scanContent == null ? new char[0] : scanContent.toCharArray();
        }

        String acceptedMessage = PaymentAuthCodePolicy.CHANNEL_WECHAT.equals(channel)
                ? "已识别微信付款码，正在确认支付"
                : "已识别支付宝付款码，正在确认支付";
        broadcast(EVENT_CODE_ACCEPTED, acceptedMessage, requestNo,
                getPurchaseStatus(), "SUBMITTING", channel);
        executor.execute(() -> submitAuthCodeOnce(requestNo, channel, sensitiveCode));
        return ScanSubmission.handled(true, acceptedMessage, channel);
    }

    private void prepareAndCreate(
            String requestNo,
            long purchaseRuleId,
            Long priceTierId,
            Integer purchaseQuantity
    ) {
        if (!requestNo.equals(getCurrentRequestNo())) {
            return;
        }
        /*
         * 占用建立前先确认现金已真实关闭，并留出短暂事实收敛窗口。这样关闭前已经进入
         * 硬件流程的现金会优先占用设备，本付款码链不会创建线上订单。之后仍调用现有
         * prepareQrCashIsolation 做第二次版本/掩码确认。
         */
        if (!confirmCashDisabledPreflight()) {
            failPreparation(requestNo, "现金入口未确认关闭，未创建付款码订单");
            return;
        }
        if (!occupancy.canStartNewTransaction()) {
            failPreparation(requestNo, "设备已被其他交易占用，未创建付款码订单");
            return;
        }
        TransactionOccupancyManager.AcquireResult acquired = occupancy.tryAcquireQr(requestNo);
        if (!acquired.success || acquired.snapshot == null) {
            failPreparation(requestNo, "设备正在处理其他交易，请稍后再试");
            return;
        }
        if (!occupancy.prepareQrCashIsolation(acquired.snapshot.sessionId)) {
            occupancy.release(acquired.snapshot.sessionId,
                    "auth-code cash isolation failed", true);
            failPreparation(requestNo, "现金入口未确认关闭，未创建付款码订单");
            return;
        }
        if (!preferences().edit().putString(KEY_STAGE, STAGE_CREATING).commit()) {
            occupancy.markBlocked("AUTH_CODE_CREATE_STATE_PERSISTENCE_FAILED");
            broadcast(EVENT_FAILED, "付款状态无法可靠保存，设备已停止本次交易",
                    requestNo, "", "", "");
            return;
        }
        try {
            handleResult(requestNo, invokePurchaseMethod(
                    "createAuthCodePurchase",
                    requestNo,
                    purchaseRuleId,
                    priceTierId,
                    purchaseQuantity
            ));
        } catch (Throwable error) {
            // 创建结果未知时只能查询相同 requestNo，禁止创建第二笔。
            Log.w(TAG, "付款码订单创建结果未知，将使用原请求号查单");
            preferences().edit().putString(KEY_STAGE, STAGE_PROCESSING).commit();
            broadcast(EVENT_PROCESSING, "创建结果暂时未知，正在确认原付款码订单",
                    requestNo, getPurchaseStatus(), getPaymentStatus(), "");
            scheduleQuery(requestNo);
        }
    }

    private void submitAuthCodeOnce(String requestNo, String channel, char[] sensitiveCode) {
        String authCode = null;
        try {
            if (!requestNo.equals(getCurrentRequestNo())) {
                return;
            }
            authCode = new String(sensitiveCode);
            handleResult(requestNo, invokePurchaseMethod("payByAuthCode", requestNo, authCode));
        } catch (Throwable error) {
            preferences().edit()
                    .putString(KEY_STAGE, STAGE_PROCESSING)
                    .putString(KEY_PAYMENT_STATUS, "PROCESSING")
                    .commit();
            Log.w(TAG, "付款码提交结果未知，将使用原请求号查单");
            broadcast(EVENT_PROCESSING, "付款结果正在确认，请勿重复出示付款码",
                    requestNo, getPurchaseStatus(), "PROCESSING", channel);
            scheduleQuery(requestNo);
        } finally {
            Arrays.fill(sensitiveCode, '\0');
            authCode = null;
        }
    }

    private synchronized void handleResult(
            String requestNo,
            DeviceAppNativePurchaseResult result
    ) {
        if (!requestNo.equals(getCurrentRequestNo()) || result == null) {
            return;
        }
        String purchaseStatus = TransactionOccupancyPolicy.normalize(result.getPurchaseStatus());
        String paymentStatus = PaymentAuthCodePolicy.normalize(
                readString(result, "getPaymentStatus")
        );
        String message = safe(result.getMessage());
        if (!preferences().edit()
                .putString(KEY_PURCHASE_STATUS, purchaseStatus)
                .putString(KEY_PAYMENT_STATUS, paymentStatus)
                .commit()) {
            occupancy.markBlocked("AUTH_CODE_STATE_PERSISTENCE_FAILED");
            broadcast(EVENT_FAILED, "付款状态无法可靠保存，设备已停止本次交易",
                    requestNo, purchaseStatus, paymentStatus, "");
            return;
        }

        // 这里只推进统一占用；真实出珠仍只允许经过 MQTT dispense_marbles。
        occupancy.onQrPurchaseStatus(requestNo, purchaseStatus);
        switch (purchaseStatus) {
            case "CANCELED":
            case "CLOSED":
                cancelQuery();
                clearState();
                broadcast(EVENT_CLOSED,
                        message.isEmpty() ? "付款码订单已关闭" : message,
                        requestNo, purchaseStatus, paymentStatus, "");
                return;
            case "COMPLETED":
                cancelQuery();
                if (!occupancy.isQrOwned(requestNo)) {
                    clearState();
                }
                broadcast(EVENT_FINISHED,
                        message.isEmpty() ? "购珠订单已完成" : message,
                        requestNo, purchaseStatus, paymentStatus, "");
                return;
            case "REFUNDING":
                setStage(STAGE_PROCESSING);
                broadcast(EVENT_PROCESSING,
                        message.isEmpty() ? "退款处理中" : message,
                        requestNo, purchaseStatus, paymentStatus, "");
                scheduleQuery(requestNo);
                return;
            case "REFUNDED":
                cancelQuery();
                if (!occupancy.isQrOwned(requestNo)) {
                    clearState();
                }
                broadcast(EVENT_FAILED,
                        message.isEmpty() ? "订单已退款" : message,
                        requestNo, purchaseStatus, paymentStatus, "");
                return;
            case "DISPENSING":
                setStage(STAGE_PAID);
                cancelQuery();
                broadcast(EVENT_SUCCESS,
                        message.isEmpty() ? "支付成功，等待平台出珠指令" : message,
                        requestNo, purchaseStatus, paymentStatus, "");
                return;
            case "EXPIRED":
                setStage(STAGE_PROCESSING);
                broadcast(EVENT_PROCESSING, "付款码订单已过期，正在确认最终支付结果",
                        requestNo, purchaseStatus, paymentStatus, "");
                scheduleQuery(requestNo);
                return;
            default:
                break;
        }

        if (paymentStatus.isEmpty() || "FAILED".equals(paymentStatus)) {
            setStage(STAGE_WAITING_CODE);
            broadcast(EVENT_READY,
                    "FAILED".equals(paymentStatus)
                            ? "本次付款未成功，请刷新付款码后重新出示"
                            : "请出示微信或支付宝付款码",
                    requestNo, purchaseStatus, paymentStatus, "");
            scheduleQuery(requestNo);
            return;
        }
        if ("PROCESSING".equals(paymentStatus)) {
            setStage(STAGE_PROCESSING);
            broadcast(EVENT_PROCESSING,
                    message.isEmpty() ? "付款结果正在确认，请勿重复出示付款码" : message,
                    requestNo, purchaseStatus, paymentStatus, "");
            scheduleQuery(requestNo);
            return;
        }
        if ("SUCCESS".equals(paymentStatus)) {
            setStage(STAGE_PAID);
            broadcast(EVENT_SUCCESS,
                    message.isEmpty() ? "支付成功，等待平台出珠指令" : message,
                    requestNo, purchaseStatus, paymentStatus, "");
            scheduleQuery(requestNo);
            return;
        }
        setStage(STAGE_PROCESSING);
        broadcast(EVENT_PROCESSING, "正在确认付款结果，请勿重复出示付款码",
                requestNo, purchaseStatus, paymentStatus, "");
        scheduleQuery(requestNo);
    }

    /** 进程重建只恢复原 clientRequestNo，不生成替代订单。 */
    public synchronized void resumePendingPayment() {
        String requestNo = getCurrentRequestNo();
        if (requestNo.isEmpty()) {
            return;
        }
        queryDeadline = System.currentTimeMillis() + MAX_QUERY_DURATION_MS;
        cancelQuery();
        String stage = getStage();
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot == null) {
            executor.execute(() -> resumeWithoutOccupancy(requestNo, stage));
            return;
        }
        if (!TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                || !requestNo.equals(snapshot.clientRequestNo)) {
            return;
        }
        executor.execute(() -> resumeWithOccupancy(requestNo, stage, snapshot.sessionId));
    }

    private void resumeWithoutOccupancy(String requestNo, String stage) {
        if (!requestNo.equals(getCurrentRequestNo())) {
            return;
        }
        if (!confirmCashDisabledPreflight()) {
            broadcast(EVENT_FAILED, "恢复付款码订单时现金入口未确认关闭，请联系工作人员",
                    requestNo, getPurchaseStatus(), getPaymentStatus(), "");
            return;
        }
        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverQr(requestNo);
        if (recovered.success && recovered.snapshot != null) {
            resumeWithOccupancy(requestNo, stage, recovered.snapshot.sessionId);
        }
    }

    private void resumeWithOccupancy(String requestNo, String stage, String sessionId) {
        if (!requestNo.equals(getCurrentRequestNo())) {
            return;
        }
        if (!occupancy.prepareQrCashIsolation(sessionId)) {
            occupancy.markBlocked("AUTH_CODE_RECOVERY_CASH_ISOLATION_FAILED");
            broadcast(EVENT_FAILED, "恢复付款码订单时现金入口未确认关闭，请联系工作人员",
                    requestNo, getPurchaseStatus(), getPaymentStatus(), "");
            return;
        }
        if (STAGE_PREPARING.equals(stage)) {
            long ruleId = preferences().getLong(KEY_RULE_ID, 0L);
            long tierId = preferences().getLong(KEY_TIER_ID, 0L);
            int quantity = preferences().getInt(KEY_PURCHASE_QUANTITY, 0);
            boolean hasTier = tierId > 0L;
            boolean hasQuantity = quantity > 0;
            if (ruleId <= 0L || hasTier == hasQuantity) {
                occupancy.markBlocked("AUTH_CODE_RECOVERY_METADATA_INVALID");
                broadcast(EVENT_FAILED, "付款码订单恢复资料不完整，请联系工作人员",
                        requestNo, getPurchaseStatus(), getPaymentStatus(), "");
                return;
            }
            setStage(STAGE_CREATING);
            try {
                handleResult(requestNo, invokePurchaseMethod(
                        "createAuthCodePurchase",
                        requestNo,
                        ruleId,
                        hasTier ? tierId : null,
                        hasQuantity ? quantity : null
                ));
            } catch (Throwable error) {
                Log.w(TAG, "恢复时创建付款码订单结果未知，将继续查原订单");
                setStage(STAGE_PROCESSING);
                scheduleQuery(requestNo);
            }
            return;
        }
        try {
            handleResult(requestNo, invokePurchaseMethod("queryAuthCodePurchase", requestNo));
        } catch (Throwable error) {
            Log.w(TAG, "恢复付款码订单查询失败，将继续使用原请求号重试");
            scheduleQuery(requestNo);
        }
    }

    /** 仅等待付款码/明确 FAILED 时允许顾客主动取消。 */
    public synchronized boolean cancelCurrentPayment() {
        String requestNo = getCurrentRequestNo();
        if (requestNo.isEmpty() || !canCancelCurrentPayment()) {
            return false;
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot == null
                || !TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                || !requestNo.equals(snapshot.clientRequestNo)
                || !occupancy.markQrCancelling(requestNo)) {
            return false;
        }
        if (!setStage(STAGE_CANCELLING)) {
            occupancy.markBlocked("AUTH_CODE_CANCEL_STATE_PERSISTENCE_FAILED");
            return false;
        }
        cancelQuery();
        broadcast(EVENT_CANCELLING, "正在确认并关闭付款码订单",
                requestNo, getPurchaseStatus(), getPaymentStatus(), "");
        executor.execute(() -> {
            try {
                handleResult(requestNo,
                        invokePurchaseMethod("cancelAuthCodePurchase", requestNo));
            } catch (Throwable error) {
                // 取消结果未知必须查单，禁止本地判失败并开启下一笔。
                setStage(STAGE_PROCESSING);
                Log.w(TAG, "付款码订单取消结果未知，将继续查原订单");
                broadcast(EVENT_PROCESSING, "取消结果暂时未知，正在确认原付款码订单",
                        requestNo, getPurchaseStatus(), getPaymentStatus(), "");
                scheduleQuery(requestNo);
            }
        });
        return true;
    }

    public synchronized boolean canCancelCurrentPayment() {
        return isActive()
                && STAGE_WAITING_CODE.equals(getStage())
                && PaymentAuthCodePolicy.canSubmit(getPaymentStatus());
    }

    /** 处理物理完成或极端现金抢占；未知支付结果一律 fail-closed。 */
    private synchronized void onOccupancyChanged() {
        String requestNo = getCurrentRequestNo();
        if (requestNo.isEmpty()) {
            return;
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && requestNo.equals(snapshot.clientRequestNo)) {
            return;
        }
        if (snapshot == null) {
            cancelQuery();
            clearState();
            return;
        }

        cancelQuery();
        String stage = getStage();
        if (STAGE_PREPARING.equals(stage)
                || STAGE_CREATING.equals(stage)
                || STAGE_WAITING_CODE.equals(stage)) {
            executor.execute(() -> cancelDisplacedOrder(requestNo));
            return;
        }
        if (STAGE_SUBMITTING.equals(stage)
                || STAGE_PROCESSING.equals(stage)
                || STAGE_PAID.equals(stage)) {
            occupancy.markBlocked("AUTH_CODE_OWNERSHIP_LOST_DURING_PAYMENT");
        }
    }

    private void cancelDisplacedOrder(String requestNo) {
        try {
            DeviceAppNativePurchaseResult result =
                    invokePurchaseMethod("cancelAuthCodePurchase", requestNo);
            String status = TransactionOccupancyPolicy.normalize(
                    result == null ? "" : result.getPurchaseStatus()
            );
            if (TransactionOccupancyPolicy.isCancellationSuccess(status)) {
                clearState();
            } else {
                occupancy.markBlocked("AUTH_CODE_DISPLACED_CANCEL_" + safe(status));
            }
        } catch (Throwable error) {
            occupancy.markBlocked("AUTH_CODE_DISPLACED_CANCEL_UNKNOWN");
        }
    }

    private synchronized void scheduleQuery(String requestNo) {
        if (!requestNo.equals(getCurrentRequestNo())) {
            return;
        }
        if (queryDeadline <= 0L) {
            queryDeadline = System.currentTimeMillis() + MAX_QUERY_DURATION_MS;
        }
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (AuthCodePaymentManager.this) {
                queryTask = null;
            }
            if (!requestNo.equals(getCurrentRequestNo())) {
                return;
            }
            if (System.currentTimeMillis() >= queryDeadline) {
                setStage(STAGE_BLOCKED);
                occupancy.markBlocked("AUTH_CODE_QUERY_TIMEOUT");
                broadcast(EVENT_FAILED, "付款状态长时间无法确认，请联系工作人员",
                        requestNo, getPurchaseStatus(), getPaymentStatus(), "");
                return;
            }
            try {
                handleResult(requestNo,
                        invokePurchaseMethod("queryAuthCodePurchase", requestNo));
            } catch (Throwable error) {
                Log.w(TAG, "付款码订单查询失败，将继续使用原请求号重试");
                broadcast(EVENT_PROCESSING, "网络波动，正在继续确认原付款码订单",
                        requestNo, getPurchaseStatus(), getPaymentStatus(), "");
                scheduleQuery(requestNo);
            }
        }, QUERY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelQuery() {
        if (queryTask != null) {
            queryTask.cancel(false);
            queryTask = null;
        }
    }

    private void failPreparation(String requestNo, String message) {
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)
                && requestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "auth-code preparation failed", true);
        } else if (occupancy.isIdle()) {
            occupancy.restoreCashAcceptanceIfSafe();
        }
        clearState();
        broadcast(EVENT_FAILED, message, requestNo, "", "", "");
    }

    /** 占用前关闭现金并等待准确 mask/version 回报，避免线上支付与已接收现金并发。 */
    private boolean confirmCashDisabledPreflight() {
        synchronized (CASH_PREFLIGHT_LOCK) {
            DeviceCommandStore store = new DeviceCommandStore(context);
            int configVersion = Math.max(1, store.getCashConfigVersion());
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] matched = new boolean[]{false};
            HandlerThread receiverThread = new HandlerThread("gouzhu-auth-cash-preflight");
            receiverThread.start();
            Handler handler = new Handler(receiverThread.getLooper());
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())
                            || intent.getIntExtra("code2", -1) != EVT_CASH_ACCEPTANCE_STATUS) {
                        return;
                    }
                    long packed = intent.getLongExtra("data", 0L);
                    int mask = (int) ((packed >>> 24) & 0xFF);
                    int version = (int) (packed & 0x00FFFFFFL);
                    if (mask == 0 && version == configVersion) {
                        matched[0] = true;
                        latch.countDown();
                    }
                }
            };

            boolean registered = false;
            try {
                context.registerReceiver(
                        receiver,
                        new IntentFilter(AppConfig.ACTION_BOARD_EVENT),
                        null,
                        handler,
                        Context.RECEIVER_NOT_EXPORTED
                );
                registered = true;
                if (!SerialManager.get(context).sendCommand(
                        CMD_CASH_APPLY_V22,
                        configVersion & 0x00FFFFFFL,
                        true
                )) {
                    return false;
                }
                if (!latch.await(CASH_DISABLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        || !matched[0]) {
                    return false;
                }
                // 关闭前已进入硬件流程的现金事实先收敛；出现现金占用就不创建支付订单。
                Thread.sleep(CASH_DISABLE_STABILIZE_MS);
                return occupancy.isIdle();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable error) {
                return false;
            } finally {
                if (registered) {
                    try {
                        context.unregisterReceiver(receiver);
                    } catch (Throwable ignored) {
                    }
                }
                receiverThread.quitSafely();
            }
        }
    }

    /**
     * AUTH_CODE API 由服务端交付 SDK 提供。这里反射仅隔离新增 API 的编译期耦合，
     * 不回退到自拼 HTTP/签名，也不会打印参数或完整付款码。
     */
    private DeviceAppNativePurchaseResult invokePurchaseMethod(
            String methodName,
            Object... arguments
    ) {
        try {
            Object client = sdkManager.newAppClient();
            Method target = null;
            for (Method method : client.getClass().getMethods()) {
                if (methodName.equals(method.getName())
                        && method.getParameterTypes().length == arguments.length) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException(methodName);
            }
            Object result = target.invoke(client, arguments);
            if (!(result instanceof DeviceAppNativePurchaseResult)) {
                throw new IllegalStateException(methodName + " 返回类型无效");
            }
            return (DeviceAppNativePurchaseResult) result;
        } catch (Throwable error) {
            Throwable cause = error.getCause();
            throw new IllegalStateException(
                    "设备SDK付款码接口执行失败：" + methodName,
                    cause == null ? error : cause
            );
        }
    }

    public boolean isActive() {
        return !getCurrentRequestNo().isEmpty();
    }

    public String getCurrentRequestNo() {
        return safe(preferences().getString(KEY_REQUEST_NO, ""));
    }

    public String getPurchaseStatus() {
        return TransactionOccupancyPolicy.normalize(
                preferences().getString(KEY_PURCHASE_STATUS, "")
        );
    }

    public String getPaymentStatus() {
        return PaymentAuthCodePolicy.normalize(
                preferences().getString(KEY_PAYMENT_STATUS, "")
        );
    }

    public String getDisplayMessage() {
        String stage = getStage();
        String paymentStatus = getPaymentStatus();
        if (STAGE_PREPARING.equals(stage) || STAGE_CREATING.equals(stage)) {
            return "正在准备付款码支付，请稍候";
        }
        if (STAGE_WAITING_CODE.equals(stage)) {
            return "FAILED".equals(paymentStatus)
                    ? "本次付款未成功，请刷新付款码后重新出示"
                    : "请出示微信或支付宝付款码";
        }
        if (STAGE_SUBMITTING.equals(stage) || STAGE_PROCESSING.equals(stage)) {
            return "付款结果正在确认，请勿重复出示付款码";
        }
        if (STAGE_PAID.equals(stage)) {
            return "支付成功，等待平台出珠";
        }
        if (STAGE_CANCELLING.equals(stage)) {
            return "正在确认并关闭付款码订单";
        }
        return "付款码交易异常，请联系工作人员";
    }

    private boolean setStage(String stage) {
        return preferences().edit().putString(KEY_STAGE, safe(stage)).commit();
    }

    private String getStage() {
        return safe(preferences().getString(KEY_STAGE, ""));
    }

    private void clearState() {
        preferences().edit().clear().commit();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void broadcast(
            String event,
            String message,
            String requestNo,
            String purchaseStatus,
            String paymentStatus,
            String channel
    ) {
        Intent intent = new Intent(ACTION_AUTH_CODE_PAYMENT_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_EVENT, safe(event));
        intent.putExtra(EXTRA_MESSAGE, safe(message));
        intent.putExtra(EXTRA_REQUEST_NO, safe(requestNo));
        intent.putExtra(EXTRA_PURCHASE_STATUS, safe(purchaseStatus));
        intent.putExtra(EXTRA_PAYMENT_STATUS, safe(paymentStatus));
        intent.putExtra(EXTRA_CHANNEL, safe(channel));
        context.sendBroadcast(intent);
    }

    private static String readString(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String newRequestNo() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "auth-" + System.currentTimeMillis() + "-" + uuid.substring(0, 12);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
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
}
