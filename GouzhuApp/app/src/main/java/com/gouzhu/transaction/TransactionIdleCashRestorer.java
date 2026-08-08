package com.gouzhu.transaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.gouzhu.AppConfig;
import com.gouzhu.mqtt.CashRuntimeCoordinator;
import com.gouzhu.serial.BoardConnectionMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 交易释放和控制板恢复后的现金运行状态触发器。
 *
 * <p>不再直接恢复旧现金掩码。所有恢复必须先经过 CashRuntimeCoordinator，重新确认
 * MQTT、bootstrap cashSale.available、交易占用和控制板状态，再决定是否允许收现。</p>
 */
public final class TransactionIdleCashRestorer {

    private final Context context;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gouzhu-idle-cash-restore");
                thread.setDaemon(true);
                return thread;
            });
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null) {
                return;
            }
            if (TransactionOccupancyManager.ACTION_CHANGED.equals(intent.getAction())) {
                String owner = intent.getStringExtra(
                        TransactionOccupancyManager.EXTRA_OWNER_TYPE
                );
                String phase = intent.getStringExtra(
                        TransactionOccupancyManager.EXTRA_PHASE
                );
                if (!"NONE".equals(owner) || !"IDLE".equals(phase)) {
                    return;
                }
                scheduleTransactionIdle(150L);
                return;
            }
            if (AppConfig.ACTION_BOARD_CONNECTION_CHANGED.equals(intent.getAction())
                    && intent.getBooleanExtra(
                    BoardConnectionMonitor.EXTRA_CONNECTED,
                    false
            )) {
                // 等控制板版本和硬件状态帧先完成，再重新计算当前现金目标状态。
                scheduleBoardRecovered(1500L);
            }
        }
    };

    public TransactionIdleCashRestorer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(TransactionOccupancyManager.ACTION_CHANGED);
        filter.addAction(AppConfig.ACTION_BOARD_CONNECTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
        CashRuntimeCoordinator.get(context).reconcile("idle_cash_restorer_started");
    }

    public synchronized void stop() {
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        registered = false;
        // DeviceCommandManager 是进程单例，保留 daemon executor 供服务重启后继续使用。
    }

    private void scheduleTransactionIdle(long delayMs) {
        executor.schedule(
                () -> CashRuntimeCoordinator.get(context).onTransactionIdle(),
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
    }

    private void scheduleBoardRecovered(long delayMs) {
        executor.schedule(
                () -> CashRuntimeCoordinator.get(context).onBoardRecovered(),
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
    }
}
