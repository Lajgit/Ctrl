# 售珠机设备端 SDK 接入说明 V2

## 1. 正式依赖

```text
GouzhuApp/app/libs/xiaoda-device-sdk-0.1.0.jar
GouzhuApp/app/libs/xiaoda-device-mqtt-0.1.0.jar
GouzhuApp/app/libs/xiaoda-device-hardware-0.1.0.jar
```

Gradle：

```groovy
implementation files('libs/xiaoda-device-sdk-0.1.0.jar')
implementation files('libs/xiaoda-device-mqtt-0.1.0.jar')
implementation files('libs/xiaoda-device-hardware-0.1.0.jar')
```

App 版本：`2.0.0`。控制板协议/固件版本：`2.0.0.0`。本版本不兼容旧的本地现金购买流程。

## 2. 生命周期与身份

首次无凭证时：

```java
lifecycleClient.enroll(identityKeyPair, firmwareVersion, apkVersion);
```

SDK 使用同一 AndroidKeyStore P-256 `KeyPair` 完成签名并携带 `identityPublicKey`。认领后继续：

```java
lifecycleClient.activateWithIdentity(
        identityKeyPair.getPrivate(),
        firmwareVersion,
        apkVersion
);
```

日常启动使用 `reactivate`；凭证丢失只允许平台开启恢复窗口后显式 `recoverCredential`。

## 3. MQTT 命令协议

控制和配置消息必须先通过：

```java
DeviceMqttCommand<?> command = commandCodec.decode(
        topic,
        payload,
        deviceNo,
        System.currentTimeMillis()
);
```

禁止先用裸 JSON 决定是否启动硬件。`DeviceMqttCommandCodec` 负责联合校验 Topic、`deviceNo`、命令类型、字段、`operationNo`、`operationToken` 和有效期。

当前入口：

```text
MqttManager
→ DeviceCommandManager.handleCommand(topic, rawPayload)
→ SdkCommandDecoder
→ DeviceMqttCommandCodec
→ DeviceHardwareCommandMapper
→ SQLite 持久化
→ ttyS5 控制板 V2
```

## 4. 统一出珠规则

所有出珠来源统一为：

```text
dispense_marbles.quantity
```

包括：

- 现金购珠；
- 扫码购珠；
- 会员取珠；
- 内部套餐核销；
- 平台运营任务。

以下内容绝不能直接驱动电机：

- 本地现金档位的 `marbleQuantity`；
- `cash_event_response.requestedQuantity`；
- 二维码支付成功页面；
- HTTP 核销成功响应；
- 旧控制板余额、单价或欠吐状态。

## 5. 现金配置

平台下发 `sync_cash_configuration` 后，App 必须：

1. 使用 SDK 协议层验证命令；
2. 验证 `configVersion`、`cashAcceptanceEnabled`、`changeEnabled=false` 和完整 `cashSaleItems`；
3. 在 SQLite 保存完整配置快照；
4. 保存并发布配置 ACK；
5. 通过 ttyS5 `CashAcceptanceApply` 把配置版本和介质启用掩码交给控制板；
6. 只有控制板返回相同版本和掩码后才发布成功终态。

任何失败都关闭纸币和硬币接收。

## 6. 现金事件

现金不可逆进入钱箱后，控制板只上报：

```text
介质 + 面额（分）+ 稳定的板端现金序号
```

App 根据当时已经应用的同一现金配置快照生成：

```json
{
  "eventNo": "稳定事件号",
  "eventType": "accepted",
  "cashMediumType": "banknote",
  "denominationAmount": 500,
  "cashCount": 1,
  "cashSaleTierNo": "平台档位号",
  "configVersion": 12,
  "timestamp": 1785542400000
}
```

App 必须先在同一 SQLite 事务写入 `cash_events` 与 cash outbox，再向控制板发送 `CashEventStored`。超时、断网和重启只能重发完全相同的 `eventNo` 和 payload。

`CashEventResponseCommandData` 使用 SDK 自带方法判断：

```java
isPending();
isProcessing();
isCompleted();
isManualReview();
isRejected();
isUnknown();
```

`unknown` 保留并重发原事件；其他状态表示平台已明确接收原现金事实，可移除现金 outbox。任何状态都不能按 `requestedQuantity` 出珠。

## 7. 出珠与存珠硬件映射

```java
DispenseRequest request = hardwareCommandMapper.toDispenseRequest(
        command,
        System.currentTimeMillis()
);

CollectRequest request = hardwareCommandMapper.toCollectRequest(
        command,
        System.currentTimeMillis()
);
```

App 在发送 ttyS5 启动命令前，必须已经持久化：

- `messageId`；
- `operationNo`；
- `operationToken`；
- 完整原始命令；
- “可能已经启动硬件”状态；
- SDK 编码的 ACK outbox。

相同 `messageId` 只重发持久化 ACK/终态，不允许再次启动电机。

控制板返回真实光眼数量后，使用：

```java
HardwareExecutionResult hardwareResult = success
        ? HardwareExecutionResult.success(actualQuantity)
        : HardwareExecutionResult.failed(actualQuantity, resultCode, resultMessage);

DeviceCommandResult terminal = hardwareCommandMapper.toTerminalResult(
        command,
        terminalEventNo,
        hardwareResult,
        System.currentTimeMillis()
);
```

再由 `DeviceCommandResultCodec` 编码并写入 command-result outbox。

## 8. SQLite 与 outbox

正式数据库：

```text
gouzhu_platform_control_v2.db
```

至少保存：

- 完整命令和硬件启动/终态状态；
- 完整现金配置快照；
- 稳定现金事件；
- command-result outbox；
- cash-event outbox；
- 当前可能未完成的物理操作。

MQTT PUBACK 不能删除业务记录。只有平台 `command_result_ack.recorded` 才删除命令结果 outbox；现金事件按 `CashEventResponseCommandData` 状态处理。

进程重启发现曾经请求过硬件但没有可靠终态时：关闭现金、禁止自动重启电机、上报物理结果未知并转人工处理。

## 9. 控制板边界

控制板 V2 只负责：

- 纸钞机/投币器使能和不可逆现金事实；
- 电机启动停止；
- PD3/PD4 真实光眼计数；
- 库存硬件事实；
- 关键事件持续重发；
- K1 补珠、K2 后台入口；
- Bootloader 升级。

控制板不再保存或计算：

- 本地价格；
- 现金余额；
- 珠数换算；
- 本地订单；
- 欠吐队列；
- 现金自动出珠；
- 旧退币兼容状态。

完整 ttyS5 协议见根目录 `ANDROID_COMMUNICATION_PROTOCOL.md`。

## 10. 构建

```powershell
cd GouzhuApp
.\gradlew.bat clean :app:assembleRelease
```

输出：

```text
GouzhuApp/app/build/outputs/apk/release/GouzhuApp_V2.0.0.apk
```

Android 2.0.0 与控制板 2.0.0.0 必须成套发布，不允许新旧混用。
