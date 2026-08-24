package com.gouzhu.redemption;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceApiException;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppInternalRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 官方小程序套餐券核销。
 *
 * <p>用户必须先点击“券码核销”进入显式扫码态。扫码原文只在本次 SDK 调用期间存在，
 * 不落库、不打印。HTTP 只创建/查询核销业务，真实出珠只执行平台签名后的 MQTT
 * dispense_marbles；一旦请求提交，超时或进程重建都只查询同一个 clientRequestNo。</p>
 */
public final class InternalRedemptionManager {

    public static final String ACTION_CHANGED = "com.gouzhu.action.INTERNAL_REDEMPTION_CHANGED";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_REQUEST_NO = "requestNo";

    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_SCANNING = "SCANNING";
    public static final String STATE_SUBMITTING = "SUBMITTING";
    public static final String STATE_WAITING_DISPENSE = "WAITING_DISPENSE";
    public static final String STATE_WAITING_FINAL = "WAITING_FINAL";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_MANUAL_REVIEW = "MANUAL_REVIEW";

    private static final String TAG = "GouzhuInternalRedeem";
    private static final int MAX_CODE_LENGTH = 4096;

    private static volatile InternalRedemptionManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final InternalRedemptionStore store;
    private final DeviceCommandStore commandStore;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> queryTask;

    private InternalRedemptionManager(Context context) {
        this.context = context.getApplicationContext();
        sdkManager = DeviceSdkManager.get(this.context);
        occupancy = TransactionOccupancyManager.get(this.context);
        store = new InternalRedemptionStore(this.context);
        commandStore = new DeviceCommandStore(this.context);
    }

    public static InternalRedemptionManager get(Context context) {
        if (instance == null) {
            synchronized (InternalRedemptionManager.class) {
                if (instance == null) {
                    instance = new InternalRedemptionManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized boolean beginScan() {
        InternalRedemptionStore.Session existing = store.load();
        if (existing != null && !existing.clientRequestNo.isEmpty() && !existing.terminal) {
            broadcast(existing);
            return true;
        }

        DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
        RedemptionCapabilityResolver.FeatureGate gate =
                RedemptionCapabilityResolver.internalRedemption(bootstrap);
        if (!gate.visible || !gate.available) {
            broadcastMessage(
                    firstNonBlank(gate.unavailableReason, "券码核销当前不可用"),
                    STATE_FAILED,
                    ""
            );
            return false;
        }
        DeviceAppRedemptionRouting routing =
                bootstrap == null ? null : bootstrap.getRedemptionRouting();
        if (routing == null || routing.getInternalRedemption() == null) {
            broadcastMessage("券码核销扫码路由尚未加载", STATE_FAILED, "");
            return false;
        }
        if (!occupancy.canStartNewTransaction()) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", STATE_FAILED, "");
            return false;
        }

        String requestNo = newRequestNo();
        TransactionOccupancyManager.AcquireResult acquired = occupancy.tryAcquireRedemption(
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION,
                requestNo
        );
        if (!acquired.success || acquired.snapshot == null) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", STATE_FAILED, "");
            return false;
        }

        InternalRedemptionStore.Session session = new InternalRedemptionStore.Session();
        session.clientRequestNo = requestNo;
        session.uiState = STATE_STARTING;
        session.message = "正在准备券码核销";
        if (!store.save(session)) {
            occupancy.release(acquired.snapshot.sessionId, "internal redemption state save failed", true);
            return false;
        }
        broadcast(session);
        executor.execute(() -> prepareScannerSession(acquired.snapshot.sessionId, requestNo));
        return true;
    }

    private void prepareScannerSession(String sessionId, String requestNo) {
        boolean ready = occupancy.prepareRedemptionCashIsolation(
                sessionId,
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION
        );
        synchronized (this) {
            InternalRedemptionStore.Session session = store.load();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            if (!ready) {
                session.uiState = STATE_FAILED;
                session.terminal = true;
                session.message = "现金入口未确认关闭，券码核销未启动";
                store.save(session);
                occupancy.release(sessionId, "internal redemption cash isolation failed", true);
                broadcast(session);
                return;
            }
            session.uiState = STATE_SCANNING;
            session.message = "请扫描官方小程序套餐核销二维码";
            store.save(session);
            broadcast(session);
        }
    }

    public boolean isWaitingForScan() {
        InternalRedemptionStore.Session session = store.load();
        return session != null
                && STATE_SCANNING.equals(session.uiState)
                && occupancy.isRedemptionOwned(
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION,
                session.clientRequestNo
        );
    }

    public synchronized boolean handleScannerInput(String rawCode) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !STATE_SCANNING.equals(session.uiState)) {
            return false;
        }
        String code = rawCode == null ? "" : rawCode;
        if (code.trim().isEmpty() || code.length() > MAX_CODE_LENGTH) {
            session.message = "官方套餐核销二维码格式无效，请重新扫码";
            store.save(session);
            broadcast(session);
            return true;
        }

        session.uiState = STATE_SUBMITTING;
        session.submittedAt = System.currentTimeMillis();
        session.message = "券码已识别，正在确认套餐核销资格";
        if (!store.save(session)) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_STATE_SAVE_FAILED");
            return true;
        }

        /*
         * submittedAt 持久化后才开放 MQTT 出珠资格。这样即使 MQTT 比 HTTP 响应先到，
         * 也能接住合法物理授权；扫码/准备阶段仍然绝不允许出珠。
         */
        if (!occupancy.markRedemptionWaitingDispense(session.clientRequestNo)) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_OCCUPANCY_FAILED");
            session.uiState = STATE_FAILED;
            session.message = "券码核销交易状态切换失败，请联系工作人员";
            store.save(session);
            broadcast(session);
            return true;
        }

        broadcast(session);
        char[] sensitiveCode = code.toCharArray();
        executor.execute(() -> submitOnce(session.clientRequestNo, sensitiveCode));
        return true;
    }

    private void submitOnce(String requestNo, char[] sensitiveCode) {
        String raw = null;
        try {
            InternalRedemptionStore.Session session = store.load();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
            DeviceAppRedemptionRouting routing =
                    RedemptionCapabilityResolver.requireRouting(bootstrap);
            if (routing.getInternalRedemption() == null) {
                throw new IllegalStateException("券码核销路由已失效");
            }
            raw = new String(sensitiveCode);
            DeviceAppInternalRedemptionResult result =
                    sdkManager.createInternalRedemptionFromRoutedCode(
                            requestNo,
                            routing,
                            raw
                    );
            applyResult(requestNo, result);
        } catch (Throwable error) {
            DeviceApiException apiError = asApiError(error);
            synchronized (this) {
                InternalRedemptionStore.Session session = store.load();
                if (session == null || !requestNo.equals(session.clientRequestNo)) {
                    return;
                }
                if (apiError != null && apiError.getApiCode() != null) {
                    Log.w(TAG, "官方券码核销接口失败：requestNo=" + requestNo
                            + "，apiCode=" + apiError.getApiCode()
                            + "，traceId=" + safe(apiError.getTraceId()));
                    session.uiState = STATE_FAILED;
                    session.terminal = true;
                    session.message = firstNonBlank(apiError.getMessage(), "券码核销失败");
                    store.save(session);
                    broadcast(session);
                    releaseIfSettled(session);
                } else {
                    // 创建结果未知时不允许重新提交原券码，只查询相同 requestNo。
                    session.uiState = STATE_WAITING_FINAL;
                    session.message = "核销结果正在确认，请勿重复扫码";
                    store.save(session);
                    broadcast(session);
                    scheduleQuery(requestNo, 2_000L);
                }
            }
        } finally {
            clearSensitive(sensitiveCode);
            raw = null;
        }
    }

    private synchronized void applyResult(
            String requestNo,
            DeviceAppInternalRedemptionResult result
    ) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !requestNo.equals(session.clientRequestNo) || result == null) {
            return;
        }
        if (!requestNo.equals(safe(result.getClientRequestNo()))) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_RESPONSE_MISMATCH");
            session.uiState = STATE_MANUAL_REVIEW;
            session.message = "券码核销响应与当前请求不一致，请联系工作人员";
            store.save(session);
            broadcast(session);
            return;
        }

        session.operationId = result.getOperationId() == null
                ? session.operationId : result.getOperationId();
        session.operationNo = safe(result.getOperationNo());
        session.requestedQuantity = result.getRequestedQuantity() == null
                ? session.requestedQuantity : Math.max(0, result.getRequestedQuantity());
        session.dispensedQuantity = result.getDispensedQuantity() == null
                ? session.dispensedQuantity : Math.max(0, result.getDispensedQuantity());
        session.operationStatus = result.getOperationStatus() == null
                ? session.operationStatus : result.getOperationStatus();
        session.redemptionStatus = safe(result.getRedemptionStatus());
        session.expireTime = safe(result.getExpireTime());
        session.terminal = result.isTerminal();
        session.lastStatusCheckedAt = System.currentTimeMillis();
        session.message = firstNonBlank(
                result.getMessage(),
                result.getRedemptionStatus(),
                "券码核销处理中"
        );

        String outcome = InternalRedemptionPolicy.terminalOutcome(
                session.terminal,
                session.requestedQuantity,
                session.dispensedQuantity
        );
        if (InternalRedemptionPolicy.OUTCOME_SUCCEEDED.equals(outcome)) {
            session.uiState = STATE_SUCCEEDED;
        } else if (InternalRedemptionPolicy.OUTCOME_MANUAL_REVIEW.equals(outcome)) {
            session.uiState = STATE_MANUAL_REVIEW;
            session.message = "券码已核销但出珠数量未完整匹配，请联系工作人员";
            store.save(session);
            broadcast(session);
            occupancy.markBlocked("INTERNAL_REDEMPTION_PARTIAL_DELIVERY");
            cancelQuery();
            return;
        } else if (InternalRedemptionPolicy.OUTCOME_FAILED.equals(outcome)) {
            session.uiState = STATE_FAILED;
        } else {
            session.uiState = STATE_WAITING_DISPENSE;
            // HTTP 查询晚于 MQTT 时只确认资格，绝不把 DISPENSING/FINISHING 回退。
            occupancy.markRedemptionWaitingDispense(session.clientRequestNo);
        }

        store.save(session);
        broadcast(session);
        if (session.terminal) {
            cancelQuery();
            releaseIfSettled(session);
        } else {
            scheduleQuery(requestNo, 2_000L);
        }
    }

    private void query(String requestNo) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !requestNo.equals(session.clientRequestNo) || session.terminal) {
            if (session != null) {
                releaseIfSettled(session);
            }
            return;
        }
        try {
            applyResult(requestNo, sdkManager.queryInternalRedemption(requestNo));
        } catch (Throwable error) {
            Log.w(TAG, "券码核销状态查询失败，将继续查询原请求：requestNo=" + requestNo
                    + "，error=" + error.getClass().getSimpleName());
            scheduleQuery(requestNo, 5_000L);
        }
    }

    private synchronized void scheduleQuery(String requestNo, long delayMs) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (InternalRedemptionManager.this) {
                queryTask = null;
            }
            query(requestNo);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelQuery() {
        if (queryTask != null) {
            queryTask.cancel(false);
            queryTask = null;
        }
    }

    public synchronized void resumePending() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || session.clientRequestNo.isEmpty()) {
            return;
        }

        // 终态不重新获得交易锁；页面只展示结果，人工异常由已有 BLOCKED 会话处理。
        if (session.terminal) {
            releaseIfSettled(session);
            broadcast(session);
            return;
        }

        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverRedemption(
                TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION,
                session.clientRequestNo
        );
        if (!recovered.success || recovered.snapshot == null) {
            return;
        }

        if (STATE_STARTING.equals(session.uiState)) {
            executor.execute(() -> prepareScannerSession(
                    recovered.snapshot.sessionId,
                    session.clientRequestNo
            ));
        } else if (STATE_SUBMITTING.equals(session.uiState)
                || STATE_WAITING_DISPENSE.equals(session.uiState)
                || STATE_WAITING_FINAL.equals(session.uiState)) {
            // 券码原文没有落库；一旦提交，重启后只恢复出珠资格并查询原 requestNo。
            occupancy.markRedemptionWaitingDispense(session.clientRequestNo);
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            broadcast(session);
        }
    }

    public synchronized void onPhysicalDispenseFinished() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null) {
            return;
        }
        if (!session.terminal) {
            session.uiState = STATE_WAITING_FINAL;
            session.message = "出珠已完成，正在确认券码核销最终状态";
            store.save(session);
            broadcast(session);
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            releaseIfSettled(session);
        }
    }

    public synchronized boolean abandonBeforeSubmit() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null) {
            return true;
        }
        if (session.submittedAt > 0L || STATE_SUBMITTING.equals(session.uiState)) {
            return false;
        }
        cancelQuery();
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "internal redemption abandoned", true);
        }
        store.clear();
        return true;
    }

    public synchronized void acknowledgeTerminal() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !session.terminal) {
            return;
        }
        if (STATE_MANUAL_REVIEW.equals(session.uiState)) {
            return;
        }
        releaseIfSettled(session);
        if (!commandStore.hasActivePhysicalOrder()) {
            store.clear();
        }
    }

    public synchronized UiSnapshot snapshot() {
        InternalRedemptionStore.Session session = store.load();
        if (session == null) {
            return null;
        }
        UiSnapshot result = new UiSnapshot();
        result.clientRequestNo = session.clientRequestNo;
        result.operationNo = session.operationNo;
        result.redemptionStatus = session.redemptionStatus;
        result.requestedQuantity = session.requestedQuantity;
        result.dispensedQuantity = session.dispensedQuantity;
        result.uiState = session.uiState;
        result.message = session.message;
        result.terminal = session.terminal;
        result.submittedAt = session.submittedAt;
        return result;
    }

    public synchronized void onOccupancyReleased(String requestNo) {
        InternalRedemptionStore.Session session = store.load();
        if (session == null || !requestNo.equals(session.clientRequestNo)) {
            return;
        }
        if (session.terminal && STATE_MANUAL_REVIEW.equals(session.uiState)) {
            // 人工处理流程已经显式释放全局占用后，清除本地异常快照，避免再次进入页面又重新阻塞。
            store.clear();
            return;
        }
        broadcast(session);
    }

    private void releaseIfSettled(InternalRedemptionStore.Session session) {
        if (session == null || !session.terminal) {
            return;
        }
        if (STATE_MANUAL_REVIEW.equals(session.uiState)) {
            occupancy.markBlocked("INTERNAL_REDEMPTION_TERMINAL_MANUAL_REVIEW");
            return;
        }
        if (commandStore.hasActivePhysicalOrder()) {
            occupancy.transitionRedemption(
                    session.clientRequestNo,
                    TransactionOccupancyManager.PHASE_FINISHING
            );
            scheduleReleaseCheck(session.clientRequestNo);
            return;
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_INTERNAL_REDEMPTION.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "internal redemption terminal", true);
        }
    }

    private synchronized void scheduleReleaseCheck(String requestNo) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (InternalRedemptionManager.this) {
                queryTask = null;
            }
            InternalRedemptionStore.Session session = store.load();
            if (session != null && requestNo.equals(session.clientRequestNo)) {
                releaseIfSettled(session);
            }
        }, 2_000L, TimeUnit.MILLISECONDS);
    }

    private void broadcast(InternalRedemptionStore.Session session) {
        broadcastMessage(session.message, session.uiState, session.clientRequestNo);
    }

    private void broadcastMessage(String message, String state, String requestNo) {
        Intent intent = new Intent(ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_MESSAGE, safe(message));
        intent.putExtra(EXTRA_STATE, safe(state));
        intent.putExtra(EXTRA_REQUEST_NO, safe(requestNo));
        context.sendBroadcast(intent);
    }

    private static DeviceApiException asApiError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof DeviceApiException) {
                return (DeviceApiException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static void clearSensitive(char[] value) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length; index++) {
            value[index] = '\0';
        }
    }

    private static String newRequestNo() {
        return "APPREDEEM_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    public static final class UiSnapshot {
        public String clientRequestNo = "";
        public String operationNo = "";
        public String redemptionStatus = "";
        public int requestedQuantity;
        public int dispensedQuantity;
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long submittedAt;
    }
}
