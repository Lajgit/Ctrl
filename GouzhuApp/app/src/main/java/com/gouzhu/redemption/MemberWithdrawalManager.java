package com.gouzhu.redemption;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceApiException;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppMemberWithdrawalResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会员取珠扫码入口。
 *
 * <p>只有用户先点击“会员取珠”后反扫内容才进入会员接口。HTTP 只建立/查询会员取珠业务，
 * 真实出珠仍只执行平台合法 MQTT dispense_marbles，扫码原文不保存、不打印。</p>
 */
public final class MemberWithdrawalManager {

    public static final String ACTION_CHANGED = "com.gouzhu.action.MEMBER_WITHDRAWAL_CHANGED";
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

    private static final String TAG = "GouzhuMemberWithdraw";
    private static final int MAX_CODE_LENGTH = 4096;

    private static volatile MemberWithdrawalManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final RedemptionSessionStore store;
    private final DeviceCommandStore commandStore;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> queryTask;

    private MemberWithdrawalManager(Context context) {
        this.context = context.getApplicationContext();
        sdkManager = DeviceSdkManager.get(this.context);
        occupancy = TransactionOccupancyManager.get(this.context);
        store = new RedemptionSessionStore(this.context);
        commandStore = new DeviceCommandStore(this.context);
    }

    public static MemberWithdrawalManager get(Context context) {
        if (instance == null) {
            synchronized (MemberWithdrawalManager.class) {
                if (instance == null) {
                    instance = new MemberWithdrawalManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized boolean beginScan() {
        RedemptionSessionStore.MemberSession existing = store.loadMember();
        if (existing != null && !existing.clientRequestNo.isEmpty() && !existing.terminal) {
            broadcast(existing);
            return true;
        }
        DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
        RedemptionCapabilityResolver.FeatureGate gate =
                RedemptionCapabilityResolver.memberWithdrawal(bootstrap);
        if (!gate.visible || !gate.available) {
            broadcastMessage(firstNonBlank(gate.unavailableReason, "会员取珠当前不可用"), STATE_FAILED, "");
            return false;
        }
        DeviceAppRedemptionRouting routing = bootstrap == null ? null : bootstrap.getRedemptionRouting();
        if (routing == null || routing.getMemberWithdrawal() == null) {
            broadcastMessage("会员取珠扫码路由尚未加载", STATE_FAILED, "");
            return false;
        }
        if (!occupancy.canStartNewTransaction()) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", STATE_FAILED, "");
            return false;
        }

        String requestNo = newRequestNo();
        TransactionOccupancyManager.AcquireResult acquired = occupancy.tryAcquireRedemption(
                TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL,
                requestNo
        );
        if (!acquired.success || acquired.snapshot == null) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", STATE_FAILED, "");
            return false;
        }

        RedemptionSessionStore.MemberSession session = new RedemptionSessionStore.MemberSession();
        session.clientRequestNo = requestNo;
        session.uiState = STATE_STARTING;
        session.message = "正在准备会员取珠";
        if (!store.saveMember(session)) {
            occupancy.release(acquired.snapshot.sessionId, "member state save failed", true);
            return false;
        }
        broadcast(session);
        executor.execute(() -> prepareScannerSession(acquired.snapshot.sessionId, requestNo));
        return true;
    }

    private void prepareScannerSession(String sessionId, String requestNo) {
        boolean ready = occupancy.prepareRedemptionCashIsolation(
                sessionId,
                TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL
        );
        synchronized (this) {
            RedemptionSessionStore.MemberSession session = store.loadMember();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            if (!ready) {
                session.uiState = STATE_FAILED;
                session.message = "现金入口未确认关闭，会员取珠未启动";
                store.saveMember(session);
                occupancy.release(sessionId, "member cash isolation failed", true);
                broadcast(session);
                return;
            }
            session.uiState = STATE_SCANNING;
            session.message = "请出示会员取珠二维码";
            store.saveMember(session);
            broadcast(session);
        }
    }

    public boolean isWaitingForScan() {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        return session != null
                && STATE_SCANNING.equals(session.uiState)
                && occupancy.isRedemptionOwned(
                TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL,
                session.clientRequestNo
        );
    }

    public synchronized boolean handleScannerInput(String rawCode) {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null || !STATE_SCANNING.equals(session.uiState)) {
            return false;
        }
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty() || code.length() > MAX_CODE_LENGTH) {
            session.message = "会员取珠二维码格式无效，请重新扫码";
            store.saveMember(session);
            broadcast(session);
            return true;
        }
        session.uiState = STATE_SUBMITTING;
        session.submittedAt = System.currentTimeMillis();
        session.message = "会员取珠码已识别，正在确认资格";
        if (!store.saveMember(session)) {
            occupancy.markBlocked("MEMBER_WITHDRAW_STATE_SAVE_FAILED");
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
            RedemptionSessionStore.MemberSession session = store.loadMember();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
            DeviceAppRedemptionRouting routing = RedemptionCapabilityResolver.requireRouting(bootstrap);
            raw = new String(sensitiveCode);
            DeviceAppMemberWithdrawalResult result = sdkManager.createMemberWithdrawalFromRoutedCode(
                    requestNo,
                    routing,
                    raw
            );
            applyResult(requestNo, result);
        } catch (Throwable error) {
            DeviceApiException apiError = asApiError(error);
            synchronized (this) {
                RedemptionSessionStore.MemberSession session = store.loadMember();
                if (session == null || !requestNo.equals(session.clientRequestNo)) {
                    return;
                }
                if (apiError != null && apiError.getApiCode() != null) {
                    Log.w(TAG, "会员取珠接口失败：requestNo=" + requestNo
                            + "，apiCode=" + apiError.getApiCode()
                            + "，traceId=" + safe(apiError.getTraceId()));
                    session.uiState = STATE_FAILED;
                    session.terminal = true;
                    session.message = firstNonBlank(apiError.getMessage(), "会员取珠失败");
                    store.saveMember(session);
                    broadcast(session);
                    releaseIfSettled(session);
                } else {
                    // 创建结果未知时禁止再次提交扫码内容，改为查询同一请求号。
                    session.uiState = STATE_WAITING_FINAL;
                    session.message = "取珠结果正在确认，请勿重复扫码";
                    store.saveMember(session);
                    broadcast(session);
                    scheduleQuery(requestNo, 2_000L);
                }
            }
        } finally {
            clearSensitive(sensitiveCode);
            raw = null;
        }
    }

    private synchronized void applyResult(String requestNo, DeviceAppMemberWithdrawalResult result) {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null || !requestNo.equals(session.clientRequestNo) || result == null) {
            return;
        }
        if (!requestNo.equals(safe(result.getClientRequestNo()))) {
            occupancy.markBlocked("MEMBER_WITHDRAW_RESPONSE_MISMATCH");
            session.uiState = STATE_FAILED;
            session.message = "会员取珠响应与当前请求不一致";
            store.saveMember(session);
            broadcast(session);
            return;
        }
        session.operationNo = safe(result.getOperationNo());
        session.withdrawalStatus = safe(result.getWithdrawalStatus());
        session.requestedQuantity = result.getRequestedQuantity() == null
                ? session.requestedQuantity : Math.max(0, result.getRequestedQuantity());
        session.dispensedQuantity = result.getDispensedQuantity() == null
                ? session.dispensedQuantity : Math.max(0, result.getDispensedQuantity());
        session.operationStatus = result.getOperationStatus() == null
                ? session.operationStatus : result.getOperationStatus();
        session.terminal = result.isTerminal();
        session.lastStatusCheckedAt = System.currentTimeMillis();
        session.message = firstNonBlank(result.getMessage(), result.getWithdrawalStatus(), "会员取珠处理中");

        if (session.terminal) {
            session.uiState = session.requestedQuantity > 0
                    && session.dispensedQuantity >= session.requestedQuantity
                    ? STATE_SUCCEEDED : STATE_FAILED;
        } else {
            session.uiState = STATE_WAITING_DISPENSE;
            occupancy.transitionRedemption(
                    session.clientRequestNo,
                    TransactionOccupancyManager.PHASE_WAITING_DISPENSE
            );
        }
        store.saveMember(session);
        broadcast(session);
        if (session.terminal) {
            cancelQuery();
            releaseIfSettled(session);
        } else {
            scheduleQuery(requestNo, 2_000L);
        }
    }

    private void query(String requestNo) {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null || !requestNo.equals(session.clientRequestNo) || session.terminal) {
            if (session != null) {
                releaseIfSettled(session);
            }
            return;
        }
        try {
            applyResult(requestNo, sdkManager.queryMemberWithdrawal(requestNo));
        } catch (Throwable error) {
            Log.w(TAG, "会员取珠状态查询失败，将继续查询原请求：requestNo=" + requestNo
                    + "，error=" + error.getClass().getSimpleName());
            scheduleQuery(requestNo, 5_000L);
        }
    }

    private synchronized void scheduleQuery(String requestNo, long delayMs) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (MemberWithdrawalManager.this) {
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
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null || session.clientRequestNo.isEmpty()) {
            return;
        }
        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverRedemption(
                TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL,
                session.clientRequestNo
        );
        if (!recovered.success) {
            return;
        }
        if (session.terminal) {
            releaseIfSettled(session);
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
            // 原扫码内容不落库；一旦曾经提交，重启后只查询原请求，禁止再次提交。
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            broadcast(session);
        }
    }

    public synchronized void onPhysicalDispenseFinished() {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null) {
            return;
        }
        if (!session.terminal) {
            session.uiState = STATE_WAITING_FINAL;
            session.message = "出珠已完成，正在确认会员取珠最终状态";
            store.saveMember(session);
            broadcast(session);
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            releaseIfSettled(session);
        }
    }

    public synchronized boolean abandonBeforeSubmit() {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null) {
            return true;
        }
        if (session.submittedAt > 0L || STATE_SUBMITTING.equals(session.uiState)) {
            return false;
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "member withdrawal abandoned", true);
        }
        store.clearMember();
        return true;
    }

    public synchronized void acknowledgeTerminal() {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null || !session.terminal) {
            return;
        }
        releaseIfSettled(session);
        if (!commandStore.hasActivePhysicalOrder()) {
            store.clearMember();
        }
    }

    public synchronized UiSnapshot snapshot() {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session == null) {
            return null;
        }
        UiSnapshot result = new UiSnapshot();
        result.clientRequestNo = session.clientRequestNo;
        result.withdrawalStatus = session.withdrawalStatus;
        result.requestedQuantity = session.requestedQuantity;
        result.dispensedQuantity = session.dispensedQuantity;
        result.uiState = session.uiState;
        result.message = session.message;
        result.terminal = session.terminal;
        result.submittedAt = session.submittedAt;
        return result;
    }

    public synchronized void onOccupancyReleased(String requestNo) {
        RedemptionSessionStore.MemberSession session = store.loadMember();
        if (session != null && requestNo.equals(session.clientRequestNo)) {
            broadcast(session);
        }
    }

    private void releaseIfSettled(RedemptionSessionStore.MemberSession session) {
        if (session == null || !session.terminal) {
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
                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "member withdrawal terminal", true);
        }
    }

    private synchronized void scheduleReleaseCheck(String requestNo) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (MemberWithdrawalManager.this) {
                queryTask = null;
            }
            RedemptionSessionStore.MemberSession session = store.loadMember();
            if (session != null && requestNo.equals(session.clientRequestNo)) {
                releaseIfSettled(session);
            }
        }, 2_000L, TimeUnit.MILLISECONDS);
    }

    private void broadcast(RedemptionSessionStore.MemberSession session) {
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
        return "APPMEMBER_" + System.currentTimeMillis() + "_"
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
        public String withdrawalStatus = "";
        public int requestedQuantity;
        public int dispensedQuantity;
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long submittedAt;
    }
}
