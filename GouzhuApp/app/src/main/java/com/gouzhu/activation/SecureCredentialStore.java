package com.gouzhu.activation;

import android.content.Context;

import com.gouzhu.sdk.SdkCredentialStore;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

/**
 * 兼容旧代码名称的 SDK 凭证存储入口。
 *
 * <p>真实读写统一委托给 {@link SdkCredentialStore}；不再维护第二份凭证格式，
 * 防止认证模块和 MQTT 模块读取到不同快照。</p>
 */
@Deprecated
public final class SecureCredentialStore {

    private SecureCredentialStore() {
    }

    public static boolean save(Context context, MqttCredential credential) {
        try {
            SdkCredentialStore.get(context).replaceAtomically(credential);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    public static MqttCredential load(Context context) {
        return SdkCredentialStore.get(context).load();
    }

    public static void clear(Context context) {
        SdkCredentialStore.get(context).clear();
    }
}
