package com.chuzhu.hardware;

import android.content.Context;

import com.chuzhu.serial.BoardEvent;
import com.chuzhu.serial.BoardEventListener;
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
    private volatile int actualQuantity;
    private volatile CountDownLatch blockingLatch;
    private volatile HardwareExecutionResult blockingResult;
    private volatile boolean localDebugSession;

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
        boolean started = startCollect(request.getMaximumQuantity(), null);
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
        synchronized (lock) {
            if (collecting) {
                return false;
            }
            if (maximumQuantity <= 0) {
                return false;
            }
            if (!serialPort.isOpen()) {
                return false;
            }
            collecting = true;
            localDebugSession = false;
            actualQuantity = 0;
            this.listener = listener;
            byte[] frame = serialPort.getCodec().buildStartCollectFrame(maximumQuantity);
            /*
             * 待存珠机控制板新串口协议确认后替换。
             * 当前 frame 为空时只建立会话，不伪造数量、不自动成功。
             */
            return serialPort.write(frame);
        }
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
        synchronized (lock) {
            if (collecting || maximumQuantity <= 0) {
                return false;
            }
            collecting = true;
            localDebugSession = true;
            actualQuantity = 0;
            this.listener = listener;
            return true;
        }
    }

    /** 仅本地调试使用：模拟控制板计数 +1。 */
    public void simulateCountIncrement() {
        if (!localDebugSession || !collecting) {
            return;
        }
        onBoardCountChanged(actualQuantity + 1);
    }

    /** 仅本地调试使用：模拟控制板返回收珠结束。 */
    public void simulateFinish() {
        if (!localDebugSession || !collecting) {
            return;
        }
        onBoardFinished(actualQuantity);
    }

    public void onBoardCountChanged(int actualQuantity) {
        if (!collecting || actualQuantity < this.actualQuantity) {
            return;
        }
        this.actualQuantity = actualQuantity;
        Listener current = listener;
        if (current != null) {
            current.onCountChanged(actualQuantity);
        }
    }

    public void onBoardFinished(int actualQuantity) {
        if (!collecting) {
            return;
        }
        this.actualQuantity = Math.max(this.actualQuantity, actualQuantity);
        collecting = false;
        localDebugSession = false;
        blockingResult = HardwareExecutionResult.success(this.actualQuantity);
        CountDownLatch latch = blockingLatch;
        if (latch != null) {
            latch.countDown();
        }
        Listener current = listener;
        if (current != null) {
            current.onFinished(this.actualQuantity);
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
        /*
         * 待存珠机控制板新串口协议确认后替换。
         * 当前 BoardFrameCodec 只广播 RAW，不按旧协议解释业务事件。
         */
        if (BoardEvent.TYPE_COUNT_CHANGED.equals(event.type)) {
            onBoardCountChanged(event.actualQuantity);
        } else if (BoardEvent.TYPE_FINISHED.equals(event.type)) {
            onBoardFinished(event.actualQuantity);
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

    private void fail(String errorCode, String errorMessage) {
        collecting = false;
        localDebugSession = false;
        blockingResult = HardwareExecutionResult.failed(
                actualQuantity,
                errorCode == null ? "COLLECT_FAILED" : errorCode,
                errorMessage == null ? "" : errorMessage
        );
        CountDownLatch latch = blockingLatch;
        if (latch != null) {
            latch.countDown();
        }
        Listener current = listener;
        if (current != null) {
            current.onFault(
                    errorCode == null ? "COLLECT_FAILED" : errorCode,
                    errorMessage == null ? "" : errorMessage,
                    actualQuantity
            );
        }
    }

    public interface Listener {
        void onCountChanged(int actualQuantity);

        void onFinished(int actualQuantity);

        void onFault(String errorCode, String errorMessage, int actualQuantity);
    }
}
