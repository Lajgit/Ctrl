package com.gouzhu.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * WiFi 保存、连接与网络状态工具。
 *
 * <p>连接逻辑沿用 OTA_XLH3566 在 RK3566 Android 13 上使用的
 * WifiConfiguration 与隐藏 connect 接口方案。量产镜像需要给予系统级 WiFi 权限。</p>
 */
public final class WifiSupport {

    private static final String TAG = "GouzhuWifi";
    private static final String PREF = "wifi_credential";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASSWORD = "password";

    private WifiSupport() {
    }

    public static void save(Context context, String ssid, String password) {
        if (isEmpty(ssid)) {
            return;
        }

        context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SSID, ssid.trim())
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .apply();
    }

    public static String getSavedSsid(Context context) {
        return preferences(context).getString(KEY_SSID, "");
    }

    public static String getSavedPassword(Context context) {
        return preferences(context).getString(KEY_PASSWORD, "");
    }

    public static boolean hasSavedWifi(Context context) {
        return !isEmpty(getSavedSsid(context));
    }

    public static void clear(Context context) {
        preferences(context).edit().clear().apply();
    }

    /** 判断当前默认网络是否具备可用互联网。 */
    public static boolean isInternetConnected(Context context) {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }

            Network network = manager.getActiveNetwork();
            if (network == null) {
                return false;
            }

            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable error) {
            Log.e(TAG, "检查网络状态失败", error);
            return false;
        }
    }

    /** 获取当前连接的 WiFi 名称。 */
    @SuppressWarnings("deprecation")
    public static String getCurrentSsid(Context context) {
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (manager == null) {
                return "";
            }

            WifiInfo info = manager.getConnectionInfo();
            if (info == null || info.getSSID() == null) {
                return "";
            }

            String ssid = info.getSSID().trim();
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return "<unknown ssid>".equalsIgnoreCase(ssid) ? "" : ssid;
        } catch (Throwable error) {
            Log.e(TAG, "获取当前 WiFi 失败", error);
            return "";
        }
    }

    /**
     * 连接指定 WiFi。
     *
     * <p>此方法依赖系统应用权限；普通三方 APK 在 Android 13 上可能被系统拒绝。</p>
     */
    @SuppressWarnings("deprecation")
    public static boolean connectWifi(Context context, String ssid, String password) {
        if (context == null || isEmpty(ssid)) {
            return false;
        }

        try {
            Context appContext = context.getApplicationContext();
            WifiManager manager =
                    (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (manager == null) {
                return false;
            }

            if (!manager.isWifiEnabled()) {
                boolean enabled = manager.setWifiEnabled(true);
                Log.i(TAG, "开启 WiFi 结果=" + enabled);
                Thread.sleep(2000L);
            }

            WifiConfiguration configuration = buildConfiguration(ssid, password);
            removeOldConfiguration(manager, ssid);

            if (connectByHiddenApi(manager, configuration)) {
                return true;
            }

            int networkId = manager.addNetwork(configuration);
            if (networkId < 0) {
                Log.e(TAG, "addNetwork 失败，networkId=" + networkId);
                return false;
            }

            manager.disconnect();
            boolean enabled = manager.enableNetwork(networkId, true);
            boolean reconnected = manager.reconnect();
            return enabled && reconnected;
        } catch (Throwable error) {
            Log.e(TAG, "连接 WiFi 失败，ssid=" + ssid, error);
            return false;
        }
    }

    /** 尝试连接已保存的正式 WiFi。 */
    public static boolean connectSavedWifi(Context context) {
        if (!hasSavedWifi(context)) {
            return false;
        }
        return connectWifi(context, getSavedSsid(context), getSavedPassword(context));
    }

    /** 等待互联网连接稳定。 */
    public static boolean waitForInternet(Context context, int seconds) {
        int stableCount = 0;
        int remaining = Math.max(seconds, 1);

        while (remaining-- > 0) {
            if (isInternetConnected(context)) {
                stableCount++;
                if (stableCount >= 3) {
                    return true;
                }
            } else {
                stableCount = 0;
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static WifiConfiguration buildConfiguration(String ssid, String password) {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = quote(ssid.trim());
        configuration.status = WifiConfiguration.Status.ENABLED;

        if (isEmpty(password)) {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            configuration.preSharedKey = quotePassword(password.trim());
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }
        return configuration;
    }

    private static boolean connectByHiddenApi(
            WifiManager manager,
            WifiConfiguration configuration
    ) {
        try {
            Class<?> listenerClass =
                    Class.forName("android.net.wifi.WifiManager$ActionListener");
            Method connectMethod = WifiManager.class.getDeclaredMethod(
                    "connect",
                    WifiConfiguration.class,
                    listenerClass
            );
            connectMethod.setAccessible(true);
            connectMethod.invoke(manager, configuration, null);
            Log.i(TAG, "已通过隐藏接口发送 WiFi 连接请求");
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "隐藏 WiFi connect 接口不可用，回退到 addNetwork", error);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static void removeOldConfiguration(WifiManager manager, String ssid) {
        try {
            List<WifiConfiguration> configurations = manager.getConfiguredNetworks();
            if (configurations == null) {
                return;
            }

            String quotedSsid = quote(ssid.trim());
            for (WifiConfiguration item : configurations) {
                if (item != null && quotedSsid.equals(item.SSID)) {
                    manager.removeNetwork(item.networkId);
                }
            }
            manager.saveConfiguration();
        } catch (Throwable error) {
            Log.w(TAG, "清理旧 WiFi 配置失败", error);
        }
    }

    private static String quote(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value;
        }
        return "\"" + value + "\"";
    }

    private static String quotePassword(String value) {
        if (value.matches("[0-9A-Fa-f]{64}")) {
            return value;
        }
        return quote(value);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
