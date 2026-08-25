package com.chuzhu;

/**
 * 存珠机应用固定配置。
 */
public final class AppConfig {

    private AppConfig() {
    }

    /** Android 板与存珠机控制板通信串口。 */
    public static final String DEFAULT_BOARD_SERIAL_PORT = "/dev/ttyS5";

    /** Android 板与存珠机控制板通信波特率，8N1 无硬件流控。 */
    public static final int DEFAULT_BOARD_BAUD_RATE = 115200;

    /** 平台设备类型：存珠机。 */
    public static final int DEVICE_TYPE_MARBLE_DEPOSIT_MACHINE = 3;

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_IN_GAME = 1;
    public static final int STATUS_FAULT = 2;
    public static final int STATUS_MAINTENANCE = 3;
    public static final int STATUS_UPGRADING = 4;
    public static final int STATUS_DISPENSING = 5;
    public static final int STATUS_COLLECTING = 6;

    /** 生产 HTTP 网关，沿用平台 SDK 生命周期接口。 */
    public static final String ACTIVATION_BASE_URL = "https://api.dzxd.top";

    public static final int DEFAULT_HEARTBEAT_SECONDS = 60;
    public static final int DEFAULT_MQTT_KEEP_ALIVE_SECONDS = 90;
    public static final int DEFAULT_COLLECT_TIMEOUT_SECONDS = 300;

    public static final String SERVICE_CHANNEL_ID = "chuzhu_device_service";
    public static final int SERVICE_NOTIFICATION_ID = 3001;

    public static final String ACTION_SERVICE_STATUS = "com.chuzhu.action.SERVICE_STATUS";
    public static final String ACTION_BOARD_EVENT = "com.chuzhu.action.BOARD_EVENT";
    public static final String ACTION_DEPOSIT_STATE = "com.chuzhu.action.DEPOSIT_STATE";
}
