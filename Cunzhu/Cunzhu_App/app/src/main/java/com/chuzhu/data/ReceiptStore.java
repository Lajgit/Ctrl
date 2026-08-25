package com.chuzhu.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ACK 和终态 command-result 持久化，重复 messageId 只重放结果。
 */
public final class ReceiptStore {

    private static final String PREF = "chuzhu_receipts_v1";
    private static final String PREFIX_ACK = "ack_";
    private static final String PREFIX_TERMINAL = "terminal_";

    private final Context context;

    public ReceiptStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void saveAck(String messageId, String payload) {
        save(PREFIX_ACK, messageId, payload);
    }

    public synchronized void saveTerminal(String messageId, String payload) {
        save(PREFIX_TERMINAL, messageId, payload);
    }

    public synchronized String loadAck(String messageId) {
        return load(PREFIX_ACK, messageId);
    }

    public synchronized String loadTerminal(String messageId) {
        return load(PREFIX_TERMINAL, messageId);
    }

    private void save(String prefix, String messageId, String payload) {
        if (safe(messageId).isEmpty() || safe(payload).isEmpty()) {
            return;
        }
        preferences().edit().putString(prefix + messageId, payload).commit();
    }

    private String load(String prefix, String messageId) {
        return preferences().getString(prefix + safe(messageId), "");
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
