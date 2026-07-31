# 本地设备 SDK JAR

本目录正式保留并由 `app/build.gradle` 引用以下三个服务端交付包：

```text
xiaoda-device-sdk-0.1.0.jar
xiaoda-device-mqtt-0.1.0.jar
xiaoda-device-hardware-0.1.0.jar
```

职责：

- `xiaoda-device-sdk`：core、protocol、client 和内部重定位 Gson；
- `xiaoda-device-mqtt`：MQTT 会话、Paho 适配器和内部重定位 Paho；
- `xiaoda-device-hardware`：`MarbleHardwareAdapter`、`CashConfigurationAdapter` 及类型化硬件请求/结果。

旧的 `pinball-device-sdk-client-0.1.0-SNAPSHOT*.jar` 已删除，禁止重新加入，避免重复类和新旧 API 混用。
