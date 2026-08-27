package com.chuzhu.mqtt;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.chuzhu.DepositConfirmActivity;
import com.chuzhu.data.CommandStore;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.device.DeviceUtil;
import com.chuzhu.hardware.SerialMarbleCollectHardwareAdapter;
import com.chuzhu.member.MemberDepositBalanceReader;
import com.chuzhu.serial.BoardFrameCodec;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.CollectMarblesCommandData;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResult;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandTypes;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 控制板自然停止后的“确认 / 继续存珠 / 返回”控制器。
 *
 * <p>自然停止只代表机构已停和当前数量可信，不立即生成 terminal。用户确认后才一次性
 * 上报累计数量；继续存珠仍复用原 messageId/operationNo/operationToken，不重新扫码、不创建
 * 第二个 Operation。控制板每次 START 会把本段计数清零，因此 Android 用 segmentBaseQuantity
 * 把每段板端计数换算成同一业务的累计数量。累计数量为 0 时禁止提交 success 结算，返回时仅
 * 用失败终态收口平台 Operation，避免产生 0 颗入账，也避免留下“未解决操作”。</p>
 */
public final class PendingDepositController {

    private static final String TAG = "CunzhuConfirm";
    private static final int BALANCE_QUERY_ATTEMPTS = 6;
    private static final long BALANCE_QUERY_DELAY_MS = 800L;
    private static final String RESULT_NO_MARBLES = "NO_MARBLES_COLLECTED";
    private static volatile PendingDepositController instance;

    private final Context context;
    private final HardwareSessionStore sessionStore;
    private final CommandStore commandStore;
    private final MemberDepositStore memberStore;
    private final CommandResultReporter resultReporter;
    private final SerialMarbleCollectHardwareAdapter hardware;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean actionRunning = new AtomicBoolean(false);

    private PendingDepositController(Context context) {
        this.context = context.getApplicationContext();
        sessionStore = new HardwareSessionStore(this.context);
        commandStore = new CommandStore(this.context);
        memberStore = new MemberDepositStore(this.context);
        resultReporter = new CommandResultReporter(this.context);
        hardware = SerialMarbleCollectHardwareAdapter.get(this.context);
    }

    public static PendingDepositController get(Context context) {
        if (instance == null) {
            synchronized (PendingDepositController.class) {
                if (instance == null) {
                    instance = new PendingDepositController(context);
                }
            }
        }
        return instance;
    }

    /** 控制板 0x21 为自然结束或达到上限时，由硬件适配器转入待确认态。 */
    public boolean pauseForConfirmation(int actualQuantity, int finishReason) {
        DepositSession session = sessionStore.load();
        if (session == null
                || actualQuantity < 0
                || (session.maximumQuantity > 0 && actualQuantity > session.maximumQuantity)) {
            return false;
        }
        if (session.sessionTimeoutSeconds <= 0) {
            DeviceMqttCommand<?> command = restoreCommand(session);
            CollectMarblesCommandData data = collectData(command);
            if (data != null && data.getSessionTimeoutSeconds() != null) {
                session.sessionTimeoutSeconds = Math.max(0, data.getSessionTimeoutSeconds());
            }
        }
        session.actualQuantity = actualQuantity;
        session.state = DepositSession.STATE_WAITING_CONFIRM;
        session.finishReason = finishReason;
        session.updatedAt = System.currentTimeMillis();
        session.finishedAt = 0L;
        session.errorCode = "";
        session.errorMessage = "";
        sessionStore.save(session);

        /* 控制板已经真实停止，所以物理 runningStatus 必须回 IDLE；业务 terminal 仍等待用户确认。 */
        DeviceStateRepository.get(context).markIdle();
        memberStore.setMessage(waitingMessage(session));
        new DeviceStatusReporter(context).report();
        launchConfirmUi();
        Log.i(TAG, "控制板已停止，等待用户确认：actual=" + actualQuantity
                + "，finishReason=" + finishReason);
        return true;
    }

    public void confirm(boolean returnAfterConfirm, Callback callback) {
        if (!actionRunning.compareAndSet(false, true)) {
            notifyCallback(callback, false, "正在处理上一项操作，请稍候");
            return;
        }
        executor.execute(() -> {
            try {
                DepositSession session = sessionStore.load();
                if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
                    notifyCallback(callback, false, "当前没有等待确认的存珠记录");
                    return;
                }
                DeviceMqttCommand<?> command = restoreCommand(session);
                if (command == null) {
                    notifyCallback(callback, false, "无法恢复本次 collect_marbles 指令，暂不能确认");
                    return;
                }

                if (session.actualQuantity <= 0) {
                    /*
                     * 0 颗不能作为成功存珠上报，否则平台可能生成无意义的 0 颗结算记录。
                     * 用户点“确认”时保持待确认态，让他选择继续存珠；只有点“返回”才发送
                     * failed terminal 收口 Operation，既不入账，也不会遗留未解决业务。
                     */
                    if (!returnAfterConfirm) {
                        notifyCallback(callback, false, "当前为 0 颗，本次不提交；请继续存珠或返回");
                        return;
                    }
                    finalizeEmptyWithoutDeposit(command, session);
                    memberStore.clearSession();
                    notifyCallback(callback, true, "未检测到珠子，本次未提交并返回");
                    return;
                }

                finalizeSuccess(command, session);
                refreshBalanceAfterSettlement(session.actualQuantity);
                if (returnAfterConfirm) {
                    /* Start 后 Redis Session 已不是业务事实，返回只清本地会员展示，不再调用 cancelSession。 */
                    memberStore.clearSession();
                }
                notifyCallback(callback, true,
                        returnAfterConfirm ? "存珠已确认并返回" : "存珠已确认，账户余额已刷新");
            } catch (Throwable error) {
                Log.e(TAG, "确认存珠失败", error);
                notifyCallback(callback, false, "确认存珠失败：" + messageOf(error));
            } finally {
                actionRunning.set(false);
            }
        });
    }

    public void continueDeposit(Callback callback) {
        if (!actionRunning.compareAndSet(false, true)) {
            notifyCallback(callback, false, "正在处理上一项操作，请稍候");
            return;
        }
        executor.execute(() -> {
            DepositSession session = sessionStore.load();
            int oldReason = session == null ? -1 : session.finishReason;
            try {
                if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
                    notifyCallback(callback, false, "当前没有可继续的存珠记录");
                    return;
                }
                int remainingQuantity = session.maximumQuantity - session.actualQuantity;
                if (remainingQuantity <= 0) {
                    notifyCallback(callback, false, "已达到本次可存上限，请直接确认");
                    return;
                }
                DeviceMqttCommand<?> command = restoreCommand(session);
                CollectMarblesCommandData data = collectData(command);
                if (command == null || data == null) {
                    notifyCallback(callback, false, "无法恢复原存珠授权，暂不能继续");
                    return;
                }
                int timeoutSeconds = session.sessionTimeoutSeconds > 0
                        ? session.sessionTimeoutSeconds
                        : (data.getSessionTimeoutSeconds() == null ? 0 : data.getSessionTimeoutSeconds());
                long elapsedSeconds = session.startedAt <= 0L
                        ? 0L
                        : Math.max(0L, (System.currentTimeMillis() - session.startedAt) / 1000L);
                int remainingTimeout = (int) Math.max(0L, (long) timeoutSeconds - elapsedSeconds);
                if (remainingTimeout <= 5) {
                    notifyCallback(callback, false, "本次平台授权即将超时，请确认当前数量");
                    return;
                }

                int baseQuantity = session.actualQuantity;
                session.segmentBaseQuantity = baseQuantity;
                session.sessionTimeoutSeconds = timeoutSeconds;
                session.finishReason = -1;
                session.state = DepositSession.STATE_COLLECTING;
                session.updatedAt = System.currentTimeMillis();
                sessionStore.save(session);

                boolean started = hardware.startContinuation(
                        remainingQuantity,
                        remainingTimeout,
                        baseQuantity,
                        new ContinuationListener(session.messageId)
                );
                if (!started) {
                    session.state = DepositSession.STATE_WAITING_CONFIRM;
                    session.finishReason = oldReason;
                    session.updatedAt = System.currentTimeMillis();
                    sessionStore.save(session);
                    DeviceStateRepository.get(context).markIdle();
                    notifyCallback(callback, false, "控制板未能重新启动，请确认当前数量或返回");
                    return;
                }

                DeviceStateRepository.get(context).markCollecting();
                memberStore.setMessage("继续收珠中，当前累计 " + baseQuantity + " 颗");
                new DeviceStatusReporter(context).report();
                notifyCallback(callback, true, "已继续存珠");
            } catch (Throwable error) {
                Log.e(TAG, "继续存珠失败", error);
                if (session != null) {
                    session.state = DepositSession.STATE_WAITING_CONFIRM;
                    session.finishReason = oldReason;
                    session.updatedAt = System.currentTimeMillis();
                    sessionStore.save(session);
                    DeviceStateRepository.get(context).markIdle();
                }
                notifyCallback(callback, false, "继续存珠失败：" + messageOf(error));
            } finally {
                actionRunning.set(false);
            }
        });
    }

    public boolean hasPendingConfirmation() {
        DepositSession session = sessionStore.load();
        return session != null && DepositSession.STATE_WAITING_CONFIRM.equals(session.state);
    }

    public void restorePendingUiIfNeeded() {
        if (hasPendingConfirmation()) {
            launchConfirmUi();
        }
    }

    private void finalizeSuccess(DeviceMqttCommand<?> command, DepositSession session) {
        int actual = session.actualQuantity;
        if (actual <= 0) {
            finalizeEmptyWithoutDeposit(command, session);
            return;
        }
        session.state = DepositSession.STATE_FINISHED;
        session.updatedAt = System.currentTimeMillis();
        session.finishedAt = session.updatedAt;
        session.errorCode = "";
        session.errorMessage = "";
        sessionStore.save(session);

        HardwareExecutionResult hardwareResult = HardwareExecutionResult.success(actual);
        DeviceCommandResult terminal = DeviceCommandResult.physicalTerminal(
                command,
                command.getMessageId() + "-terminal",
                true,
                hardwareResult.getActualQuantity(),
                hardwareResult.getResultCode(),
                hardwareResult.getResultMessage(),
                System.currentTimeMillis()
        );
        resultReporter.reportTerminal(terminal, command.getMessageId());
        memberStore.setMessage("已确认 " + actual + " 颗，正在刷新账户余额");
        DeviceStateRepository.get(context).markIdle();
        new DeviceStatusReporter(context).report();
        Log.i(TAG, "用户确认存珠 terminal success：actual=" + actual
                + "，messageId=" + command.getMessageId());
    }

    /**
     * 0 颗只做业务收口，不提交成功存珠，也不触发余额刷新。
     * failed terminal 的唯一作用是告诉平台本次 Operation 已结束，避免下一次扫码被旧 Operation 阻塞。
     */
    private void finalizeEmptyWithoutDeposit(DeviceMqttCommand<?> command, DepositSession session) {
        long now = System.currentTimeMillis();
        String message = "本次未检测到珠子，未执行存珠结算";
        session.actualQuantity = 0;
        session.state = DepositSession.STATE_FINISHED;
        session.errorCode = RESULT_NO_MARBLES;
        session.errorMessage = message;
        session.updatedAt = now;
        session.finishedAt = now;
        sessionStore.save(session);

        DeviceCommandResult terminal = DeviceCommandResult.physicalTerminal(
                command,
                command.getMessageId() + "-terminal",
                false,
                0,
                RESULT_NO_MARBLES,
                message,
                now
        );
        resultReporter.reportTerminal(terminal, command.getMessageId());
        memberStore.setMessage("未检测到珠子，本次未提交存珠");
        DeviceStateRepository.get(context).markIdle();
        new DeviceStatusReporter(context).report();
        Log.i(TAG, "0 颗存珠未提交 success，已按空存珠收口：messageId=" + command.getMessageId());
    }

    private void finalizeFailed(String messageId, String errorCode, String errorMessage, int actual) {
        DepositSession session = sessionStore.load();
        if (session == null || !safe(messageId).equals(safe(session.messageId))) {
            return;
        }
        DeviceMqttCommand<?> command = restoreCommand(session);
        if (command == null) {
            DeviceStateRepository.get(context).markFault(errorMessage);
            memberStore.setMessage("收珠失败：" + errorMessage);
            return;
        }
        session.actualQuantity = actual;
        session.state = DepositSession.STATE_FAILED;
        session.errorCode = safe(errorCode);
        session.errorMessage = safe(errorMessage);
        session.updatedAt = System.currentTimeMillis();
        session.finishedAt = session.updatedAt;
        sessionStore.save(session);
        DeviceCommandResult terminal = DeviceCommandResult.physicalTerminal(
                command,
                command.getMessageId() + "-terminal",
                false,
                actual,
                errorCode,
                errorMessage,
                System.currentTimeMillis()
        );
        resultReporter.reportTerminal(terminal, command.getMessageId());
        memberStore.setMessage("收珠失败：" + errorMessage);
        DeviceStateRepository.get(context).markFault(errorMessage);
        new DeviceStatusReporter(context).report();
    }

    private void refreshBalanceAfterSettlement(int confirmedQuantity) {
        MemberDepositStore.Snapshot member = memberStore.loadWithoutScheduling();
        String requestNo = member.clientRequestNo;
        if (requestNo.isEmpty()) {
            memberStore.setMessage("已确认 " + confirmedQuantity + " 颗；缺少请求号，余额暂未刷新");
            return;
        }
        String oldBalance = member.availableQuantity;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= BALANCE_QUERY_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1) {
                    Thread.sleep(BALANCE_QUERY_DELAY_MS);
                }
                MemberDepositBalanceReader.BalanceSnapshot result =
                        new MemberDepositBalanceReader(context).query(requestNo);
                if (result != null && !result.availableQuantity.isEmpty()) {
                    boolean changed = oldBalance.isEmpty()
                            || !oldBalance.equals(result.availableQuantity)
                            || isTerminalStatus(result.status);
                    if (changed) {
                        memberStore.updateAvailableQuantity(
                                result.availableQuantity,
                                "存珠已确认 " + confirmedQuantity + " 颗，账户余额已刷新"
                        );
                        Log.i(TAG, "会员余额刷新成功：availableQuantity=" + result.availableQuantity
                                + "，operationStatus=" + result.status);
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastError = interrupted;
                break;
            } catch (Throwable error) {
                lastError = error;
                Log.w(TAG, "确认后第 " + attempt + " 次查询账户余额失败", error);
            }
        }
        memberStore.setMessage("存珠已确认 " + confirmedQuantity + " 颗，账户余额暂未刷新");
        if (lastError != null) {
            Log.w(TAG, "账户余额刷新最终失败", lastError);
        }
    }

    private DeviceMqttCommand<?> restoreCommand(DepositSession session) {
        if (session == null || safe(session.messageId).isEmpty()) {
            return null;
        }
        JSONObject envelope = commandStore.loadCommand(session.messageId);
        if (envelope == null) {
            return null;
        }
        long originalTimestamp = envelope.optLong("timestamp", 0L);
        long validationNow = originalTimestamp > 0L ? originalTimestamp : System.currentTimeMillis();
        String deviceNo = DeviceUtil.requireDeviceNo(context);
        DepositCommandCodec.Decoded decoded = new DepositCommandCodec().decode(
                "pxd/v1/device/" + deviceNo + "/command/control",
                envelope.toString().getBytes(StandardCharsets.UTF_8),
                deviceNo,
                validationNow
        );
        if (decoded.command == null
                || !DeviceMqttCommandTypes.COLLECT_MARBLES.equals(decoded.commandType())) {
            return null;
        }
        return decoded.command;
    }

    private static CollectMarblesCommandData collectData(DeviceMqttCommand<?> command) {
        if (command == null) {
            return null;
        }
        try {
            return command.requireData(CollectMarblesCommandData.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String waitingMessage(DepositSession session) {
        if (session.actualQuantity <= 0) {
            return "未检测到珠子，本次不会提交存珠，可继续存珠或返回";
        }
        if (session.finishReason == BoardFrameCodec.FINISH_REASON_MAXIMUM_REACHED
                || session.actualQuantity >= session.maximumQuantity) {
            return "已达到本次可存上限，共 " + session.actualQuantity + " 颗，请确认或返回";
        }
        return "控制板已停止，本次累计 " + session.actualQuantity + " 颗，可确认、继续存珠或返回";
    }

    private void launchConfirmUi() {
        try {
            Intent intent = new Intent(context, DepositConfirmActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Throwable error) {
            Log.e(TAG, "打开存珠确认界面失败", error);
        }
    }

    private static boolean isTerminalStatus(String status) {
        String value = safe(status).toUpperCase();
        return "SUCCESS".equals(value)
                || "SUCCEEDED".equals(value)
                || "COMPLETED".equals(value)
                || "FINISHED".equals(value);
    }

    private static void notifyCallback(Callback callback, boolean success, String message) {
        if (callback != null) {
            callback.onResult(success, message == null ? "" : message);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable cursor = error;
        String message = "";
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().trim().isEmpty()) {
                message = cursor.getMessage().trim();
            }
            cursor = cursor.getCause();
        }
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private final class ContinuationListener implements SerialMarbleCollectHardwareAdapter.Listener {
        private final String messageId;

        ContinuationListener(String messageId) {
            this.messageId = safe(messageId);
        }

        @Override
        public void onCountChanged(int actualQuantity) {
            DepositSession session = sessionStore.load();
            if (session == null || !messageId.equals(safe(session.messageId))) {
                return;
            }
            if (actualQuantity < 0 || actualQuantity > session.maximumQuantity) {
                hardware.onBoardFault(
                        "ACTUAL_QUANTITY_INVALID",
                        "控制板累计计数超出本次最大允许数量"
                );
                return;
            }
            session.actualQuantity = actualQuantity;
            session.state = DepositSession.STATE_COLLECTING;
            session.updatedAt = System.currentTimeMillis();
            sessionStore.save(session);
            /* 每颗可信计数都广播当前物理状态，让会员页实时刷新累计数量。 */
            DeviceStateRepository.get(context).markCollecting();
            Log.i(TAG, "继续存珠累计计数 actualQuantity=" + actualQuantity);
        }

        @Override
        public void onFinished(int actualQuantity) {
            DepositSession session = sessionStore.load();
            if (session == null || !messageId.equals(safe(session.messageId))) {
                return;
            }
            DeviceMqttCommand<?> command = restoreCommand(session);
            if (command != null) {
                session.actualQuantity = actualQuantity;
                finalizeSuccess(command, session);
            }
        }

        @Override
        public void onFault(String errorCode, String errorMessage, int actualQuantity) {
            finalizeFailed(messageId, errorCode, errorMessage, actualQuantity);
        }
    }
}
