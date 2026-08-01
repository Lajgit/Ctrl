package com.gouzhu.hardware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.gouzhu.AppConfig;
import com.gouzhu.serial.SerialManager;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationAdapter;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;
import com.pinball.xiaoda.device.sdk.hardware.CashTier;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 新版 SDK CashConfigurationAdapter 的 ttyS5 实现。
 *
 * <p>硬币器只有12V、GND和脉冲三根线，无法由软件物理关闭，因此控制板
 * 状态中的硬币位始终为1。所谓disable只关闭纸钞机。</p>
 */
public final class SerialCashConfigurationAdapter implements CashConfigurationAdapter {

    private static final int CMD_CASH_APPLY = 0x18;
    private static final int EVT_CASH_ACCEPTANCE_STATUS = 0x11;
    private static final int BANKNOTE_MASK = 1;
    private static final int ALWAYS_ON_COIN_MASK = 2;
    private static final long APPLY_TIMEOUT_MS = 5_000L;

    private final Context context;
    private final Object applyLock = new Object();

    private volatile ApplyWaiter waiter;
    private volatile long lastConfigVersion = 1L;
    private boolean receiverRegistered;

    private final BroadcastReceiver boardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())
                    || intent.getIntExtra("code2", -1) != EVT_CASH_ACCEPTANCE_STATUS) {
                return;
            }
            long packed = intent.getLongExtra("data", 0L);
            ApplyWaiter active = waiter;
            if (active == null) {
                return;
            }
            active.actualMask = (int) ((packed >>> 24) & 0xFF);
            active.actualVersion = packed & 0x00FFFFFFL;
            if (active.actualMask == active.expectedMask
                    && active.actualVersion == active.expectedVersion) {
                active.matched = true;
                active.latch.countDown();
            }
        }
    };

    public SerialCashConfigurationAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(boardReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(boardReceiver, filter);
        }
        receiverRegistered = true;
    }

    public synchronized void stop() {
        ApplyWaiter active = waiter;
        if (active != null) {
            active.latch.countDown();
        }
        waiter = null;
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(boardReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
    }

    @Override
    public CashConfigurationResult apply(long configVersion, List<CashTier> tiers) {
        if (configVersion <= 0L || configVersion > 0x00FFFFFFL) {
            disableCashAcceptance();
            return CashConfigurationResult.rejected("configVersion超出控制板24位范围");
        }
        if (tiers == null || tiers.isEmpty()) {
            return applyMask(configVersion, ALWAYS_ON_COIN_MASK);
        }

        int mask = ALWAYS_ON_COIN_MASK;
        for (CashTier tier : tiers) {
            if (tier == null
                    || tier.getDenominationAmount() <= 0
                    || tier.getMarbleQuantity() <= 0
                    || tier.getTierNo() == null
                    || tier.getTierNo().trim().isEmpty()) {
                disableCashAcceptance();
                return CashConfigurationResult.rejected("现金档位不完整");
            }
            if ("banknote".equals(tier.getMediumType())) {
                mask |= BANKNOTE_MASK;
            } else if (!"coin".equals(tier.getMediumType())) {
                disableCashAcceptance();
                return CashConfigurationResult.rejected("不支持的现金介质");
            }
        }
        return applyMask(configVersion, mask);
    }

    public CashConfigurationResult applyDisabled(long configVersion) {
        return applyMask(configVersion, ALWAYS_ON_COIN_MASK);
    }

    @Override
    public void disableCashAcceptance() {
        long version = Math.max(1L, Math.min(0x00FFFFFFL, lastConfigVersion));
        long packed = ((long) ALWAYS_ON_COIN_MASK << 24)
                | (version & 0x00FFFFFFL);
        SerialManager.get(context).sendCommand(CMD_CASH_APPLY, packed, true);
    }

    private CashConfigurationResult applyMask(long configVersion, int mask) {
        synchronized (applyLock) {
            ApplyWaiter active = new ApplyWaiter();
            active.expectedMask = (mask | ALWAYS_ON_COIN_MASK) & 0xFF;
            active.expectedVersion = configVersion;
            waiter = active;
            lastConfigVersion = configVersion;
            try {
                long packed = ((long) active.expectedMask << 24)
                        | (configVersion & 0x00FFFFFFL);
                if (!SerialManager.get(context).sendCommand(
                        CMD_CASH_APPLY,
                        packed,
                        true
                )) {
                    return CashConfigurationResult.rejected("控制板现金配置命令发送失败");
                }
                if (!active.latch.await(APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return CashConfigurationResult.rejected("控制板未确认现金配置");
                }
                if (!active.matched) {
                    return CashConfigurationResult.rejected("现金配置应用被中断");
                }
                return CashConfigurationResult.applied();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return CashConfigurationResult.rejected("等待现金配置确认被中断");
            } finally {
                waiter = null;
            }
        }
    }

    private static final class ApplyWaiter {
        final CountDownLatch latch = new CountDownLatch(1);
        int expectedMask;
        long expectedVersion;
        int actualMask;
        long actualVersion;
        boolean matched;
    }
}
