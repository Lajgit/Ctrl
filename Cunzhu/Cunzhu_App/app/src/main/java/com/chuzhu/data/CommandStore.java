package com.chuzhu.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * MQTT 命令原文持久化，用于 messageId 幂等判断。
 */
public final class CommandStore {

    private static final String PREF = "chuzhu_commands_v1";

    private final Context context;

    public CommandStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean hasCommand(String messageId) {
        return !safe(messageId).isEmpty()
                && preferences().contains(key(messageId));
    }

    public synchronized void saveCommand(String messageId, JSONObject envelope) {
        if (safe(messageId).isEmpty() || envelope == null) {
            return;
        }
        preferences().edit().putString(key(messageId), envelope.toString()).commit();
    }

    public synchronized JSONObject loadCommand(String messageId) {
        String value = preferences().getString(key(messageId), "");
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String key(String messageId) {
        return "command_" + safe(messageId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
