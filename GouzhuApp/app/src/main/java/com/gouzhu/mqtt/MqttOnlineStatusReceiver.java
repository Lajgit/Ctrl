package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.AppConfig;

/**
 * MQTT 在线状态接收器。
 *
 * <p>MqttManager 在连接、重连、断线和连接失败时都会发送应用内状态广播。
 * 本接收器把这些变化统一交给 CashRuntimeCoordinator：断线立即关闭现金输入；
 * 重连后先保持关闭，再在首次 heartbeat/status 之后重新读取 bootstrap，只有新的
 * cashSale.available=true 才允许恢复收现。</p>
 */
public final class MqttOnlineStatusReceiver extends BroadcastReceiver {

    private static final String TAG = "GouzhuMqttOnline";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null
                || intent == null
                || !AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
            return;
        }

        String key = safe(intent.getStringExtra("key"));
        if (!"mqtt".equals(key)) {
            return;
        }

        String value = safe(intent.getStringExtra("value"));
        boolean connected = MqttManager.get(context).isConnected();
        Log.i(
                TAG,
                "收到MQTT状态：value=" + value
                        + "，clientConnected=" + connected
        );

        CashRuntimeCoordinator.get(context).onMqttStatusChanged(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
