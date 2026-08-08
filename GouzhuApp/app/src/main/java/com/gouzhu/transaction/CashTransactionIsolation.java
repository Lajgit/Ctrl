package com.gouzhu.transaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;

import com.gouzhu.AppConfig;
import com.gouzhu.mqtt.DeviceCommandStore;
import com.gouzhu.serial.SerialManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 非现金物理操作前的现金硬件隔离确认。
 *
 * <p>只有在同一个持久化交易占用仍然有效，并且控制板明确回报 mask=0、版本匹配后，
 * 调用方才能继续执行出珠或存珠硬件动作。这样“已发送关闭命令”不会被误当作
 * “已经停止收现”。</p>
 */
public final class CashTransactionIsolation {

    private static final int CMD_CASH_APPLY_V22 = 0x33;
    private static final int EVT_CASH_ACCEPTANCE_STATUS = 0x11;
    private static final long TIMEOUT_MS = 3500L;
    private static final Object LOCK = new Object();

    private CashTransactionIsolation() {
    }

    public static boolean confirmDisabled(Context sourceContext, String sessionId) {
        if (sourceContext == null || sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        Context context = sourceContext.getApplicationContext();
        TransactionOccupancyManager occupancy = TransactionOccupancyManager.get(context);
        TransactionOccupancyManager.Snapshot before = occupancy.current();
        if (before == null || !sessionId.equals(before.sessionId)) {
            return false;
        }

        synchronized (LOCK) {
            DeviceCommandStore store = new DeviceCommandStore(context);
            int configVersion = Math.max(1, store.getCashConfigVersion());
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] matched = new boolean[]{false};
            HandlerThread receiverThread = new HandlerThread("gouzhu-cash-isolation-rx");
            receiverThread.start();
            Handler receiverHandler = new Handler(receiverThread.getLooper());

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())
                            || intent.getIntExtra("code2", -1)
                            != EVT_CASH_ACCEPTANCE_STATUS) {
                        return;
                    }
                    long packed = intent.getLongExtra("data", 0L);
                    int mask = (int) ((packed >>> 24) & 0xFF);
                    int version = (int) (packed & 0x00FFFFFFL);
                    if (mask == 0 && version == configVersion) {
                        matched[0] = true;
                        latch.countDown();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_EVENT);
            boolean registered = false;
            try {
                // minSdk=33，可直接使用带调度Handler和NOT_EXPORTED标志的注册方式。
                context.registerReceiver(
                        receiver,
                        filter,
                        null,
                        receiverHandler,
                        Context.RECEIVER_NOT_EXPORTED
                );
                registered = true;

                if (!SerialManager.get(context).sendCommand(
                        CMD_CASH_APPLY_V22,
                        configVersion & 0x00FFFFFFL,
                        true
                )) {
                    return false;
                }
                if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || !matched[0]) {
                    return false;
                }

                TransactionOccupancyManager.Snapshot after = occupancy.current();
                return after != null && sessionId.equals(after.sessionId);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable error) {
                return false;
            } finally {
                if (registered) {
                    try {
                        context.unregisterReceiver(receiver);
                    } catch (Throwable ignored) {
                    }
                }
                receiverThread.quitSafely();
            }
        }
    }
}
