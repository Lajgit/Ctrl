package com.chuzhu.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.chuzhu.AppConfig;

/**
 * 注册激活状态持久化。
 */
public final class ActivationStore {

    private static final String PREF = "chuzhu_activation_v1";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_DEVICE_NO = "deviceNo";
    private static final String KEY_DEVICE_TYPE = "deviceType";
    private static final String KEY_CLAIM_CODE = "claimCode";
    private static final String KEY_CLAIM_QR = "claimQr";
    private static final String KEY_LAST_ERROR = "lastError";

    private final Context context;

    public ActivationStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isActivated() {
        return preferences().getBoolean(KEY_ACTIVATED, false);
    }

    public void markActivated(String deviceNo) {
        preferences().edit()
                .putBoolean(KEY_ACTIVATED, true)
                .putString(KEY_DEVICE_NO, safe(deviceNo))
                .putInt(KEY_DEVICE_TYPE, AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE)
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public String getDeviceNo() {
        return preferences().getString(KEY_DEVICE_NO, "");
    }

    public int getDeviceType() {
        return preferences().getInt(
                KEY_DEVICE_TYPE,
                AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE
        );
    }

    public void saveClaim(String claimCode, String claimQr) {
        preferences().edit()
                .putString(KEY_CLAIM_CODE, safe(claimCode))
                .putString(KEY_CLAIM_QR, safe(claimQr))
                .putInt(KEY_DEVICE_TYPE, AppConfig.DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE)
                .apply();
    }

    public String getClaimCode() {
        return preferences().getString(KEY_CLAIM_CODE, "");
    }

    public String getClaimQr() {
        return preferences().getString(KEY_CLAIM_QR, "");
    }

    public void saveError(String error) {
        preferences().edit().putString(KEY_LAST_ERROR, safe(error)).apply();
    }

    public String getLastError() {
        return preferences().getString(KEY_LAST_ERROR, "");
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
