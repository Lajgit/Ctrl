package com.gouzhu.mqtt;

import com.pinball.xiaoda.device.sdk.protocol.CashEventResponseCommandData;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandCodec;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** 新版 SDK MQTT 命令协议的唯一解码入口。 */
public final class SdkCommandDecoder {

    private final DeviceMqttCommandCodec codec = new DeviceMqttCommandCodec();

    public DecodedCommand decode(
            String topic,
            byte[] payload,
            String deviceNo,
            long nowMillis
    ) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        try {
            DeviceMqttCommand<?> sdkCommand = codec.decode(
                    topic,
                    safePayload,
                    deviceNo,
                    nowMillis
            );
            return new DecodedCommand(
                    new JSONObject(new String(safePayload, StandardCharsets.UTF_8)),
                    sdkCommand
            );
        } catch (Throwable error) {
            throw new IllegalArgumentException(
                    "MQTT命令未通过新版SDK协议校验：" + messageOf(error),
                    error
            );
        }
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
        public final DeviceMqttCommand<?> sdkCommand;

        DecodedCommand(JSONObject envelope, DeviceMqttCommand<?> sdkCommand) {
            this.envelope = envelope;
            this.sdkCommand = sdkCommand;
        }

        public CashEventResponseCommandData requireCashEventResponse() {
            return sdkCommand.requireData(CashEventResponseCommandData.class);
        }

        public boolean invokeCashStatus(String methodName) {
            CashEventResponseCommandData data = requireCashEventResponse();
            switch (methodName) {
                case "isPending":
                    return data.isPending();
                case "isProcessing":
                    return data.isProcessing();
                case "isCompleted":
                    return data.isCompleted();
                case "isManualReview":
                    return data.isManualReview();
                case "isRejected":
                    return data.isRejected();
                case "isUnknown":
                    return data.isUnknown();
                default:
                    throw new IllegalArgumentException("未知现金响应状态方法：" + methodName);
            }
        }
    }
}
