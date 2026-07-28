package com.gouzhu;

/**
 * 购珠机应用的固定配置。
 *
 * <p>这里只保存当前项目已经明确的设备参数，不加入未确认的业务配置。</p>
 */
public final class AppConfig {

    private AppConfig() {
    }

    /** 安卓板与控制板通信串口。 */
    public static final String SERIAL_DEVICE = "/dev/ttyS5";

    /** 安卓板与控制板正常通信及 Bootloader 通信波特率。 */
    public static final int SERIAL_BAUD_RATE = 115200;

    /** 设备注册激活接口地址，沿用 OTA_XLH3566。 */
    public static final String ACTIVATION_BASE_URL = "https://api.dzxd.top";

    /** MQTT 默认心跳周期，激活接口未下发时使用。 */
    public static final int DEFAULT_HEARTBEAT_SECONDS = 60;

    /** MQTT 默认 keepAlive，激活接口未下发时使用。 */
    public static final int DEFAULT_MQTT_KEEP_ALIVE_SECONDS = 20;

    /** 前台服务通知渠道。 */
    public static final String SERVICE_CHANNEL_ID = "gouzhu_device_service";

    /** 前台服务通知编号。 */
    public static final int SERVICE_NOTIFICATION_ID = 1001;

    /** 设备服务状态广播。 */
    public static final String ACTION_SERVICE_STATUS =
            "com.gouzhu.action.SERVICE_STATUS";

    /** 控制板事件广播。 */
    public static final String ACTION_BOARD_EVENT =
            "com.gouzhu.action.BOARD_EVENT";
}
