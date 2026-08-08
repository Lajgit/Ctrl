package com.gouzhu.hardware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.mqtt.CashRuntimeCoordinator;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationAdapter;
import com.pinball.xiaoda.device.sdk.hardware.CashConfigurationResult;
import com.pinball.xiaoda.device.sdk.hardware.CashTier;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 新版 SDK CashConfigurationAdapter 的 ttyS5 实现。
 *
 * <p>bit0控制纸钞机，bit1通过控制板PB13控制硬币器12V电源。
 * 目标掩码严格按完整cashSaleItems中的介质生成，控制板返回相同版本和
 * 实际掩码后才视为硬件应用成功。</p>
 *
 * <p>商家配置和当前运行可用状态是两个事实。任何非零现金掩码在真正发送前都必须
 * 通过 CashRuntimeCoordinator 的 MQTT、bootstrap、交易占用和控制板运行门控。</p>
 */
public final class SerialCashConfigurationAdapter implements CashConfigurationAdapter {

    private static final String TAG = "GouzhuCashConfig";

    private static final int CMD_CASH_APPLY_V21 = 0x18;
    private static final int CMD_CASH_APPLY_V22 = 0x33;
    private static final int EVT_CASH_ACCEPTANCE_STATUS = 0x11;
    private static final int BANKNOTE_MASK = 1;
    private static final int COIN_MASK = 2;
    private static final long APPLY_TIMEOUT_MS = 5_000L;

    /** 所有适配器实例共用一个锁，避免配置应用与运行状态恢复交叉操作同一控制板。 */
    private static final Object APPLY_LOCK = new Object();

    private final Context context;

    private volatile ApplyWaiter waiter;
    private volatile long lastAppliedConfigVersion = 1L;
    private volatile boolean protocolV22Ready;
    private int lastObservedActualMask = -1;
    private long lastObservedActualVersion = -1L;
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
            boolean stateChanged = actualMask != lastObservedActualMask
                    || actualVersion != lastObservedActualVersion;

            if (active != null || stateChanged) {
                Log.i(
                        TAG,
                        "收到控制板现金配置状态：actualMask=0x"
                                + Integer.toHexString(actualMask)
                                + "，actualVersion=" + actualVersion
                                + "，hasWaiter=" + (active != null)
                );
            }
            lastObservedActualMask = actualMask;
            lastObservedActualVersion = actualVersion;

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

    /**
     * SDK 兼容入口只用于已有配置的运行恢复。旧恢复链不能再产生
     * CASH_CONFIGURATION_REAPPLY_FAILED；配置损坏或运行条件暂不可用时都保守关闭，
     * 真正的新配置校验/应用失败只由 applyConfiguration 返回 rejected。
     */
    @Override
    public CashConfigurationResult apply(long configVersion, List<CashTier> tiers) {
        CashConfigurationResult validation = validateConfiguration(configVersion, tiers);
        if (validation != null) {
            disableCashAcceptance();
            Log.w(
                    TAG,
                    "本地已应用现金快照无法用于运行恢复，保持关闭：configVersion="
                            + configVersion + "，message=" + validation.getMessage()
            );
            return CashConfigurationResult.applied();
        }
        int mask = buildMask(tiers);
        if (!CashRuntimeCoordinator.get(context).isCashAcceptanceAllowed()) {
            disableCashAcceptance();
            Log.i(
                    TAG,
                    "运行门控暂不允许现金接收，仅保持硬件关闭：configVersion="
                            + configVersion + "，configuredMask=0x"
                            + Integer.toHexString(mask)
            );
            return CashConfigurationResult.applied();
        }

        CashConfigurationResult result = applyMask(configVersion, mask);
        if (result != null && result.isApplied()) {
            return result;
        }
        disableCashAcceptance();
        Log.w(
                TAG,
                "恢复现金运行状态失败，已保持关闭；不作为配置故障：configVersion="
                        + configVersion + "，message="
                        + (result == null ? "null" : result.getMessage())
        );
        return CashConfigurationResult.applied();
    }

    /**
     * 新 MQTT 配置的严格应用入口。即使 bootstrap 当前不可用，也会把相同 configVersion
     * 以 mask=0 写入并等待控制板确认；配置形成 success 后再由运行协调器刷新 bootstrap
     * 并决定是否真正打开现金输入。
     */
    public CashConfigurationResult applyConfiguration(
            long configVersion,
            List<CashTier> tiers
    ) {
        CashConfigurationResult validation = validateConfiguration(configVersion, tiers);
        if (validation != null) {
            disableCashAcceptance();
            return validation;
        }
        int configuredMask = buildMask(tiers);
        boolean runtimeAllowed = CashRuntimeCoordinator.get(context).isCashAcceptanceAllowed();
        int targetMask = runtimeAllowed ? configuredMask : 0;
        Log.i(
                TAG,
                "严格应用现金配置：configVersion=" + configVersion
                        + "，configuredMask=0x" + Integer.toHexString(configuredMask)
                        + "，runtimeAllowed=" + runtimeAllowed
                        + "，targetMask=0x" + Integer.toHexString(targetMask)
        );
        return applyMask(configVersion, targetMask);
    }

    public CashConfigurationResult applyConfigurationDisabled(long configVersion) {
        if (configVersion <= 0L || configVersion > 0x00FFFFFFL) {
            return CashConfigurationResult.rejected("configVersion超出控制板24位范围");
        }
        return applyMask(configVersion, 0);
    }

    /** 运行协调器专用入口，只改变当前现金输入掩码，不改变本地商家配置事实。 */
    public CashConfigurationResult applyRuntimeMask(long configVersion, int mask) {
        if (configVersion <= 0L || configVersion > 0x00FFFFFFL) {
            return CashConfigurationResult.rejected("configVersion超出控制板24位范围");
        }
        if ((mask & ~(BANKNOTE_MASK | COIN_MASK)) != 0 || mask < 0) {
            return CashConfigurationResult.rejected("现金运行掩码无效");
        }
        return applyMask(configVersion, mask);
    }

    /**
     * 旧运行时的禁用恢复入口同样只做故障关闭，不把控制板暂时无应答转换成
     * CASH_CONFIGURATION_REAPPLY_FAILED。新 MQTT 配置仍调用严格的
     * applyConfigurationDisabled 获取真实成功/失败结果。
     */
    public CashConfigurationResult applyDisabled(long configVersion) {
        CashConfigurationResult result = applyConfigurationDisabled(configVersion);
        if (result != null && result.isApplied()) {
            return result;
        }
        disableCashAcceptance();
        Log.w(
                TAG,
                "恢复禁用现金状态失败，继续保持关闭且不发布配置故障：configVersion="
                        + configVersion + "，message="
                        + (result == null ? "null" : result.getMessage())
        );
        return CashConfigurationResult.applied();
    }

    public void markApplied(long configVersion) {
        if (configVersion > 0L && configVersion <= 0x00FFFFFFL) {
            lastAppliedConfigVersion = configVersion;
        }
    }

    public void setProtocolV22Ready(boolean ready) {
        protocolV22Ready = ready;
    }

    @Override
    public void disableCashAcceptance() {
        long version = Math.max(
                1L,
                Math.min(0x00FFFFFFL, lastAppliedConfigVersion)
        );
        long packed = version & 0x00FFFFFFL;
        boolean sent = SerialManager.get(context).sendCommand(
                protocolV22Ready ? CMD_CASH_APPLY_V22 : CMD_CASH_APPLY_V21,
                packed,
                true
        );
        if (sent) {
            Log.i(TAG, "已请求关闭纸钞机和硬币器：version=" + version);
        } else {
            Log.e(TAG, "关闭现金设备命令发送失败：version=" + version
                    + "，ttyS5可能未连接");
        }
    }

    private CashConfigurationResult validateConfiguration(
            long configVersion,
            List<CashTier> tiers
    ) {
        if (configVersion <= 0L || configVersion > 0x00FFFFFFL) {
            Log.e(TAG, "现金配置版本无效：configVersion=" + configVersion);
            return CashConfigurationResult.rejected("configVersion超出控制板24位范围");
        }
        if (tiers == null || tiers.isEmpty()) {
            Log.e(TAG, "cashAcceptanceEnabled=true但现金档位为空");
            return CashConfigurationResult.rejected("可用现金配置的档位不能为空");
        }
        for (int index = 0; index < tiers.size(); index++) {
            CashTier tier = tiers.get(index);
            if (tier == null
                    || tier.getDenominationAmount() <= 0
                    || tier.getMarbleQuantity() <= 0
                    || tier.getTierNo() == null
                    || tier.getTierNo().trim().isEmpty()) {
                Log.e(TAG, "现金档位不完整：index=" + index
                        + "，tier=" + summarizeTier(tier));
                return CashConfigurationResult.rejected("现金档位不完整");
            }
            if (!"banknote".equals(tier.getMediumType())
                    && !"coin".equals(tier.getMediumType())) {
                Log.e(TAG, "现金介质不支持：index=" + index
                        + "，mediumType=" + tier.getMediumType());
                return CashConfigurationResult.rejected("不支持的现金介质");
            }
            Log.d(TAG, "现金档位：index=" + index + "，" + summarizeTier(tier));
        }
        return null;
    }

    private static int buildMask(List<CashTier> tiers) {
        int mask = 0;
        for (CashTier tier : tiers) {
            if ("banknote".equals(tier.getMediumType())) {
                mask |= BANKNOTE_MASK;
            } else if ("coin".equals(tier.getMediumType())) {
                mask |= COIN_MASK;
            }
        }
        return mask;
    }

    private CashConfigurationResult applyMask(long configVersion, int mask) {
        if (mask != 0 && !protocolV22Ready) {
            disableCashAcceptance();
            return CashConfigurationResult.rejected("控制板协议未确认，禁止启用现金");
        }
        if (mask != 0 && !TransactionOccupancyManager.get(context).isIdle()) {
            disableCashAcceptance();
            return CashConfigurationResult.rejected("设备存在交易占用，禁止启用现金");
        }
        if (mask != 0 && !CashRuntimeCoordinator.get(context).isCashAcceptanceAllowed()) {
            disableCashAcceptance();
            return CashConfigurationResult.rejected("当前运行状态不允许现金接收");
        }

        synchronized (APPLY_LOCK) {
            ApplyWaiter active = new ApplyWaiter();
            active.expectedMask = mask & 0xFF;
            active.expectedVersion = configVersion;
            waiter = active;
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
                        protocolV22Ready ? CMD_CASH_APPLY_V22 : CMD_CASH_APPLY_V21,
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
