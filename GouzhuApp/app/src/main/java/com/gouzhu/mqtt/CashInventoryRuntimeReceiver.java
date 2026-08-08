package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.gouzhu.AppConfig;

/**
 * 库存事件只触发现金运行可用性刷新，不修改 MQTT 现金配置事实。
 *
 * <p>库存为0、库存数变化或K1补珠后，先作废旧 bootstrap.available 并保持现金关闭，
 * 再重新读取服务端 cashSale.available。这样库存恢复必须由服务端最新运行状态确认，
 * 不能因为本地 cash_blocked 被清除就直接沿用旧 available=true。</p>
 */
public final class CashInventoryRuntimeReceiver extends BroadcastReceiver {

    private static final int EVT_BEAD_STOCK = 0x20;
    private static final int EVT_BEAD_EMPTY = 0x22;
    private static final int EVT_BEAD_REFILLED = 0x23;

    @Override
    public void onReceive(Context receiverContext, Intent intent) {
        if (receiverContext == null
                || intent == null
                || !AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
            return;
        }

        int code = intent.getIntExtra("code2", -1);
        if (code != EVT_BEAD_STOCK
                && code != EVT_BEAD_EMPTY
                && code != EVT_BEAD_REFILLED) {
            return;
        }

        CashRuntimeCoordinator.get(receiverContext.getApplicationContext())
                .onInventoryChanged(code, intent.getLongExtra("data", 0L));
    }
}
