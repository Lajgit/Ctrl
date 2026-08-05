package com.gouzhu.transaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.gouzhu.AppConfig;
import com.gouzhu.serial.BoardConnectionMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Restores the configured cash mask after the global transaction lock becomes idle or after the
 * controller link has recovered.
 *
 * <p>The physical runtime broadcasts dispense completion before its legacy cash reapply call.
 * Broadcast delivery is asynchronous, so that legacy call can still see the old occupancy and
 * be rejected by the safety gate. This receiver runs after the persisted occupancy row has been
 * deleted and closes that ordering gap. Reapplying the same mask/version is idempotent.</p>
 */
public final class TransactionIdleCashRestorer {

    private final Context context;
    private final TransactionOccupancyManager occupancy;
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
                scheduleRestore(150L);
                return;
            }
            if (AppConfig.ACTION_BOARD_CONNECTION_CHANGED.equals(intent.getAction())
                    && intent.getBooleanExtra(
                    BoardConnectionMonitor.EXTRA_CONNECTED,
                    false
            )) {
                // BoardConnectionMonitor requests version once on recovery. Allow that reply and
                // the hardware-status events to be processed before restoring the configured mask.
                scheduleRestore(1500L);
            }
        }
    };

    public TransactionIdleCashRestorer(Context context) {
        this.context = context.getApplicationContext();
        this.occupancy = TransactionOccupancyManager.get(this.context);
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
        // Keep the daemon executor alive because DeviceCommandManager is a process singleton
        // and the service can stop/start again without recreating this object.
    }

    private void scheduleRestore(long delayMs) {
        executor.schedule(
                occupancy::restoreCashAcceptanceIfSafe,
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
    }
}
