package com.gouzhu.mqtt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gouzhu.AppConfig;
import com.gouzhu.sdk.DeviceSdkManager;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;

/**
 * MQTT 在线状态日志接收器。
 *
 * <p>MqttManager 完成连接、订阅、首次心跳和状态上报后，会发送应用内状态广播。
 * 本接收器负责补充明确的 Logcat 成功日志，并在 MQTT 在线后重新读取一次
 * bootstrap，避免首页早于 MQTT 建链时长期显示设备离线。</p>
 *
 * <p>重新读取 bootstrap 只更新服务端状态快照，不直接控制现金硬件。纸币、硬币
 * 配置仍然只接受 MQTT sync_cash_configuration 指令。</p>
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

        // 第一处：明确打印 MQTT 连接/重连成功状态，便于从 Logcat 直接确认建链结果。
        Log.i(
                TAG,
                "收到MQTT状态：value=" + value
                        + "，clientConnected=" + connected
        );

        if (!("MQTT已连接".equals(value) || "MQTT已重连".equals(value))) {
            return;
        }

        // 第二处：MQTT上线后重新读取bootstrap，确认服务端是否已将设备判定为在线。
        Log.i(TAG, "MQTT连接已确认，开始重新读取bootstrap确认设备在线状态");
        DeviceSdkManager.get(context).refreshBootstrap(
                new DeviceSdkManager.BootstrapCallback() {
                    @Override
                    public void onSuccess(DeviceAppBootstrapResult result) {
                        Log.i(
                                TAG,
                                "MQTT上线后bootstrap刷新成功：resultNull="
                                        + (result == null)
                        );
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        Log.e(TAG, "MQTT上线后bootstrap刷新失败", error);
                    }
                }
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
