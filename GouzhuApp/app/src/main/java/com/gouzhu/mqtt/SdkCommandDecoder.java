package com.gouzhu.mqtt;

import com.pinball.xiaoda.device.sdk.hardware.CollectRequest;
import com.pinball.xiaoda.device.sdk.hardware.DeviceHardwareCommandMapper;
import com.pinball.xiaoda.device.sdk.hardware.DispenseRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.CashEventResponseCommandData;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResult;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResultCodec;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandCodec;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** 新版 SDK MQTT 命令、硬件请求和结果编码的唯一入口。 */
public final class SdkCommandDecoder {

    private final DeviceMqttCommandCodec codec = new DeviceMqttCommandCodec();
    private final DeviceHardwareCommandMapper hardwareMapper =
            new DeviceHardwareCommandMapper();
    private final DeviceCommandResultCodec resultCodec =
            new DeviceCommandResultCodec();

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
                    sdkCommand,
                    hardwareMapper,
                    resultCodec
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

        private final DeviceHardwareCommandMapper hardwareMapper;
        private final DeviceCommandResultCodec resultCodec;

        DecodedCommand(
                JSONObject envelope,
                DeviceMqttCommand<?> sdkCommand,
                DeviceHardwareCommandMapper hardwareMapper,
                DeviceCommandResultCodec resultCodec
        ) {
            this.envelope = envelope;
            this.sdkCommand = sdkCommand;
            this.hardwareMapper = hardwareMapper;
            this.resultCodec = resultCodec;
        }

        public DispenseRequest toDispenseRequest(long nowMillis) {
            return hardwareMapper.toDispenseRequest(sdkCommand, nowMillis);
        }

        public CollectRequest toCollectRequest(long nowMillis) {
            return hardwareMapper.toCollectRequest(sdkCommand, nowMillis);
        }

        public EncodedResult acknowledgement(String eventNo, long nowMillis) {
            return encode(DeviceCommandResult.acknowledgement(
                    sdkCommand,
                    eventNo,
                    nowMillis
            ));
        }

        public EncodedResult physicalTerminal(
                String eventNo,
                boolean success,
                int actualQuantity,
                String resultCode,
                String resultMessage,
                long nowMillis
        ) {
            HardwareExecutionResult hardwareResult = success
                    ? HardwareExecutionResult.success(actualQuantity)
                    : HardwareExecutionResult.failed(
                            actualQuantity,
                            resultCode,
                            resultMessage
                    );
            return encode(hardwareMapper.toTerminalResult(
                    sdkCommand,
                    eventNo,
                    hardwareResult,
                    nowMillis
            ));
        }

        public EncodedResult configurationTerminal(
                String eventNo,
                boolean success,
                long configVersion,
                String resultCode,
                String resultMessage,
                long nowMillis
        ) {
            DeviceCommandResult result = DeviceCommandResult.builder(
                            sdkCommand.getMessageId(),
                            sdkCommand.getCommandType(),
                            success
                                    ? DeviceCommandResult.STATUS_SUCCESS
                                    : DeviceCommandResult.STATUS_FAILED,
                            eventNo,
                            nowMillis
                    )
                    .terminal(0, resultCode, resultMessage)
                    .configurationVersion(configVersion)
                    .build();
            return encode(result);
        }

        public EncodedResult genericTerminal(
                String eventNo,
                boolean success,
                String resultCode,
                String resultMessage,
                long nowMillis
        ) {
            DeviceCommandResult result = DeviceCommandResult.builder(
                            sdkCommand.getMessageId(),
                            sdkCommand.getCommandType(),
                            success
                                    ? DeviceCommandResult.STATUS_SUCCESS
                                    : DeviceCommandResult.STATUS_FAILED,
                            eventNo,
                            nowMillis
                    )
                    .terminal(0, resultCode, resultMessage)
                    .build();
            return encode(result);
        }

        public CashEventResponseCommandData requireCashEventResponse() {
            return sdkCommand.requireData(CashEventResponseCommandData.class);
        }

        private EncodedResult encode(DeviceCommandResult result) {
            return new EncodedResult(
                    result.getMessageId(),
                    result.getEventNo(),
                    result.getStatus(),
                    new String(resultCodec.encode(result), StandardCharsets.UTF_8)
            );
        }
    }

    public static final class EncodedResult {
        public final String sourceMessageId;
        public final String eventNo;
        public final String resultStatus;
        public final String payload;

        EncodedResult(
                String sourceMessageId,
                String eventNo,
                String resultStatus,
                String payload
        ) {
            this.sourceMessageId = sourceMessageId;
            this.eventNo = eventNo;
            this.resultStatus = resultStatus;
            this.payload = payload;
        }
    }
}
