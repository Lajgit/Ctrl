package com.chuzhu.hardware;

import android.content.Context;

import com.chuzhu.AppConfig;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.mqtt.PendingDepositController;
import com.chuzhu.serial.BoardEvent;
import com.chuzhu.serial.BoardEventListener;
import com.chuzhu.serial.BoardFrameCodec;
import com.chuzhu.serial.BoardSerialPort;
import com.pinball.xiaoda.device.sdk.hardware.CollectRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.hardware.MarbleCollectHardwareAdapter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 存珠机收珠硬件适配器。
 */
public final class SerialMarbleCollectHardwareAdapter
        implements MarbleCollectHardwareAdapter, BoardEventListener {

    private static volatile SerialMarbleCollectHardwareAdapter instance;

    private final Context context;
    private final BoardSerialPort serialPort;
    private final Object lock = new Object();
    private volatile boolean collecting;
    private volatile Listener listener;
    /** 当前控制板这一段开始前已经确认的累计数量。 */
    private volatile int quantityOffset;
    /** 当前控制板这一段自身的原始计数；继续 START 后会重新从 0 开始。 */
    private volatile int segmentRawQuantity;
    /** Android 对外展示/上报的同一 Operation 累计数量。 */
    private volatile int actualQuantity;
    private volatile CountDownLatch blockingLatch;
    private volatile HardwareExecutionResult blockingResult;
    private volatile boolean localDebugSession;
    private volatile CountDownLatch statusQueryLatch;
    private volatile BoardEvent statusQueryResult;

    private SerialMarbleCollectHardwareAdapter(Context context) {
        this.context = context.getApplicationContext();
        serialPort = BoardSerialPort.get(this.context);
        serialPort.setListener(this);
    }

    public static SerialMarbleCollectHardwareAdapter get(Context context) {
        if (instance == null) {
            synchronized (SerialMarbleCollectHardwareAdapter.class) {
                if (instance == null) {
                    instance = new SerialMarbleCollectHardwareAdapter(context);
                }
            }
        }
        return instance;
    }

    @Override
    public HardwareExecutionResult collect(CollectRequest request) {
        if (request == null) {
            return HardwareExecutionResult.failed(0, "PARAM_INVALID", "collect request is null");
        }
        CountDownLatch latch = new CountDownLatch(1);
        blockingLatch = latch;
        blockingResult = null;
        boolean started = startCollect(
                request.getMaximumQuantity(),
                request.getSessionTimeoutSeconds(),
                null
        );
        if (!started) {
            blockingLatch = null;
            return HardwareExecutionResult.failed(
                    actualQuantity,
                    "COLLECT_START_FAILED",
                    "collect start failed"
            );
        }
        try {
            long timeout = Math.max(1, request.getSessionTimeoutSeconds());
            if (!latch.await(timeout, TimeUnit.SECONDS)) {
                fail("COLLECT_TIMEOUT", "控制板收珠超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail("INTERRUPTED", "等待控制板收珠结果被中断");
        } finally {
            blockingLatch = null;
        }
        return blockingResult == null
                ? HardwareExecutionResult.failed(actualQuantity, "NO_TERMINAL", "控制板未返回终态")
                : blockingResult;
    }

    public boolean startCollect(int maximumQuantity, Listener listener) {
        return startCollect(maximumQuantity, AppConfig.DEFAULT_COLLECT_TIMEOUT_SECONDS, listener);
    }

    public boolean startCollect(int maximumQuantity, int sessionTimeoutSeconds, Listener listener) {
        return startSegment(maximumQuantity, sessionTimeoutSeconds, 0, listener, false);
    }

    /**
     * 同一会员同一 Operation 的继续存珠。
     * 控制板 START 会清零板端计数，因此只把剩余允许数量发给控制板，并用 accumulatedQuantity
     * 作为 Android 累计偏移；后续 0x20/0x21 都转换成累计数量再交给业务层。
     */
    public boolean startContinuation(
            int remainingQuantity,
            int remainingTimeoutSeconds,
            int accumulatedQuantity,
            Listener listener
    ) {
        return startSegment(
                remainingQuantity,
                remainingTimeoutSeconds,
                accumulatedQuantity,
                listener,
                false
        );
    }

    private boolean startSegment(
            int boardMaximumQuantity,
            int sessionTimeoutSeconds,
            int accumulatedQuantity,
            Listener listener,
            boolean localDebug
    ) {
        synchronized (lock) {
            if (collecting) {
                return false;
            }
            if (boardMaximumQuantity <= 0 || sessionTimeoutSeconds <= 0 || accumulatedQuantity < 0) {
                return false;
            }
            if (!localDebug && !serialPort.isOpen()) {
                return false;
            }
            collecting = true;
            localDebugSession = localDebug;
            quantityOffset = accumulatedQuantity;
            segmentRawQuantity = 0;
            actualQuantity = accumulatedQuantity;
            blockingResult = null;
            this.listener = listener;
            if (localDebug) {
                return true;
            }
            byte[] frame = serialPort.getCodec().buildStartCollectFrame(
                    boardMaximumQuantity,
                    sessionTimeoutSeconds
            );
            boolean written = serialPort.write(frame);
            if (!written) {
                collecting = false;
                this.listener = null;
            }
            return written;
        }
    }

    /**
     * APP 进程重启但控制板未重启时，控制板可能仍在执行上一笔收珠。
     * queryStatus 已将“继续存珠”的板端分段计数换算成累计数量，因此这里再按持久化
     * segmentBaseQuantity 还原分段原始值，避免二次叠加。
     */
    public boolean resumeCollect(int recoveredActualQuantity, Listener listener) {
        int total;
        int raw;
        synchronized (lock) {
            if (collecting || !serialPort.isOpen() || recoveredActualQuantity < 0) {
                return false;
            }
            DepositSession stored = new HardwareSessionStore(context).load();
            int base = stored == null ? 0 : Math.max(0, stored.segmentBaseQuantity);
            total = recoveredActualQuantity;
            raw = total - base;
            if (raw < 0
                    || (stored != null && stored.maximumQuantity > 0 && total > stored.maximumQuantity)) {
                return false;
            }
            collecting = true;
            localDebugSession = false;
            quantityOffset = base;
            segmentRawQuantity = raw;
            actualQuantity = total;
            blockingResult = null;
            this.listener = listener;
        }
        /* 立即把恢复后的累计总数回灌业务层，保证 UI/本地会话保持总累计数量。 */
        if (listener != null) {
            listener.onCountChanged(total);
        }
        return true;
    }

    /**
     * 主动查询控制板 0x12 STATUS。只接受本次查询对应的 STATUS 帧，
     * HEARTBEAT/BOARD_BOOT 虽然同属状态类事件，但不能作为重启恢复依据。
     *
     * <p>继续存珠时 MCU 每次 START 都把本段计数从 0 开始。APP 重启恢复路径的上层代码
     * 不知道“分段计数”概念，因此这里把 STATUS 的原始数量加上 segmentBaseQuantity，统一
     * 对上层返回同一 Operation 的累计数量。这样控制板若在 APP 重启期间已经自然停止，
     * 上层拿到 IDLE 状态时也不会把 21+3 错结算成 3。</p>
     */
    public BoardEvent queryStatus(long timeoutMillis) {
        if (!serialPort.isOpen()) {
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            statusQueryResult = null;
            statusQueryLatch = latch;
        }
        if (!serialPort.write(serialPort.getCodec().buildStatusQueryFrame())) {
            synchronized (lock) {
                if (statusQueryLatch == latch) {
                    statusQueryLatch = null;
                }
            }
            return null;
        }
        try {
            if (!latch.await(Math.max(100L, timeoutMillis), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return cumulativeStatus(statusQueryResult);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            synchronized (lock) {
                if (statusQueryLatch == latch) {
                    statusQueryLatch = null;
                }
            }
        }
    }

    private BoardEvent cumulativeStatus(BoardEvent event) {
        if (event == null) {
            return null;
        }
        DepositSession stored = new HardwareSessionStore(context).load();
        if (stored == null
                || !DepositSession.STATE_COLLECTING.equals(stored.state)
                || stored.segmentBaseQuantity <= 0) {
            return event;
        }
        int total = stored.segmentBaseQuantity + event.actualQuantity;
        if (stored.maximumQuantity > 0 && total > stored.maximumQuantity) {
            return event;
        }
        return new BoardEvent(
                event.type,
                total,
                event.errorCode,
                event.errorMessage,
                event.raw,
                event.resendId,
                event.frameId,
                event.code1,
                event.code2,
                event.data1,
                event.data2,
                event.data3,
                event.data4,
                event.ackByte,
                event.expandCode,
                event.requiresAck
        );
    }

    public void stopCollect() {
        synchronized (lock) {
            if (!collecting) {
                return;
            }
            serialPort.write(serialPort.getCodec().buildStopCollectFrame());
            fail("COLLECT_STOPPED", "收珠已停止");
        }
    }

    public boolean isCollecting() {
        return collecting;
    }

    public int getActualQuantity() {
        return actualQuantity;
    }

    /** 仅本地调试使用：模拟启动收珠，不代表正式 MQTT 指令已经执行硬件。 */
    public boolean startLocalDebugCollect(int maximumQuantity, Listener listener) {
        return startSegment(
                maximumQuantity,
                AppConfig.DEFAULT_COLLECT_TIMEOUT_SECONDS,
                0,
                listener,
                true
        );
    }

    /** 仅本地调试使用：模拟控制板计数 +1。 */
    public void simulateCountIncrement() {
        if (!localDebugSession || !collecting) {
            return;
        }
        onBoardCountChanged(segmentRawQuantity + 1);
    }

    /** 仅本地调试使用：模拟控制板返回自然结束。 */
    public void simulateFinish() {
        if (!localDebugSession || !collecting) {
            return;
        }
        onBoardFinished(segmentRawQuantity, BoardFrameCodec.FINISH_REASON_NATURAL);
    }

    public void onBoardCountChanged(int boardActualQuantity) {
        Listener current;
        int total;
        synchronized (lock) {
            if (!collecting || boardActualQuantity < segmentRawQuantity) {
                return;
            }
            segmentRawQuantity = boardActualQuantity;
            total = quantityOffset + boardActualQuantity;
            actualQuantity = total;
            current = listener;
        }
        if (current != null) {
            current.onCountChanged(total);
        }
        /* 每颗珠子的可信计数都刷新运行状态广播，MainActivity 因此实时读取 HardwareSessionStore。 */
        DeviceStateRepository.get(context).markCollecting();
    }

    public void onBoardFinished(int boardActualQuantity) {
        onBoardFinished(boardActualQuantity, BoardFrameCodec.FINISH_REASON_ANDROID_STOP);
    }

    private void onBoardFinished(int boardActualQuantity, int finishReason) {
        Listener current;
        int total;
        boolean waitForUser;
        synchronized (lock) {
            if (!collecting) {
                return;
            }
            segmentRawQuantity = Math.max(segmentRawQuantity, boardActualQuantity);
            total = quantityOffset + segmentRawQuantity;
            actualQuantity = Math.max(actualQuantity, total);
            collecting = false;
            localDebugSession = false;
            current = listener;
            waitForUser = current != null
                    && (finishReason == BoardFrameCodec.FINISH_REASON_NATURAL
                    || finishReason == BoardFrameCodec.FINISH_REASON_MAXIMUM_REACHED);
            if (!waitForUser) {
                blockingResult = HardwareExecutionResult.success(actualQuantity);
            }
        }

        if (waitForUser) {
            /* 自然结束/达到上限只暂停业务，不生成 terminal；确认页面决定确认、继续或返回。 */
            if (PendingDepositController.get(context)
                    .pauseForConfirmation(actualQuantity, finishReason)) {
                return;
            }
            /* 极端情况下本地会话丢失，退回原有成功终态，避免硬件已经停止却永久卡死。 */
        }

        CountDownLatch latch = blockingLatch;
        if (latch != null) {
            latch.countDown();
        }
        if (current != null) {
            current.onFinished(actualQuantity);
        }
    }

    public void onBoardFault(String errorCode, String errorMessage) {
        fail(errorCode, errorMessage);
    }

    @Override
    public void onBoardEvent(BoardEvent event) {
        if (event == null) {
            return;
        }
        if (event.requiresAck) {
            /*
             * 0x21 收珠结束和 0x22 故障是控制板关键终态帧，Android 必须回 ACK echo。
             * 控制板未收到 ACK 时可用相同 ID、递增 ResendID 重发，Android 侧按终态幂等处理。
             */
            serialPort.write(serialPort.getCodec().buildAckFrame(event, BoardFrameCodec.RESULT_OK));
        }
        if (BoardEvent.TYPE_STATUS.equals(event.type)
                && event.code2 == BoardFrameCodec.CODE2_STATUS) {
            CountDownLatch latch = statusQueryLatch;
            if (latch != null) {
                statusQueryResult = event;
                latch.countDown();
            }
        }
        if (BoardEvent.TYPE_ACK.equals(event.type)) {
            handleCommandAck(event);
        } else if (BoardEvent.TYPE_COUNT_CHANGED.equals(event.type)) {
            onBoardCountChanged(event.actualQuantity);
        } else if (BoardEvent.TYPE_FINISHED.equals(event.type)) {
            onBoardFinished(event.actualQuantity, event.data3);
        } else if (BoardEvent.TYPE_FAULT.equals(event.type)) {
            onBoardFault(event.errorCode, event.errorMessage);
        }
    }

    @Override
    public void onSerialError(String message, Throwable error) {
        if (collecting) {
            fail("SERIAL_ERROR", message);
        }
    }

    private void handleCommandAck(BoardEvent event) {
        if (event.code2 == BoardFrameCodec.CODE2_START_COLLECT
                && event.expandCode != BoardFrameCodec.RESULT_OK
                && event.expandCode != BoardFrameCodec.RESULT_DUPLICATE_ACCEPTED) {
            fail(event.errorCode, "控制板拒绝开始收珠：" + event.errorMessage);
        }
    }

    private void fail(String errorCode, String errorMessage) {
        Listener current;
        int total;
        synchronized (lock) {
            collecting = false;
            localDebugSession = false;
            total = actualQuantity;
            blockingResult = HardwareExecutionResult.failed(
                    total,
                    errorCode == null ? "COLLECT_FAILED" : errorCode,
                    errorMessage == null ? "" : errorMessage
            );
            current = listener;
        }
        CountDownLatch latch = blockingLatch;
        if (latch != null) {
            latch.countDown();
        }
        if (current != null) {
            current.onFault(
                    errorCode == null ? "COLLECT_FAILED" : errorCode,
                    errorMessage == null ? "" : errorMessage,
                    total
            );
        }
    }

    public interface Listener {
        void onCountChanged(int actualQuantity);

        void onFinished(int actualQuantity);

        void onFault(String errorCode, String errorMessage, int actualQuantity);
    }
}
