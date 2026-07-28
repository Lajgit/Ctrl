package com.gouzhu.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Locale;
import java.util.UUID;

/**
 * 设备编号与版本工具。
 */
public final class DeviceUtil {

    private static final String PREF = "device_identity";
    private static final String KEY_FALLBACK_ID = "fallback_device_id";

    private DeviceUtil() {
    }

    /**
     * 获取稳定设备号。
     *
     * <p>优先使用 ANDROID_ID；取不到时生成 UUID 并持久化。</p>
     */
    public static String getDeviceId(Context context) {
        String id = null;

        try {
            id = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
        } catch (Throwable ignored) {
        }

        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);

        if (isEmpty(id)) {
            id = preferences.getString(KEY_FALLBACK_ID, "");
        }

        if (isEmpty(id)) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_FALLBACK_ID, id).apply();
        }

        return id.toUpperCase(Locale.ROOT)
                .replace(":", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    /** 获取当前 App 版本名。 */
    public static String getAppVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Throwable ignored) {
            return "0.0.0";
        }
    }

    /** 获取当前 App versionCode。 */
    public static long getAppVersionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 将形如 1.2.3.4 的固件版本转换成控制板升级协议的 32 位版本值。
     */
    public static int parseBoardVersionCode(String version) {
        if (isEmpty(version)) {
            return 0;
        }

        String[] parts = version.trim().split("\\.");
        int result = 0;
        for (int index = 0; index < 4; index++) {
            int value = 0;
            if (index < parts.length) {
                try {
                    value = Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {
                    value = 0;
                }
            }
            result = (result << 8) | (value & 0xFF);
        }
        return result;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
