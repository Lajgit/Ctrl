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

        /*
         * actualQuantity 是“已确认收珠数”，不能持久化负数或超过本次 maximumQuantity 的
         * 诊断原值。异常帧的原始 observedQuantity 只允许留在诊断日志；本地业务快照继续保留
         * 最近一次合法可信数量，避免 UI 或恢复流程把越界值误认为已确认事实。
         */
        if (session.actualQuantity < 0
                || (session.maximumQuantity > 0
                && session.actualQuantity > session.maximumQuantity)) {
            DepositSession previous = load();
            if (previous != null
                    && previous.actualQuantity >= 0
                    && (session.maximumQuantity <= 0
                    || previous.actualQuantity <= session.maximumQuantity)) {
                session.actualQuantity = previous.actualQuantity;
            } else {
                session.actualQuantity = 0;
            }
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
