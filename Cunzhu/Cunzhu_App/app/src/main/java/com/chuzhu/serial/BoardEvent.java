package com.chuzhu.serial;

/**
 * 控制板事件。
 *
 * <p>字段与 14 字节存珠机控制板协议一一对应，便于 Android 在收到终态帧时
 * 回 ACK echo，也便于后续把 RAW 日志和业务事件关联排查。</p>
 */
public final class BoardEvent {

    public static final String TYPE_RAW = "RAW";
    public static final String TYPE_ACK = "ACK";
    public static final String TYPE_STATUS = "STATUS";
    public static final String TYPE_COUNT_CHANGED = "COUNT_CHANGED";
    public static final String TYPE_FINISHED = "FINISHED";
    public static final String TYPE_FAULT = "FAULT";

    public final String type;
    public final int actualQuantity;
    public final String errorCode;
    public final String errorMessage;
    public final byte[] raw;

    public final int resendId;
    public final int frameId;
    public final int code1;
    public final int code2;
    public final int data1;
    public final int data2;
    public final int data3;
    public final int data4;
    public final int ackByte;
    public final int expandCode;
    public final boolean requiresAck;

    public BoardEvent(
            String type,
            int actualQuantity,
            String errorCode,
            String errorMessage,
            byte[] raw
    ) {
        this(type, actualQuantity, errorCode, errorMessage, raw,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, false);
    }

    public BoardEvent(
            String type,
            int actualQuantity,
            String errorCode,
            String errorMessage,
            byte[] raw,
            int resendId,
            int frameId,
            int code1,
            int code2,
            int data1,
            int data2,
            int data3,
            int data4,
            int ackByte,
            int expandCode,
            boolean requiresAck
    ) {
        this.type = type;
        this.actualQuantity = actualQuantity;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.raw = raw == null ? new byte[0] : raw;
        this.resendId = resendId & 0xFF;
        this.frameId = frameId & 0xFF;
        this.code1 = code1 & 0xFF;
        this.code2 = code2 & 0xFF;
        this.data1 = data1 & 0xFF;
        this.data2 = data2 & 0xFF;
        this.data3 = data3 & 0xFF;
        this.data4 = data4 & 0xFF;
        this.ackByte = ackByte & 0xFF;
        this.expandCode = expandCode & 0xFF;
        this.requiresAck = requiresAck;
    }
}
