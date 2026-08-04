package com.gouzhu.mqtt;

import android.content.Context;

/**
 * 平台统一现金与硬件命令门面。
 *
 * <p>具体状态机、SDK协议校验、SQLite outbox 和 ttyS5 硬件适配由
 * {@link PlatformCommandRuntime} 实现。人工结案指令由
 * {@link OperationResolutionManager} 按operationNo精确收尾；该指令不会
 * 继续出珠，补充库存和控制板复位必须由工作人员按下K1补珠键完成。</p>
 */
public final class DeviceCommandManager {

    public static final String EXTRA_COLLECTION_EVENT = "collectionEvent";
    public static final String EXTRA_COLLECTION_MESSAGE = "collectionMessage";

    public static final String COLLECTION_READY = "ready";
    public static final String COLLECTION_STARTED = "started";
    public static final String COLLECTION_PROGRESS = "progress";
    public static final String COLLECTION_FINISHED = "finished";
    public static final String COLLECTION_FAILED = "failed";

    private static volatile DeviceCommandManager instance;

    private final PlatformCommandRuntime runtime;
    private final OperationResolutionManager resolutionManager;

    private DeviceCommandManager(Context context) {
        Context applicationContext = context.getApplicationContext();
        runtime = new PlatformCommandRuntime(applicationContext);
        resolutionManager = new OperationResolutionManager(applicationContext);
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
        // 先注册人工结案硬件状态监听，再启动主运行时，避免漏掉恢复事件。
        resolutionManager.start();
        runtime.start();
    }

    public void stop() {
        runtime.stop();
        resolutionManager.stop();
    }

    public void handleCommand(String topic, byte[] payload) {
        if (resolutionManager.handles(payload)) {
            resolutionManager.handleCommand(topic, payload);
            return;
        }
        runtime.handleCommand(topic, payload);
    }

    public boolean startPendingCollection() {
        return runtime.startPendingCollection();
    }

    public boolean finishPendingCollection() {
        return runtime.finishPendingCollection();
    }

    public boolean hasPendingCollection() {
        return runtime.hasPendingCollection();
    }

    public int getRunningStatus() {
        return resolutionManager.getRunningStatus();
    }

    public void requestActivePhysicalOrderState() {
        runtime.broadcastActivePhysicalOrderState();
    }

    public void flushPending() {
        runtime.flushPending();
    }
}
