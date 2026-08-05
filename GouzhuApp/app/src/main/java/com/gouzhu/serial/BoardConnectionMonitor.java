package com.gouzhu.serial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.ControllerFaultActivity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the real controller response instead of treating an opened tty node as online.
 * A version query is sent periodically; any valid controller frame restores the online state.
 */
public final class BoardConnectionMonitor {

    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_REASON = "reason";

    private static final String TAG = "GouzhuBoardLink";
    private static final long STARTUP_GRACE_MS = 5_000L;
    private static final long PROBE_INTERVAL_MS = 2_000L;
    private static final long RESPONSE_TIMEOUT_MS = 8_000L;
    private static final long SERIAL_RECYCLE_INTERVAL_MS = 12_000L;
    private static final long PROBE_SUPPRESSION_LIMIT_MS = 300_000L;
    private static final long TICK_INTERVAL_MS = 1_000L;
    private static final int CMD_QUERY_VERSION = 0x00;

    private static volatile BoardConnectionMonitor instance;

    private final Context context;
    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "购珠机-控制板在线监控");
                thread.setDaemon(true);
                return thread;
            });

    private boolean started;
    private boolean receiverRegistered;
    private boolean stateKnown;
    private boolean connected;
    private long startedAt;
    private volatile long lastFrameAt;
    private long lastProbeAt;
    private long lastSerialRecycleAt;
    private long probeUnavailableSince;
    private String lastReason = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (AppConfig.ACTION_BOARD_EVENT.equals(action)) {
                onValidBoardFrame();
                return;
            }
            if (!AppConfig.ACTION_SERVICE_STATUS.equals(action)
                    || !"serial".equals(intent.getStringExtra("key"))) {
                return;
            }
            String value = safe(intent.getStringExtra("value"));
            if (value.contains("连接失败")
                    || value.contains("读取异常")
                    || value.contains("发送异常")
                    || value.contains("已断开")) {
                markDisconnected(value);
            }
        }
    };

    private BoardConnectionMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public static BoardConnectionMonitor get(Context context) {
        if (instance == null) {
            synchronized (BoardConnectionMonitor.class) {
                if (instance == null) {
                    instance = new BoardConnectionMonitor(context);
                }
            }
        }
        return instance;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        startedAt = SystemClock.elapsedRealtime();
        lastSerialRecycleAt = startedAt;
        registerReceiver();
        watchdogExecutor.scheduleAtFixedRate(
                this::safeTick,
                0L,
                TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    public synchronized boolean isStateKnown() {
        return stateKnown;
    }

    public synchronized boolean isConnected() {
        return stateKnown && connected;
    }

    public synchronized String getLastReason() {
        return lastReason;
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_BOARD_EVENT);
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable error) {
            Log.e(TAG, "控制板在线监控异常", error);
        }
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        SerialManager serial = SerialManager.get(context);

        if (!serial.isOpen()) {
            if (serial.open()) {
                lastSerialRecycleAt = now;
            }
        }

        boolean probeUnavailable = false;
        if (serial.isOpen() && now - lastProbeAt >= PROBE_INTERVAL_MS) {
            lastProbeAt = now;
            if (serial.sendCommand(CMD_QUERY_VERSION, 0L, false)) {
                probeUnavailableSince = 0L;
            } else {
                if (probeUnavailableSince <= 0L) {
                    probeUnavailableSince = now;
                }
                probeUnavailable = true;
                Log.d(TAG, "控制板在线探测暂不可发送，可能正在进行串口升级");
            }
        } else if (probeUnavailableSince > 0L) {
            probeUnavailable = true;
        }

        boolean currentlyConnected;
        synchronized (this) {
            currentlyConnected = stateKnown && connected;
        }
        if (probeUnavailable
                && currentlyConnected
                && now - probeUnavailableSince < PROBE_SUPPRESSION_LIMIT_MS) {
            return;
        }

        long latestFrameAt = lastFrameAt;
        long reference = latestFrameAt > 0L ? latestFrameAt : startedAt;
        long allowedSilence = latestFrameAt > 0L ? RESPONSE_TIMEOUT_MS : STARTUP_GRACE_MS;
        if (now - reference >= allowedSilence) {
            markDisconnected(latestFrameAt > 0L
                    ? "控制板通信超时，正在自动重连"
                    : "控制板未响应，正在自动重连");
        }

        boolean offline;
        synchronized (this) {
            offline = stateKnown && !connected;
        }
        if (offline && now - lastSerialRecycleAt >= SERIAL_RECYCLE_INTERVAL_MS) {
            lastSerialRecycleAt = now;
            serial.close();
            if (!serial.open()) {
                Log.w(TAG, "控制板串口重开失败，稍后继续重试");
            }
        }
    }

    private void onValidBoardFrame() {
        lastFrameAt = SystemClock.elapsedRealtime();
        probeUnavailableSince = 0L;
        markConnected("控制板通信已恢复");
    }

    private void markConnected(String reason) {
        boolean changed;
        synchronized (this) {
            changed = !stateKnown || !connected;
            stateKnown = true;
            connected = true;
            lastReason = safe(reason);
        }
        if (changed) {
            Log.i(TAG, reason);
            broadcast(true, reason);
        }
    }

    private void markDisconnected(String reason) {
        boolean changed;
        synchronized (this) {
            changed = !stateKnown || connected;
            stateKnown = true;
            connected = false;
            lastReason = safe(reason);
        }
        if (changed) {
            Log.e(TAG, reason);
            broadcast(false, reason);
            openFaultScreen();
        }
    }

    private void broadcast(boolean online, String reason) {
        Intent intent = new Intent(AppConfig.ACTION_BOARD_CONNECTION_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_CONNECTED, online);
        intent.putExtra(EXTRA_REASON, safe(reason));
        context.sendBroadcast(intent);
    }

    private void openFaultScreen() {
        Intent intent = new Intent(context, ControllerFaultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(intent);
        } catch (Throwable error) {
            Log.e(TAG, "打开控制板故障界面失败", error);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
