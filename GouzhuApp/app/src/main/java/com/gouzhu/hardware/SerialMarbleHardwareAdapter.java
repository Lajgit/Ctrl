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
 * 新版 SDK MarbleHardwareAdapter 的 ttyS5 实现。
 *
 * <p>同步接口在专用硬件线程执行，内部等待控制板真实光眼终态。Android 主线程
 * 只接收广播并唤醒等待线程。实际数量只取控制板 PD3/PD4 累计结果。</p>
 */
public final class SerialMarbleHardwareAdapter implements MarbleHardwareAdapter {

    private static final String TAG = "GouzhuHardwareV2";

    private static final int CMD_DISPENSE_START = 0x01;
    private static final int CMD_COLLECT_START = 0x02;
    private static final int CMD_COLLECT_STOP = 0x03;
    private static final int CMD_BOARD_EVENT_STORED = 0x1B;
    private static final int CMD_EMERGENCY_STOP = 0xFF;

    private static final int EVT_DISPENSE_STARTED = 0x01;
    private static final int EVT_DISPENSE_PROGRESS = 0x02;
    private static final int EVT_DISPENSE_COMPLETED = 0x03;
    private static final int EVT_DISPENSE_FAILED = 0x04;
    private static final int EVT_COLLECT_STARTED = 0x05;
    private static final int EVT_COLLECT_PROGRESS = 0x06;
    private static final int EVT_COLLECT_COMPLETED = 0x07;
    private static final int EVT_COLLECT_FAILED = 0x08;

    private static final long DISPENSE_MAX_WAIT_MS = 5L * 60L * 1000L;
    private static final long COLLECT_EXTRA_WAIT_MS = 15_000L;

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
        if (request == null) {
            return HardwareExecutionResult.failed(0, "PARAM_INVALID", "出珠请求为空");
        }
        int quantity = request.getQuantity();
        if (quantity <= 0 || quantity > 0xFFFF) {
            return HardwareExecutionResult.failed(
                    0,
                    "PARAM_INVALID",
                    "出珠数量必须为1..65535"
            );
        }
        return execute(
                request.getMessageId(),
                quantity,
                CMD_DISPENSE_START,
                EVT_DISPENSE_STARTED,
                EVT_DISPENSE_PROGRESS,
                EVT_DISPENSE_COMPLETED,
                EVT_DISPENSE_FAILED,
                DISPENSE_MAX_WAIT_MS
        );
    }

    @Override
    public HardwareExecutionResult collect(CollectRequest request) {
        if (request == null) {
            return HardwareExecutionResult.failed(0, "PARAM_INVALID", "存珠请求为空");
        }
        int maximum = request.getMaximumQuantity();
        if (maximum <= 0 || maximum > 0xFFFF) {
            return HardwareExecutionResult.failed(
                    0,
                    "PARAM_INVALID",
                    "存珠数量上限必须为1..65535"
            );
        }
        int timeoutSeconds = Math.max(1, request.getSessionTimeoutSeconds());
        return execute(
                request.getMessageId(),
                maximum,
                CMD_COLLECT_START,
                EVT_COLLECT_STARTED,
                EVT_COLLECT_PROGRESS,
                EVT_COLLECT_COMPLETED,
                EVT_COLLECT_FAILED,
                timeoutSeconds * 1000L + COLLECT_EXTRA_WAIT_MS
        );
    }

    public boolean stopCollect(String messageId) {
        PendingOperation active = pending;
        if (active == null || !active.collect || !active.messageId.equals(messageId)) {
            return false;
        }
        long packed = (long) active.token << 24;
        return SerialManager.get(context).sendCommand(CMD_COLLECT_STOP, packed, true);
    }

    public void emergencyStop() {
        SerialManager.get(context).sendCommand(CMD_EMERGENCY_STOP, 0L, true);
    }

    /** Android 已持久化控制板关键事件后，显式停止该事件重发。 */
    public boolean confirmBoardEventStored(int eventCode, int token) {
        long packed = ((long) (eventCode & 0xFF) << 24)
                | ((long) (token & 0xFF) << 16);
        return SerialManager.get(context).sendCommand(
                CMD_BOARD_EVENT_STORED,
                packed,
                true
        );
    }

    public static int tokenForMessageId(String messageId) {
        int hash = messageId == null ? 1 : messageId.hashCode();
        return Math.floorMod(hash, 255) + 1;
    }

    private HardwareExecutionResult execute(
            String messageId,
            int quantity,
            int startCommand,
            int startedEvent,
            int progressEvent,
            int completedEvent,
            int failedEvent,
            long timeoutMs
    ) {
        synchronized (operationLock) {
            if (pending != null) {
                return HardwareExecutionResult.failed(
                        0,
                        "DEVICE_BUSY",
                        "存在其他未完成物理操作"
                );
            }

            PendingOperation operation = new PendingOperation();
            operation.messageId = safe(messageId);
            operation.token = tokenForMessageId(messageId);
            operation.requested = quantity;
            operation.startCommand = startCommand;
            operation.startedEvent = startedEvent;
            operation.progressEvent = progressEvent;
            operation.completedEvent = completedEvent;
            operation.failedEvent = failedEvent;
            operation.collect = startCommand == CMD_COLLECT_START;
            pending = operation;

            try {
                long packed = ((long) operation.token << 24)
                        | (quantity & 0x00FFFFFFL);
                if (!SerialManager.get(context).sendCommand(startCommand, packed, true)) {
                    return HardwareExecutionResult.failed(
                            0,
                            "CONTROLLER_OFFLINE",
                            "控制板启动命令发送失败"
                    );
                }

                if (!operation.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return HardwareExecutionResult.failed(
                            operation.actual,
                            "CONTROLLER_RESULT_TIMEOUT",
                            "控制板未在限定时间返回可靠终态"
                    );
                }
                if (operation.cancelled) {
                    return HardwareExecutionResult.failed(
                            operation.actual,
                            "ADAPTER_STOPPED",
                            "硬件适配器已停止"
                    );
                }
                if (operation.terminalEvent == completedEvent
                        && operation.resultCode == 0) {
                    return HardwareExecutionResult.success(operation.actual);
                }
                return HardwareExecutionResult.failed(
                        operation.actual,
                        boardResultName(operation.resultCode),
                        "控制板返回失败或部分完成终态"
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return HardwareExecutionResult.failed(
                        operation.actual,
                        "INTERRUPTED",
                        "等待硬件终态被中断"
                );
            } finally {
                pending = null;
            }
        }
    }

    private void onBoardEvent(int code2, long packed, int expandCode) {
        PendingOperation operation = pending;
        if (operation == null) {
            return;
        }
        int token = (int) ((packed >>> 24) & 0xFF);
        int actual = (int) (packed & 0x00FFFFFFL);
        if (token != operation.token) {
            return;
        }

        operation.actual = Math.max(operation.actual, actual);
        Observer currentObserver = observer;
        if (code2 == operation.startedEvent) {
            operation.started = true;
            boolean persisted = currentObserver != null
                    && currentObserver.onStarted(
                            operation.messageId,
                            code2,
                            token,
                            operation.requested
                    );
            if (persisted) {
                confirmBoardEventStored(code2, token);
            }
            return;
        }
        if (code2 == operation.progressEvent) {
            if (currentObserver != null) {
                currentObserver.onProgress(
                        operation.messageId,
                        code2,
                        token,
                        operation.actual
                );
            }
            return;
        }
        if (code2 == operation.completedEvent || code2 == operation.failedEvent) {
            operation.terminalEvent = code2;
            operation.resultCode = expandCode & 0xFF;
            operation.latch.countDown();
        }
    }

    private static String boardResultName(int code) {
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
        int token;
        int requested;
        int actual;
        int startCommand;
        int startedEvent;
        int progressEvent;
        int completedEvent;
        int failedEvent;
        int terminalEvent;
        int resultCode;
        boolean collect;
        boolean started;
        volatile boolean cancelled;
    }

    public interface Observer {
        boolean onStarted(String messageId, int eventCode, int token, int requested);

        void onProgress(String messageId, int eventCode, int token, int actual);
    }
}
