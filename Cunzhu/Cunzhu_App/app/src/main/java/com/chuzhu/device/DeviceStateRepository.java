package com.chuzhu.device;

import android.content.Context;
import android.content.Intent;

import com.chuzhu.AppConfig;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;

/**
 * 存珠机本地运行状态。
 */
public final class DeviceStateRepository {

    private static volatile DeviceStateRepository instance;

    private final Context context;
    private final HardwareSessionStore sessionStore;
    private volatile int runningStatus = AppConfig.STATUS_IDLE;
    private volatile String lastError = "";

    private DeviceStateRepository(Context context) {
        this.context = context.getApplicationContext();
        sessionStore = new HardwareSessionStore(this.context);
        reconcileFromStoredSession();
    }

    public static DeviceStateRepository get(Context context) {
        if (instance == null) {
            synchronized (DeviceStateRepository.class) {
                if (instance == null) {
                    instance = new DeviceStateRepository(context);
                }
            }
        }
        return instance;
    }

    /** 重启发现未完成硬件会话时，不自动假成功，转为故障等待人工处理。 */
    public synchronized void reconcileFromStoredSession() {
        DepositSession session = sessionStore.load();
        if (session == null) {
            runningStatus = AppConfig.STATUS_IDLE;
            return;
        }
        if (DepositSession.STATE_ACCEPTED.equals(session.state)
                || DepositSession.STATE_COLLECTING.equals(session.state)) {
            long now = System.currentTimeMillis();
            session.state = DepositSession.STATE_FAULT;
            session.updatedAt = now;
            session.finishedAt = now;
            session.errorCode = "RECOVERED_UNCONFIRMED_SESSION";
            session.errorMessage = "APP 重启后无法确认控制板收珠状态，等待人工处理";
            sessionStore.save(session);
            runningStatus = AppConfig.STATUS_FAULT;
            lastError = session.errorMessage;
            broadcast();
        }
    }

    public synchronized void markIdle() {
        runningStatus = AppConfig.STATUS_IDLE;
        broadcast();
    }

    public synchronized void markCollecting() {
        runningStatus = AppConfig.STATUS_COLLECTING;
        broadcast();
    }

    public synchronized void markFault(String error) {
        runningStatus = AppConfig.STATUS_FAULT;
        lastError = error == null ? "" : error;
        broadcast();
    }

    public int getRunningStatus() {
        return runningStatus == AppConfig.STATUS_IN_GAME
                ? AppConfig.STATUS_FAULT
                : runningStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public DepositSession getSession() {
        return sessionStore.load();
    }

    private void broadcast() {
        Intent intent = new Intent(AppConfig.ACTION_DEPOSIT_STATE);
        intent.setPackage(context.getPackageName());
        intent.putExtra("runningStatus", getRunningStatus());
        intent.putExtra("lastError", lastError);
        context.sendBroadcast(intent);
    }
}
