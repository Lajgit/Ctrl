package com.gouzhu.mqtt;

import android.content.Context;

/**
 * 平台统一现金与硬件命令门面。
 *
 * <p>具体状态机、SDK协议校验、SQLite outbox 和 ttyS5 硬件适配由
 * {@link PlatformCommandRuntime} 实现。保留本类名称作为 App 内部稳定入口，
 * 不包含任何 1.x 本地现金购买兼容逻辑。</p>
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

    private DeviceCommandManager(Context context) {
        runtime = new PlatformCommandRuntime(context.getApplicationContext());
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
        runtime.start();
    }

    public void stop() {
        runtime.stop();
    }

    public void handleCommand(String topic, byte[] payload) {
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
        return runtime.getRunningStatus();
    }

    public void requestActivePhysicalOrderState() {
        runtime.broadcastActivePhysicalOrderState();
    }

    public void flushPending() {
        runtime.flushPending();
    }
}
