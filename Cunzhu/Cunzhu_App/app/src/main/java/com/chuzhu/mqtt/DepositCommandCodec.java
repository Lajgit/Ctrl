package com.chuzhu.mqtt;

import com.chuzhu.device.DeviceUtil;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandCodec;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandData;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 存珠机 MQTT 命令解码器。
 */
public final class DepositCommandCodec {

    private static final String CMD_COMMAND_RESULT_ACK = "command_result_ack";
    private final DeviceMqttCommandCodec sdkCodec = new DeviceMqttCommandCodec();

    public Decoded decode(String topic, byte[] payload, String deviceNo, long now) {
        try {
            DeviceMqttCommand<? extends DeviceMqttCommandData> command =
                    sdkCodec.decode(topic, payload, deviceNo, now);
            return new Decoded(command, parse(payload), null);
        } catch (Throwable error) {
            return new Decoded(null, parse(payload), error);
        }
    }

    private static JSONObject parse(byte[] payload) {
        try {
            JSONObject envelope = new JSONObject(new String(
                    payload == null ? new byte[0] : payload,
                    StandardCharsets.UTF_8
            ));
            normalizeCommandResultAck(envelope);
            return envelope;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 正式 SDK 的 command_result_ack 用 receiptStatus=recorded/rejected 表示平台是否已记录，
     * 旧业务处理代码读取的是 recorded 布尔字段。这里仅在服务端未直接下发 recorded 时做兼容映射，
     * 避免 receiptStatus=recorded + retryable=false 被误判成 recorded=false 的协议异常。
     */
    private static void normalizeCommandResultAck(JSONObject envelope) {
        if (envelope == null
                || !CMD_COMMAND_RESULT_ACK.equals(envelope.optString("commandType", ""))) {
            return;
        }
        JSONObject data = envelope.optJSONObject("data");
        if (data == null || data.has("recorded")) {
            return;
        }
        String receiptStatus = data.optString("receiptStatus", "").trim();
        if (receiptStatus.isEmpty()) {
            return;
        }
        try {
            if ("recorded".equalsIgnoreCase(receiptStatus)) {
                data.put("recorded", true);
            } else if ("rejected".equalsIgnoreCase(receiptStatus)) {
                data.put("recorded", false);
            }
        } catch (Throwable ignored) {
        }
    }

    public static final class Decoded {
        public final DeviceMqttCommand<? extends DeviceMqttCommandData> command;
        public final JSONObject envelope;
        public final Throwable error;

        Decoded(
                DeviceMqttCommand<? extends DeviceMqttCommandData> command,
                JSONObject envelope,
                Throwable error
        ) {
            this.command = command;
            this.envelope = envelope;
            this.error = error;
        }

        public String messageId() {
            if (command != null) {
                return command.getMessageId();
            }
            return envelope == null ? "" : envelope.optString("messageId", "");
        }

        public String deviceNo() {
            if (command != null) {
                return command.getDeviceNo();
            }
            return envelope == null ? "" : DeviceUtil.normalizeDeviceNo(envelope.optString("deviceNo", ""));
        }

        public String commandType() {
            if (command != null) {
                return command.getCommandType();
            }
            return envelope == null ? "" : envelope.optString("commandType", "");
        }
    }
}
