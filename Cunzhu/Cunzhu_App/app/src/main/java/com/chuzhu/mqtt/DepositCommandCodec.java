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
            return new JSONObject(new String(
                    payload == null ? new byte[0] : payload,
                    StandardCharsets.UTF_8
            ));
        } catch (Throwable ignored) {
            return null;
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
