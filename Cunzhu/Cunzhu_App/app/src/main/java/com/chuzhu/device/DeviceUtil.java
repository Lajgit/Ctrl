package com.chuzhu.device;

import android.content.Context;
import android.provider.Settings;

import java.util.Locale;

/**
 * 存珠机设备号与版本工具。
 */
public final class DeviceUtil {

    private DeviceUtil() {
    }

    /** 读取并规范化本机 deviceNo；第一阶段不生成随机设备号，避免激活身份漂移。 */
    public static String getDeviceNo(Context context) {
        String id = "";
        try {
            id = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
        } catch (Throwable ignored) {
        }
        return normalizeDeviceNo(id);
    }

    /** 缺少设备号时直接报错，禁止用临时随机值注册。 */
    public static String requireDeviceNo(Context context) {
        String deviceNo = getDeviceNo(context);
        if (deviceNo.isEmpty()) {
            throw new IllegalStateException("设备号为空，禁止生成随机 deviceNo");
        }
        return deviceNo;
    }

    public static String normalizeDeviceNo(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT)
                .replace(":", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .trim();
    }

    public static String getAppVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Throwable ignored) {
            return "0.0.0";
        }
    }

    public static long getAppVersionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 存珠机串口协议未确认前，固件版本按 0.0.0.0 上报。 */
    public static String getBoardVersion() {
        return "0.0.0.0";
    }

    public static int parseBoardVersionCode(String version) {
        if (version == null || version.trim().isEmpty()) {
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
}
