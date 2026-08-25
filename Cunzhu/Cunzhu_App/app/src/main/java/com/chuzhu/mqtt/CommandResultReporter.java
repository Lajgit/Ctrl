package com.chuzhu.mqtt;

import android.content.Context;

import com.chuzhu.data.PendingOutboxStore;
import com.chuzhu.data.ReceiptStore;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResult;
import com.pinball.xiaoda.device.sdk.protocol.DeviceCommandResultCodec;
import com.pinball.xiaoda.device.sdk.protocol.DeviceMqttCommand;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * command-result 上报封装。
 */
public final class CommandResultReporter {

    private final Context context;
    private final DeviceCommandResultCodec resultCodec = new DeviceCommandResultCodec();
    private final ReceiptStore receiptStore;
    private final PendingOutboxStore outboxStore;

    public CommandResultReporter(Context context) {
        this.context = context.getApplicationContext();
        receiptStore = new ReceiptStore(this.context);
        outboxStore = new PendingOutboxStore(this.context);
    }

    public String reportAck(DeviceMqttCommand<?> command) {
        String eventNo = command.getMessageId() + "-ack";
        String payload = encode(DeviceCommandResult.acknowledgement(
                command,
                eventNo,
                System.currentTimeMillis()
        ));
        receiptStore.saveAck(command.getMessageId(), payload);
        publish(command.getMessageId(), "ack", payload);
        return payload;
    }

    public String reportTerminal(
            DeviceCommandResult result,
            String messageId
    ) {
        String payload = encode(result);
        receiptStore.saveTerminal(messageId, payload);
        publish(messageId, "terminal", payload);
        return payload;
    }

    public void reportFailureJson(
            String messageId,
            String deviceNo,
            String commandType,
            String resultCode,
            String resultMessage
    ) {
        String payload = buildFailureJson(
                messageId,
                deviceNo,
                commandType,
                resultCode,
                resultMessage
        );
        if (!messageId.isEmpty()) {
            receiptStore.saveTerminal(messageId, payload);
        }
        publish(messageId, "terminal", payload);
    }

    public void replay(String messageId) {
        String terminal = receiptStore.loadTerminal(messageId);
        if (!terminal.isEmpty()) {
            publish(messageId, "terminal", terminal);
            return;
        }
        String ack = receiptStore.loadAck(messageId);
        if (!ack.isEmpty()) {
            publish(messageId, "ack", ack);
        }
    }

    private String encode(DeviceCommandResult result) {
        return new String(resultCodec.encode(result), StandardCharsets.UTF_8);
    }

    private void publish(String messageId, String kind, String payload) {
        MqttManager manager = MqttManager.get(context);
        String topic = manager.getReportTopic("command-result");
        outboxStore.add(messageId, kind, topic, payload);
        manager.reportCommandResult(payload);
    }

    private static String buildFailureJson(
            String messageId,
            String deviceNo,
            String commandType,
            String resultCode,
            String resultMessage
    ) {
        JSONObject json = new JSONObject();
        try {
            json.put("messageId", safe(messageId));
            json.put("deviceNo", safe(deviceNo));
            json.put("commandType", safe(commandType));
            json.put("status", "failed");
            json.put("eventNo", safe(messageId) + "-reject");
            json.put("resultCode", safe(resultCode));
            json.put("resultMessage", safe(resultMessage));
            json.put("timestamp", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return json.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
