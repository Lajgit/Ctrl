package com.gouzhu.mqtt;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.hardware.SerialMarbleHardwareAdapter;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.gouzhu.util.DeviceUtil;
import com.pinball.xiaoda.device.sdk.hardware.DispenseRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandTypes;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 部分出珠后的单次继续出珠执行器。
 *
 * <p>本类不把 continue_marble_dispense 转换为原 dispense_marbles，也不会在补珠、
 * 重连或定时任务中自动启动硬件。只有收到合法的新指令并先持久化 ACK 后，才使用
 * SDK 的继续出珠硬件映射启动一次新的控制板物理序号。</p>
 */
final class ContinuationDispenseManager {

    private static final String TAG = "GouzhuContinuation";
    private static final String COMMAND_TYPE =
            DeviceMqttCommandTypes.CONTINUE_MARBLE_DISPENSE;
    private static final String FIRST_COMMAND_TYPE = "dispense_marbles";

    private static final String TABLE = "continuation_dispense";
    private static final String REJECTION_TABLE = "continuation_rejections";
    private static final String CLAIM_TABLE = "operation_flow_claims";
    private static final String RESOLUTION_TABLE = "operation_resolutions";

    private static final String FLOW_CONTINUATION = "CONTINUATION";
    private static final String FLOW_RESOLUTION = "RESOLUTION";
    private static final String CLAIM_ACTIVE = "ACTIVE";
    private static final String CLAIM_AWAITING_RESOLUTION = "AWAITING_RESOLUTION";
    private static final String CLAIM_COMPLETED = "COMPLETED";

    private static final String STATE_DISPENSING = "DISPENSING";
    private static final String STATE_TERMINAL_PENDING_ACK = "TERMINAL_PENDING_ACK";
    private static final String STATE_AWAITING_RESOLUTION = "AWAITING_RESOLUTION";
    private static final String STATE_COMPLETED = "COMPLETED";

    private static final String META_NEXT_ORDER_SEQUENCE = "next_order_sequence";
    private static final String META_PHYSICAL_BLOCKED = "physical_blocked";
    private static final String META_LOCAL_RESET_REQUIRED =
            "manual_operation_local_reset_required";

    private static final long MIN_CONTROLLER_PROTOCOL_VERSION = 0x02020000L;
    private static final Object DB_LOCK = new Object();

    private final Context context;
    private final DeviceCommandStore store;
    private final SdkCommandDecoder decoder = new SdkCommandDecoder();
    private final SerialMarbleHardwareAdapter marbleAdapter;
    private final ExecutorService hardwareExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-continuation-dispense");
                thread.setDaemon(true);
                return thread;
            });

    private boolean started;

    private final SerialMarbleHardwareAdapter.Observer hardwareObserver =
            new SerialMarbleHardwareAdapter.Observer() {
                @Override
                public void onProgress(String messageId, int orderSequence, int actual) {
                    updateProgress(messageId, orderSequence, actual);
                }

                @Override
                public boolean onTerminalEvidence(
                        String messageId,
                        SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence
                ) {
                    return persistTerminalEvidence(messageId, evidence);
                }

                @Override
                public void onTerminalAckEcho(
                        String messageId,
                        SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
                        boolean echoed
                ) {
                    finishTerminalAcknowledgement(messageId, evidence, echoed);
                }
            };

    ContinuationDispenseManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DeviceCommandStore(this.context);
        this.marbleAdapter = new SerialMarbleHardwareAdapter(this.context);
    }

    synchronized void start() {
        ensureSchema();
        if (started) {
            return;
        }
        started = true;
        marbleAdapter.start(hardwareObserver);
        recoverInterruptedContinuation();
    }

    synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        marbleAdapter.stop();
    }

    boolean handles(byte[] payload) {
        JSONObject envelope = parseEnvelope(payload);
        return envelope != null
                && COMMAND_TYPE.equals(envelope.optString("commandType", ""));
    }

    /**
     * 在人工结案异步执行前同步占用 operationNo，保证人工结案与继续出珠只有一条流程
     * 能进入处理。返回 true 时继续交给 OperationResolutionManager；false 表示本类已拒绝。
     */
    boolean prepareResolution(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            // 保留原人工结案管理器的协议错误处理，不在这里重复上报。
            return true;
        }

        JSONObject data = decoded.envelope.optJSONObject("data");
        String messageId = decoded.envelope.optString("messageId", "").trim();
        String operationNo = data == null
                ? ""
                : data.optString("operationNo", "").trim();
        String resolutionNo = data == null
                ? ""
                : data.optString("resolutionNo", "").trim();
        if (blank(messageId) || blank(operationNo) || blank(resolutionNo)) {
            return true;
        }

        ensureSchema();
        String rejectCode = "";
        String rejectMessage = "";
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                FlowClaim claim = loadClaim(db, operationNo);
                if (claim == null) {
                    if (!insertClaim(
                            db,
                            operationNo,
                            FLOW_RESOLUTION,
                            resolutionNo,
                            messageId,
                            CLAIM_ACTIVE
                    )) {
                        rejectCode = "LOCAL_STORAGE_ERROR";
                        rejectMessage = "operation flow claim could not be saved";
                    }
                } else if (FLOW_RESOLUTION.equals(claim.flowType)) {
                    // 同一 operationNo 的人工流程由原管理器继续做 messageId/resolutionNo 幂等。
                } else if (CLAIM_AWAITING_RESOLUTION.equals(claim.state)) {
                    ContentValues values = new ContentValues();
                    values.put("flow_type", FLOW_RESOLUTION);
                    values.put("flow_no", resolutionNo);
                    values.put("message_id", messageId);
                    values.put("state", CLAIM_ACTIVE);
                    values.put("updated_at", System.currentTimeMillis());
                    if (db.update(
                            CLAIM_TABLE,
                            values,
                            "operation_no=? AND flow_type=? AND state=?",
                            new String[]{
                                    operationNo,
                                    FLOW_CONTINUATION,
                                    CLAIM_AWAITING_RESOLUTION
                            }
                    ) != 1) {
                        rejectCode = "LOCAL_STORAGE_ERROR";
                        rejectMessage = "operation flow transition could not be saved";
                    }
                } else if (CLAIM_COMPLETED.equals(claim.state)) {
                    rejectCode = "OPERATION_ALREADY_COMPLETED";
                    rejectMessage = "continuation dispense already completed this operation";
                } else {
                    rejectCode = "OPERATION_CONTINUATION_ACTIVE";
                    rejectMessage = "continuation dispense is processing this operation";
                }

                if (blank(rejectCode)) {
                    db.setTransactionSuccessful();
                }
            } finally {
                db.endTransaction();
            }
        }

        if (blank(rejectCode)) {
            return true;
        }
        rejectResolution(decoded, operationNo, rejectCode, rejectMessage);
        return false;
    }

    void handleCommand(String topic, byte[] payload) {
        final SdkCommandDecoder.DecodedCommand decoded;
        try {
            decoded = decoder.decode(
                    topic,
                    payload,
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportFault(
                    "CONTINUATION_COMMAND_INVALID",
                    "继续出珠指令未通过SDK校验：" + messageOf(error)
            );
            return;
        }

        if (!COMMAND_TYPE.equals(decoded.sdkCommand.getCommandType())) {
            return;
        }
        ensureSchema();

        JSONObject data = decoded.envelope.optJSONObject("data");
        String messageId = decoded.envelope.optString("messageId", "").trim();
        String operationNo = data == null
                ? ""
                : data.optString("operationNo", "").trim();
        String continuationNo = data == null
                ? ""
                : data.optString("continuationNo", "").trim();
        String operationToken = data == null
                ? ""
                : data.optString("operationToken", "").trim();

        ContinuationRecord byMessage = loadByMessageId(messageId);
        if (byMessage != null) {
            replayRecord(byMessage);
            return;
        }
        RejectionRecord rejectedByMessage = loadRejectionByMessageId(messageId);
        if (rejectedByMessage != null) {
            replayRejection(rejectedByMessage);
            return;
        }
        if (store.hasCommand(messageId)) {
            resendCommandResults(messageId);
            return;
        }

        if (!blank(continuationNo)) {
            ContinuationRecord byContinuation = loadByContinuationNo(continuationNo);
            if (byContinuation != null) {
                replayRecord(byContinuation);
                return;
            }
            RejectionRecord rejectedByContinuation =
                    loadRejectionByContinuationNo(continuationNo);
            if (rejectedByContinuation != null) {
                replayRejection(rejectedByContinuation);
                return;
            }
        }

        final DispenseRequest request;
        try {
            request = decoded.toContinuationDispenseRequest(System.currentTimeMillis());
        } catch (Throwable error) {
            rejectContinuation(
                    decoded,
                    operationNo,
                    continuationNo,
                    "SDK_HARDWARE_MAPPING_FAILED",
                    messageOf(error)
            );
            return;
        }

        String mappedContinuationNo = safe(request.getContinuationNo()).trim();
        int remainingQuantity = request.getQuantity();
        if (blank(continuationNo)) {
            continuationNo = mappedContinuationNo;
        }
        if (blank(messageId)
                || blank(operationNo)
                || blank(continuationNo)
                || blank(mappedContinuationNo)
                || !continuationNo.equals(mappedContinuationNo)
                || blank(operationToken)
                || remainingQuantity <= 0
                || remainingQuantity > 0xFFFF) {
            rejectContinuation(
                    decoded,
                    operationNo,
                    continuationNo,
                    "PARAM_INVALID",
                    "continue dispense fields are incomplete or invalid"
            );
            return;
        }

        ContinuationRecord previous = loadByOperationNo(operationNo);
        if (previous != null) {
            if (continuationNo.equals(previous.continuationNo)) {
                replayRecord(previous);
            } else {
                rejectContinuation(
                        decoded,
                        operationNo,
                        continuationNo,
                        "CONTINUATION_ALREADY_USED",
                        "only one continuation is allowed for an operation"
                );
            }
            return;
        }

        ValidationResult validation = validateLocalSession(
                operationNo,
                operationToken,
                remainingQuantity
        );
        if (!validation.success) {
            rejectContinuation(
                    decoded,
                    operationNo,
                    continuationNo,
                    validation.resultCode,
                    validation.resultMessage
            );
            return;
        }

        final SdkCommandDecoder.EncodedResult acknowledgement;
        try {
            acknowledgement = decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            rejectContinuation(
                    decoded,
                    operationNo,
                    continuationNo,
                    "LOCAL_ACK_ENCODE_FAILED",
                    messageOf(error)
            );
            return;
        }

        PersistAcceptResult accepted = persistAccepted(
                decoded.envelope,
                topic,
                operationNo,
                continuationNo,
                operationToken,
                remainingQuantity,
                validation.active,
                validation.firstActual,
                acknowledgement
        );
        if (!accepted.success) {
            ContinuationRecord concurrent = loadByContinuationNo(continuationNo);
            if (concurrent != null) {
                replayRecord(concurrent);
                return;
            }
            rejectContinuation(
                    decoded,
                    operationNo,
                    continuationNo,
                    accepted.resultCode,
                    accepted.resultMessage
            );
            return;
        }

        MqttManager.get(context).reportCommandResult(acknowledgement.payload);
        broadcastDispenseOrder(
                "started",
                accepted.orderSequence,
                remainingQuantity,
                0,
                0,
                "继续出珠已开始"
        );

        final String acceptedMessageId = messageId;
        final int orderSequence = accepted.orderSequence;
        hardwareExecutor.execute(() -> executeContinuation(
                acceptedMessageId,
                request,
                orderSequence,
                remainingQuantity
        ));
    }

    int getRunningStatus() {
        ContinuationRecord record = loadCurrentRecord();
        if (record == null || STATE_COMPLETED.equals(record.state)) {
            return 0;
        }
        if (STATE_DISPENSING.equals(record.state)
                || STATE_TERMINAL_PENDING_ACK.equals(record.state)) {
            return 1;
        }
        return 2;
    }

    void broadcastCurrentState() {
        ContinuationRecord record = loadCurrentRecord();
        if (record == null || STATE_COMPLETED.equals(record.state)) {
            return;
        }
        if (STATE_DISPENSING.equals(record.state)) {
            broadcastDispenseOrder(
                    "started",
                    record.orderSequence,
                    record.remainingQuantity,
                    record.progressActual,
                    0,
                    "继续出珠执行中"
            );
            return;
        }
        if (STATE_TERMINAL_PENDING_ACK.equals(record.state)) {
            broadcastDispenseOrder(
                    "finishing",
                    record.orderSequence,
                    record.remainingQuantity,
                    record.terminalActual,
                    record.controllerResultCode,
                    "继续出珠终态确认中"
            );
            return;
        }
        broadcastDispenseOrder(
                "blocked",
                record.orderSequence,
                record.remainingQuantity,
                Math.max(record.progressActual, record.terminalActual),
                record.controllerResultCode,
                blank(record.blockedReason)
                        ? "继续出珠未完成，等待人工处理"
                        : record.blockedReason
        );
    }

    private void executeContinuation(
            String messageId,
            DispenseRequest request,
            int orderSequence,
            int remainingQuantity
    ) {
        HardwareExecutionResult result = marbleAdapter.dispenseOrder(request, orderSequence);
        if (result == null) {
            persistUnknownResult(
                    messageId,
                    0,
                    "CONTROLLER_TERMINAL_MISSING",
                    "控制板未返回继续出珠结果"
            );
            return;
        }
        ContinuationRecord stored = loadByMessageId(messageId);
        if (stored != null && !blank(stored.terminalPayload)) {
            return;
        }

        String resultCode = safe(result.getResultCode());
        if ("CONTROLLER_RESULT_TIMEOUT".equals(resultCode)
                || "CONTROLLER_TERMINAL_MISSING".equals(resultCode)
                || "DEVICE_BUSY".equals(resultCode)
                || "ADAPTER_STOPPED".equals(resultCode)) {
            int actual = Math.max(0, Math.min(
                    remainingQuantity,
                    result.getActualQuantity()
            ));
            persistUnknownResult(
                    messageId,
                    actual,
                    blank(resultCode) ? "CONTROLLER_TERMINAL_MISSING" : resultCode,
                    safe(result.getResultMessage())
            );
        }
    }

    private void updateProgress(String messageId, int orderSequence, int actual) {
        ContinuationRecord record = loadByMessageId(messageId);
        if (record == null
                || record.orderSequence != orderSequence
                || !STATE_DISPENSING.equals(record.state)
                || actual < record.progressActual
                || actual > record.remainingQuantity) {
            return;
        }

        synchronized (DB_LOCK) {
            ContentValues values = new ContentValues();
            values.put("progress_actual", actual);
            values.put("updated_at", System.currentTimeMillis());
            store.getWritableDatabase().update(
                    TABLE,
                    values,
                    "message_id=? AND order_sequence=? AND state=?",
                    new String[]{
                            messageId,
                            String.valueOf(orderSequence),
                            STATE_DISPENSING
                    }
            );
        }
        broadcastDispenseOrder(
                "progress",
                orderSequence,
                record.remainingQuantity,
                actual,
                0,
                "继续出珠执行中"
        );
    }

    private boolean persistTerminalEvidence(
            String messageId,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence
    ) {
        ContinuationRecord record = loadByMessageId(messageId);
        if (record == null
                || record.orderSequence != evidence.orderSequence
                || !(STATE_DISPENSING.equals(record.state)
                || STATE_TERMINAL_PENDING_ACK.equals(record.state))) {
            return false;
        }

        if (!blank(record.terminalPayload)) {
            boolean same = record.terminalFrameId == evidence.frameId
                    && record.terminalActual == evidence.terminalActual
                    && record.controllerResultCode == evidence.controllerResultCode;
            if (same) {
                requeueStoredRecord(record);
            } else {
                reportFault(
                        "CONTINUATION_TERMINAL_CONFLICT",
                        "继续出珠控制板终态冲突：messageId=" + messageId
                );
            }
            return same;
        }

        int finalActual = Math.max(
                Math.max(record.progressActual, evidence.lastProgressActual),
                evidence.terminalActual
        );
        boolean success = evidence.controllerResultCode == 0
                && finalActual == record.remainingQuantity;
        String resultCode;
        String resultMessage;
        if (success) {
            resultCode = "DISPENSE_COMPLETED";
            resultMessage = "剩余出珠完成";
        } else if (evidence.terminalActual < evidence.lastProgressActual
                || evidence.terminalActual < record.progressActual) {
            resultCode = "CONTROLLER_ACTUAL_REGRESSION";
            resultMessage = "继续出珠终态计数小于过程计数";
        } else if (finalActual > record.remainingQuantity) {
            resultCode = "ACTUAL_QUANTITY_EXCEEDED";
            resultMessage = "继续出珠实际数量超过授权剩余数量";
        } else if (evidence.controllerResultCode == 0) {
            resultCode = "ACTUAL_QUANTITY_MISMATCH";
            resultMessage = "继续出珠实际数量未达到剩余数量";
        } else {
            resultCode = SerialMarbleHardwareAdapter.boardResultName(
                    evidence.controllerResultCode
            );
            resultMessage = evidence.controllerResultCode == 2
                    ? "继续出珠库存仍不足，已出" + finalActual + "颗"
                    : "继续出珠控制板返回：" + resultCode;
        }

        final SdkCommandDecoder.DecodedCommand decoded;
        final SdkCommandDecoder.EncodedResult terminal;
        try {
            decoded = decodeStored(record);
            if (decoded == null) {
                return false;
            }
            terminal = decoded.physicalTerminal(
                    record.messageId + "-result",
                    success,
                    finalActual,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportFault(
                    "CONTINUATION_TERMINAL_ENCODE_FAILED",
                    messageOf(error)
            );
            return false;
        }

        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ContinuationRecord latest = loadRecord(
                        db,
                        "message_id=?",
                        new String[]{messageId}
                );
                if (latest == null || latest.orderSequence != evidence.orderSequence) {
                    return false;
                }
                if (!blank(latest.terminalPayload)) {
                    boolean same = latest.terminalFrameId == evidence.frameId
                            && latest.terminalActual == evidence.terminalActual
                            && latest.controllerResultCode == evidence.controllerResultCode;
                    if (same && saveOutbox(
                            db,
                            latest.messageId + "|" + latest.terminalEventNo + "|"
                                    + latest.terminalStatus,
                            latest.messageId,
                            latest.terminalEventNo,
                            latest.terminalStatus,
                            latest.terminalPayload
                    )) {
                        db.setTransactionSuccessful();
                    }
                    return same;
                }

                ContentValues values = new ContentValues();
                values.put("state", STATE_TERMINAL_PENDING_ACK);
                values.put("progress_actual", Math.max(
                        latest.progressActual,
                        evidence.lastProgressActual
                ));
                values.put("terminal_event_no", terminal.eventNo);
                values.put("terminal_status", terminal.resultStatus);
                values.put("terminal_payload", terminal.payload);
                values.put("terminal_actual", finalActual);
                values.put("terminal_success", success ? 1 : 0);
                values.put("terminal_frame_id", evidence.frameId);
                values.put("controller_result_code", evidence.controllerResultCode);
                values.put("blocked_reason", success ? "" : resultCode);
                values.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        TABLE,
                        values,
                        "message_id=? AND order_sequence=?",
                        new String[]{messageId, String.valueOf(evidence.orderSequence)}
                ) != 1) {
                    return false;
                }
                if (!updateCommandState(db, messageId, "continuation_terminal")) {
                    return false;
                }
                if (!saveOutbox(
                        db,
                        messageId + "|" + terminal.eventNo + "|"
                                + terminal.resultStatus,
                        messageId,
                        terminal.eventNo,
                        terminal.resultStatus,
                        terminal.payload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        MqttManager.get(context).reportCommandResult(terminal.payload);
        if (success) {
            broadcastDispenseOrder(
                    "finishing",
                    evidence.orderSequence,
                    record.remainingQuantity,
                    finalActual,
                    evidence.controllerResultCode,
                    "继续出珠终态确认中"
            );
        }
        return true;
    }

    private void finishTerminalAcknowledgement(
            String messageId,
            SerialMarbleHardwareAdapter.ControllerTerminalEvidence evidence,
            boolean echoed
    ) {
        ContinuationRecord record = loadByMessageId(messageId);
        if (record == null
                || record.orderSequence != evidence.orderSequence
                || blank(record.terminalPayload)) {
            return;
        }

        boolean completed = echoed
                && record.terminalSuccess
                && record.firstActual + record.terminalActual
                == loadOriginalRequestedQuantity(record.originalMessageId);
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                values.put("terminal_ack_sent", 1);
                values.put("terminal_ack_echoed", echoed ? 1 : 0);
                values.put(
                        "state",
                        completed ? STATE_COMPLETED : STATE_AWAITING_RESOLUTION
                );
                if (!completed && blank(record.blockedReason)) {
                    values.put(
                            "blocked_reason",
                            echoed
                                    ? "CONTINUATION_NOT_COMPLETED"
                                    : "CONTROLLER_TERMINAL_ACK_TIMEOUT"
                    );
                }
                values.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        TABLE,
                        values,
                        "message_id=? AND order_sequence=?",
                        new String[]{messageId, String.valueOf(evidence.orderSequence)}
                ) != 1) {
                    return;
                }

                if (completed) {
                    if (db.delete(
                            "active_physical_order",
                            "id=1 AND message_id=?",
                            new String[]{record.originalMessageId}
                    ) != 1) {
                        return;
                    }
                    putMeta(db, META_PHYSICAL_BLOCKED, "0");
                    putMeta(db, META_LOCAL_RESET_REQUIRED, "0");
                    updateClaimState(db, record.operationNo, CLAIM_COMPLETED);
                    updateCommandState(db, messageId, "terminal");
                } else {
                    putMeta(db, META_PHYSICAL_BLOCKED, "1");
                    putMeta(db, META_LOCAL_RESET_REQUIRED, "1");
                    updateClaimState(
                            db,
                            record.operationNo,
                            CLAIM_AWAITING_RESOLUTION
                    );
                    updateCommandState(db, messageId, "blocked");
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        if (completed) {
            broadcastDispenseOrder(
                    "finished",
                    record.orderSequence,
                    record.remainingQuantity,
                    record.terminalActual,
                    evidence.controllerResultCode,
                    "剩余出珠完成"
            );
            TransactionOccupancyManager.get(context).restoreCashAcceptanceIfSafe();
        } else {
            broadcastDispenseOrder(
                    "blocked",
                    record.orderSequence,
                    record.remainingQuantity,
                    record.terminalActual,
                    evidence.controllerResultCode,
                    echoed
                            ? "继续出珠未完成，等待人工处理"
                            : "继续出珠终态确认失败，等待人工处理"
            );
        }
        MqttManager.get(context).reportStatus();
    }

    private void persistUnknownResult(
            String messageId,
            int actualQuantity,
            String resultCode,
            String resultMessage
    ) {
        ContinuationRecord record = loadByMessageId(messageId);
        if (record == null || !blank(record.terminalPayload)) {
            return;
        }
        int safeActual = Math.max(0, Math.min(
                record.remainingQuantity,
                actualQuantity
        ));

        final SdkCommandDecoder.EncodedResult terminal;
        try {
            SdkCommandDecoder.DecodedCommand decoded = decodeStored(record);
            if (decoded == null) {
                return;
            }
            terminal = decoded.physicalTerminal(
                    messageId + "-result",
                    false,
                    safeActual,
                    resultCode,
                    blank(resultMessage)
                            ? "继续出珠物理结果不明确，等待人工处理"
                            : resultMessage,
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportFault("CONTINUATION_TERMINAL_ENCODE_FAILED", messageOf(error));
            return;
        }

        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ContinuationRecord latest = loadRecord(
                        db,
                        "message_id=?",
                        new String[]{messageId}
                );
                if (latest == null || !blank(latest.terminalPayload)) {
                    return;
                }
                ContentValues values = new ContentValues();
                values.put("state", STATE_AWAITING_RESOLUTION);
                values.put("terminal_event_no", terminal.eventNo);
                values.put("terminal_status", terminal.resultStatus);
                values.put("terminal_payload", terminal.payload);
                values.put("terminal_actual", safeActual);
                values.put("terminal_success", 0);
                values.put("blocked_reason", resultCode);
                values.put("updated_at", System.currentTimeMillis());
                if (db.update(
                        TABLE,
                        values,
                        "message_id=?",
                        new String[]{messageId}
                ) != 1) {
                    return;
                }
                if (!saveOutbox(
                        db,
                        messageId + "|" + terminal.eventNo + "|"
                                + terminal.resultStatus,
                        messageId,
                        terminal.eventNo,
                        terminal.resultStatus,
                        terminal.payload
                )) {
                    return;
                }
                updateCommandState(db, messageId, "blocked");
                updateClaimState(
                        db,
                        latest.operationNo,
                        CLAIM_AWAITING_RESOLUTION
                );
                putMeta(db, META_PHYSICAL_BLOCKED, "1");
                putMeta(db, META_LOCAL_RESET_REQUIRED, "1");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        MqttManager.get(context).reportCommandResult(terminal.payload);
        broadcastDispenseOrder(
                "blocked",
                record.orderSequence,
                record.remainingQuantity,
                safeActual,
                -1,
                "继续出珠结果不明确，等待人工处理"
        );
        MqttManager.get(context).reportStatus();
    }

    private ValidationResult validateLocalSession(
            String operationNo,
            String operationToken,
            int remainingQuantity
    ) {
        ValidationResult result = new ValidationResult();
        if (store.getBoardVersion() < MIN_CONTROLLER_PROTOCOL_VERSION) {
            return result.fail(
                    "CONTROLLER_PROTOCOL_UNSUPPORTED",
                    "controller protocol 2.2.0.0 is required"
            );
        }

        DeviceCommandStore.ActivePhysicalOrder active =
                store.loadActivePhysicalOrder();
        if (active == null || !"BLOCKED".equals(active.state)) {
            return result.fail(
                    "LOCAL_SESSION_STATE_INVALID",
                    "no blocked original dispense session is retained"
            );
        }
        JSONObject original = store.loadCommand(active.messageId);
        JSONObject originalData = original == null
                ? null
                : original.optJSONObject("data");
        if (original == null
                || originalData == null
                || !FIRST_COMMAND_TYPE.equals(
                original.optString("commandType", ""))) {
            return result.fail(
                    "LOCAL_SESSION_STATE_INVALID",
                    "original dispense command context is missing"
            );
        }
        String originalOperationNo = originalData.optString(
                "operationNo",
                ""
        ).trim();
        if (!operationNo.equals(originalOperationNo)) {
            return result.fail(
                    "OPERATION_MISMATCH",
                    "operationNo does not match the retained dispense session"
            );
        }

        int firstActual = Math.max(
                Math.max(0, active.lastProgressActual),
                Math.max(0, active.terminalActual)
        );
        boolean stockInsufficient = active.terminalResultCode == 2
                && firstActual > 0
                && firstActual < active.requestedQuantity;
        if (!stockInsufficient) {
            return result.fail(
                    "FIRST_RESULT_NOT_CONTINUABLE",
                    "original result is not partial stock insufficiency"
            );
        }
        if (!active.terminalAckEchoed || blank(active.terminalPayload)) {
            return result.fail(
                    "FIRST_DISPENSE_NOT_SETTLED",
                    "original controller terminal has not been safely acknowledged"
            );
        }
        int expectedRemaining = active.requestedQuantity - firstActual;
        if (remainingQuantity != expectedRemaining) {
            return result.fail(
                    "REMAINING_QUANTITY_MISMATCH",
                    "remainingQuantity must equal local remaining quantity "
                            + expectedRemaining
            );
        }

        String originalToken = originalData.optString(
                "operationToken",
                ""
        ).trim();
        if (!blank(originalToken) && originalToken.equals(operationToken)) {
            return result.fail(
                    "OPERATION_TOKEN_REUSED",
                    "continuation must use a new operationToken"
            );
        }
        if (store.isCashBlocked() || isLocalResetRequired()) {
            return result.fail(
                    "MARBLE_STOCK_NOT_REFILLED",
                    "local refill/reset gate has not been cleared"
            );
        }
        if (hasResolutionStarted(operationNo)) {
            return result.fail(
                    "OPERATION_RESOLUTION_STARTED",
                    "manual operation resolution has already started"
            );
        }
        FlowClaim claim = loadClaim(operationNo);
        if (claim != null && FLOW_RESOLUTION.equals(claim.flowType)) {
            return result.fail(
                    "OPERATION_RESOLUTION_STARTED",
                    "manual operation resolution has already claimed this operation"
            );
        }

        result.success = true;
        result.active = active;
        result.firstActual = firstActual;
        return result;
    }

    private PersistAcceptResult persistAccepted(
            JSONObject envelope,
            String sourceTopic,
            String operationNo,
            String continuationNo,
            String operationToken,
            int remainingQuantity,
            DeviceCommandStore.ActivePhysicalOrder active,
            int firstActual,
            SdkCommandDecoder.EncodedResult acknowledgement
    ) {
        PersistAcceptResult result = new PersistAcceptResult();
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (active == null
                        || loadActiveOriginalMessageId(db) == null
                        || !active.messageId.equals(loadActiveOriginalMessageId(db))) {
                    return result.fail(
                            "LOCAL_SESSION_STATE_INVALID",
                            "original dispense session changed before continuation claim"
                    );
                }
                if (loadRecord(
                        db,
                        "operation_no=?",
                        new String[]{operationNo}
                ) != null) {
                    return result.fail(
                            "CONTINUATION_ALREADY_USED",
                            "only one continuation is allowed for an operation"
                    );
                }
                if (hasResolutionStarted(db, operationNo)) {
                    return result.fail(
                            "OPERATION_RESOLUTION_STARTED",
                            "manual operation resolution has already started"
                    );
                }
                FlowClaim claim = loadClaim(db, operationNo);
                if (claim != null) {
                    return result.fail(
                            FLOW_RESOLUTION.equals(claim.flowType)
                                    ? "OPERATION_RESOLUTION_STARTED"
                                    : "CONTINUATION_ALREADY_USED",
                            "operation flow has already been claimed"
                    );
                }

                int orderSequence = allocateOrderSequence(db);
                if (orderSequence <= 0) {
                    return result.fail(
                            "LOCAL_STORAGE_ERROR",
                            "continuation order sequence could not be allocated"
                    );
                }
                if (!saveCommandEnvelope(
                        db,
                        envelope,
                        "continuation_dispensing"
                )) {
                    return result.fail(
                            "LOCAL_STORAGE_ERROR",
                            "continuation command could not be saved"
                    );
                }

                long now = System.currentTimeMillis();
                ContentValues values = new ContentValues();
                values.put("message_id", envelope.optString("messageId", ""));
                values.put("continuation_no", continuationNo);
                values.put("operation_no", operationNo);
                values.put("original_message_id", active.messageId);
                values.put("source_topic", sourceTopic);
                values.put("order_sequence", orderSequence);
                values.put("remaining_quantity", remainingQuantity);
                values.put("first_actual", firstActual);
                values.put("operation_token", operationToken);
                values.put("command_envelope", envelope.toString());
                values.put("state", STATE_DISPENSING);
                values.put("ack_event_no", acknowledgement.eventNo);
                values.put("ack_status", acknowledgement.resultStatus);
                values.put("ack_payload", acknowledgement.payload);
                values.put("created_at", now);
                values.put("updated_at", now);
                if (db.insert(TABLE, null, values) == -1L) {
                    return result.fail(
                            "LOCAL_STORAGE_ERROR",
                            "continuation receipt could not be saved"
                    );
                }
                if (!insertClaim(
                        db,
                        operationNo,
                        FLOW_CONTINUATION,
                        continuationNo,
                        envelope.optString("messageId", ""),
                        CLAIM_ACTIVE
                )) {
                    return result.fail(
                            "LOCAL_STORAGE_ERROR",
                            "continuation operation claim could not be saved"
                    );
                }
                if (!saveOutbox(
                        db,
                        envelope.optString("messageId", "") + "|"
                                + acknowledgement.eventNo + "|"
                                + acknowledgement.resultStatus,
                        envelope.optString("messageId", ""),
                        acknowledgement.eventNo,
                        acknowledgement.resultStatus,
                        acknowledgement.payload
                )) {
                    return result.fail(
                            "LOCAL_STORAGE_ERROR",
                            "continuation acknowledgement could not be queued"
                    );
                }
                putMeta(db, META_PHYSICAL_BLOCKED, "1");
                db.setTransactionSuccessful();
                result.success = true;
                result.orderSequence = orderSequence;
                return result;
            } catch (Throwable error) {
                return result.fail("LOCAL_STORAGE_ERROR", messageOf(error));
            } finally {
                db.endTransaction();
            }
        }
    }

    private void rejectContinuation(
            SdkCommandDecoder.DecodedCommand decoded,
            String operationNo,
            String continuationNo,
            String resultCode,
            String resultMessage
    ) {
        String messageId = decoded.sdkCommand.getMessageId();
        RejectionRecord existing = loadRejectionByMessageId(messageId);
        if (existing != null) {
            replayRejection(existing);
            return;
        }

        try {
            SdkCommandDecoder.EncodedResult ack = decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            SdkCommandDecoder.EncodedResult terminal = decoded.physicalTerminal(
                    messageId + "-result",
                    false,
                    0,
                    resultCode,
                    blank(resultMessage) ? "continue dispense rejected" : resultMessage,
                    System.currentTimeMillis()
            );
            if (!persistRejection(
                    decoded.envelope,
                    operationNo,
                    continuationNo,
                    ack,
                    terminal
            )) {
                reportFault(
                        "LOCAL_STORAGE_ERROR",
                        "继续出珠拒绝回执未能可靠保存：" + messageId
                );
                return;
            }
            MqttManager.get(context).reportCommandResult(ack.payload);
            MqttManager.get(context).reportCommandResult(terminal.payload);
        } catch (Throwable error) {
            reportFault(
                    "CONTINUATION_REJECTION_ENCODE_FAILED",
                    messageOf(error)
            );
        }
    }

    private void rejectResolution(
            SdkCommandDecoder.DecodedCommand decoded,
            String operationNo,
            String resultCode,
            String resultMessage
    ) {
        String messageId = decoded.sdkCommand.getMessageId();
        if (store.hasCommand(messageId)) {
            resendCommandResults(messageId);
            return;
        }
        try {
            SdkCommandDecoder.EncodedResult ack = decoded.acknowledgement(
                    messageId + "-ack",
                    System.currentTimeMillis()
            );
            SdkCommandDecoder.EncodedResult terminal = decoded.genericTerminal(
                    messageId + "-result",
                    false,
                    resultCode,
                    resultMessage,
                    System.currentTimeMillis()
            );
            String ackPayload = addOperationNo(ack.payload, operationNo);
            String terminalPayload = addOperationNo(terminal.payload, operationNo);
            synchronized (DB_LOCK) {
                SQLiteDatabase db = store.getWritableDatabase();
                db.beginTransaction();
                try {
                    if (!saveCommandEnvelope(db, decoded.envelope, "failed")
                            || !saveOutbox(
                            db,
                            messageId + "|" + ack.eventNo + "|" + ack.resultStatus,
                            messageId,
                            ack.eventNo,
                            ack.resultStatus,
                            ackPayload
                    )
                            || !saveOutbox(
                            db,
                            messageId + "|" + terminal.eventNo + "|"
                                    + terminal.resultStatus,
                            messageId,
                            terminal.eventNo,
                            terminal.resultStatus,
                            terminalPayload
                    )) {
                        return;
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            MqttManager.get(context).reportCommandResult(ackPayload);
            MqttManager.get(context).reportCommandResult(terminalPayload);
        } catch (Throwable error) {
            reportFault("LOCAL_STORAGE_ERROR", messageOf(error));
        }
    }

    private boolean persistRejection(
            JSONObject envelope,
            String operationNo,
            String continuationNo,
            SdkCommandDecoder.EncodedResult ack,
            SdkCommandDecoder.EncodedResult terminal
    ) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                String messageId = envelope.optString("messageId", "").trim();
                if (!saveCommandEnvelope(db, envelope, "failed")) {
                    return false;
                }
                ContentValues values = new ContentValues();
                values.put("message_id", messageId);
                values.put("continuation_no", safe(continuationNo));
                values.put("operation_no", safe(operationNo));
                values.put("ack_event_no", ack.eventNo);
                values.put("ack_status", ack.resultStatus);
                values.put("ack_payload", ack.payload);
                values.put("terminal_event_no", terminal.eventNo);
                values.put("terminal_status", terminal.resultStatus);
                values.put("terminal_payload", terminal.payload);
                values.put("created_at", System.currentTimeMillis());
                if (db.insertWithOnConflict(
                        REJECTION_TABLE,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE
                ) == -1L) {
                    RejectionRecord existing = loadRejection(
                            db,
                            "message_id=?",
                            new String[]{messageId}
                    );
                    if (existing == null
                            || !ack.payload.equals(existing.ackPayload)
                            || !terminal.payload.equals(existing.terminalPayload)) {
                        return false;
                    }
                }
                if (!saveOutbox(
                        db,
                        messageId + "|" + ack.eventNo + "|" + ack.resultStatus,
                        messageId,
                        ack.eventNo,
                        ack.resultStatus,
                        ack.payload
                )
                        || !saveOutbox(
                        db,
                        messageId + "|" + terminal.eventNo + "|"
                                + terminal.resultStatus,
                        messageId,
                        terminal.eventNo,
                        terminal.resultStatus,
                        terminal.payload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    private void recoverInterruptedContinuation() {
        ContinuationRecord record = loadCurrentRecord();
        if (record == null
                || !(STATE_DISPENSING.equals(record.state)
                || STATE_TERMINAL_PENDING_ACK.equals(record.state))) {
            return;
        }
        requeueStoredRecord(record);
        if (!blank(record.terminalPayload)) {
            markAwaitingResolution(
                    record,
                    "APP_RESTARTED_BEFORE_TERMINAL_ACK"
            );
            return;
        }
        persistUnknownResult(
                record.messageId,
                record.progressActual,
                "CONTROLLER_TERMINAL_MISSING",
                "应用重启时继续出珠结果尚未明确，禁止自动重放"
        );
    }

    private void markAwaitingResolution(
            ContinuationRecord record,
            String reason
    ) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                values.put("state", STATE_AWAITING_RESOLUTION);
                values.put("blocked_reason", reason);
                values.put("updated_at", System.currentTimeMillis());
                db.update(
                        TABLE,
                        values,
                        "message_id=?",
                        new String[]{record.messageId}
                );
                updateClaimState(
                        db,
                        record.operationNo,
                        CLAIM_AWAITING_RESOLUTION
                );
                putMeta(db, META_PHYSICAL_BLOCKED, "1");
                putMeta(db, META_LOCAL_RESET_REQUIRED, "1");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        broadcastDispenseOrder(
                "blocked",
                record.orderSequence,
                record.remainingQuantity,
                Math.max(record.progressActual, record.terminalActual),
                record.controllerResultCode,
                "继续出珠被应用重启中断，等待人工处理"
        );
    }

    private void replayRecord(ContinuationRecord record) {
        if (!requeueStoredRecord(record)) {
            reportFault(
                    "LOCAL_STORAGE_ERROR",
                    "继续出珠重复回执未能重新进入outbox：" + record.messageId
            );
            return;
        }
        if (!blank(record.ackPayload)) {
            MqttManager.get(context).reportCommandResult(record.ackPayload);
        }
        if (!blank(record.terminalPayload)) {
            MqttManager.get(context).reportCommandResult(record.terminalPayload);
        }
        broadcastCurrentState();
    }

    private void replayRejection(RejectionRecord record) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (!saveOutbox(
                        db,
                        record.messageId + "|" + record.ackEventNo + "|"
                                + record.ackStatus,
                        record.messageId,
                        record.ackEventNo,
                        record.ackStatus,
                        record.ackPayload
                )
                        || !saveOutbox(
                        db,
                        record.messageId + "|" + record.terminalEventNo + "|"
                                + record.terminalStatus,
                        record.messageId,
                        record.terminalEventNo,
                        record.terminalStatus,
                        record.terminalPayload
                )) {
                    return;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        MqttManager.get(context).reportCommandResult(record.ackPayload);
        MqttManager.get(context).reportCommandResult(record.terminalPayload);
    }

    private boolean requeueStoredRecord(ContinuationRecord record) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (!blank(record.ackPayload) && !saveOutbox(
                        db,
                        record.messageId + "|" + record.ackEventNo + "|"
                                + record.ackStatus,
                        record.messageId,
                        record.ackEventNo,
                        record.ackStatus,
                        record.ackPayload
                )) {
                    return false;
                }
                if (!blank(record.terminalPayload) && !saveOutbox(
                        db,
                        record.messageId + "|" + record.terminalEventNo + "|"
                                + record.terminalStatus,
                        record.messageId,
                        record.terminalEventNo,
                        record.terminalStatus,
                        record.terminalPayload
                )) {
                    return false;
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    private void resendCommandResults(String messageId) {
        for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
            if (messageId.equals(item.sourceMessageId)) {
                MqttManager.get(context).reportCommandResult(item.payload);
            }
        }
    }

    private SdkCommandDecoder.DecodedCommand decodeStored(
            ContinuationRecord record
    ) {
        if (record == null || blank(record.sourceTopic)
                || blank(record.commandEnvelope)) {
            return null;
        }
        try {
            return decoder.decode(
                    record.sourceTopic,
                    record.commandEnvelope.getBytes(StandardCharsets.UTF_8),
                    DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
        } catch (Throwable error) {
            reportFault(
                    "CONTINUATION_RECOVERY_CONTEXT_INVALID",
                    messageOf(error)
            );
            return null;
        }
    }

    private int loadOriginalRequestedQuantity(String originalMessageId) {
        DeviceCommandStore.ActivePhysicalOrder active =
                store.loadActivePhysicalOrder();
        return active != null && safe(originalMessageId).equals(active.messageId)
                ? active.requestedQuantity
                : -1;
    }

    private boolean isLocalResetRequired() {
        synchronized (DB_LOCK) {
            try (Cursor cursor = store.getReadableDatabase().query(
                    "meta",
                    new String[]{"value"},
                    "key=?",
                    new String[]{META_LOCAL_RESET_REQUIRED},
                    null,
                    null,
                    null
            )) {
                return cursor.moveToFirst() && "1".equals(cursor.getString(0));
            }
        }
    }

    private boolean hasResolutionStarted(String operationNo) {
        synchronized (DB_LOCK) {
            return hasResolutionStarted(store.getReadableDatabase(), operationNo);
        }
    }

    private static boolean hasResolutionStarted(
            SQLiteDatabase db,
            String operationNo
    ) {
        if (!tableExists(db, RESOLUTION_TABLE)) {
            return false;
        }
        try (Cursor cursor = db.query(
                RESOLUTION_TABLE,
                new String[]{"message_id"},
                "operation_no=?",
                new String[]{operationNo},
                null,
                null,
                null,
                "1"
        )) {
            return cursor.moveToFirst();
        }
    }

    private FlowClaim loadClaim(String operationNo) {
        synchronized (DB_LOCK) {
            return loadClaim(store.getReadableDatabase(), operationNo);
        }
    }

    private static FlowClaim loadClaim(
            SQLiteDatabase db,
            String operationNo
    ) {
        try (Cursor cursor = db.query(
                CLAIM_TABLE,
                new String[]{"flow_type", "flow_no", "message_id", "state"},
                "operation_no=?",
                new String[]{operationNo},
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            FlowClaim claim = new FlowClaim();
            claim.flowType = cursor.getString(0);
            claim.flowNo = cursor.getString(1);
            claim.messageId = cursor.getString(2);
            claim.state = cursor.getString(3);
            return claim;
        }
    }

    private static boolean insertClaim(
            SQLiteDatabase db,
            String operationNo,
            String flowType,
            String flowNo,
            String messageId,
            String state
    ) {
        ContentValues values = new ContentValues();
        values.put("operation_no", operationNo);
        values.put("flow_type", flowType);
        values.put("flow_no", flowNo);
        values.put("message_id", messageId);
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return db.insertWithOnConflict(
                CLAIM_TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L;
    }

    private static void updateClaimState(
            SQLiteDatabase db,
            String operationNo,
            String state
    ) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        db.update(
                CLAIM_TABLE,
                values,
                "operation_no=? AND flow_type=?",
                new String[]{operationNo, FLOW_CONTINUATION}
        );
    }

    private static int allocateOrderSequence(SQLiteDatabase db) {
        int sequence = parsePositiveInt(getMeta(db, META_NEXT_ORDER_SEQUENCE));
        if (sequence <= 0 || sequence > 0xFFFF) {
            sequence = 1;
        }
        int next = sequence >= 0xFFFF ? 1 : sequence + 1;
        putMeta(db, META_NEXT_ORDER_SEQUENCE, String.valueOf(next));
        return sequence;
    }

    private static String loadActiveOriginalMessageId(SQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "active_physical_order",
                new String[]{"message_id"},
                "id=1 AND state='BLOCKED'",
                null,
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private ContinuationRecord loadByMessageId(String messageId) {
        synchronized (DB_LOCK) {
            return loadRecord(
                    store.getReadableDatabase(),
                    "message_id=?",
                    new String[]{safe(messageId)}
            );
        }
    }

    private ContinuationRecord loadByContinuationNo(String continuationNo) {
        synchronized (DB_LOCK) {
            return loadRecord(
                    store.getReadableDatabase(),
                    "continuation_no=?",
                    new String[]{safe(continuationNo)}
            );
        }
    }

    private ContinuationRecord loadByOperationNo(String operationNo) {
        synchronized (DB_LOCK) {
            return loadRecord(
                    store.getReadableDatabase(),
                    "operation_no=?",
                    new String[]{safe(operationNo)}
            );
        }
    }

    private ContinuationRecord loadCurrentRecord() {
        synchronized (DB_LOCK) {
            return loadRecord(
                    store.getReadableDatabase(),
                    "state<>?",
                    new String[]{STATE_COMPLETED}
            );
        }
    }

    private static ContinuationRecord loadRecord(
            SQLiteDatabase db,
            String selection,
            String[] selectionArgs
    ) {
        try (Cursor cursor = db.query(
                TABLE,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "updated_at DESC",
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ContinuationRecord record = new ContinuationRecord();
            record.messageId = getString(cursor, "message_id");
            record.continuationNo = getString(cursor, "continuation_no");
            record.operationNo = getString(cursor, "operation_no");
            record.originalMessageId = getString(cursor, "original_message_id");
            record.sourceTopic = getString(cursor, "source_topic");
            record.orderSequence = getInt(cursor, "order_sequence");
            record.remainingQuantity = getInt(cursor, "remaining_quantity");
            record.firstActual = getInt(cursor, "first_actual");
            record.commandEnvelope = getString(cursor, "command_envelope");
            record.state = getString(cursor, "state");
            record.progressActual = getInt(cursor, "progress_actual");
            record.ackEventNo = getString(cursor, "ack_event_no");
            record.ackStatus = getString(cursor, "ack_status");
            record.ackPayload = getString(cursor, "ack_payload");
            record.terminalEventNo = getString(cursor, "terminal_event_no");
            record.terminalStatus = getString(cursor, "terminal_status");
            record.terminalPayload = getString(cursor, "terminal_payload");
            record.terminalActual = getInt(cursor, "terminal_actual");
            record.terminalSuccess = getInt(cursor, "terminal_success") != 0;
            record.terminalFrameId = getInt(cursor, "terminal_frame_id", -1);
            record.controllerResultCode = getInt(
                    cursor,
                    "controller_result_code",
                    -1
            );
            record.blockedReason = getString(cursor, "blocked_reason");
            return record;
        }
    }

    private RejectionRecord loadRejectionByMessageId(String messageId) {
        synchronized (DB_LOCK) {
            return loadRejection(
                    store.getReadableDatabase(),
                    "message_id=?",
                    new String[]{safe(messageId)}
            );
        }
    }

    private RejectionRecord loadRejectionByContinuationNo(String continuationNo) {
        if (blank(continuationNo)) {
            return null;
        }
        synchronized (DB_LOCK) {
            return loadRejection(
                    store.getReadableDatabase(),
                    "continuation_no=?",
                    new String[]{continuationNo}
            );
        }
    }

    private static RejectionRecord loadRejection(
            SQLiteDatabase db,
            String selection,
            String[] selectionArgs
    ) {
        try (Cursor cursor = db.query(
                REJECTION_TABLE,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "created_at DESC",
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            RejectionRecord record = new RejectionRecord();
            record.messageId = getString(cursor, "message_id");
            record.ackEventNo = getString(cursor, "ack_event_no");
            record.ackStatus = getString(cursor, "ack_status");
            record.ackPayload = getString(cursor, "ack_payload");
            record.terminalEventNo = getString(cursor, "terminal_event_no");
            record.terminalStatus = getString(cursor, "terminal_status");
            record.terminalPayload = getString(cursor, "terminal_payload");
            return record;
        }
    }

    private void ensureSchema() {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "message_id TEXT PRIMARY KEY,"
                    + "continuation_no TEXT NOT NULL UNIQUE,"
                    + "operation_no TEXT NOT NULL UNIQUE,"
                    + "original_message_id TEXT NOT NULL,"
                    + "source_topic TEXT NOT NULL,"
                    + "order_sequence INTEGER NOT NULL,"
                    + "remaining_quantity INTEGER NOT NULL,"
                    + "first_actual INTEGER NOT NULL,"
                    + "operation_token TEXT NOT NULL,"
                    + "command_envelope TEXT NOT NULL,"
                    + "state TEXT NOT NULL,"
                    + "progress_actual INTEGER NOT NULL DEFAULT 0,"
                    + "ack_event_no TEXT NOT NULL,"
                    + "ack_status TEXT NOT NULL,"
                    + "ack_payload TEXT NOT NULL,"
                    + "terminal_event_no TEXT,"
                    + "terminal_status TEXT,"
                    + "terminal_payload TEXT,"
                    + "terminal_actual INTEGER NOT NULL DEFAULT 0,"
                    + "terminal_success INTEGER NOT NULL DEFAULT 0,"
                    + "terminal_frame_id INTEGER,"
                    + "controller_result_code INTEGER,"
                    + "terminal_ack_sent INTEGER NOT NULL DEFAULT 0,"
                    + "terminal_ack_echoed INTEGER NOT NULL DEFAULT 0,"
                    + "blocked_reason TEXT NOT NULL DEFAULT '',"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + REJECTION_TABLE + " ("
                    + "message_id TEXT PRIMARY KEY,"
                    + "continuation_no TEXT NOT NULL DEFAULT '',"
                    + "operation_no TEXT NOT NULL DEFAULT '',"
                    + "ack_event_no TEXT NOT NULL,"
                    + "ack_status TEXT NOT NULL,"
                    + "ack_payload TEXT NOT NULL,"
                    + "terminal_event_no TEXT NOT NULL,"
                    + "terminal_status TEXT NOT NULL,"
                    + "terminal_payload TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "idx_continuation_rejection_no ON " + REJECTION_TABLE
                    + "(continuation_no) WHERE continuation_no<>''");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + CLAIM_TABLE + " ("
                    + "operation_no TEXT PRIMARY KEY,"
                    + "flow_type TEXT NOT NULL,"
                    + "flow_no TEXT NOT NULL,"
                    + "message_id TEXT NOT NULL,"
                    + "state TEXT NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");
        }
    }

    private static boolean saveCommandEnvelope(
            SQLiteDatabase db,
            JSONObject envelope,
            String state
    ) {
        String messageId = envelope == null
                ? ""
                : envelope.optString("messageId", "").trim();
        if (blank(messageId)) {
            return false;
        }
        try (Cursor cursor = db.query(
                "commands",
                new String[]{"envelope"},
                "message_id=?",
                new String[]{messageId},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return envelope.toString().equals(cursor.getString(0));
            }
        }
        ContentValues values = new ContentValues();
        values.put("message_id", messageId);
        values.put("envelope", envelope.toString());
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return db.insert("commands", null, values) != -1L;
    }

    private static boolean updateCommandState(
            SQLiteDatabase db,
            String messageId,
            String state
    ) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return db.update(
                "commands",
                values,
                "message_id=?",
                new String[]{messageId}
        ) == 1;
    }

    private static boolean saveOutbox(
            SQLiteDatabase db,
            String receiptKey,
            String sourceMessageId,
            String eventNo,
            String status,
            String payload
    ) {
        ContentValues values = new ContentValues();
        values.put("receipt_key", receiptKey);
        values.put("kind", "command_result");
        values.put("source_message_id", sourceMessageId);
        values.put("event_no", eventNo);
        values.put("result_status", status);
        values.put("payload", payload);
        values.put("created_at", System.currentTimeMillis());
        long inserted = db.insertWithOnConflict(
                "outbox",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
        if (inserted != -1L) {
            return true;
        }
        try (Cursor cursor = db.query(
                "outbox",
                new String[]{"payload"},
                "receipt_key=?",
                new String[]{receiptKey},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() && payload.equals(cursor.getString(0));
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.query(
                "sqlite_master",
                new String[]{"name"},
                "type='table' AND name=?",
                new String[]{table},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst();
        }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", safe(value));
        db.insertWithOnConflict(
                "meta",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    private static String getMeta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                "meta",
                new String[]{"value"},
                "key=?",
                new String[]{key},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? safe(cursor.getString(0)) : "";
        }
    }

    private static String addOperationNo(String payload, String operationNo) {
        try {
            JSONObject json = new JSONObject(payload);
            json.put("operationNo", operationNo);
            return json.toString();
        } catch (Throwable error) {
            throw new IllegalArgumentException(messageOf(error), error);
        }
    }

    private void broadcastDispenseOrder(
            String eventType,
            int orderSequence,
            int requestedQuantity,
            int actualQuantity,
            int resultCode,
            String message
    ) {
        Intent intent = new Intent(AppConfig.ACTION_DISPENSE_ORDER_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra("eventType", safe(eventType));
        intent.putExtra("orderSequence", orderSequence);
        intent.putExtra("requestedQuantity", requestedQuantity);
        intent.putExtra("actualQuantity", actualQuantity);
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("message", safe(message));
        context.sendBroadcast(intent);
    }

    private void reportFault(String code, String description) {
        Log.e(TAG, code + ": " + description);
        MqttManager.get(context).reportFault(
                code,
                "continue marble dispense failed",
                3,
                description
        );
    }

    private static JSONObject parseEnvelope(byte[] payload) {
        try {
            return new JSONObject(new String(
                    payload == null ? new byte[0] : payload,
                    StandardCharsets.UTF_8
            ));
        } catch (Throwable error) {
            return null;
        }
    }

    private static String getString(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? "" : safe(cursor.getString(index));
    }

    private static int getInt(Cursor cursor, String column) {
        return getInt(cursor, column, 0);
    }

    private static int getInt(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static int parsePositiveInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(safe(value)));
        } catch (Throwable error) {
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static final class ValidationResult {
        boolean success;
        String resultCode;
        String resultMessage;
        DeviceCommandStore.ActivePhysicalOrder active;
        int firstActual;

        ValidationResult fail(String code, String message) {
            success = false;
            resultCode = code;
            resultMessage = message;
            return this;
        }
    }

    private static final class PersistAcceptResult {
        boolean success;
        int orderSequence;
        String resultCode;
        String resultMessage;

        PersistAcceptResult fail(String code, String message) {
            success = false;
            resultCode = code;
            resultMessage = message;
            return this;
        }
    }

    private static final class ContinuationRecord {
        String messageId;
        String continuationNo;
        String operationNo;
        String originalMessageId;
        String sourceTopic;
        int orderSequence;
        int remainingQuantity;
        int firstActual;
        String commandEnvelope;
        String state;
        int progressActual;
        String ackEventNo;
        String ackStatus;
        String ackPayload;
        String terminalEventNo;
        String terminalStatus;
        String terminalPayload;
        int terminalActual;
        boolean terminalSuccess;
        int terminalFrameId = -1;
        int controllerResultCode = -1;
        String blockedReason;
    }

    private static final class RejectionRecord {
        String messageId;
        String ackEventNo;
        String ackStatus;
        String ackPayload;
        String terminalEventNo;
        String terminalStatus;
        String terminalPayload;
    }

    private static final class FlowClaim {
        String flowType;
        String flowNo;
        String messageId;
        String state;
    }
}
