package com.chuzhu.serial;

import com.chuzhu.AppConfig;

/**
 * 存珠机控制板串口配置。
 */
public final class BoardSerialConfig {

    public final String devicePath;
    public final int baudRate;

    public BoardSerialConfig(String devicePath, int baudRate) {
        this.devicePath = devicePath;
        this.baudRate = baudRate;
    }

    public static BoardSerialConfig defaultConfig() {
        return new BoardSerialConfig(
                AppConfig.DEFAULT_BOARD_SERIAL_PORT,
                AppConfig.DEFAULT_BOARD_BAUD_RATE
        );
    }
}
