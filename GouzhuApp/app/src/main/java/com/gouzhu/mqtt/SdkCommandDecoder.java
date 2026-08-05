package com.gouzhu.mqtt;

import android.util.Log;

import com.pinball.xiaoda.device.sdk.hardware.CollectRequest;
import com.pinball.xiaoda.device.sdk.hardware.DeviceHardwareCommandMapper;
import com.pinball.xiaoda.device.sdk.hardware.DispenseRequest;
import com.pinball.xiaoda.device.sdk.hardware.HardwareExecutionResult;
import com.pinball.xiaoda.device.sdk.protocol.CashEventResponseCommandData;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResult;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResultCodec;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommandCodec;
import com.pinball.xiaoda.device.sdk.protocol.MarbleDispenseResultCodes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** 新版 SDK MQTT 命令、硬件请求和结果编码的唯一入口。 */
public final class SdkCommandDecoder {

    /** 现金配置完整 JSON 使用独立标签，便于 Logcat 单独过滤。 */
    private static final String CASH_CONFIG_JSON_TAG = "GouzhuCashConfigJson";
    private static final String CASH_CONFIG_COMMAND = "sync_cash_configuration";
    private static final String DISPENSE_COMMAND = "dispense_marbles";
    private static final String CONTROLLER_NO_MARBLES = "NO_MARBLES";
    private static final int LOG_CHUNK_SIZE = 3000;

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
            String rawJson = new String(safePayload, StandardCharsets.UTF_8);
            JSONObject envelope = new JSONObject(rawJson);

            /*
             * 现金配置日志在 SDK 强类型校验前输出。这样即使配置字段错误、版本越界
             * 或档位不完整，现场仍能看到服务器实际下发的完整 JSON，便于联调定位。
             * 这里只打印 sync_cash_configuration；其他可能含业务令牌的指令不打印原文。
             */
            logCashConfigurationJson(topic, safePayload.length, envelope);

            DeviceMqttCommand<?> sdkCommand = codec.decode(
                    topic,
                    safePayload,
                    deviceNo,
                    nowMillis
            );
            return new DecodedCommand(
                    envelope,
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

    /**
     * 完整打印 sync_cash_configuration 信封和 data 中所有字段。
     *
     * <p>日志包含 messageId、deviceNo、commandType、timestamp，以及 data 下的
     * configVersion、cashAcceptanceEnabled、changeEnabled 和 cashSaleItems 全量内容。
     * 长 JSON 按 3000 字符分段，避免 Logcat 单条日志被截断。</p>
     */
    private static void logCashConfigurationJson(
            String topic,
            int payloadBytes,
            JSONObject envelope
    ) {
        if (envelope == null
                || !CASH_CONFIG_COMMAND.equals(envelope.optString("commandType", ""))) {
            return;
        }

        JSONObject data = envelope.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("cashSaleItems");
        Log.i(
                CASH_CONFIG_JSON_TAG,
                "收到MQTT现金配置：topic=" + safe(topic)
                        + "，payloadBytes=" + payloadBytes
                        + "，messageId=" + envelope.optString("messageId", "")
                        + "，data.configVersion="
                        + (data == null ? "<缺失>" : String.valueOf(data.opt("configVersion")))
                        + "，data.cashAcceptanceEnabled="
                        + (data == null
                        ? "<缺失>"
                        : String.valueOf(data.opt("cashAcceptanceEnabled")))
                        + "，data.changeEnabled="
                        + (data == null ? "<缺失>" : String.valueOf(data.opt("changeEnabled")))
                        + "，data.cashSaleItems数量=" + (items == null ? -1 : items.length())
        );

        String prettyJson;
        try {
            prettyJson = envelope.toString(2);
        } catch (Throwable error) {
            prettyJson = envelope.toString();
        }
        logLong(CASH_CONFIG_JSON_TAG, "现金配置完整JSON：\n" + prettyJson);
    }

    /** 将长日志分段输出，并标注当前段和总段数。 */
    private static void logLong(String tag, String content) {
        String safeContent = content == null ? "null" : content;
        if (safeContent.isEmpty()) {
            Log.i(tag, "现金配置完整JSON为空");
            return;
        }

        int total = (safeContent.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE;
        for (int start = 0, index = 1;
             start < safeContent.length();
             start += LOG_CHUNK_SIZE, index++) {
            int end = Math.min(safeContent.length(), start + LOG_CHUNK_SIZE);
            Log.i(
                    tag,
                    "cashConfig[" + index + "/" + total + "] "
                            + safeContent.substring(start, end)
            );
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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

        /** 使用新版SDK保留 continuationNo，不重放原 dispense_marbles。 */
        public DispenseRequest toContinuationDispenseRequest(long nowMillis) {
            return hardwareMapper.toContinuationDispenseRequest(sdkCommand, nowMillis);
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

        public EncodedResult configurationAcknowledgement(
                String eventNo,
                long nowMillis
        ) {
            return encode(DeviceCommandResult.configurationAcknowledgement(
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
            String normalizedCode = resultCode;
            String normalizedMessage = resultMessage;
            /*
             * 只有首轮固定数量出珠在控制板明确返回无珠且已真实出过珠时，才转换为
             * 平台允许继续出珠的库存不足结果。0颗、卡珠、传感器和其他故障保持原码。
             */
            if (!success
                    && DISPENSE_COMMAND.equals(sdkCommand.getCommandType())
                    && actualQuantity > 0
                    && CONTROLLER_NO_MARBLES.equals(resultCode)) {
                normalizedCode =
                        MarbleDispenseResultCodes.MARBLE_STOCK_INSUFFICIENT;
                normalizedMessage = "珠仓库存不足，已出" + actualQuantity + "颗";
            }

            HardwareExecutionResult hardwareResult = success
                    ? HardwareExecutionResult.success(actualQuantity)
                    : HardwareExecutionResult.failed(
                            actualQuantity,
                            normalizedCode,
                            normalizedMessage
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
                String resultCode,
                String resultMessage,
                long nowMillis
        ) {
            return encode(DeviceCommandResult.configurationTerminal(
                    sdkCommand,
                    eventNo,
                    success,
                    resultCode,
                    resultMessage,
                    nowMillis
            ));
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
