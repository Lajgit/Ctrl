package com.gouzhu.mqtt;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 新版 xiaoda-device-sdk 协议校验入口。
 *
 * <p>设备只处理通过 DeviceMqttCommandCodec.decode 的 Topic、deviceNo、命令类型、
 * operationToken 和过期时间校验的消息。反射仅用于隔离服务端交付 JAR 的二进制
 * 小版本签名差异；任何类或方法缺失都按协议不可用处理，不回退到裸 JSON 执行。</p>
 */
public final class SdkCommandDecoder {

    private static final String CODEC_CLASS =
            "com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandCodec";

    private final Object codec;
    private final Method decodeMethod;

    public SdkCommandDecoder() {
        try {
            Class<?> type = Class.forName(CODEC_CLASS);
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            codec = constructor.newInstance();
            decodeMethod = findDecodeMethod(type);
            decodeMethod.setAccessible(true);
        } catch (Throwable error) {
            throw new IllegalStateException("新版SDK命令协议初始化失败", error);
        }
    }

    public DecodedCommand decode(
            String topic,
            byte[] payload,
            String deviceNo,
            long nowMillis
    ) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        String text = new String(safePayload, StandardCharsets.UTF_8);
        try {
            Class<?> payloadType = decodeMethod.getParameterTypes()[1];
            Object payloadArg = payloadType == byte[].class ? safePayload : text;
            Object sdkCommand = decodeMethod.invoke(
                    codec,
                    topic,
                    payloadArg,
                    deviceNo,
                    nowMillis
            );
            if (sdkCommand == null) {
                throw new IllegalStateException("SDK命令解码结果为空");
            }
            return new DecodedCommand(new JSONObject(text), sdkCommand);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalArgumentException(
                    "MQTT命令未通过新版SDK协议校验：" + messageOf(cause),
                    cause
            );
        }
    }

    private static Method findDecodeMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (!"decode".equals(method.getName()) || method.getParameterCount() != 4) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] == String.class
                    && (parameters[1] == String.class || parameters[1] == byte[].class)
                    && parameters[2] == String.class
                    && (parameters[3] == long.class || parameters[3] == Long.class)) {
                return method;
            }
        }
        throw new IllegalStateException("新版SDK缺少四参数 DeviceMqttCommandCodec.decode");
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    public static final class DecodedCommand {
        public final JSONObject envelope;
        public final Object sdkCommand;

        DecodedCommand(JSONObject envelope, Object sdkCommand) {
            this.envelope = envelope;
            this.sdkCommand = sdkCommand;
        }

        /** 使用 CashEventResponseCommandData 自带状态方法，不在 App 重建枚举。 */
        public boolean invokeCashStatus(String methodName) {
            try {
                Object data = sdkCommand.getClass().getMethod("getData").invoke(sdkCommand);
                if (data == null) {
                    return false;
                }
                Object value = data.getClass().getMethod(methodName).invoke(data);
                return value instanceof Boolean && (Boolean) value;
            } catch (Throwable error) {
                throw new IllegalStateException(
                        "新版SDK现金响应缺少状态方法：" + methodName,
                        error
                );
            }
        }
    }
}
