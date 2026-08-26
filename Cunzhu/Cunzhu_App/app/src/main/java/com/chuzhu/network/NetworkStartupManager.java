package com.chuzhu.network;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * 存珠机启动联网检查。
 *
 * <p>启动设备激活前必须先确认存在 VALIDATED 互联网。若系统已经保存过 WiFi，
 * 优先打开 WiFi 并触发系统自动重连；仍无法联网时再交给前台 Activity 打开系统 WiFi 面板。</p>
 *
 * <p>Android 10 以后普通三方应用通常不能直接开关 WiFi、也不能读取系统保存网络列表；
 * RK3566 量产镜像若授予系统级 WiFi 权限可继续使用这些能力。代码对受限场景全部做了
 * 降级处理，不会因为 SecurityException 阻塞设备启动。</p>
 */
public final class NetworkStartupManager {

    private static final String TAG = "CunzhuNetwork";
    private static final String PREF = "chuzhu_network_startup_v1";
    private static final String KEY_WIFI_CONNECTED_BEFORE = "wifi_connected_before";

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

    /**
     * 判断设备是否存在可优先尝试的历史 WiFi。
     *
     * <p>首先使用本 APP 曾经成功通过 WiFi 访问互联网的事实；其次在系统权限允许时读取
     * 系统保存网络。Android 10+ 普通三方 APP 可能无法读取 configuredNetworks，所以
     * “返回 false”只代表 APP 无法确认，不代表系统一定没有保存网络。</p>
     */
    @SuppressWarnings("deprecation")
    public boolean hasSavedWifiInformation() {
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
            Log.w(TAG, "系统限制读取已保存 WiFi，回退到系统自动重连");
            return false;
        } catch (Throwable error) {
            Log.w(TAG, "读取已保存 WiFi 失败", error);
            return false;
        }
    }

    /**
     * 后台线程调用：优先尝试系统已经保存的 WiFi。
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

        boolean savedWifiKnown = hasSavedWifiInformation();
        try {
            if (wifiManager.isWifiEnabled()) {
                /*
                 * 即使普通三方 APP 无权读取 configuredNetworks，也先给 Android 自己的
                 * 已保存网络自动重连留出时间。这样不会把“列表不可见”误判成“从未配网”。
                 */
                triggerReconnect();
                if (waitForInternet(waitSeconds)) {
                    return PrepareResult.online(
                            savedWifiKnown ? "已自动连接保存的 WiFi" : "系统已自动恢复 WiFi 网络"
                    );
                }
                return PrepareResult.needWifiUi(
                        savedWifiKnown
                                ? "已尝试连接保存的 WiFi，但仍无法访问互联网"
                                : "未自动连接到可用 WiFi，请选择网络"
                );
            }

            if (!savedWifiKnown) {
                /* WiFi 已关闭且 APP 无法确认历史网络时，不盲等，直接让用户进入系统面板。 */
                return PrepareResult.needWifiUi("未检测到可恢复的 WiFi，请连接网络");
            }

            /*
             * Android 10+ 普通三方 APP 调用 setWifiEnabled 会返回 false；量产系统应用/
             * 定制 ROM 若允许则可直接开启。失败时必须回退系统 WiFi 面板，不能循环重试。
             */
            boolean enabled = wifiManager.setWifiEnabled(true);
            Log.i(TAG, "检测到历史 WiFi，尝试开启 WiFi，result=" + enabled);
            if (!enabled) {
                return PrepareResult.needWifiUi("系统未允许 APP 自动打开 WiFi，请手动开启");
            }

            /* 给 WiFi 状态机一点启动时间，再触发系统保存网络重连。 */
            sleepQuietly(1500L);
            triggerReconnect();
            if (waitForInternet(waitSeconds)) {
                return PrepareResult.online("已自动打开并连接保存的 WiFi");
            }
            return PrepareResult.needWifiUi("已打开 WiFi，但保存的网络未能联网，请重新选择网络");
        } catch (SecurityException error) {
            Log.w(TAG, "自动打开/连接 WiFi 被系统拒绝", error);
            return PrepareResult.needWifiUi("系统限制 APP 自动连接 WiFi，请手动连接");
        } catch (Throwable error) {
            Log.e(TAG, "自动连接保存 WiFi 失败", error);
            return PrepareResult.needWifiUi("自动连接 WiFi 失败，请手动连接");
        }
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

    public static final class PrepareResult {
        public static final int ONLINE = 0;
        public static final int NEED_PERMISSION = 1;
        public static final int NEED_WIFI_UI = 2;

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

        private static PrepareResult online(String message) {
            return new PrepareResult(ONLINE, message);
        }

        private static PrepareResult needPermission(String message) {
            return new PrepareResult(NEED_PERMISSION, message);
        }

        private static PrepareResult needWifiUi(String message) {
            return new PrepareResult(NEED_WIFI_UI, message);
        }
    }
}
