package com.gouzhu.mqtt;

import android.content.Context;
import android.database.Cursor;

import com.gouzhu.payment.PaymentManager;
import com.gouzhu.redemption.MemberWithdrawalManager;
import com.gouzhu.redemption.ThirdPartyRedemptionManager;
import com.gouzhu.serial.BoardConnectionMonitor;
import com.gouzhu.transaction.CashTransactionIsolation;
import com.gouzhu.transaction.TransactionIdleCashRestorer;
import com.gouzhu.transaction.TransactionOccupancyManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Platform command facade with one persisted device-wide transaction occupancy lock.
 */
public final class DeviceCommandManager {

    public static final String EXTRA_COLLECTION_EVENT = "collectionEvent";
    public static final String EXTRA_COLLECTION_MESSAGE = "collectionMessage";

    public static final String COLLECTION_READY = "ready";
    public static final String COLLECTION_STARTED = "started";
    public static final String COLLECTION_PROGRESS = "progress";
    public static final String COLLECTION_FINISHED = "finished";
    public static final String COLLECTION_FAILED = "failed";

    private static final long MIN_COLLECTION_STATE_VERSION = 0x02020002L;

    private static volatile DeviceCommandManager instance;

    private final Context context;
    private final PlatformCommandRuntime runtime;
    private final CashConfigurationCommandCoordinator cashConfigurationCoordinator;
    private final OperationResolutionManager resolutionManager;
    private final ContinuationDispenseManager continuationManager;
    private final CollectionSessionManager collectionManager;
    private final CollectionControllerStateGuard collectionControllerGuard;
    private final TransactionOccupancyManager occupancy;
    private final TransactionIdleCashRestorer idleCashRestorer;
    private final DeviceCommandStore store;

    private volatile boolean continuationReady;

    private DeviceCommandManager(Context context) {
        this.context = context.getApplicationContext();
        runtime = new PlatformCommandRuntime(this.context);
        cashConfigurationCoordinator = new CashConfigurationCommandCoordinator(this.context);
        resolutionManager = new OperationResolutionManager(this.context);
        continuationManager = new ContinuationDispenseManager(this.context);
        collectionManager = new CollectionSessionManager(this.context);
        collectionControllerGuard = new CollectionControllerStateGuard(this.context);
        occupancy = TransactionOccupancyManager.get(this.context);
        idleCashRestorer = new TransactionIdleCashRestorer(this.context);
        store = new DeviceCommandStore(this.context);
    }

    public static DeviceCommandManager get(Context context) {
        if (instance == null) {
            synchronized (DeviceCommandManager.class) {
                if (instance == null) {
                    instance = new DeviceCommandManager(context);
                }
            }
        }
        return instance;
    }

    public void start() {
        occupancy.start();
        idleCashRestorer.start();
        continuationManager.start();
        continuationReady = true;
        resolutionManager.start();
        collectionManager.start();
        collectionControllerGuard.start();
        // 先让旧运行时完成控制板版本确认和旧pending收敛，再启动新的现金配置账本。
        runtime.start();
        cashConfigurationCoordinator.start();
        CashRuntimeCoordinator.get(context).reconcile("device_command_manager_started");
        // Recovery must not depend on MainActivity being visible. The persisted requestNo
        // remains authoritative and the same QR purchase is queried/recreated idempotently.
        PaymentManager.get(context).resumePendingPayment();
        MemberWithdrawalManager.get(context).resumePending();
        ThirdPartyRedemptionManager.get(context).resumePending();
    }

    public void stop() {
        cashConfigurationCoordinator.stop();
        runtime.stop();
        collectionControllerGuard.stop();
        collectionManager.stop();
        resolutionManager.stop();
        continuationManager.stop();
        continuationReady = false;
        idleCashRestorer.stop();
        occupancy.stop();
    }

    /**
     * 同步完成继续出珠与人工结案的本地流程占用，再把耗时硬件动作交给各自执行器。
     * 这样两个互斥指令即使连续到达，也不会同时进入物理执行或本地结案事务。
     */
    public synchronized void handleCommand(String topic, byte[] payload) {
        if (collectionManager.handlesResolution(payload)) {
            collectionManager.handleResolution(topic, payload);
            return;
        }
        if (continuationManager.handles(payload)) {
            continuationManager.handleCommand(topic, payload);
            return;
        }
        if (resolutionManager.handles(payload)) {
            /*
             * 只有字段完整且匹配本地BLOCKED首轮会话，或已经存在继续出珠流程占用时，
             * 才交给继续模块做互斥判定。无效指令仍由原结案管理器返回失败结果，
             * 不会因为预占过早而永久阻止后续合法继续出珠。
             */
            if (!shouldClaimMarbleResolution(payload)
                    || continuationManager.prepareResolution(topic, payload)) {
                resolutionManager.handleCommand(topic, payload);
            }
            return;
        }
        if (collectionManager.handlesCollect(payload)) {
            if (store.getBoardVersion() < MIN_COLLECTION_STATE_VERSION) {
                rejectCommand(
                        topic,
                        payload,
                        "CONTROLLER_PROTOCOL_UNSUPPORTED",
                        "member deposit requires controller protocol 2.2.0.2"
                );
            } else {
                collectionManager.handleCollect(topic, payload);
            }
            return;
        }

        JSONObject envelope = parseEnvelope(payload);
        String commandType = envelope == null
                ? ""
                : envelope.optString("commandType", "");
        if ("cash_event_response".equals(commandType)) {
            runtime.handleCommand(topic, payload);
            JSONObject data = envelope.optJSONObject("data");
            occupancy.onCashEventResponse(
                    data == null ? "" : data.optString("status", "")
            );
            return;
        }
        if ("sync_cash_configuration".equals(commandType)) {
            /*
             * 现金配置不再因为正常交易占用立即回失败，也不再回退失败版本号。
             * 新处理器负责 messageId 幂等、highestKnownVersion、同版本内容冲突、
             * durable ACK/终态以及占用释放后的串行真实应用。
             */
            cashConfigurationCoordinator.handleCommand(topic, payload);
            return;
        }
        if (!"dispense_marbles".equals(commandType)) {
            runtime.handleCommand(topic, payload);
            return;
        }

        String messageId = envelope.optString("messageId", "").trim();
        JSONObject data = envelope.optJSONObject("data");
        String operationNo = data == null
                ? ""
                : data.optString("operationNo", "").trim();
        TransactionOccupancyManager.DispenseReservation reservation =
                occupancy.reserveDispense(messageId, operationNo);
        if (!reservation.allowed) {
            rejectCommand(
                    topic,
                    payload,
                    reservation.resultCode == null || reservation.resultCode.isEmpty()
                            ? "DEVICE_TRANSACTION_OCCUPIED"
                            : reservation.resultCode,
                    reservation.reason
            );
            return;
        }

        // 获得物理出珠占用后，必须等控制板明确确认现金掩码=0，才能启动出珠硬件。
        if (reservation.current == null
                || !CashTransactionIsolation.confirmDisabled(
                context,
                reservation.current.sessionId
        )) {
            occupancy.rollbackDispense(reservation);
            rejectCommand(
                    topic,
                    payload,
                    "CASH_ISOLATION_FAILED",
                    "cash hardware could not be confirmed disabled before dispensing"
            );
            return;
        }

        runtime.handleCommand(topic, payload);
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active != null && messageId.equals(active.messageId)) {
            occupancy.commitDispense(reservation);
        } else {
            occupancy.rollbackDispense(reservation);
        }
    }

    public boolean startPendingCollection() {
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot == null
                || !TransactionOccupancyManager.OWNER_MEMBER_DEPOSIT.equals(snapshot.ownerType)) {
            return false;
        }
        // 存珠电机启动前同样要求控制板确认纸钞机/硬币器已经停止接收现金。
        if (!CashTransactionIsolation.confirmDisabled(context, snapshot.sessionId)) {
            return false;
        }
        return collectionManager.startPendingCollection();
    }

    public boolean finishPendingCollection() {
        return collectionManager.finishPendingCollection();
    }

    public boolean hasPendingCollection() {
        return collectionManager.hasPendingCollection();
    }

    public int getRunningStatus() {
        BoardConnectionMonitor boardMonitor = BoardConnectionMonitor.get(context);
        if (boardMonitor.isStateKnown() && !boardMonitor.isConnected()) {
            return 2;
        }
        // 人工结案删除原暂停会话后，历史继续记录不得继续占用运行状态。
        if (continuationReady && store.hasActivePhysicalOrder()) {
            int continuationStatus = continuationManager.getRunningStatus();
            if (continuationStatus != 0) {
                return continuationStatus;
            }
        }
        TransactionOccupancyManager.Snapshot snapshot = occupancy.current();
        if (snapshot != null) {
            if (TransactionOccupancyManager.PHASE_BLOCKED.equals(snapshot.phase)
                    || TransactionOccupancyManager.PHASE_REFUNDING.equals(snapshot.phase)) {
                return 2;
            }
            return 1;
        }
        return resolutionManager.getRunningStatus();
    }

    public void requestActivePhysicalOrderState() {
        runtime.broadcastActivePhysicalOrderState();
        collectionManager.broadcastCurrentState();
        /*
         * 原首轮会话仍保留且继续模块已完成建表/恢复时，才读取继续动作状态。
         * 避免Activity早于DeviceService启动时访问尚未创建的继续出珠表。
         */
        if (continuationReady && store.hasActivePhysicalOrder()) {
            continuationManager.broadcastCurrentState();
        }
    }

    public void flushPending() {
        runtime.flushPending();
    }

    void resumeDeferredCashConfiguration() {
        cashConfigurationCoordinator.resumeDeferredIfPossible();
    }

    private boolean shouldClaimMarbleResolution(byte[] payload) {
        JSONObject envelope = parseEnvelope(payload);
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        String messageId = envelope == null
                ? ""
                : envelope.optString("messageId", "").trim();
        String operationNo = data == null
                ? ""
                : data.optString("operationNo", "").trim();
        String resolutionNo = data == null
                ? ""
                : data.optString("resolutionNo", "").trim();
        String resolutionType = data == null
                ? ""
                : data.optString("resolutionType", "").trim();
        int settledQuantity = data == null
                ? -1
                : data.optInt("settledQuantity", -1);
        String resolvedAt = data == null
                ? ""
                : data.optString("resolvedAt", "").trim();
        if (messageId.isEmpty()
                || operationNo.isEmpty()
                || resolutionNo.isEmpty()
                || !isSupportedResolutionType(resolutionType)
                || settledQuantity < 0
                || resolvedAt.isEmpty()) {
            return false;
        }

        // 已有继续流程时，即使原会话已完成，也必须由继续模块拒绝迟到的人工结案。
        if (hasContinuationFlowClaim(operationNo)) {
            return true;
        }

        DeviceCommandStore.ActivePhysicalOrder active =
                store.loadActivePhysicalOrder();
        if (active == null || !"BLOCKED".equals(active.state)) {
            return false;
        }
        JSONObject original = store.loadCommand(active.messageId);
        JSONObject originalData = original == null
                ? null
                : original.optJSONObject("data");
        return original != null
                && "dispense_marbles".equals(
                original.optString("commandType", ""))
                && originalData != null
                && operationNo.equals(
                originalData.optString("operationNo", "").trim()
        );
    }

    private boolean hasContinuationFlowClaim(String operationNo) {
        if (!continuationReady || operationNo == null || operationNo.trim().isEmpty()) {
            return false;
        }
        try (Cursor cursor = store.getReadableDatabase().query(
                "operation_flow_claims",
                new String[]{"operation_no"},
                "operation_no=? AND flow_type=?",
                new String[]{operationNo, "CONTINUATION"},
                null,
                null,
                null,
                "1"
        )) {
            return cursor.moveToFirst();
        } catch (Throwable ignored) {
            // 继续模块尚未完成建表时不提前占用，交给原人工结案流程处理。
            return false;
        }
    }

    private static boolean isSupportedResolutionType(String value) {
        return "manual_settlement".equals(value)
                || "offline_cash_refund".equals(value)
                || "offline_marble_delivery".equals(value)
                || "device_cash_return".equals(value)
                || "accept_actual_delivery".equals(value);
    }

    private void rejectCommand(
            String topic,
            byte[] payload,
            String resultCode,
            String reason
    ) {
        try {
            SdkCommandDecoder.DecodedCommand decoded = new SdkCommandDecoder().decode(
                    topic,
                    payload,
                    com.gouzhu.util.DeviceUtil.requireDeviceNo(context),
                    System.currentTimeMillis()
            );
            if (store.hasCommand(decoded.sdkCommand.getMessageId())) {
                for (DeviceCommandStore.OutboxItem item : store.listCommandResults()) {
                    if (decoded.sdkCommand.getMessageId().equals(item.sourceMessageId)) {
                        MqttManager.get(context).reportCommandResult(item.payload);
                    }
                }
                return;
            }
            SdkCommandDecoder.EncodedResult terminal = decoded.genericTerminal(
                    decoded.sdkCommand.getMessageId() + "-result",
                    false,
                    resultCode,
                    reason == null || reason.trim().isEmpty()
                            ? "device is processing another transaction"
                            : reason,
                    System.currentTimeMillis()
            );
            if (store.saveCommand(decoded.envelope)
                    && store.saveCommandResult(
                    terminal.sourceMessageId,
                    terminal.eventNo,
                    terminal.resultStatus,
                    terminal.payload
            )) {
                MqttManager.get(context).reportCommandResult(terminal.payload);
            }
        } catch (Throwable error) {
            MqttManager.get(context).reportFault(
                    resultCode,
                    "rejected command could not be encoded",
                    3,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
            );
        }
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
}
