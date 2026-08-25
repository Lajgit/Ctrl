package com.chuzhu.mqtt;

import android.content.Context;

/**
 * heartbeat 上报。
 */
public final class HeartbeatReporter {

    private final Context context;

    public HeartbeatReporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean report() {
        return MqttManager.get(context).publishReport("heartbeat", "{}");
    }
}
