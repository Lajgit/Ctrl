package com.chuzhu.network;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 存珠机启动联网检查与 WiFi 凭证连接。
 *
 * <p>正式启动顺序必须是：先联网，再激活/重新激活，再连接 MQTT。这里同时支持两类场景：</p>
 * <ul>
 *     <li>量产系统/系统签名允许旧 WiFi API：使用 APP 保存的 SSID/密码自动打开并连接。</li>
 *     <li>普通 Android 10+ 限制旧 API：保存用户输入，尝试系统自动恢复，失败后引导系统添加网络。</li>
 * </ul>
 */
public final class NetworkStartupManager {

    private static final String TAG = "CunzhuNetwork";
    private static final String PREF = "chuzhu_network_startup_v2";
    private static final String KEY_WIFI_CONNECTED_BEFORE = "wifi_connected_before";
    private static final String KEY_SAVED_SSID = "saved_ssid";
    private static final String KEY_SAVED_PASSWORD = "saved_password";

    private final Context context;
    private final ConnectivityManager connectivityManager;
    private final WifiManager wifiManager;

    public NetworkStartupManager(Context context) {
        this.context = context.getApplicationContext();
        connectivityManager = (ConnectivityManager) this.context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    /** 当前默认网络是否真正具备互联网，而不只是拿到了局域网 IP。 */
    public boolean hasValidatedInternet() {
        try {
            if (connectivityManager == null) {
                return false;
            }
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(network);
            boolean connected = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (connected) {
                rememberValidatedWifi(capabilities);
            }
            return connected;
        } catch (Throwable error) {
            Log.w(TAG, "检查互联网状态失败", error);
            return false;
        }
    }

    /** Android 13+ 查询/管理附近 WiFi 时需要运行时授权。 */
    public boolean needsNearbyWifiPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED;
    }

    public void saveWifiCredential(String ssid, String password) {
        if (blank(ssid)) {
            return;
        }
        preferences().edit()
                .putString(KEY_SAVED_SSID, ssid.trim())
                .putString(KEY_SAVED_PASSWORD, password == null ? "" : password)
                .apply();
    }

    public void clearWifiCredential() {
        preferences().edit()
                .remove(KEY_SAVED_SSID)
                .remove(KEY_SAVED_PASSWORD)
                .remove(KEY_WIFI_CONNECTED_BEFORE)
                .apply();
    }

    public String getSavedSsid() {
        return preferences().getString(KEY_SAVED_SSID, "");
    }

    public String getSavedPassword() {
        return preferences().getString(KEY_SAVED_PASSWORD, "");
    }

    public boolean hasAppWifiCredential() {
        return !blank(getSavedSsid());
    }

    /**
     * 判断设备是否存在可优先尝试的历史 WiFi。
     *
     * <p>优先使用本 APP 保存的 SSID/密码；其次使用本 APP 曾经通过 WiFi 成功联网的事实；
     * 最后在系统权限允许时读取 configuredNetworks。Android 10+ 普通三方 APP 可能无法读取
     * configuredNetworks，因此 false 只代表 APP 无法确认，不代表系统一定没有保存网络。</p>
     */
    @SuppressWarnings("deprecation")
    public boolean hasSavedWifiInformation() {
        if (hasAppWifiCredential()) {
            return true;
        }
        if (preferences().getBoolean(KEY_WIFI_CONNECTED_BEFORE, false)) {
            return true;
        }
        if (wifiManager == null || needsNearbyWifiPermission()) {
            return false;
        }
        try {
            List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
            return configured != null && !configured.isEmpty();
        } catch (SecurityException error) {
            Log.w(TAG, "系统限制读取已保存 WiFi，回退到触屏配置");
            return false;
        } catch (Throwable error) {
            Log.w(TAG, "读取已保存 WiFi 失败", error);
            return false;
        }
    }

    /**
     * 后台线程调用：激活前联网准备。
     *
     * @param waitSeconds 打开/重连后最多等待多少秒
     */
    @SuppressWarnings("deprecation")
    public PrepareResult prepareBeforeActivation(int waitSeconds) {
        if (hasValidatedInternet()) {
            return PrepareResult.online("网络已连接");
        }
        if (needsNearbyWifiPermission()) {
            return PrepareResult.needPermission("需要 WiFi 权限后才能检查并恢复网络");
        }
        if (wifiManager == null) {
            return PrepareResult.needWifiUi("系统 WiFi 服务不可用");
        }

        if (hasAppWifiCredential()) {
            return connectWifiWithCredential(getSavedSsid(), getSavedPassword(), waitSeconds);
        }

        boolean savedWifiKnown = hasSavedWifiInformation();
        try {
            if (wifiManager.isWifiEnabled()) {
                /*
                 * 即使普通三方 APP 无权读取 configuredNetworks，也先给 Android 自己的
                 * 已保存网络自动重连留出时间。这样不会把“列表不可见”误判成“从未配网”。
                 */
                triggerReconnect();
                if (waitForInternet(Math.min(waitSeconds, 8))) {
                    return PrepareResult.online(
                            savedWifiKnown ? "已自动连接保存的 WiFi" : "系统已自动恢复 WiFi 网络"
                    );
                }
                return PrepareResult.needWifiUi("未检测到可用网络，请在设备屏输入 WiFi 名称和密码");
            }

            if (!savedWifiKnown) {
                return PrepareResult.needWifiUi("未保存 WiFi 信息，请在设备屏输入 WiFi 名称和密码");
            }

            boolean enabled = wifiManager.setWifiEnabled(true);
            Log.i(TAG, "检测到历史 WiFi，尝试开启 WiFi，result=" + enabled);
            if (!enabled) {
                return PrepareResult.needWifiUi("系统未允许 APP 自动打开 WiFi，请手动输入并连接");
            }

            sleepQuietly(1500L);
            triggerReconnect();
            if (waitForInternet(waitSeconds)) {
                return PrepareResult.online("已自动打开并连接保存的 WiFi");
            }
            return PrepareResult.needWifiUi("已打开 WiFi，但保存的网络未能联网，请重新输入 WiFi");
        } catch (SecurityException error) {
            Log.w(TAG, "自动打开/连接 WiFi 被系统拒绝", error);
            return PrepareResult.needWifiUi("系统限制 APP 自动连接 WiFi，请在设备屏输入网络信息");
        } catch (Throwable error) {
            Log.e(TAG, "自动连接保存 WiFi 失败", error);
            return PrepareResult.needWifiUi("自动连接 WiFi 失败，请重新输入网络信息");
        }
    }

    /** 使用 APP 保存/用户输入的 SSID 和密码尝试连接。必须在后台线程调用。 */
    @SuppressWarnings("deprecation")
    public PrepareResult connectWifiWithCredential(String ssid, String password, int waitSeconds) {
        if (blank(ssid)) {
            return PrepareResult.needWifiUi("WiFi 名称不能为空");
        }
        if (hasValidatedInternet()) {
            saveWifiCredential(ssid, password);
            return PrepareResult.online("网络已连接");
        }
        if (needsNearbyWifiPermission()) {
            return PrepareResult.needPermission("需要 WiFi 权限后才能连接网络");
        }
        if (wifiManager == null) {
            return PrepareResult.needWifiUi("系统 WiFi 服务不可用");
        }

        String normalizedSsid = ssid.trim();
        try {
            if (!wifiManager.isWifiEnabled()) {
                boolean enabled = wifiManager.setWifiEnabled(true);
                Log.i(TAG, "尝试开启 WiFi，result=" + enabled);
                sleepQuietly(1500L);
                if (!enabled && !wifiManager.isWifiEnabled()) {
                    return PrepareResult.needSystemAddNetwork(
                            "系统未允许 APP 自动打开 WiFi，请通过系统确认连接"
                    );
                }
            }

            boolean requestSent = connectByLegacyConfiguration(normalizedSsid, password);
            if (!requestSent) {
                triggerReconnect();
            }

            if (waitForInternet(Math.max(waitSeconds, 20))) {
                saveWifiCredential(normalizedSsid, password);
                return PrepareResult.online("WiFi 已连接：" + normalizedSsid);
            }
            return PrepareResult.needSystemAddNetwork(
                    "已尝试连接 WiFi，但未获得可用互联网：" + normalizedSsid
            );
        } catch (SecurityException error) {
            Log.w(TAG, "连接 WiFi 被系统权限限制", error);
            return PrepareResult.needSystemAddNetwork("系统限制 APP 直接连接 WiFi，请通过系统确认连接");
        } catch (Throwable error) {
            Log.e(TAG, "连接 WiFi 失败 ssid=" + normalizedSsid, error);
            return PrepareResult.needSystemAddNetwork("连接 WiFi 失败，请通过系统确认连接");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean connectByLegacyConfiguration(String ssid, String password) {
        try {
            WifiConfiguration configuration = buildConfiguration(ssid, password);
            removeOldConfiguration(ssid);
            int networkId = wifiManager.addNetwork(configuration);
            if (networkId < 0) {
                Log.w(TAG, "addNetwork 失败，networkId=" + networkId);
                return false;
            }
            wifiManager.disconnect();
            boolean enabled = wifiManager.enableNetwork(networkId, true);
            boolean reconnected = wifiManager.reconnect();
            Log.i(TAG, "legacy WiFi 连接请求：networkId=" + networkId
                    + "，enabled=" + enabled + "，reconnect=" + reconnected);
            return enabled || reconnected;
        } catch (SecurityException error) {
            throw error;
        } catch (Throwable error) {
            Log.w(TAG, "legacy WiFi 连接接口不可用", error);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void removeOldConfiguration(String ssid) {
        try {
            List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
            if (configured == null) {
                return;
            }
            String quoted = quote(ssid);
            for (WifiConfiguration item : configured) {
                if (item != null && quoted.equals(item.SSID)) {
                    wifiManager.removeNetwork(item.networkId);
                }
            }
            wifiManager.saveConfiguration();
        } catch (Throwable error) {
            Log.w(TAG, "清理旧 WiFi 配置失败", error);
        }
    }

    private static WifiConfiguration buildConfiguration(String ssid, String password) {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = quote(ssid);
        configuration.status = WifiConfiguration.Status.ENABLED;
        if (blank(password)) {
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

    @SuppressWarnings("deprecation")
    private void triggerReconnect() {
        try {
            boolean result = wifiManager.reconnect();
            Log.i(TAG, "触发系统 WiFi 自动重连，result=" + result);
        } catch (Throwable error) {
            Log.w(TAG, "触发系统 WiFi 自动重连失败，将继续等待网络", error);
        }
    }

    public boolean waitForInternet(int seconds) {
        int stableCount = 0;
        int remaining = Math.max(1, seconds);
        while (remaining-- > 0) {
            if (hasValidatedInternet()) {
                stableCount++;
                if (stableCount >= 2) {
                    return true;
                }
            } else {
                stableCount = 0;
            }
            if (!sleepQuietly(1000L)) {
                return false;
            }
        }
        return false;
    }

    /** 构造系统“添加 WiFi 网络”Intent，普通 Android 10+ 需要用户确认后才会保存网络。 */
    public static Intent buildSystemAddNetworkIntent(Context context, String ssid, String password) {
        Intent addNetwork = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !blank(ssid)) {
            try {
                WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                        .setSsid(ssid.trim());
                if (!blank(password)) {
                    builder.setWpa2Passphrase(password);
                }
                ArrayList<WifiNetworkSuggestion> suggestions = new ArrayList<>();
                suggestions.add(builder.build());
                addNetwork = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                        .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, suggestions);
            } catch (Throwable error) {
                Log.w(TAG, "构造系统添加 WiFi 网络 Intent 失败", error);
            }
        }
        if (addNetwork != null && addNetwork.resolveActivity(context.getPackageManager()) != null) {
            return addNetwork;
        }
        Intent panel = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        if (panel.resolveActivity(context.getPackageManager()) != null) {
            return panel;
        }
        Intent wifiSettings = new Intent(Settings.ACTION_WIFI_SETTINGS);
        if (wifiSettings.resolveActivity(context.getPackageManager()) != null) {
            return wifiSettings;
        }
        return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void rememberValidatedWifi(NetworkCapabilities capabilities) {
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            preferences().edit().putBoolean(KEY_WIFI_CONNECTED_BEFORE, true).apply();
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String quote(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("\"") && text.endsWith("\"")) {
            return text;
        }
        return "\"" + text + "\"";
    }

    private static String quotePassword(String value) {
        if (value != null && value.matches("[0-9A-Fa-f]{64}")) {
            return value;
        }
        return quote(value);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class PrepareResult {
        public static final int ONLINE = 0;
        public static final int NEED_PERMISSION = 1;
        public static final int NEED_WIFI_UI = 2;
        public static final int NEED_SYSTEM_ADD_NETWORK = 3;

        public final int state;
        public final String message;

        private PrepareResult(int state, String message) {
            this.state = state;
            this.message = message == null ? "" : message;
        }

        public boolean isOnline() {
            return state == ONLINE;
        }

        public boolean needsPermission() {
            return state == NEED_PERMISSION;
        }

        public boolean needsSystemAddNetwork() {
            return state == NEED_SYSTEM_ADD_NETWORK;
        }

        private static PrepareResult online(String message) {
            return new PrepareResult(ONLINE, message);
        }

        private static PrepareResult needPermission(String message) {
            return new PrepareResult(NEED_PERMISSION, message);
        }

        private static PrepareResult needWifiUi(String message) {
            return new PrepareResult(NEED_WIFI_UI, message);
        }

        private static PrepareResult needSystemAddNetwork(String message) {
            return new PrepareResult(NEED_SYSTEM_ADD_NETWORK, message);
        }
    }
}
