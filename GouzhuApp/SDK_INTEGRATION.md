# 售珠机设备端 SDK 接入说明

## 1. JAR 放置位置

正式 Android 工程只放置并引用以下文件：

```text
GouzhuApp/app/libs/pinball-device-sdk-client-0.1.0-SNAPSHOT-standalone.jar
```

Gradle 引用：

```groovy
implementation files('libs/pinball-device-sdk-client-0.1.0-SNAPSHOT-standalone.jar')
```

不要同时加入 `pinball-device-sdk-client-0.1.0-SNAPSHOT.jar`。后者是瘦包，缺少 core 和 Gson 依赖；standalone 已包含 core，并使用重定位后的 Gson，不会与 App 自己的 Gson 包名冲突。

本次接入 JAR 的 SHA-256：

```text
c43e038e1261dd04d565a4b686c475dbb1ee5607f561df3d94fe94a83f840e86
```

## 2. 当前接入范围

- 使用 SDK `DeviceLifecycleClient` 完成首次报到、身份激活、日常 reactivate 和人工凭证恢复。
- 使用 AndroidKeyStore P-256 私钥作为设备身份；私钥不导出。
- 使用 SDK `DeviceCredentialManager` 和自定义 `CredentialStore` 原子保存完整 MQTT 凭证快照。
- MQTT Broker、账号、密码、订阅 Topic、上报 Topic、QoS、心跳和 KeepAlive 全部来自 SDK 凭证。
- 使用 `DeviceAppClient.bootstrap()` 动态加载购珠区标题、说明、购珠规则和价格档位。
- 使用 SDK 创建并轮询聚合扫码购珠订单。
- 已提供会员取珠和内部套餐核销的 SDK 调用入口；物理吐珠仍只响应 MQTT `dispense_marbles`。
- 现有 `/dev/ttyS5` 串口、真实光眼计数、指令持久化、ACK/终态和现金业务保持由 App/控制板负责。

## 3. 未包含的交付物

服务端说明要求的以下文件本次未收到：

```text
pinball-device-sdk-hardware-api-0.1.0-SNAPSHOT.jar
```

因此当前不能按该 JAR 中的真实接口签名实现 `MarbleHardwareAdapter` 和 `CashConfigurationAdapter`。在收到硬件 API JAR 前，项目继续使用现有 `SerialManager`、`DeviceCommandManager` 和控制板协议执行真实硬件动作，不能猜测或伪造缺失接口。

## 4. 构建

```powershell
cd GouzhuApp
.\gradlew.bat clean :app:assembleRelease
```

输出：

```text
GouzhuApp/app/build/outputs/apk/release/GouzhuApp_V1.2.0.apk
```

## 5. 上线前必须完成

1. 使用最终包名 `com.gouzhu` 和最终签名证书安装。
2. 在每台设备生成身份公钥，并向平台登记规范化后的 `deviceNo` 和 X.509 公钥 Base64。
3. 验证首次报到、等待认领、激活、重启后 reactivate、管理员开启窗口后的凭证恢复。
4. 验证 SDK bootstrap、聚合扫码创建/查询、重复请求号和超时行为。
5. 验证 MQTT Topic、QoS、心跳、状态、ACK、终态、断网重连和重复消息不重复驱动电机。
6. 日志不得输出私钥、MQTT 密码、签名、完整取珠码和 operationToken。
