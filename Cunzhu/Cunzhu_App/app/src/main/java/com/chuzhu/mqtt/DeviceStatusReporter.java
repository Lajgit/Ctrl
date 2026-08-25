package com.chuzhu.mqtt;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.chuzhu.AppConfig;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.device.DeviceUtil;

import org.json.JSONObject;

/**
 * 存珠机 status 上报。
 */
public final class DeviceStatusReporter {

    private final Context context;

    public DeviceStatusReporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean report() {
        try {
            DeviceStateRepository state = DeviceStateRepository.get(context);
            int runningStatus = state.getRunningStatus();
            if (runningStatus == AppConfig.STATUS_IN_GAME) {
                runningStatus = AppConfig.STATUS_FAULT;
            }
            JSONObject json = new JSONObject();
            json.put("deviceType", AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE);
            json.put("runningStatus", runningStatus);
            json.put("publicIp", "");
            json.put("privateIp", getPrivateIp());
            json.put("networkType", getNetworkType());
            json.put("privateHttpBaseUrl", "");
            json.put("apkVersion", DeviceUtil.getAppVersion(context));
            json.put("apkVersionCode", DeviceUtil.getAppVersionCode(context));
            json.put("firmwareVersion", DeviceUtil.getBoardVersion());
            json.put("firmwareVersionCode", DeviceUtil.parseBoardVersionCode(DeviceUtil.getBoardVersion()));
            json.put("timestamp", System.currentTimeMillis());
            return MqttManager.get(context).publishReport("status", json.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String getPrivateIp() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            LinkProperties properties = manager == null || network == null
                    ? null
                    : manager.getLinkProperties(network);
            if (properties != null) {
                for (LinkAddress address : properties.getLinkAddresses()) {
                    String value = address.getAddress().getHostAddress();
                    if (!address.getAddress().isLoopbackAddress()
                            && value != null
                            && !value.contains(":")) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String getNetworkType() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager == null || network == null
                    ? null
                    : manager.getNetworkCapabilities(network);
            if (capabilities == null) {
                return "unknown";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "wifi";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "ethernet";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "cellular";
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}
