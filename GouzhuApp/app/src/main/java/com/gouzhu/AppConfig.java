package com.gouzhu;

/**
 * 购珠机应用固定配置。
 *
 * <p>当前正式应用只使用包名 com.gouzhu，不读取旧包名或旧应用数据。</p>
 */
public final class AppConfig {

    private AppConfig() {
    }

    /** 安卓板与控制板通信串口。 */
    public static final String SERIAL_DEVICE = "/dev/ttyS5";

    /** 安卓板与控制板通信波特率。 */
    public static final int SERIAL_BAUD_RATE = 115200;

    /** 生产 HTTP 网关。 */
    public static final String ACTIVATION_BASE_URL = "https://api.dzxd.top";

    /** 激活接口未返回时使用的默认心跳周期。 */
    public static final int DEFAULT_HEARTBEAT_SECONDS = 60;

    /** 激活接口未返回时使用的默认 MQTT keepAlive。 */
    public static final int DEFAULT_MQTT_KEEP_ALIVE_SECONDS = 90;

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

    /** 会员存珠界面事件广播。 */
    public static final String ACTION_COLLECTION_EVENT =
            "com.gouzhu.action.COLLECTION_EVENT";
}
