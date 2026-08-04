package com.gouzhu.hardware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.serial.SerialManager;
import com.pinball.xiaoda.device.sdk.hardware.CollectRequest;
import com.pinball.xiaoda.device.sdk.hardware.DispenseRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.hardware.MarbleHardwareAdapter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ttyS5 hardware adapter for the single active dispense order protocol.
 */
public final class SerialMarbleHardwareAdapter implements MarbleHardwareAdapter {

    private static final String TAG = "GouzhuHardwareV22";

    private static final int CMD_DISPENSE_START_ORDER = 0x30;
    private static final int CMD_DISPENSE_TERMINAL_ACK = 0x31;
    private static final int CMD_EMERGENCY_STOP = 0xFF;

    private static final int EVT_DISPENSE_PROGRESS = 0x40;
    private static final int EVT_DISPENSE_TERMINAL = 0x41;

    private static final long START_ECHO_TIMEOUT_MS = 2500L;
    private static final long TERMINAL_ACK_ECHO_TIMEOUT_MS = 2500L;
    private static final long DISPENSE_MAX_WAIT_MS = 5L * 60L * 1000L;

    private final Context context;
    private final Object operationLock = new Object();

    private volatile PendingOperation pending;
    private volatile Observer observer;
    private boolean receiverRegistered;

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                return;
            }
            onBoardEvent(
                    intent.getIntExtra("frameId", -1),
                    intent.getIntExtra("code2", -1),
                    intent.getLongExtra("data", 0L),
                    intent.getIntExtra("expandCode", 0)
            );
        }
    };

    public SerialMarbleHardwareAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(Observer observer) {
        this.observer = observer;
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(boardReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(boardReceiver, filter);
        }
        receiverRegistered = true;
    }

    public synchronized void stop() {
        PendingOperation active = pending;
        if (active != null) {
            active.cancelled = true;
            active.latch.countDown();
        }
        pending = null;
        observer = null;
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(boardReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
    }

    @Override
    public HardwareExecutionResult dispense(DispenseRequest request) {
        return HardwareExecutionResult.failed(
                0,
                "ORDER_SEQUENCE_REQUIRED",
                "single-order protocol requires an allocated orderSequence"
        );
    }

    public HardwareExecutionResult dispenseOrder(
            DispenseRequest request,
            int orderSequence
    ) {
        if (request == null) {
            return HardwareExecutionResult.failed(0, "PARAM_INVALID", "dispense request is null");
        }
        int quantity = request.getQuantity();
        if (orderSequence <= 0 || orderSequence > 0xFFFF) {
            return HardwareExecutionResult.failed(0, "PARAM_INVALID", "orderSequence must be 1..65535");
        }
        if (quantity <= 0 || quantity > 0xFFFF) {
            return HardwareExecutionResult.failed(0, "PARAM_INVALID", "quantity must be 1..65535");
        }
        return execute(safe(request.getMessageId()), orderSequence, quantity);
    }

    @Override
    public HardwareExecutionResult collect(CollectRequest request) {
        return HardwareExecutionResult.failed(
                0,
                "COLLECT_MAINTENANCE_ONLY",
                "collect is a local maintenance function in the single-order protocol"
        );
    }

    public boolean stopCollect(String messageId) {
        return false;
    }

    public void emergencyStop() {
        SerialManager.get(context).sendCommand(CMD_EMERGENCY_STOP, 0L, true);
    }

    private HardwareExecutionResult execute(
            String messageId,
            int orderSequence,
            int quantity
    ) {
        synchronized (operationLock) {
            if (pending != null) {
                return HardwareExecutionResult.failed(
                        0,
                        "DEVICE_BUSY",
                        "another physical order is active"
                );
            }

            PendingOperation operation = new PendingOperation();
            operation.messageId = messageId;
            operation.orderSequence = orderSequence;
            operation.requested = quantity;
            pending = operation;

            try {
                long startData = packOrderData(orderSequence, quantity);
                boolean startEchoed = SerialManager.get(context).sendCommandAndWaitEcho(
                        CMD_DISPENSE_START_ORDER,
                        startData,
                        START_ECHO_TIMEOUT_MS
                );
                if (!startEchoed) {
                    Log.w(TAG, "DispenseStartOrder echo timeout, waiting for terminal: seq="
                            + orderSequence);
                }

                if (!operation.latch.await(DISPENSE_MAX_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    return HardwareExecutionResult.failed(
                            operation.lastProgressActual,
                            "CONTROLLER_RESULT_TIMEOUT",
                            "controller did not return DispenseTerminal"
                    );
                }
                if (operation.cancelled) {
                    return HardwareExecutionResult.failed(
                            operation.lastProgressActual,
                            "ADAPTER_STOPPED",
                            "hardware adapter stopped"
                    );
                }
                ControllerTerminalEvidence evidence = operation.terminalEvidence;
                if (evidence == null) {
                    return HardwareExecutionResult.failed(
                            operation.lastProgressActual,
                            "CONTROLLER_TERMINAL_MISSING",
                            "terminal evidence is missing"
                    );
                }

                long ackData = packTerminalAckData(orderSequence, evidence.frameId);
                boolean ackEchoed = SerialManager.get(context).sendCommandAndWaitEcho(
                        CMD_DISPENSE_TERMINAL_ACK,
                        ackData,
                        TERMINAL_ACK_ECHO_TIMEOUT_MS
                );
                Observer currentObserver = observer;
                if (currentObserver != null) {
                    currentObserver.onTerminalAckEcho(messageId, evidence, ackEchoed);
                }
                if (!ackEchoed) {
                    return HardwareExecutionResult.failed(
                            finalActual(operation),
                            "CONTROLLER_TERMINAL_ACK_TIMEOUT",
                            "DispenseTerminalAck echo timeout"
                    );
                }

                if (evidence.controllerResultCode == 0
                        && evidence.terminalActual > 0
                        && evidence.terminalActual == quantity) {
                    return HardwareExecutionResult.success(evidence.terminalActual);
                }
                if (evidence.terminalActual < evidence.lastProgressActual) {
                    return HardwareExecutionResult.failed(
                            evidence.lastProgressActual,
                            "CONTROLLER_ACTUAL_REGRESSION",
                            "terminal actual is lower than last progress actual"
                    );
                }
                return HardwareExecutionResult.failed(
                        finalActual(operation),
                        boardResultName(evidence.controllerResultCode),
                        "controller terminal is failed, zero, or partial"
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return HardwareExecutionResult.failed(
                        operation.lastProgressActual,
                        "INTERRUPTED",
                        "interrupted while waiting for controller terminal"
                );
            } finally {
                pending = null;
            }
        }
    }

    private void onBoardEvent(int frameId, int code2, long packed, int expandCode) {
        PendingOperation operation = pending;
        if (operation == null) {
            return;
        }

        int orderSequence = (int) ((packed >>> 16) & 0xFFFF);
        int value = (int) (packed & 0xFFFF);
        if (orderSequence != operation.orderSequence) {
            return;
        }

        if (code2 == EVT_DISPENSE_PROGRESS) {
            if (value < operation.lastProgressActual || value > operation.requested) {
                Log.w(TAG, "Ignoring invalid progress: seq=" + orderSequence
                        + ", actual=" + value
                        + ", last=" + operation.lastProgressActual
                        + ", requested=" + operation.requested);
                return;
            }
            operation.lastProgressActual = value;
            Observer currentObserver = observer;
            if (currentObserver != null) {
                currentObserver.onProgress(operation.messageId, orderSequence, value);
            }
            return;
        }

        if (code2 != EVT_DISPENSE_TERMINAL || frameId < 0 || frameId > 0xFF) {
            return;
        }

        ControllerTerminalEvidence evidence = new ControllerTerminalEvidence(
                frameId,
                orderSequence,
                value,
                expandCode & 0xFF,
                operation.lastProgressActual,
                System.currentTimeMillis()
        );

        Observer currentObserver = observer;
        boolean persisted = currentObserver != null
                && currentObserver.onTerminalEvidence(operation.messageId, evidence);
        if (!persisted) {
            return;
        }

        operation.terminalEvidence = evidence;
        operation.latch.countDown();
    }

    private static long packOrderData(int orderSequence, int value) {
        return ((long) (orderSequence & 0xFFFF) << 16)
                | (long) (value & 0xFFFF);
    }

    private static long packTerminalAckData(int orderSequence, int frameId) {
        return ((long) (orderSequence & 0xFFFF) << 16)
                | ((long) (frameId & 0xFF) << 8);
    }

    private static int finalActual(PendingOperation operation) {
        ControllerTerminalEvidence evidence = operation.terminalEvidence;
        if (evidence == null) {
            return Math.max(0, operation.lastProgressActual);
        }
        return Math.max(evidence.lastProgressActual, evidence.terminalActual);
    }

    public static String boardResultName(int code) {
        switch (code) {
            case 0:
                return "OK";
            case 1:
                return "CONTROLLER_BUSY";
            case 2:
                return "NO_MARBLES";
            case 3:
                return "INVALID_QUANTITY";
            case 4:
                return "SENSOR_TIMEOUT";
            case 5:
                return "ABORTED";
            case 6:
                return "NOT_ACTIVE";
            case 7:
                return "CONTROLLER_BLOCKED";
            case 8:
                return "ORDER_SEQUENCE_MISMATCH";
            default:
                return "CONTROLLER_ERROR_" + code;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class PendingOperation {
        final CountDownLatch latch = new CountDownLatch(1);
        String messageId;
        int orderSequence;
        int requested;
        int lastProgressActual;
        volatile ControllerTerminalEvidence terminalEvidence;
        volatile boolean cancelled;
    }

    public static final class ControllerTerminalEvidence {
        public final int frameId;
        public final int orderSequence;
        public final int terminalActual;
        public final int controllerResultCode;
        public final int lastProgressActual;
        public final long receivedAt;

        public ControllerTerminalEvidence(
                int frameId,
                int orderSequence,
                int terminalActual,
                int controllerResultCode,
                int lastProgressActual,
                long receivedAt
        ) {
            this.frameId = frameId;
            this.orderSequence = orderSequence;
            this.terminalActual = terminalActual;
            this.controllerResultCode = controllerResultCode;
            this.lastProgressActual = lastProgressActual;
            this.receivedAt = receivedAt;
        }
    }

    public interface Observer {
        void onProgress(String messageId, int orderSequence, int actual);

        boolean onTerminalEvidence(
                String messageId,
                ControllerTerminalEvidence evidence
        );

        void onTerminalAckEcho(
                String messageId,
                ControllerTerminalEvidence evidence,
                boolean echoed
        );
    }
}
