package com.chuzhu.serial;

import java.util.Arrays;

/**
 * 存珠机控制板帧编解码框架。
 */
public final class BoardFrameCodec {

    /*
     * 待存珠机控制板新串口协议确认后替换。
     * 当前不定义旧售珠机业务码，不把旧协议表迁移到存珠机。
     */
    private static final byte[] TODO_START_COLLECT_FRAME = new byte[0];
    private static final byte[] TODO_STOP_COLLECT_FRAME = new byte[0];

    public BoardEvent decode(byte[] data, int length) {
        if (data == null || length <= 0) {
            return null;
        }
        byte[] raw = Arrays.copyOf(data, length);
        return new BoardEvent(BoardEvent.TYPE_RAW, 0, "", "", raw);
    }

    public byte[] buildStartCollectFrame(int maximumQuantity) {
        /*
         * 待存珠机控制板新串口协议确认后替换。
         * 不用 maximumQuantity 构造任何旧售珠机命令，避免误驱动硬件。
         */
        return TODO_START_COLLECT_FRAME;
    }

    public byte[] buildStopCollectFrame() {
        /*
         * 待存珠机控制板新串口协议确认后替换。
         */
        return TODO_STOP_COLLECT_FRAME;
    }
}
