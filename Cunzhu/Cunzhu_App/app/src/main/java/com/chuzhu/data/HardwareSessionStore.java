package com.chuzhu.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 当前硬件收珠会话持久化，进程重启后用于恢复未完成任务。
 */
public final class HardwareSessionStore {

    private static final String PREF = "chuzhu_hardware_session_v1";
    private static final String KEY_SESSION = "session";

    private final Context context;

    public HardwareSessionStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void save(DepositSession session) {
        if (session == null) {
            return;
        }
        preferences().edit()
                .putString(KEY_SESSION, session.toJson().toString())
                .commit();
    }

    public synchronized DepositSession load() {
        String value = preferences().getString(KEY_SESSION, "");
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return DepositSession.fromJson(new JSONObject(value));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public synchronized void clear() {
        preferences().edit().remove(KEY_SESSION).commit();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
