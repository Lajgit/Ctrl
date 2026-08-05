package com.gouzhu.mqtt;

import android.content.Context;

import com.gouzhu.payment.PaymentManager;
import com.gouzhu.serial.BoardConnectionMonitor;
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
    private final OperationResolutionManager resolutionManager;
    private final CollectionSessionManager collectionManager;
    private final CollectionControllerStateGuard collectionControllerGuard;
    private final TransactionOccupancyManager occupancy;
    private final TransactionIdleCashRestorer idleCashRestorer;
    private final DeviceCommandStore store;

    private DeviceCommandManager(Context context) {
        this.context = context.getApplicationContext();
        runtime = new PlatformCommandRuntime(this.context);
        resolutionManager = new OperationResolutionManager(this.context);
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
        resolutionManager.start();
        collectionManager.start();
        collectionControllerGuard.start();
        runtime.start();
        // Recovery must not depend on MainActivity being visible. The persisted requestNo
        // remains authoritative and the same QR purchase is queried/recreated idempotently.
        PaymentManager.get(context).resumePendingPayment();
    }

    public void stop() {
        runtime.stop();
        collectionControllerGuard.stop();
        collectionManager.stop();
        resolutionManager.stop();
        idleCashRestorer.stop();
        occupancy.stop();
    }

    public void handleCommand(String topic, byte[] payload) {
        if (collectionManager.handlesResolution(payload)) {
            collectionManager.handleResolution(topic, payload);
            return;
        }
        if (resolutionManager.handles(payload)) {
            resolutionManager.handleCommand(topic, payload);
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
            JSONObject data = envelope.optJSONObject("data");
            boolean enablesCash = data != null
                    && data.optBoolean("cashAcceptanceEnabled", false);
            TransactionOccupancyManager.Snapshot occupied = occupancy.current();
            if (enablesCash && occupied != null) {
                rejectCommand(
                        topic,
                        payload,
                        "DEVICE_TRANSACTION_OCCUPIED",
                        "cash cannot be enabled while "
                                + occupied.ownerType + " is " + occupied.phase
                );
                return;
            }
            runtime.handleCommand(topic, payload);
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

        runtime.handleCommand(topic, payload);
        DeviceCommandStore.ActivePhysicalOrder active = store.loadActivePhysicalOrder();
        if (active != null && messageId.equals(active.messageId)) {
            occupancy.commitDispense(reservation);
        } else {
            occupancy.rollbackDispense(reservation);
        }
    }

    public boolean startPendingCollection() {
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
    }

    public void flushPending() {
        runtime.flushPending();
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
