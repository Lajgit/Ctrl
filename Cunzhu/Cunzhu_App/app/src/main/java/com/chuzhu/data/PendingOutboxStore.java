package com.chuzhu.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小待上报队列。第一阶段不删除业务记录，只用于断线后重放。
 */
public final class PendingOutboxStore {

    private static final String PREF = "chuzhu_outbox_v1";
    private static final String KEY_ITEMS = "items";

    private final Context context;

    public PendingOutboxStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void add(String messageId, String kind, String topic, String payload) {
        if (blank(topic) || blank(payload)) {
            return;
        }
        JSONArray array = readArray();
        String receiptKey = safe(messageId) + "|" + safe(kind);
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item != null && receiptKey.equals(item.optString("receiptKey", ""))) {
                return;
            }
        }
        JSONObject json = new JSONObject();
        try {
            json.put("receiptKey", receiptKey);
            json.put("messageId", safe(messageId));
            json.put("kind", safe(kind));
            json.put("topic", topic);
            json.put("payload", payload);
            json.put("createdAt", System.currentTimeMillis());
            array.put(json);
            preferences().edit().putString(KEY_ITEMS, array.toString()).commit();
        } catch (Throwable ignored) {
        }
    }

    public synchronized List<Item> list() {
        JSONArray array = readArray();
        List<Item> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject json = array.optJSONObject(index);
            if (json == null) {
                continue;
            }
            Item item = new Item();
            item.receiptKey = json.optString("receiptKey", "");
            item.topic = json.optString("topic", "");
            item.payload = json.optString("payload", "");
            result.add(item);
        }
        return result;
    }

    private JSONArray readArray() {
        try {
            return new JSONArray(preferences().getString(KEY_ITEMS, "[]"));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Item {
        public String receiptKey;
        public String topic;
        public String payload;
    }
}
