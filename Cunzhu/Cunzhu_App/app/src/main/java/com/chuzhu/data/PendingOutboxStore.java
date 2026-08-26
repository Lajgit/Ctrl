package com.chuzhu.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 存珠机可靠回执最小 outbox。
 *
 * <p>ACK/terminal 先落盘再发布；只有平台 command_result_ack 明确
 * recorded=true 且 retryable=false 时才删除对应回执。</p>
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
        String receiptKey = receiptKey(messageId, kind, payload);
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
            item.messageId = json.optString("messageId", "");
            item.kind = json.optString("kind", "");
            item.topic = json.optString("topic", "");
            item.payload = json.optString("payload", "");
            result.add(item);
        }
        return result;
    }

    /**
     * 按平台确认的 sourceMessageId + eventNo + resultStatus 精确删除回执。
     * 同时兼容升级前使用 messageId|kind 作为 receiptKey 的旧记录：匹配以 payload 为准。
     */
    public synchronized int removeConfirmed(
            String sourceMessageId,
            String eventNo,
            String resultStatus
    ) {
        if (blank(sourceMessageId) || blank(eventNo) || blank(resultStatus)) {
            return 0;
        }
        JSONArray source = readArray();
        JSONArray kept = new JSONArray();
        int removed = 0;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) {
                continue;
            }
            if (matchesReceipt(item, sourceMessageId, eventNo, resultStatus)) {
                removed++;
            } else {
                kept.put(item);
            }
        }
        if (removed > 0) {
            preferences().edit().putString(KEY_ITEMS, kept.toString()).commit();
        }
        return removed;
    }

    private static boolean matchesReceipt(
            JSONObject item,
            String sourceMessageId,
            String eventNo,
            String resultStatus
    ) {
        try {
            JSONObject payload = new JSONObject(item.optString("payload", "{}"));
            return sourceMessageId.equals(payload.optString("messageId", "").trim())
                    && eventNo.equals(payload.optString("eventNo", "").trim())
                    && resultStatus.equalsIgnoreCase(payload.optString("status", "").trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String receiptKey(String messageId, String kind, String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String eventNo = json.optString("eventNo", "").trim();
            String status = json.optString("status", "").trim();
            if (!blank(messageId) && !blank(eventNo) && !blank(status)) {
                return safe(messageId) + "|" + eventNo + "|" + status;
            }
        } catch (Throwable ignored) {
        }
        return safe(messageId) + "|" + safe(kind);
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
        public String messageId;
        public String kind;
        public String topic;
        public String payload;
    }
}
