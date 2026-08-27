package com.chuzhu.device;

import android.content.Context;
import android.content.Intent;

import com.chuzhu.AppConfig;
import com.chuzhu.DepositConfirmActivity;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;

/**
 * 存珠机本地运行状态。
 */
public final class DeviceStateRepository {

    private static final String ERROR_APP_RESTART_RESULT_UNCONFIRMED =
            "APP_RESTART_RESULT_UNCONFIRMED";
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

    /**
     * 进程启动时只根据本地快照恢复“忙/故障”门禁，不再直接把未完成会话改写成故障。
     * 真正的机械状态由 MQTT 建连后的恢复流程通过 0x12 STATUS 向未重启的控制板确认。
     */
    public synchronized void reconcileFromStoredSession() {
        DepositSession session = sessionStore.load();
        if (session == null) {
            runningStatus = AppConfig.STATUS_IDLE;
            lastError = "";
            broadcast();
            return;
        }
        if (DepositSession.STATE_ACCEPTED.equals(session.state)
                || DepositSession.STATE_COLLECTING.equals(session.state)) {
            runningStatus = AppConfig.STATUS_COLLECTING;
            lastError = "";
            broadcast();
            return;
        }
        if (DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
            /*
             * 0x21 已明确说明控制板电机停止，因此物理状态是 IDLE；业务 terminal 尚未发送。
             * APP 重启后重新打开确认页，继续保留“确认/继续/返回”的业务选择。
             */
            runningStatus = AppConfig.STATUS_IDLE;
            lastError = "";
            broadcast();
            launchPendingConfirmUi();
            return;
        }
        if (DepositSession.STATE_FAILED.equals(session.state)
                && ERROR_APP_RESTART_RESULT_UNCONFIRMED.equals(session.errorCode)) {
            /*
             * 该异常终态是在 0x12 STATUS 已明确确认控制板 IDLE 后产生，
             * 业务结果仍需平台核对，但物理设备不能在下次 APP 重启时再次锁成故障。
             */
            runningStatus = AppConfig.STATUS_IDLE;
            lastError = "";
            broadcast();
            return;
        }
        if (DepositSession.STATE_FAULT.equals(session.state)
                || DepositSession.STATE_FAILED.equals(session.state)) {
            runningStatus = AppConfig.STATUS_FAULT;
            lastError = session.errorMessage == null ? "" : session.errorMessage;
            broadcast();
            return;
        }
        runningStatus = AppConfig.STATUS_IDLE;
        lastError = "";
        broadcast();
    }

    public synchronized void markIdle() {
        runningStatus = AppConfig.STATUS_IDLE;
        lastError = "";
        broadcast();
    }

    public synchronized void markCollecting() {
        runningStatus = AppConfig.STATUS_COLLECTING;
        lastError = "";
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

    private void launchPendingConfirmUi() {
        try {
            Intent intent = new Intent(context, DepositConfirmActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }
}
