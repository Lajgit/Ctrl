package com.gouzhu.activation;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 注册、激活、MQTT 联机流程的本地诊断日志。
 *
 * <p>仅记录阶段、状态和异常类型/原因，不记录私钥、MQTT 密码、签名、
 * operationToken 或完整业务 payload。日志保存在应用私有目录，按字符上限滚动。</p>
 */
public final class ActivationLogStore {

    private static final String PREF = "activation_debug_log_v1";
    private static final String KEY_TEXT = "log_text";
    private static final int MAX_CHARS = 48 * 1024;
    private static final int MAX_ERROR_DEPTH = 6;
    private static final int MAX_MESSAGE_CHARS = 800;

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(password|mqttPassword|batchSecret|operationToken|signature)"
                    + "\\s*[:=]\\s*[^,;\\s}]+"
    );
    private static final Pattern LONG_VALUE_PATTERN = Pattern.compile(
            "[A-Za-z0-9_+/=-]{80,}"
    );

    private ActivationLogStore() {
    }

    public static synchronized void append(
            Context context,
            String stage,
            String message
    ) {
        if (context == null) {
            return;
        }

        String safeStage = sanitize(stage == null ? "流程" : stage);
        String safeMessage = sanitize(message == null ? "" : message);
        String line = formatTimestamp() + " [" + safeStage + "] " + safeMessage;

        SharedPreferences preferences = preferences(context);
        String current = preferences.getString(KEY_TEXT, "");
        String next = (current == null || current.isEmpty())
                ? line
                : current + "\n" + line;
        if (next.length() > MAX_CHARS) {
            int start = next.length() - MAX_CHARS;
            int newline = next.indexOf('\n', start);
            next = newline >= 0 ? next.substring(newline + 1) : next.substring(start);
        }
        preferences.edit().putString(KEY_TEXT, next).apply();
    }

    public static synchronized void appendError(
            Context context,
            String stage,
            Throwable error
    ) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_ERROR_DEPTH) {
            if (depth > 0) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                builder.append(": ").append(message.trim());
            }
            current = current.getCause();
            depth++;
        }
        if (builder.length() == 0) {
            builder.append("未知异常");
        }
        append(context, stage, builder.toString());
    }

    public static synchronized String read(Context context) {
        if (context == null) {
            return "";
        }
        String value = preferences(context).getString(KEY_TEXT, "");
        return value == null ? "" : value;
    }

    public static synchronized void clear(Context context) {
        if (context != null) {
            preferences(context).edit().remove(KEY_TEXT).apply();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String formatTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.CHINA
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date());
    }

    private static String sanitize(String value) {
        String result = value.replace('\r', ' ').replace('\n', ' ').trim();
        result = SECRET_PATTERN.matcher(result).replaceAll("$1=<已隐藏>");
        result = LONG_VALUE_PATTERN.matcher(result).replaceAll("<长字段已隐藏>");
        if (result.length() > MAX_MESSAGE_CHARS) {
            result = result.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        return result;
    }
}
