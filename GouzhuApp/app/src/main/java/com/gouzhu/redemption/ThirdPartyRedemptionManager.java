package com.gouzhu.redemption;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceApiException;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppRedemptionRouting;
import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyRedemptionPrepareResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyRedemptionResult;
import com.pinball.xiaoda.device.sdk.client.DeviceAppThirdPartyVoucherCandidate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 抖音/美团第三方团购核销状态机。
 *
 * <p>渠道必须由用户先选定；扫码原文只在 prepare 调用期间短暂存在内存，不写日志、不落库。
 * confirm 之前只做非消费式验券，confirm 之后结果未知时只按原 clientRequestNo 查询状态。
 * HTTP 成功绝不直接出珠，真实硬件动作只能来自平台合法 MQTT dispense_marbles。</p>
 */
public final class ThirdPartyRedemptionManager {

    public static final String ACTION_CHANGED = "com.gouzhu.action.THIRD_PARTY_REDEMPTION_CHANGED";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_REQUEST_NO = "requestNo";

    private static final String TAG = "GouzhuThirdParty";
    private static final int MAX_VOUCHER_LENGTH = 4096;
    private static final int MAX_PREPARE_NETWORK_RETRIES = 3;

    private static volatile ThirdPartyRedemptionManager instance;

    private final Context context;
    private final DeviceSdkManager sdkManager;
    private final TransactionOccupancyManager occupancy;
    private final RedemptionSessionStore store;
    private final DeviceCommandStore commandStore;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> queryTask;
    private int consecutiveQueryFailures;

    private ThirdPartyRedemptionManager(Context context) {
        this.context = context.getApplicationContext();
        sdkManager = DeviceSdkManager.get(this.context);
        occupancy = TransactionOccupancyManager.get(this.context);
        store = new RedemptionSessionStore(this.context);
        commandStore = new DeviceCommandStore(this.context);
    }

    public static ThirdPartyRedemptionManager get(Context context) {
        if (instance == null) {
            synchronized (ThirdPartyRedemptionManager.class) {
                if (instance == null) {
                    instance = new ThirdPartyRedemptionManager(context);
                }
            }
        }
        return instance;
    }

    public List<RedemptionCapabilityResolver.ChannelOption> getAvailableChannels() {
        DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
        RedemptionCapabilityResolver.FeatureGate gate =
                RedemptionCapabilityResolver.thirdPartyRedemption(bootstrap);
        if (!gate.visible || !gate.available) {
            return Collections.emptyList();
        }
        return RedemptionCapabilityResolver.thirdPartyChannels(bootstrap);
    }

    /** 选定渠道后先占用设备并确认现金入口关闭，成功后才进入扫码态。 */
    public synchronized boolean startChannel(String channelCode) {
        RedemptionSessionStore.ThirdPartySession existing = store.loadThirdParty();
        if (existing != null && !existing.clientRequestNo.isEmpty() && !existing.terminal) {
            broadcast(existing);
            return normalize(channelCode).equals(normalize(existing.channelCode));
        }

        DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
        RedemptionCapabilityResolver.FeatureGate gate =
                RedemptionCapabilityResolver.thirdPartyRedemption(bootstrap);
        if (!gate.visible || !gate.available) {
            broadcastMessage(
                    firstNonBlank(gate.unavailableReason, "团购核销当前不可用"),
                    ThirdPartyRedemptionPolicy.STATE_FAILED,
                    ""
            );
            return false;
        }
        RedemptionCapabilityResolver.ChannelOption channel =
                RedemptionCapabilityResolver.findChannel(bootstrap, channelCode);
        if (channel == null) {
            broadcastMessage("当前门店未开放该团购渠道", ThirdPartyRedemptionPolicy.STATE_FAILED, "");
            return false;
        }
        if (!occupancy.canStartNewTransaction()) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", ThirdPartyRedemptionPolicy.STATE_FAILED, "");
            return false;
        }

        String requestNo = newRequestNo();
        TransactionOccupancyManager.AcquireResult acquired = occupancy.tryAcquireRedemption(
                TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION,
                requestNo
        );
        if (!acquired.success || acquired.snapshot == null) {
            broadcastMessage("设备正在处理其他交易，请稍后再试", ThirdPartyRedemptionPolicy.STATE_FAILED, "");
            return false;
        }

        RedemptionSessionStore.ThirdPartySession session =
                new RedemptionSessionStore.ThirdPartySession();
        session.clientRequestNo = requestNo;
        session.channelCode = channel.code;
        session.channelName = channel.name;
        session.uiState = ThirdPartyRedemptionPolicy.STATE_STARTING;
        session.message = "正在准备" + channel.name + "团购核销";
        if (!store.saveThirdParty(session)) {
            occupancy.release(acquired.snapshot.sessionId, "third party state save failed", true);
            broadcastMessage("无法保存核销状态，请稍后重试", ThirdPartyRedemptionPolicy.STATE_FAILED, "");
            return false;
        }
        broadcast(session);

        String sessionId = acquired.snapshot.sessionId;
        executor.execute(() -> prepareScannerSession(sessionId, requestNo));
        return true;
    }

    private void prepareScannerSession(String sessionId, String requestNo) {
        boolean ready = occupancy.prepareRedemptionCashIsolation(
                sessionId,
                TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION
        );
        synchronized (this) {
            RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                return;
            }
            if (!ready) {
                session.uiState = ThirdPartyRedemptionPolicy.STATE_FAILED;
                session.message = "现金入口未确认关闭，团购核销未启动";
                store.saveThirdParty(session);
                occupancy.release(sessionId, "redemption cash isolation failed", true);
                broadcast(session);
                return;
            }
            session.uiState = ThirdPartyRedemptionPolicy.STATE_SCANNING;
            session.message = "请出示" + session.channelName + "团购券二维码";
            store.saveThirdParty(session);
            broadcast(session);
        }
    }

    public boolean isWaitingForScan() {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        return session != null
                && ThirdPartyRedemptionPolicy.STATE_SCANNING.equals(session.uiState)
                && occupancy.isRedemptionOwned(
                TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION,
                session.clientRequestNo
        );
    }

    /**
     * 反扫模块只在已经明确选定渠道且处于 SCANNING 时调用本方法。
     * 返回 true 表示该原始扫码内容已被第三方核销流程消费。
     */
    public synchronized boolean handleScannerInput(String rawCode) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null
                || !ThirdPartyRedemptionPolicy.STATE_SCANNING.equals(session.uiState)
                || !occupancy.isRedemptionOwned(
                TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION,
                session.clientRequestNo)) {
            return false;
        }
        String code = rawCode == null ? "" : rawCode;
        if (code.trim().isEmpty()
                || code.length() > MAX_VOUCHER_LENGTH
                || code.indexOf('|') >= 0) {
            session.message = "团购券二维码格式无效，请重新扫码";
            store.saveThirdParty(session);
            broadcast(session);
            return true;
        }

        session.uiState = ThirdPartyRedemptionPolicy.STATE_PREPARING;
        session.message = "正在验证" + session.channelName + "团购券，请稍候";
        if (!store.saveThirdParty(session)) {
            occupancy.markBlocked("THIRD_PARTY_PREPARE_STATE_SAVE_FAILED");
            return true;
        }
        broadcast(session);
        char[] sensitiveCode = code.toCharArray();
        executor.execute(() -> prepareOnce(session.clientRequestNo, sensitiveCode, 0));
        return true;
    }

    private void prepareOnce(String requestNo, char[] sensitiveCode, int retryCount) {
        String rawCode = null;
        try {
            RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
            if (session == null || !requestNo.equals(session.clientRequestNo)) {
                clearSensitive(sensitiveCode);
                return;
            }
            DeviceAppBootstrapResult bootstrap = sdkManager.getLastBootstrap();
            DeviceAppRedemptionRouting routing = RedemptionCapabilityResolver.requireRouting(bootstrap);
            if (RedemptionCapabilityResolver.findChannel(bootstrap, session.channelCode) == null) {
                failPrepare(requestNo, "当前门店已停止开放该团购渠道");
                clearSensitive(sensitiveCode);
                return;
            }
            rawCode = new String(sensitiveCode);
            DeviceAppThirdPartyRedemptionPrepareResult result =
                    sdkManager.prepareThirdPartyRedemptionForSelectedChannel(
                            requestNo,
                            routing,
                            session.channelCode,
                            rawCode
                    );
            applyPrepareResult(requestNo, result, voucherSnapshot(rawCode));
            clearSensitive(sensitiveCode);
        } catch (Throwable error) {
            DeviceApiException apiError = asApiError(error);
            if (apiError != null && apiError.getApiCode() != null) {
                String message = apiError.getApiCode() == 5014
                        ? firstNonBlank(apiError.getMessage(), "该团购券已使用或当前不可核销")
                        : firstNonBlank(apiError.getMessage(), "团购券验证失败");
                logApiFailure("prepare", requestNo, apiError);
                failPrepare(requestNo, message);
                clearSensitive(sensitiveCode);
                return;
            }
            if (retryCount < MAX_PREPARE_NETWORK_RETRIES) {
                long delayMs = retryCount == 0 ? 1_000L : (1L << retryCount) * 1_000L;
                Log.w(TAG, "prepare网络结果未知，复用原请求号重试：requestNo=" + requestNo
                        + "，retry=" + (retryCount + 1));
                executor.schedule(
                        () -> prepareOnce(requestNo, sensitiveCode, retryCount + 1),
                        delayMs,
                        TimeUnit.MILLISECONDS
                );
                return;
            }
            synchronized (this) {
                RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
                if (session != null && requestNo.equals(session.clientRequestNo)) {
                    // prepare 不消费券，进程内重试仍未知时回到扫码态并保留同一请求号。
                    session.uiState = ThirdPartyRedemptionPolicy.STATE_SCANNING;
                    session.message = "网络暂不可用，请重新扫描同一张团购券";
                    store.saveThirdParty(session);
                    broadcast(session);
                }
            }
            clearSensitive(sensitiveCode);
        } finally {
            rawCode = null;
        }
    }

    private synchronized void applyPrepareResult(
            String requestNo,
            DeviceAppThirdPartyRedemptionPrepareResult result,
            String voucherSnapshot
    ) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || !requestNo.equals(session.clientRequestNo) || result == null) {
            return;
        }
        if (!requestNo.equals(safe(result.getClientRequestNo()))) {
            block("THIRD_PARTY_PREPARE_REQUEST_MISMATCH", "验券响应与当前请求不一致");
            return;
        }
        if (!normalize(session.channelCode).equals(normalize(result.getChannelCode()))) {
            block("THIRD_PARTY_PREPARE_CHANNEL_MISMATCH", "验券响应渠道与所选渠道不一致");
            return;
        }
        long expireTime = result.getSessionExpireTime() == null ? 0L : result.getSessionExpireTime();
        List<DeviceAppThirdPartyVoucherCandidate> certificates = result.getCertificates();
        if (expireTime <= System.currentTimeMillis() / 1000L
                || certificates == null || certificates.isEmpty()) {
            failPrepare(requestNo, "未找到可确认的有效团购券，请重新扫码");
            return;
        }
        session.sessionExpireTime = expireTime;
        session.voucherSnapshot = safe(voucherSnapshot);
        session.candidatesJson = encodeCandidates(certificates);
        session.uiState = ThirdPartyRedemptionPolicy.STATE_CANDIDATE_CONFIRMING;
        session.message = "请核对团购券和出珠数量后确认核销";
        if (!store.saveThirdParty(session)) {
            block("THIRD_PARTY_CANDIDATES_SAVE_FAILED", "候选券状态无法可靠保存");
            return;
        }
        broadcast(session);
    }

    private synchronized void failPrepare(String requestNo, String message) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || !requestNo.equals(session.clientRequestNo)) {
            return;
        }
        session.uiState = ThirdPartyRedemptionPolicy.STATE_FAILED;
        session.message = safe(message);
        store.saveThirdParty(session);
        broadcast(session);
    }

    /** 用户明确确认某一候选券后才允许消费第三方券。 */
    public synchronized boolean confirmCandidate(String certificateId) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || session.terminal) {
            return false;
        }
        CandidateSnapshot candidate = findCandidate(session.candidatesJson, certificateId);
        if (candidate == null || !ThirdPartyRedemptionPolicy.canConfirm(
                session.uiState,
                candidate.redeemable,
                session.sessionExpireTime,
                System.currentTimeMillis(),
                session.confirmRequestedAt)) {
            if (session.sessionExpireTime > 0L
                    && session.sessionExpireTime <= System.currentTimeMillis() / 1000L) {
                session.uiState = ThirdPartyRedemptionPolicy.STATE_FAILED;
                session.message = "验券会话已过期，请返回重新选择渠道并扫码";
                store.saveThirdParty(session);
                broadcast(session);
            }
            return false;
        }

        session.selectedCertificateId = candidate.certificateId;
        session.confirmRequestedAt = System.currentTimeMillis();
        session.uiState = ThirdPartyRedemptionPolicy.STATE_CONFIRMING;
        session.message = "正在正式核销" + session.channelName + "团购券，请勿重复操作";
        if (!store.saveThirdParty(session)) {
            block("THIRD_PARTY_CONFIRM_STATE_SAVE_FAILED", "正式核销状态无法可靠保存");
            return false;
        }
        if (!occupancy.markRedemptionWaitingDispense(session.clientRequestNo)) {
            block("THIRD_PARTY_CONFIRM_OCCUPANCY_FAILED", "正式核销前交易状态切换失败");
            return false;
        }
        broadcast(session);
        executor.execute(() -> confirmOnce(session.clientRequestNo, candidate.certificateId));
        return true;
    }

    private void confirmOnce(String requestNo, String certificateId) {
        try {
            DeviceAppThirdPartyRedemptionResult result =
                    sdkManager.confirmThirdPartyRedemption(requestNo, certificateId);
            consecutiveQueryFailures = 0;
            applyStatusResult(requestNo, result);
        } catch (Throwable error) {
            DeviceApiException apiError = asApiError(error);
            if (apiError != null) {
                logApiFailure("confirm", requestNo, apiError);
            } else {
                Log.w(TAG, "confirm结果未知，改为查询原请求：requestNo=" + requestNo
                        + "，error=" + error.getClass().getSimpleName());
            }
            synchronized (this) {
                RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
                if (session == null || !requestNo.equals(session.clientRequestNo)) {
                    return;
                }
                // confirm 可能已经真实消费第三方券，任何异常都只能先查原请求，禁止换请求号。
                session.uiState = ThirdPartyRedemptionPolicy.STATE_WAITING_FINAL_STATUS;
                session.message = "核销结果正在确认，请勿重复扫码或再次确认";
                store.saveThirdParty(session);
                broadcast(session);
                scheduleQuery(requestNo, ThirdPartyRedemptionPolicy.NORMAL_QUERY_DELAY_MS);
            }
        }
    }

    private synchronized void applyStatusResult(
            String requestNo,
            DeviceAppThirdPartyRedemptionResult result
    ) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || !requestNo.equals(session.clientRequestNo) || result == null) {
            return;
        }
        if (!requestNo.equals(safe(result.getClientRequestNo()))) {
            block("THIRD_PARTY_STATUS_REQUEST_MISMATCH", "核销状态响应与当前请求不一致");
            return;
        }
        String resultChannel = normalize(result.getChannelCode());
        if (!resultChannel.isEmpty() && !resultChannel.equals(normalize(session.channelCode))) {
            block("THIRD_PARTY_STATUS_CHANNEL_MISMATCH", "核销状态响应渠道不一致");
            return;
        }

        session.redemptionNo = safe(result.getRedemptionNo());
        session.channelStatus = normalize(result.getChannelStatus());
        session.fulfillmentStatus = normalize(result.getFulfillmentStatus());
        session.resolutionStatus = normalize(result.getResolutionStatus());
        session.requestedQuantity = result.getRequestedQuantity() == null
                ? session.requestedQuantity : Math.max(0, result.getRequestedQuantity());
        session.actualQuantity = result.getActualQuantity() == null
                ? -1 : Math.max(0, result.getActualQuantity());
        session.terminal = result.isTerminal();
        session.lastStatusCheckedAt = System.currentTimeMillis();
        session.uiState = ThirdPartyRedemptionPolicy.terminalUiState(
                session.terminal,
                session.channelStatus,
                session.fulfillmentStatus,
                session.resolutionStatus
        );
        session.message = firstNonBlank(result.getMessage(), statusMessage(session));
        if (!store.saveThirdParty(session)) {
            block("THIRD_PARTY_STATUS_SAVE_FAILED", "核销状态无法可靠保存");
            return;
        }

        if (!session.terminal) {
            String resolution = normalize(session.resolutionStatus);
            String channelStatus = normalize(session.channelStatus);
            if ("MANUAL_REVIEW".equals(resolution)) {
                occupancy.markBlocked("THIRD_PARTY_MANUAL_REVIEW");
            } else if ("AUTO_COMPENSATING".equals(resolution)
                    || "REVERSING".equals(channelStatus)
                    || "REVERSED".equals(channelStatus)) {
                if (!commandStore.hasActivePhysicalOrder()) {
                    occupancy.transitionRedemption(
                            session.clientRequestNo,
                            TransactionOccupancyManager.PHASE_REFUNDING
                    );
                }
            } else {
                // HTTP 非终态不得把已经开始的物理出珠回退到 WAITING_DISPENSE。
                occupancy.markRedemptionWaitingDispense(session.clientRequestNo);
            }
            broadcast(session);
            scheduleQuery(requestNo, ThirdPartyRedemptionPolicy.NORMAL_QUERY_DELAY_MS);
            return;
        }

        cancelQuery();
        broadcast(session);
        releaseIfTerminalAndPhysicalSettled(session);
    }

    private synchronized void scheduleQuery(String requestNo, long delayMs) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (ThirdPartyRedemptionManager.this) {
                queryTask = null;
            }
            queryStatus(requestNo);
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void queryStatus(String requestNo) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || !requestNo.equals(session.clientRequestNo)) {
            return;
        }
        if (session.terminal) {
            releaseIfTerminalAndPhysicalSettled(session);
            return;
        }
        try {
            DeviceAppThirdPartyRedemptionResult result = sdkManager.queryThirdPartyRedemption(requestNo);
            consecutiveQueryFailures = 0;
            applyStatusResult(requestNo, result);
        } catch (Throwable error) {
            consecutiveQueryFailures++;
            DeviceApiException apiError = asApiError(error);
            if (apiError != null) {
                logApiFailure("status", requestNo, apiError);
            } else {
                Log.w(TAG, "status查询失败，将继续查询原请求：requestNo=" + requestNo
                        + "，error=" + error.getClass().getSimpleName());
            }
            synchronized (this) {
                RedemptionSessionStore.ThirdPartySession latest = store.loadThirdParty();
                if (latest == null || !requestNo.equals(latest.clientRequestNo)) {
                    return;
                }
                latest.lastStatusCheckedAt = System.currentTimeMillis();
                latest.message = "网络波动，正在继续确认原核销订单";
                store.saveThirdParty(latest);
                broadcast(latest);
                scheduleQuery(
                        requestNo,
                        ThirdPartyRedemptionPolicy.retryDelayMs(consecutiveQueryFailures)
                );
            }
        }
    }

    /** APP/服务重建后恢复同一 requestNo；confirm 后永远先 query，绝不重新消费券。 */
    public synchronized void resumePending() {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || session.clientRequestNo.isEmpty()) {
            return;
        }
        TransactionOccupancyManager.AcquireResult recovered = occupancy.recoverRedemption(
                TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION,
                session.clientRequestNo
        );
        if (!recovered.success) {
            return;
        }
        if (session.terminal) {
            releaseIfTerminalAndPhysicalSettled(session);
            return;
        }
        if (ThirdPartyRedemptionPolicy.STATE_STARTING.equals(session.uiState)) {
            executor.execute(() -> prepareScannerSession(
                    recovered.snapshot.sessionId,
                    session.clientRequestNo
            ));
            return;
        }
        if (ThirdPartyRedemptionPolicy.STATE_PREPARING.equals(session.uiState)) {
            // 原始券码按安全要求没有持久化；进程重启后只能要求重新扫码，但仍复用原请求号。
            session.uiState = ThirdPartyRedemptionPolicy.STATE_SCANNING;
            session.message = "设备已恢复，请重新扫描同一张" + session.channelName + "团购券";
            store.saveThirdParty(session);
            broadcast(session);
            return;
        }
        if (session.confirmRequestedAt > 0L
                || ThirdPartyRedemptionPolicy.STATE_CONFIRMING.equals(session.uiState)
                || ThirdPartyRedemptionPolicy.STATE_WAITING_FINAL_STATUS.equals(session.uiState)
                || ThirdPartyRedemptionPolicy.STATE_WAITING_DISPENSE_COMMAND.equals(session.uiState)
                || ThirdPartyRedemptionPolicy.STATE_DISPENSING.equals(session.uiState)
                || ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {
            // confirm 已经越过消费边界；如果 occupancy 是重建出来的 PREPARING，
            // 先恢复到 WAITING_DISPENSE，避免合法 MQTT 比 HTTP query 更早到达时被误拒绝。
            if (!ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {
                occupancy.markRedemptionWaitingDispense(session.clientRequestNo);
            }
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            broadcast(session);
        }
    }

    /** 控制板完成只表示物理动作结束；第三方业务必须继续等待后端 terminal 收敛。 */
    public synchronized void onPhysicalDispenseFinished() {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null) {
            return;
        }
        if (!session.terminal) {
            session.uiState = ThirdPartyRedemptionPolicy.STATE_WAITING_FINAL_STATUS;
            session.message = "出珠已完成，正在确认平台最终核销状态";
            store.saveThirdParty(session);
            broadcast(session);
            scheduleQuery(session.clientRequestNo, 0L);
        } else {
            releaseIfTerminalAndPhysicalSettled(session);
        }
    }

    public synchronized boolean abandonBeforeConfirm() {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null) {
            return true;
        }
        if (!ThirdPartyRedemptionPolicy.canAbandonBeforeConfirm(
                session.uiState, session.confirmRequestedAt)) {
            return false;
        }
        cancelQuery();
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "third party abandoned before confirm", true);
        }
        store.clearThirdParty();
        broadcastMessage("已取消本次团购核销", ThirdPartyRedemptionPolicy.STATE_FAILED, "");
        return true;
    }

    /** 终态页面关闭后只清理已结束的业务快照，不影响物理 outbox。 */
    public synchronized void acknowledgeTerminal() {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session == null || !session.terminal) {
            return;
        }
        releaseIfTerminalAndPhysicalSettled(session);
        if (!commandStore.hasActivePhysicalOrder()) {
            store.clearThirdParty();
        }
    }

    public synchronized UiSnapshot snapshot() {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        return session == null ? null : toUiSnapshot(session);
    }

    public synchronized void onOccupancyReleased(String requestNo) {
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session != null && requestNo.equals(session.clientRequestNo)) {
            broadcast(session);
        }
    }

    private void releaseIfTerminalAndPhysicalSettled(
            RedemptionSessionStore.ThirdPartySession session
    ) {
        if (session == null || !session.terminal) {
            return;
        }
        if (ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(session.uiState)) {
            occupancy.markBlocked("THIRD_PARTY_TERMINAL_MANUAL_REVIEW");
            return;
        }
        if (commandStore.hasActivePhysicalOrder()) {
            occupancy.transitionRedemption(
                    session.clientRequestNo,
                    TransactionOccupancyManager.PHASE_FINISHING
            );
            scheduleTerminalReleaseCheck(session.clientRequestNo);
            return;
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)
                && session.clientRequestNo.equals(snapshot.clientRequestNo)) {
            occupancy.release(snapshot.sessionId, "third party backend terminal", true);
        }
    }

    private synchronized void scheduleTerminalReleaseCheck(String requestNo) {
        if (queryTask != null && !queryTask.isDone()) {
            return;
        }
        queryTask = executor.schedule(() -> {
            synchronized (ThirdPartyRedemptionManager.this) {
                queryTask = null;
            }
            RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
            if (session != null && requestNo.equals(session.clientRequestNo)) {
                releaseIfTerminalAndPhysicalSettled(session);
            }
        }, 2_000L, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelQuery() {
        if (queryTask != null) {
            queryTask.cancel(false);
            queryTask = null;
        }
    }

    private void block(String reason, String message) {
        occupancy.markBlocked(reason);
        RedemptionSessionStore.ThirdPartySession session = store.loadThirdParty();
        if (session != null) {
            session.uiState = ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW;
            session.message = message;
            store.saveThirdParty(session);
            broadcast(session);
        }
    }

    private void logApiFailure(String stage, String requestNo, DeviceApiException error) {
        // 只记录阶段、请求号、稳定错误码和 traceId，禁止记录券码、签名和 certificateId。
        Log.w(TAG, "第三方核销接口失败：stage=" + stage
                + "，requestNo=" + requestNo
                + "，apiCode=" + error.getApiCode()
                + "，traceId=" + safe(error.getTraceId()));
    }

    private void broadcast(RedemptionSessionStore.ThirdPartySession session) {
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

    private static String encodeCandidates(List<DeviceAppThirdPartyVoucherCandidate> values) {
        JSONArray array = new JSONArray();
        for (DeviceAppThirdPartyVoucherCandidate value : values) {
            if (value == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("certificateId", safe(value.getCertificateId()));
                item.put("title", safe(value.getTitle()));
                item.put("startTime", value.getStartTime() == null ? 0L : value.getStartTime());
                item.put("expireTime", value.getExpireTime() == null ? 0L : value.getExpireTime());
                item.put("itemName", safe(value.getItemName()));
                item.put("marbleQuantity", value.getMarbleQuantity() == null
                        ? 0 : Math.max(0, value.getMarbleQuantity()));
                item.put("redeemable", value.isRedeemable());
                item.put("unavailableReason", safe(value.getUnavailableReason()));
                array.put(item);
            } catch (Throwable ignored) {
            }
        }
        return array.toString();
    }

    private static List<CandidateSnapshot> decodeCandidates(String json) {
        List<CandidateSnapshot> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(blank(json) ? "[]" : json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                CandidateSnapshot candidate = new CandidateSnapshot();
                candidate.certificateId = item.optString("certificateId", "");
                candidate.title = item.optString("title", "");
                candidate.startTime = item.optLong("startTime", 0L);
                candidate.expireTime = item.optLong("expireTime", 0L);
                candidate.itemName = item.optString("itemName", "");
                candidate.marbleQuantity = Math.max(0, item.optInt("marbleQuantity", 0));
                candidate.redeemable = item.optBoolean("redeemable", false);
                candidate.unavailableReason = item.optString("unavailableReason", "");
                result.add(candidate);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static CandidateSnapshot findCandidate(String json, String certificateId) {
        String target = safe(certificateId);
        if (target.isEmpty()) {
            return null;
        }
        for (CandidateSnapshot candidate : decodeCandidates(json)) {
            if (target.equals(candidate.certificateId)) {
                return candidate;
            }
        }
        return null;
    }

    private static UiSnapshot toUiSnapshot(RedemptionSessionStore.ThirdPartySession session) {
        UiSnapshot result = new UiSnapshot();
        result.clientRequestNo = session.clientRequestNo;
        result.channelCode = session.channelCode;
        result.channelName = session.channelName;
        result.sessionExpireTime = session.sessionExpireTime;
        result.selectedCertificateId = session.selectedCertificateId;
        result.redemptionNo = session.redemptionNo;
        result.channelStatus = session.channelStatus;
        result.fulfillmentStatus = session.fulfillmentStatus;
        result.resolutionStatus = session.resolutionStatus;
        result.requestedQuantity = session.requestedQuantity;
        result.actualQuantity = session.actualQuantity;
        result.uiState = session.uiState;
        result.message = session.message;
        result.terminal = session.terminal;
        result.confirmRequestedAt = session.confirmRequestedAt;
        result.candidates = decodeCandidates(session.candidatesJson);
        return result;
    }

    private static String voucherSnapshot(String rawCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < Math.min(6, hash.length); index++) {
                builder.append(String.format(Locale.ROOT, "%02x", hash[index] & 0xFF));
            }
            return "len=" + rawCode.length() + ",sha256=" + builder;
        } catch (Throwable ignored) {
            return "len=" + rawCode.length();
        }
    }

    private static String statusMessage(RedemptionSessionStore.ThirdPartySession session) {
        if (session.terminal) {
            if (ThirdPartyRedemptionPolicy.STATE_SUCCEEDED.equals(session.uiState)) {
                return "团购核销和出珠已完成";
            }
            return "团购核销已结束";
        }
        if ("MANUAL_REVIEW".equals(normalize(session.resolutionStatus))) {
            return "当前核销需要人工处理，请联系工作人员";
        }
        if ("DISPENSING".equals(normalize(session.fulfillmentStatus))) {
            return "核销成功，等待设备完成出珠";
        }
        return "正在确认团购核销状态";
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
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "APPCHNL_" + System.currentTimeMillis() + "_" + random;
    }

    private static String normalize(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public static final class CandidateSnapshot {
        public String certificateId = "";
        public String title = "";
        public long startTime;
        public long expireTime;
        public String itemName = "";
        public int marbleQuantity;
        public boolean redeemable;
        public String unavailableReason = "";
    }

    public static final class UiSnapshot {
        public String clientRequestNo = "";
        public String channelCode = "";
        public String channelName = "";
        public long sessionExpireTime;
        public String selectedCertificateId = "";
        public String redemptionNo = "";
        public String channelStatus = "";
        public String fulfillmentStatus = "";
        public String resolutionStatus = "";
        public int requestedQuantity;
        public int actualQuantity = -1;
        public String uiState = "";
        public String message = "";
        public boolean terminal;
        public long confirmRequestedAt;
        public List<CandidateSnapshot> candidates = Collections.emptyList();
    }
}
