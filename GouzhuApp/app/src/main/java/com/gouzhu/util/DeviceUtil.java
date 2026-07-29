package com.gouzhu.util;

import android.content.Context;
import android.provider.Settings;

import java.util.Locale;

/**
 * 设备编号与版本工具。
 */
public final class DeviceUtil {

    private DeviceUtil() {
    }

    /**
     * 获取并规范化正式 deviceNo。
     *
     * <p>当前项目不再生成随机兜底编号，避免设备号变化后与平台登记公钥不匹配。</p>
     */
    public static String getDeviceId(Context context) {
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

    /** 获取设备号，缺失时直接抛出生产配置错误。 */
    public static String requireDeviceNo(Context context) {
        String deviceNo = getDeviceId(context);
        if (deviceNo.isEmpty()) {
            throw new IllegalStateException("设备号为空，禁止生成随机deviceNo");
        }
        return deviceNo;
    }

    /** 平台统一设备号规范化规则。 */
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

    /** 将四段控制板版本转换成可上报整数。 */
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

    /** 将控制板 32 位版本值显示成四段版本。 */
    public static String formatBoardVersion(long value) {
        return ((value >>> 24) & 0xFF)
                + "." + ((value >>> 16) & 0xFF)
                + "." + ((value >>> 8) & 0xFF)
                + "." + (value & 0xFF);
    }
}
