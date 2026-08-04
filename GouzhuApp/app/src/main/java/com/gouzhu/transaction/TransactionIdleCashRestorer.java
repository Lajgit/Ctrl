package com.gouzhu.transaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Restores the configured cash mask after the global transaction lock becomes idle.
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
            if (intent == null
                    || !TransactionOccupancyManager.ACTION_CHANGED.equals(
                    intent.getAction())) {
                return;
            }
            String owner = intent.getStringExtra(
                    TransactionOccupancyManager.EXTRA_OWNER_TYPE
            );
            String phase = intent.getStringExtra(
                    TransactionOccupancyManager.EXTRA_PHASE
            );
            if (!"NONE".equals(owner) || !"IDLE".equals(phase)) {
                return;
            }
            executor.schedule(
                    occupancy::restoreCashAcceptanceIfSafe,
                    150L,
                    TimeUnit.MILLISECONDS
            );
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
        IntentFilter filter = new IntentFilter(
                TransactionOccupancyManager.ACTION_CHANGED
        );
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
        if (registered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            registered = false;
        }
        executor.shutdownNow();
    }
}
