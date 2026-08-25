package com.chuzhu.serial;

/**
 * 控制板事件。业务字段待正式存珠协议确认后补齐。
 */
public final class BoardEvent {

    public static final String TYPE_RAW = "RAW";
    public static final String TYPE_COUNT_CHANGED = "COUNT_CHANGED";
    public static final String TYPE_FINISHED = "FINISHED";
    public static final String TYPE_FAULT = "FAULT";

    public final String type;
    public final int actualQuantity;
    public final String errorCode;
    public final String errorMessage;
    public final byte[] raw;

    public BoardEvent(
            String type,
            int actualQuantity,
            String errorCode,
            String errorMessage,
            byte[] raw
    ) {
        this.type = type;
        this.actualQuantity = actualQuantity;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.raw = raw == null ? new byte[0] : raw;
    }
}
