package com.chuzhu.serial;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 存珠机控制板 14 字节帧编解码。
 *
 * <p>帧格式与售珠机保持一致：Head、ResendID、ID、Code1、Code2、Data1..Data4、
 * ACKbyte、ExpandCode、CRC16、Tail。存珠机仅重新定义 Code2 业务功能码。</p>
 */
public final class BoardFrameCodec {

    public static final int FRAME_LENGTH = 14;
    public static final int HEAD = 0xAA;
    public static final int TAIL = 0x55;

    /** 控制板到 Android。 */
    public static final int CODE1_BOARD_TO_ANDROID = 0x00;
    /** Android 到控制板。 */
    public static final int CODE1_ANDROID_TO_BOARD = 0x01;

    public static final int ACK_NONE = 0x00;
    public static final int ACK_ECHO = 0x01;

    public static final int CODE2_START_COLLECT = 0x10;
    public static final int CODE2_STOP_COLLECT = 0x11;
    public static final int CODE2_STATUS = 0x12;
    public static final int CODE2_CLEAR_FAULT = 0x13;
    public static final int CODE2_HEARTBEAT = 0x14;
    public static final int CODE2_COUNT_CHANGED = 0x20;
    public static final int CODE2_COLLECT_FINISHED = 0x21;
    public static final int CODE2_FAULT_EVENT = 0x22;
    public static final int CODE2_BOARD_BOOT = 0x23;

    public static final int RESULT_OK = 0x00;
    public static final int RESULT_BUSY = 0x01;
    public static final int RESULT_PARAM_INVALID = 0x02;
    public static final int RESULT_STATE_INVALID = 0x03;
    public static final int RESULT_CRC_ERROR = 0x04;
    public static final int RESULT_UNKNOWN_CODE2 = 0x05;
    public static final int RESULT_NOT_READY = 0x06;
    public static final int RESULT_SENSOR_ERROR = 0x07;
    public static final int RESULT_TIMEOUT = 0x08;
    public static final int RESULT_MOTOR_ERROR = 0x09;
    public static final int RESULT_JAM = 0x0A;
    public static final int RESULT_EMERGENCY_STOP = 0x0B;
    public static final int RESULT_STORAGE_FULL = 0x0C;
    public static final int RESULT_DUPLICATE_ACCEPTED = 0x0D;
    public static final int RESULT_UNKNOWN = 0xFF;

    public static final int STATE_IDLE = 0x00;
    public static final int STATE_COLLECTING = 0x01;
    public static final int STATE_FAULT = 0x02;
    public static final int STATE_STOPPING = 0x03;
    public static final int STATE_MAINTENANCE = 0x04;
    public static final int STATE_SELF_TEST = 0x05;

    /** 0x21 Data3：Android 主动停止。 */
    public static final int FINISH_REASON_ANDROID_STOP = 0x00;
    /** 0x21 Data3：达到本次最大允许数量。 */
    public static final int FINISH_REASON_MAXIMUM_REACHED = 0x01;
    /** 0x21 Data3：控制板会话超时。 */
    public static final int FINISH_REASON_SESSION_TIMEOUT = 0x02;
    /** 0x21 Data3：用户停止投珠后的自然结束。 */
    public static final int FINISH_REASON_NATURAL = 0x03;
    /** 0x21 Data3：维护结束。 */
    public static final int FINISH_REASON_MAINTENANCE = 0x04;

    private static final int DEFAULT_COLLECT_TIMEOUT_SECONDS = 300;
    private static final int STOP_REASON_ANDROID = 0x00;
    private static final int RESET_CLEAR_FAULT_ONLY = 0x00;

    private final AtomicInteger frameId = new AtomicInteger(0);

    public BoardEvent decode(byte[] data, int length) {
        if (data == null || length <= 0) {
            return null;
        }
        byte[] raw = Arrays.copyOf(data, length);
        int offset = findFrameOffset(data, length);
        if (offset < 0) {
            return new BoardEvent(BoardEvent.TYPE_RAW, 0, "", "", raw);
        }

        byte[] frame = Arrays.copyOfRange(data, offset, offset + FRAME_LENGTH);
        if (!crcValid(frame)) {
            return new BoardEvent(
                    BoardEvent.TYPE_RAW,
                    0,
                    codeName(RESULT_CRC_ERROR),
                    "控制板帧 CRC 校验失败",
                    frame
            );
        }

        int resendId = u8(frame[1]);
        int id = u8(frame[2]);
        int code1 = u8(frame[3]);
        int code2 = u8(frame[4]);
        int data1 = u8(frame[5]);
        int data2 = u8(frame[6]);
        int data3 = u8(frame[7]);
        int data4 = u8(frame[8]);
        int ackByte = u8(frame[9]);
        int expandCode = u8(frame[10]);

        if (code1 != CODE1_BOARD_TO_ANDROID) {
            return new BoardEvent(BoardEvent.TYPE_RAW, 0, "", "非控制板到 Android 方向帧", frame,
                    resendId, id, code1, code2, data1, data2, data3, data4,
                    ackByte, expandCode, false);
        }

        if (ackByte == ACK_ECHO) {
            return new BoardEvent(BoardEvent.TYPE_ACK, quantity(data1, data2),
                    codeName(expandCode), codeName(expandCode), frame,
                    resendId, id, code1, code2, data1, data2, data3, data4,
                    ackByte, expandCode, false);
        }

        switch (code2) {
            case CODE2_STATUS:
            case CODE2_HEARTBEAT:
            case CODE2_BOARD_BOOT:
                return new BoardEvent(BoardEvent.TYPE_STATUS, quantity(data2, data3),
                        codeName(expandCode), codeName(expandCode), frame,
                        resendId, id, code1, code2, data1, data2, data3, data4,
                        ackByte, expandCode, false);
            case CODE2_COUNT_CHANGED:
                return new BoardEvent(BoardEvent.TYPE_COUNT_CHANGED, quantity(data1, data2),
                        codeName(expandCode), codeName(expandCode), frame,
                        resendId, id, code1, code2, data1, data2, data3, data4,
                        ackByte, expandCode, false);
            case CODE2_COLLECT_FINISHED:
                return new BoardEvent(BoardEvent.TYPE_FINISHED, quantity(data1, data2),
                        codeName(expandCode), finishReasonName(data3), frame,
                        resendId, id, code1, code2, data1, data2, data3, data4,
                        ackByte, expandCode, true);
            case CODE2_FAULT_EVENT:
                return new BoardEvent(BoardEvent.TYPE_FAULT, quantity(data1, data2),
                        codeName(expandCode), codeName(expandCode), frame,
                        resendId, id, code1, code2, data1, data2, data3, data4,
                        ackByte, expandCode, true);
            default:
                return new BoardEvent(BoardEvent.TYPE_RAW, 0,
                        codeName(RESULT_UNKNOWN_CODE2), "未知控制板 Code2：0x" + hex(code2), frame,
                        resendId, id, code1, code2, data1, data2, data3, data4,
                        ackByte, expandCode, false);
        }
    }

    public byte[] buildStartCollectFrame(int maximumQuantity) {
        return buildStartCollectFrame(maximumQuantity, DEFAULT_COLLECT_TIMEOUT_SECONDS);
    }

    public byte[] buildStartCollectFrame(int maximumQuantity, int sessionTimeoutSeconds) {
        int max = clampU16(maximumQuantity);
        int timeout = clampU16(sessionTimeoutSeconds);
        return buildFrame(
                nextFrameId(),
                CODE2_START_COLLECT,
                (max >> 8) & 0xFF,
                max & 0xFF,
                (timeout >> 8) & 0xFF,
                timeout & 0xFF,
                ACK_NONE,
                RESULT_OK
        );
    }

    public byte[] buildStopCollectFrame() {
        return buildStopCollectFrame(STOP_REASON_ANDROID);
    }

    public byte[] buildStopCollectFrame(int stopReason) {
        return buildFrame(
                nextFrameId(),
                CODE2_STOP_COLLECT,
                stopReason & 0xFF,
                0,
                0,
                0,
                ACK_NONE,
                RESULT_OK
        );
    }

    public byte[] buildStatusQueryFrame() {
        return buildFrame(nextFrameId(), CODE2_STATUS, 0, 0, 0, 0, ACK_NONE, RESULT_OK);
    }

    public byte[] buildClearFaultFrame() {
        return buildClearFaultFrame(RESET_CLEAR_FAULT_ONLY);
    }

    public byte[] buildClearFaultFrame(int resetType) {
        return buildFrame(nextFrameId(), CODE2_CLEAR_FAULT,
                resetType & 0xFF, 0, 0, 0, 0, ACK_NONE, RESULT_OK);
    }

    public byte[] buildHeartbeatFrame() {
        return buildFrame(nextFrameId(), CODE2_HEARTBEAT, 0, 0, 0, 0, ACK_NONE, RESULT_OK);
    }

    public byte[] buildAckFrame(BoardEvent event) {
        return buildAckFrame(event, RESULT_OK);
    }

    public byte[] buildAckFrame(BoardEvent event, int resultCode) {
        if (event == null) {
            return new byte[0];
        }
        return buildFrame(
                event.resendId,
                event.frameId,
                event.code2,
                event.data1,
                event.data2,
                event.data3,
                event.data4,
                ACK_ECHO,
                resultCode & 0xFF
        );
    }

    private byte[] buildFrame(
            int id,
            int code2,
            int data1,
            int data2,
            int data3,
            int data4,
            int ackByte,
            int expandCode
    ) {
        return buildFrame(0, id, code2, data1, data2, data3, data4, ackByte, expandCode);
    }

    private byte[] buildFrame(
            int resendId,
            int id,
            int code2,
            int data1,
            int data2,
            int data3,
            int data4,
            int ackByte,
            int expandCode
    ) {
        byte[] frame = new byte[FRAME_LENGTH];
        frame[0] = (byte) HEAD;
        frame[1] = (byte) (resendId & 0xFF);
        frame[2] = (byte) (id & 0xFF);
        frame[3] = (byte) CODE1_ANDROID_TO_BOARD;
        frame[4] = (byte) (code2 & 0xFF);
        frame[5] = (byte) (data1 & 0xFF);
        frame[6] = (byte) (data2 & 0xFF);
        frame[7] = (byte) (data3 & 0xFF);
        frame[8] = (byte) (data4 & 0xFF);
        frame[9] = (byte) (ackByte & 0xFF);
        frame[10] = (byte) (expandCode & 0xFF);
        int crc = crc16Modbus(frame, 0, 11);
        frame[11] = (byte) (crc & 0xFF);
        frame[12] = (byte) ((crc >> 8) & 0xFF);
        frame[13] = (byte) TAIL;
        return frame;
    }

    private int nextFrameId() {
        return frameId.updateAndGet(value -> (value + 1) & 0xFF);
    }

    private static int findFrameOffset(byte[] data, int length) {
        int maxOffset = length - FRAME_LENGTH;
        for (int i = 0; i <= maxOffset; i++) {
            if (u8(data[i]) == HEAD && u8(data[i + FRAME_LENGTH - 1]) == TAIL) {
                return i;
            }
        }
        return -1;
    }

    private static boolean crcValid(byte[] frame) {
        if (frame == null || frame.length != FRAME_LENGTH) {
            return false;
        }
        int expected = crc16Modbus(frame, 0, 11);
        int actual = u8(frame[11]) | (u8(frame[12]) << 8);
        return expected == actual;
    }

    private static int crc16Modbus(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= u8(data[i]);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static int quantity(int high, int low) {
        return ((high & 0xFF) << 8) | (low & 0xFF);
    }

    private static int clampU16(int value) {
        if (value <= 0) {
            return 0;
        }
        return Math.min(value, 0xFFFF);
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }

    private static String hex(int value) {
        String text = Integer.toHexString(value & 0xFF).toUpperCase();
        return text.length() == 1 ? "0" + text : text;
    }

    private static String codeName(int code) {
        switch (code & 0xFF) {
            case RESULT_OK:
                return "OK";
            case RESULT_BUSY:
                return "BUSY";
            case RESULT_PARAM_INVALID:
                return "PARAM_INVALID";
            case RESULT_STATE_INVALID:
                return "STATE_INVALID";
            case RESULT_CRC_ERROR:
                return "CRC_ERROR";
            case RESULT_UNKNOWN_CODE2:
                return "UNKNOWN_CODE2";
            case RESULT_NOT_READY:
                return "NOT_READY";
            case RESULT_SENSOR_ERROR:
                return "SENSOR_ERROR";
            case RESULT_TIMEOUT:
                return "TIMEOUT";
            case RESULT_MOTOR_ERROR:
                return "MOTOR_ERROR";
            case RESULT_JAM:
                return "JAM";
            case RESULT_EMERGENCY_STOP:
                return "EMERGENCY_STOP";
            case RESULT_STORAGE_FULL:
                return "STORAGE_FULL";
            case RESULT_DUPLICATE_ACCEPTED:
                return "DUPLICATE_ACCEPTED";
            case RESULT_UNKNOWN:
                return "UNKNOWN";
            default:
                return "CODE_0x" + hex(code);
        }
    }

    private static String finishReasonName(int reason) {
        switch (reason & 0xFF) {
            case FINISH_REASON_ANDROID_STOP:
                return "ANDROID_STOP";
            case FINISH_REASON_MAXIMUM_REACHED:
                return "MAXIMUM_REACHED";
            case FINISH_REASON_SESSION_TIMEOUT:
                return "SESSION_TIMEOUT";
            case FINISH_REASON_NATURAL:
                return "NATURAL_FINISH";
            case FINISH_REASON_MAINTENANCE:
                return "MAINTENANCE_FINISH";
            default:
                return "FINISH_0x" + hex(reason);
        }
    }
}
