package com.gouzhu.hardware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

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

    private static final String TAG = "GouzhuCashConfig";

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
            int actualMask = (int) ((packed >>> 24) & 0xFF);
            long actualVersion = packed & 0x00FFFFFFL;
            ApplyWaiter active = waiter;

            Log.i(
                    TAG,
                    "收到控制板现金配置状态：actualMask=0x"
                            + Integer.toHexString(actualMask)
                            + "，actualVersion=" + actualVersion
                            + "，hasWaiter=" + (active != null)
            );

            if (active == null) {
                return;
            }
            active.actualMask = actualMask;
            active.actualVersion = actualVersion;
            if (active.actualMask == active.expectedMask
                    && active.actualVersion == active.expectedVersion) {
                active.matched = true;
                active.latch.countDown();
            } else {
                Log.w(
                        TAG,
                        "控制板现金配置状态不匹配：expectedMask=0x"
                                + Integer.toHexString(active.expectedMask)
                                + "，actualMask=0x"
                                + Integer.toHexString(active.actualMask)
                                + "，expectedVersion=" + active.expectedVersion
                                + "，actualVersion=" + active.actualVersion
                );
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
        Log.i(TAG, "现金配置适配器已启动，等待ttyS5控制板状态");
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
            } catch (Throwable error) {
                Log.w(TAG, "注销现金配置广播失败", error);
            }
            receiverRegistered = false;
        }
        Log.i(TAG, "现金配置适配器已停止");
    }

    @Override
    public CashConfigurationResult apply(long configVersion, List<CashTier> tiers) {
        Log.i(
                TAG,
                "开始应用现金配置：configVersion=" + configVersion
                        + "，tierCount=" + (tiers == null ? -1 : tiers.size())
        );

        if (configVersion <= 0L || configVersion > 0x00FFFFFFL) {
            Log.e(TAG, "现金配置版本无效：configVersion=" + configVersion);
            disableCashAcceptance();
            return CashConfigurationResult.rejected("configVersion超出控制板24位范围");
        }
        if (tiers == null || tiers.isEmpty()) {
            Log.w(TAG, "现金档位为空，仅保持三线硬币脉冲输入有效");
            return applyMask(configVersion, ALWAYS_ON_COIN_MASK);
        }

        int mask = ALWAYS_ON_COIN_MASK;
        for (int index = 0; index < tiers.size(); index++) {
            CashTier tier = tiers.get(index);
            if (tier == null
                    || tier.getDenominationAmount() <= 0
                    || tier.getMarbleQuantity() <= 0
                    || tier.getTierNo() == null
                    || tier.getTierNo().trim().isEmpty()) {
                Log.e(
                        TAG,
                        "现金档位不完整：index=" + index
                                + "，tier=" + summarizeTier(tier)
                );
                disableCashAcceptance();
                return CashConfigurationResult.rejected("现金档位不完整");
            }
            if ("banknote".equals(tier.getMediumType())) {
                mask |= BANKNOTE_MASK;
            } else if (!"coin".equals(tier.getMediumType())) {
                Log.e(
                        TAG,
                        "现金介质不支持：index=" + index
                                + "，mediumType=" + tier.getMediumType()
                );
                disableCashAcceptance();
                return CashConfigurationResult.rejected("不支持的现金介质");
            }
            Log.d(TAG, "现金档位：index=" + index + "，" + summarizeTier(tier));
        }
        return applyMask(configVersion, mask);
    }

    public CashConfigurationResult applyDisabled(long configVersion) {
        Log.i(TAG, "平台要求关闭可控现金入口：configVersion=" + configVersion);
        return applyMask(configVersion, ALWAYS_ON_COIN_MASK);
    }

    @Override
    public void disableCashAcceptance() {
        long version = Math.max(1L, Math.min(0x00FFFFFFL, lastConfigVersion));
        long packed = ((long) ALWAYS_ON_COIN_MASK << 24)
                | (version & 0x00FFFFFFL);
        boolean sent = SerialManager.get(context).sendCommand(
                CMD_CASH_APPLY,
                packed,
                true
        );
        if (sent) {
            Log.i(
                    TAG,
                    "已请求关闭纸钞机，三线硬币保持有效：version=" + version
            );
        } else {
            Log.e(
                    TAG,
                    "关闭纸钞机命令发送失败：version=" + version
                            + "，ttyS5可能未连接"
            );
        }
    }

    private CashConfigurationResult applyMask(long configVersion, int mask) {
        synchronized (applyLock) {
            ApplyWaiter active = new ApplyWaiter();
            active.expectedMask = (mask | ALWAYS_ON_COIN_MASK) & 0xFF;
            active.expectedVersion = configVersion;
            waiter = active;
            lastConfigVersion = configVersion;
            long startedAt = System.currentTimeMillis();
            try {
                long packed = ((long) active.expectedMask << 24)
                        | (configVersion & 0x00FFFFFFL);
                Log.i(
                        TAG,
                        "发送现金配置到控制板：expectedMask=0x"
                                + Integer.toHexString(active.expectedMask)
                                + "，expectedVersion=" + active.expectedVersion
                                + "，packed=0x" + Long.toHexString(packed)
                );
                if (!SerialManager.get(context).sendCommand(
                        CMD_CASH_APPLY,
                        packed,
                        true
                )) {
                    Log.e(
                            TAG,
                            "控制板现金配置命令发送失败：expectedMask=0x"
                                    + Integer.toHexString(active.expectedMask)
                                    + "，expectedVersion=" + active.expectedVersion
                                    + "，ttyS5可能未打开或发送队列失败"
                    );
                    return CashConfigurationResult.rejected("控制板现金配置命令发送失败");
                }
                if (!active.latch.await(APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.e(
                            TAG,
                            "等待控制板现金配置确认超时：timeout="
                                    + APPLY_TIMEOUT_MS + "ms"
                                    + "，expectedMask=0x"
                                    + Integer.toHexString(active.expectedMask)
                                    + "，expectedVersion=" + active.expectedVersion
                                    + "，lastActualMask=0x"
                                    + Integer.toHexString(active.actualMask)
                                    + "，lastActualVersion=" + active.actualVersion
                    );
                    return CashConfigurationResult.rejected("控制板未确认现金配置");
                }
                if (!active.matched) {
                    Log.e(
                            TAG,
                            "现金配置应用被中断或返回不匹配：expectedMask=0x"
                                    + Integer.toHexString(active.expectedMask)
                                    + "，actualMask=0x"
                                    + Integer.toHexString(active.actualMask)
                                    + "，expectedVersion=" + active.expectedVersion
                                    + "，actualVersion=" + active.actualVersion
                    );
                    return CashConfigurationResult.rejected("现金配置应用被中断");
                }
                Log.i(
                        TAG,
                        "现金配置应用成功：mask=0x"
                                + Integer.toHexString(active.actualMask)
                                + "，version=" + active.actualVersion
                                + "，耗时="
                                + (System.currentTimeMillis() - startedAt) + "ms"
                );
                return CashConfigurationResult.applied();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "等待现金配置确认被中断", error);
                return CashConfigurationResult.rejected("等待现金配置确认被中断");
            } catch (Throwable error) {
                Log.e(
                        TAG,
                        "现金配置应用发生未处理异常：configVersion="
                                + configVersion + "，mask=0x"
                                + Integer.toHexString(mask),
                        error
                );
                return CashConfigurationResult.rejected(
                        "现金配置应用异常：" + messageOf(error)
                );
            } finally {
                waiter = null;
            }
        }
    }

    private static String summarizeTier(CashTier tier) {
        if (tier == null) {
            return "null";
        }
        return "mediumType=" + tier.getMediumType()
                + "，denominationAmount=" + tier.getDenominationAmount()
                + "，marbleQuantity=" + tier.getMarbleQuantity()
                + "，tierNo=" + tier.getTierNo();
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
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
