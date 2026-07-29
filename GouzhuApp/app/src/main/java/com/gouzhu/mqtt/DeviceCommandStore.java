package com.gouzhu.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MQTT 指令、回执和现金事件持久化。
 *
 * <p>相同 messageId 或 eventNo 使用同一条记录，重连后只能补发原始 payload，
 * 不会重新执行物理动作。</p>
 */
public final class DeviceCommandStore {

    private static final String PREF = "device_command_store_v1";
    private static final String PREFIX_COMMAND = "command_";
    private static final String PREFIX_RESULT = "result_";
    private static final String PREFIX_CASH = "cash_";
    private static final String PREFIX_ACCEPTED_CASH_QUEUE = "accepted_cash_queue_";
    private static final String KEY_ACTIVE_DISPENSE = "active_dispense";
    private static final String KEY_ACTIVE_COLLECT = "active_collect";
    private static final String KEY_BOARD_VERSION = "board_version";
    private static final String KEY_CONFIG_VERSION = "cash_config_version";
    private static final String KEY_CASH_ENABLED = "cash_enabled";

    private final SharedPreferences preferences;

    public DeviceCommandStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public synchronized boolean saveCommand(JSONObject envelope) {
        String messageId = envelope.optString("messageId", "");
        if (messageId.isEmpty()) {
            return false;
        }
        return preferences.edit()
                .putString(PREFIX_COMMAND + keyOf(messageId), envelope.toString())
                .commit();
    }

    public synchronized JSONObject loadCommand(String messageId) {
        return parse(preferences.getString(PREFIX_COMMAND + keyOf(messageId), ""));
    }

    public synchronized boolean hasCommand(String messageId) {
        return loadCommand(messageId) != null;
    }

    public synchronized void setActiveDispense(String messageId) {
        preferences.edit().putString(KEY_ACTIVE_DISPENSE, safe(messageId)).commit();
    }

    public synchronized String getActiveDispense() {
        return preferences.getString(KEY_ACTIVE_DISPENSE, "");
    }

    public synchronized void clearActiveDispense() {
        preferences.edit().remove(KEY_ACTIVE_DISPENSE).commit();
    }

    public synchronized void setActiveCollect(String messageId) {
        preferences.edit().putString(KEY_ACTIVE_COLLECT, safe(messageId)).commit();
    }

    public synchronized String getActiveCollect() {
        return preferences.getString(KEY_ACTIVE_COLLECT, "");
    }

    public synchronized void clearActiveCollect() {
        preferences.edit().remove(KEY_ACTIVE_COLLECT).commit();
    }

    public synchronized boolean saveCommandResult(
            String sourceMessageId,
            String eventNo,
            String resultStatus,
            String payload
    ) {
        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("sourceMessageId", sourceMessageId);
            wrapper.put("eventNo", eventNo);
            wrapper.put("resultStatus", resultStatus);
            wrapper.put("payload", payload);
            return preferences.edit()
                    .putString(PREFIX_RESULT + keyOf(eventNo), wrapper.toString())
                    .commit();
        } catch (Throwable error) {
            return false;
        }
    }

    public synchronized List<OutboxItem> listCommandResults() {
        return listByPrefix(PREFIX_RESULT);
    }

    public synchronized void removeCommandResult(String eventNo) {
        preferences.edit().remove(PREFIX_RESULT + keyOf(eventNo)).commit();
    }

    public synchronized boolean saveCashEvent(String eventNo, String payload) {
        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("eventNo", eventNo);
            wrapper.put("payload", payload);
            return preferences.edit()
                    .putString(PREFIX_CASH + keyOf(eventNo), wrapper.toString())
                    .commit();
        } catch (Throwable error) {
            return false;
        }
    }

    public synchronized List<OutboxItem> listCashEvents() {
        return listByPrefix(PREFIX_CASH);
    }

    public synchronized void removeCashEvent(String eventNo) {
        preferences.edit().remove(PREFIX_CASH + keyOf(eventNo)).commit();
    }

    /**
     * 保存尚可能发生真实退币的 accepted 事件号。
     * 同介质、同面额使用先进先出队列，returned 可以关联原 accepted。
     */
    public synchronized boolean appendAcceptedCashEvent(
            String cashMediumType,
            int amountYuan,
            String eventNo
    ) {
        if (amountYuan <= 0 || eventNo == null || eventNo.isEmpty()) {
            return false;
        }
        String key = acceptedCashQueueKey(cashMediumType, amountYuan);
        JSONArray queue = parseArray(preferences.getString(key, ""));
        if (queue == null) {
            queue = new JSONArray();
        }
        queue.put(eventNo);
        return preferences.edit().putString(key, queue.toString()).commit();
    }

    /** 取出并删除最早的一条 accepted 事件号，供真实退币 relatedEventNo 使用。 */
    public synchronized String popAcceptedCashEvent(String cashMediumType, int amountYuan) {
        String key = acceptedCashQueueKey(cashMediumType, amountYuan);
        JSONArray queue = parseArray(preferences.getString(key, ""));
        if (queue == null || queue.length() == 0) {
            return "";
        }
        String eventNo = queue.optString(0, "");
        JSONArray next = new JSONArray();
        for (int index = 1; index < queue.length(); index++) {
            next.put(queue.optString(index, ""));
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (next.length() == 0) {
            editor.remove(key);
        } else {
            editor.putString(key, next.toString());
        }
        return editor.commit() ? eventNo : "";
    }

    public synchronized void saveBoardVersion(long value) {
        preferences.edit().putLong(KEY_BOARD_VERSION, value).commit();
    }

    public synchronized long getBoardVersion() {
        return preferences.getLong(KEY_BOARD_VERSION, 0L);
    }

    public synchronized void saveCashConfiguration(int configVersion, boolean enabled) {
        preferences.edit()
                .putInt(KEY_CONFIG_VERSION, configVersion)
                .putBoolean(KEY_CASH_ENABLED, enabled)
                .commit();
    }

    public synchronized int getCashConfigVersion() {
        return preferences.getInt(KEY_CONFIG_VERSION, 0);
    }

    public synchronized boolean isCashEnabled() {
        return preferences.getBoolean(KEY_CASH_ENABLED, true);
    }

    private List<OutboxItem> listByPrefix(String prefix) {
        List<OutboxItem> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof String)) {
                continue;
            }
            JSONObject wrapper = parse((String) entry.getValue());
            if (wrapper == null) {
                continue;
            }
            OutboxItem item = new OutboxItem();
            item.sourceMessageId = wrapper.optString("sourceMessageId", "");
            item.eventNo = wrapper.optString("eventNo", "");
            item.resultStatus = wrapper.optString("resultStatus", "");
            item.payload = wrapper.optString("payload", "");
            if (!item.eventNo.isEmpty() && !item.payload.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private static JSONArray parseArray(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new JSONArray(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String acceptedCashQueueKey(String cashMediumType, int amountYuan) {
        return PREFIX_ACCEPTED_CASH_QUEUE
                + keyOf(safe(cashMediumType) + "|" + amountYuan);
    }

    private static JSONObject parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String keyOf(String value) {
        return Base64.encodeToString(
                safe(value).getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class OutboxItem {
        public String sourceMessageId;
        public String eventNo;
        public String resultStatus;
        public String payload;
    }
}
