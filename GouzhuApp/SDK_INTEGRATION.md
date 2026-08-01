# 售珠机设备端 SDK 接入说明

## 1. JAR 放置位置

Android 工程正式使用以下三个服务端交付包：

```text
GouzhuApp/app/libs/xiaoda-device-sdk-0.1.0.jar
GouzhuApp/app/libs/xiaoda-device-mqtt-0.1.0.jar
GouzhuApp/app/libs/xiaoda-device-hardware-0.1.0.jar
```

Gradle 引用：

```groovy
implementation files('libs/xiaoda-device-sdk-0.1.0.jar')
implementation files('libs/xiaoda-device-mqtt-0.1.0.jar')
implementation files('libs/xiaoda-device-hardware-0.1.0.jar')
```

旧文件已删除，禁止重新加入：

```text
pinball-device-sdk-client-0.1.0-SNAPSHOT-standalone.jar
pinball-device-sdk-client-0.1.0-SNAPSHOT.jar
```

三个新 JAR 均为 Java 8 字节码；当前 App 继续使用 Java 11 编译配置。开启 R8/ProGuard 时保留：

```proguard
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
```

## 2. 三个模块职责

- `xiaoda-device-sdk`：core、protocol、client、HTTP 生命周期、设备屏 API、MQTT 协议模型和内部重定位 Gson。
- `xiaoda-device-mqtt`：`MqttSessionManager`、`PahoMqttTransport`、断线重连、心跳、状态上报及 outbox 回放；内部 Paho 已重定位。
- `xiaoda-device-hardware`：`MarbleHardwareAdapter`、`CashConfigurationAdapter`、`DispenseRequest`、`CollectRequest`、`HardwareExecutionResult` 和现金档位类型。

## 3. 首次报到自动登记

新版 `xiaoda-device-sdk-0.1.0.jar` 的首次报到接口改为接收完整 `KeyPair`：

```java
lifecycleClient.enroll(identityKeyPair, firmwareVersion, apkVersion);
```

SDK 使用同一个 `KeyPair` 完成以下工作：

1. 使用私钥对报到请求签名；
2. 自动把 X.509 SubjectPublicKeyInfo Base64 公钥写入 `identityPublicKey`；
3. 调用 `/api/device/enroll`；
4. 平台在 `device.identity.auto-registration-enabled=true` 时验证签名并自动登记首次身份公钥。

设备端不得自行拼接 `identityPublicKey`，也不得生成临时报到密钥。报到、激活和凭证恢复必须继续使用 AndroidKeyStore 中同一个 alias 对应的密钥对。

激活和凭证恢复接口不变，仍传私钥句柄：

```java
lifecycleClient.activateWithIdentity(identityKeyPair.getPrivate(), firmwareVersion, apkVersion);
lifecycleClient.recoverCredential(identityKeyPair.getPrivate(), firmwareVersion, apkVersion);
```

平台开关关闭时仍保留原来的预登记行为，未登记设备会返回 `设备身份未登记`。完整增量说明见：

```text
docs/设备报到自动登记增量说明.md
```

## 4. 当前代码接入范围

当前 App 已使用新 `xiaoda-device-sdk` 中的强类型接口：

- `DeviceLifecycleClient`：完整 `KeyPair` 首次报到、身份激活、日常 reactivate 和人工凭证恢复；
- `DeviceCredentialManager` + `SdkCredentialStore`：原子保存完整 MQTT 凭证；
- `DeviceAppClient.bootstrap()`：动态首页、功能配置、购珠规则和价格档位；
- 原生购珠、会员取珠、内部核销的创建和查询接口。

三个 JAR 均已加入正式构建依赖。现有生产逻辑暂时保留以下边界，避免在未完成数据库和真机闭环前改变物理动作：

- MQTT 仍由现有 `MqttManager` 和宿主 Paho 执行；新版 MQTT JAR 的内部 Paho 已重定位，不会产生重复类。
- `/dev/ttyS5`、14 字节协议、真实光眼计数和控制板库存仍由现有 `SerialManager`、`DeviceCommandManager` 和控制板固件负责。
- `MarbleHardwareAdapter`、`CashConfigurationAdapter` 已可引用，但只有在完成真实结果等待、掉电恢复和幂等数据库后才能作为正式执行入口，不能返回伪造的物理数量。

## 5. 后续切换 MQTT 会话模块的前置条件

切换到 `MqttSessionManager` 前必须先完成：

1. Android 数据库版 `PendingMessageStore`，使用稳定单调游标分页；
2. 完整保存 `MqttReceiptKey`，只能在平台业务 ACK 后删除；
3. 收到 MQTT 原始消息后先持久化，再使用 `DeviceMqttCommandCodec` 联合校验 Topic、deviceNo、类型和字段；
4. 相同 `messageId` 只能重发已保存 ACK/终态，不得再次启动电机；
5. `MarbleHardwareAdapter` 必须等待控制板真实光眼结果，再返回 `actualQuantity`；
6. 现金配置完整保存并应用成功后才允许开启验钞，失败调用 `disableCashAcceptance()`。

## 6. 构建

```powershell
cd GouzhuApp
.\gradlew.bat clean :app:assembleRelease
```

输出：

```text
GouzhuApp/app/build/outputs/apk/release/GouzhuApp_V1.2.5.apk
```

## 7. 联调重点

1. 开启平台自动登记后，未登记设备首次报到不再返回 `设备身份未登记`；
2. 首次报到返回认领二维码和认领码，商户认领后完成首次激活；
3. 已登记设备继续按原公钥验签，不允许自动换钥；
4. 重启后 reactivate、管理员恢复窗口及 MQTT 凭证原子替换保持不变；
5. 动态首页、购珠档位、扫码订单创建和状态轮询；
6. MQTT Broker、账号、Topic、QoS、心跳和 KeepAlive 全部以最新凭证为准；
7. 出珠、收珠和现金配置必须使用平台合法命令和真实物理结果；
8. 断网、进程重启和重复消息不能重复驱动电机；
9. 日志不得输出私钥、MQTT 密码、签名、完整取珠码或 operationToken。
