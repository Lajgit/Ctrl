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
 * <p>ACK/terminal 先落盘再发布；只有平台明确 recorded=true 且 retryable=false 时才删除。
 * 对协议未定义但明确不可重试的回执保留原始记录并暂停自动重放，既避免历史消息永久刷屏，
 * 又不丢失后续人工对账所需的原始 payload。</p>
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
            json.put("suspended", false);
            array.put(json);
            preferences().edit().putString(KEY_ITEMS, array.toString()).commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只返回允许自动重放的记录。历史版本没有 suspended 字段时按 false 兼容。
     */
    public synchronized List<Item> list() {
        JSONArray array = readArray();
        List<Item> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject json = array.optJSONObject(index);
            if (json == null || json.optBoolean("suspended", false)) {
                continue;
            }
            Item item = new Item();
            item.receiptKey = json.optString("receiptKey", "");
            item.messageId = json.optString("messageId", "");
            item.kind = json.optString("kind", "");
            item.topic = json.optString("topic", "");
            item.payload = json.optString("payload", "");
            item.suspended = false;
            result.add(item);
        }
        return result;
    }

    /**
     * 按 sourceMessageId + eventNo 精确删除已被平台确认入库的回执；resultStatus 有值时再附加状态匹配。
     * eventNo 在同一业务消息内唯一，因此兼容平台 ACK 未返回 resultStatus 的情况。
     * 同时兼容升级前使用 messageId|kind 作为 receiptKey 的旧记录：匹配仍以 payload 为准。
     */
    public synchronized int removeConfirmed(
            String sourceMessageId,
            String eventNo,
            String resultStatus
    ) {
        if (blank(sourceMessageId) || blank(eventNo)) {
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

    /**
     * 平台返回协议未定义的“未记录且不可重试”时，不把它当 confirmed 删除。
     * 保留原始回执用于对账，只从自动重放队列中隔离，防止每次心跳继续发送同一历史消息。
     */
    public synchronized int suspendReceipt(
            String sourceMessageId,
            String eventNo,
            String resultStatus
    ) {
        if (blank(sourceMessageId) || blank(eventNo)) {
            return 0;
        }
        JSONArray source = readArray();
        int suspended = 0;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null
                    || !matchesReceipt(item, sourceMessageId, eventNo, resultStatus)
                    || item.optBoolean("suspended", false)) {
                continue;
            }
            try {
                item.put("suspended", true);
                item.put("suspendedAt", System.currentTimeMillis());
                suspended++;
            } catch (Throwable ignored) {
            }
        }
        if (suspended > 0) {
            preferences().edit().putString(KEY_ITEMS, source.toString()).commit();
        }
        return suspended;
    }

    private static boolean matchesReceipt(
            JSONObject item,
            String sourceMessageId,
            String eventNo,
            String resultStatus
    ) {
        try {
            JSONObject payload = new JSONObject(item.optString("payload", "{}"));
            if (!sourceMessageId.equals(payload.optString("messageId", "").trim())
                    || !eventNo.equals(payload.optString("eventNo", "").trim())) {
                return false;
            }
            return blank(resultStatus)
                    || resultStatus.equalsIgnoreCase(payload.optString("status", "").trim());
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
        public boolean suspended;
    }
}
