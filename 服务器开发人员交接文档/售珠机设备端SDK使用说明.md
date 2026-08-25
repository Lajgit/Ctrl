# 售珠机设备端 SDK 使用说明

本文供售珠机 Android Java 设备同事使用，目标是把设备从首次报到带到 MQTT 在线，并完成
设备屏动态首页、购珠、会员取珠、内部套餐核销和硬件执行对接。

SDK 不替代 Android APP、AndroidKeyStore、数据库和主板串口驱动。SDK 负责协议签名、HTTP
请求、响应模型、MQTT 连接与会话、Topic/命令校验和硬件适配接口；设备工程负责界面、
持久化、扫码器、业务消息处理和真实物理计数。

现金配置、投币事件、平台出珠授权和真实数量回报的完整实现顺序见
[现金投币与出珠增量说明](现金投币与出珠增量说明.md)。现金开关与运行可用状态的字段边界、
启动顺序和排查方法见[现金开关与运行可用状态增量说明](现金开关与运行可用状态增量说明.md)。

## 1. 交付包

使用以下三个包：

```text
xiaoda-device-sdk-0.1.0.jar
xiaoda-device-mqtt-0.1.0.jar
xiaoda-device-hardware-0.1.0.jar
```

`xiaoda-device-sdk` 已包含 `core`、`protocol`、`client` 和重定位后的 Gson；
`xiaoda-device-mqtt` 已包含 MQTT 适配器和重定位后的 Eclipse Paho；
`xiaoda-device-hardware` 提供硬件适配接口。生产网关为：
`https://api.dzxd.top`。不要在 APP 中写死 MQTT Broker、用户名、密码或 Topic，全部以激活
和凭证恢复响应为准。

SDK 构建工具链要求 JDK 17 或更高版本，支持 JDK 17 和 JDK 21。交付 JAR 为了兼容更多设备
工程，采用 Java 8 字节码；使用 JDK 17/21 的 Android APP 可以直接引入，不需要降级开发环境。
Android 工程应声明 `android.permission.INTERNET`，建议最低 API 21。

## 2. 上线前准备

1. 冻结最终应用包名、签名证书和 AndroidKeyStore alias。
2. 每台设备在本机 AndroidKeyStore 生成独立 ECDSA P-256 密钥对。
3. 首次报到把完整 `KeyPair` 传给 SDK；SDK 只上传 X.509 SubjectPublicKeyInfo 公钥，绝不上传私钥。
4. 后端开启首次报到自动登记时无需提前登记；关闭后必须先由平台登记相同公钥。
5. `deviceNo` 始终使用 SDK 规范化后的值：去除冒号、短横线、下划线和空格，再转大写。

私钥必须可在重启和同包名覆盖升级后继续读取。卸载、清除应用数据和更换签名证书不属于
需要保留凭证的场景。

## 3. 首次报到与激活

```java
DeviceSdkConfig config = DeviceSdkConfig.builder()
        .apiBaseUrl("https://api.dzxd.top")
        .deviceNo(deviceNo)
        .build();
HttpTransport http = new HttpUrlConnectionTransport(config);
DeviceLifecycleClient lifecycle = new DeviceLifecycleClient(config, http);

DeviceEnrollResult enroll = lifecycle.enroll(identityKeyPair, firmwareVersion, apkVersion);
// enroll.isClaimed() 为 false 时展示 claimQrContent 或 claimCode，等待商户认领。

DeviceActivationResult activation = lifecycle.activateWithIdentity(
        identityKeyPair.getPrivate(), firmwareVersion, apkVersion);
if (activation.hasMqttCredential()) {
    new DeviceCredentialManager(credentialStore).replaceFrom(activation);
}
```

商户未认领时，`activateWithIdentity` 返回 `claimed=false`，设备按退避策略继续轮询；不应
重复报到或自行修改 `deviceType`。商户认领后再次调用同一方法，返回完整 MQTT 凭证。

批次自动认领必须让 SDK 同时生成身份签名和批次签名：

```java
DeviceEnrollResult enroll = lifecycle.enrollWithBatch(
        identityKeyPair, firmwareVersion, apkVersion, batchNo, batchSecret);
```

`batchSecret` 只在内存中短暂使用，不写日志、不写普通配置。不要自行先生成
`batchSignature`，因为它必须和身份签名共享同一 nonce/timestamp。

## 4. 日常启动与凭证恢复

日常启动顺序固定为：

```text
读取完整 MQTT 凭证
-> 使用 mqttPassword 调用 reactivate
-> 原子保存新凭证快照
-> 使用响应的 Broker、账号、密码、Topic、QoS 连接
-> 订阅 commandSubscribeTopic
-> 发布 heartbeat 和 status
-> 恢复未确认事件和终态
```

```java
MqttCredential current = new DeviceCredentialManager(credentialStore).loadRequired();
DeviceActivationResult refreshed = lifecycle.reactivate(
        current.getPassword(), firmwareVersion, apkVersion);
new DeviceCredentialManager(credentialStore).replaceFrom(refreshed);
mqttSession.reconnectWithLatestCredential();
```

凭证丢失时不能自动用身份私钥恢复。平台管理员先开启一次性恢复窗口，然后调用：

```java
DeviceActivationResult recovered = lifecycle.recoverCredential(
        identityPrivateKey, firmwareVersion, apkVersion);
new DeviceCredentialManager(credentialStore).replaceFrom(recovered);
```

恢复成功会立即使旧 MQTT 密码失效，必须先完整保存新凭证，再重连 MQTT。

## 5. CredentialStore 实现要求

设备工程实现 `CredentialStore`，至少保存以下完整字段：`deviceNo`、`brokerUrl`、
`clientId`、`username`、`password`、心跳、Keep Alive、配置版本、订阅 Topic、上报 Topic
和 QoS。

- 用 AndroidKeyStore 保护加密密钥，不能明文写入 SharedPreferences 或文件。
- 临时文件/临时版本写入并校验完成后，再原子切换当前版本。
- 任何字段缺失都视为凭证不可用，不能拼接新旧两次响应。
- 日志、崩溃上报和埋点不得出现私钥、MQTT 密码、签名、完整取珠码或 operationToken。

## 6. 设备屏 APP API

APP API 使用当前 MQTT password 作为 HMAC 密钥：

```java
DeviceAppClient app = new DeviceAppClient(config, new DeviceSecretProvider() {
    @Override
    public String getCurrentSecret() {
        return new DeviceCredentialManager(credentialStore).loadRequired().getPassword();
    }
}, http);

DeviceAppBootstrapResult home = app.bootstrap(BuildConfig.VERSION_NAME);
```

`bootstrap` 返回强类型对象，设备屏必须动态使用：

- `presentation.heroMedia`：顶部图片/视频、封面、排序和播放时长；
- `presentation.purchaseSection`：购珠区标题和说明；
- `features`：入口的 `visible`、`available`、`interactionMode` 和不可用原因；
- `purchaseRules`：服务端返回的 `purchaseRuleId`、档位 ID、数量和分价；
- `memberEntryScene`：会员存珠微信小程序入口；
- `pointsMallEntryScene`：积分商城微信小程序入口；
- `cashSale`：设备已确认应用的现金配置快照。

购珠套餐和会员小程序使用同一套后端业务规则数据。设备屏只提交服务端返回的 ID，不能
自行上传金额、数量价格或租户信息。微信 Native、小程序、公众号微信支付和支付宝扫码均由后端根据
租户配置选路；设备只展示返回的 `scanUrl` 和 `supportedChannels`。

现金入口和已应用快照读取示例：

```java
DeviceAppBootstrapResult.CashSaleInfo cashSale = home.getCashSale();
if (cashSale != null && cashSale.getConfigurationVersion() != null
        && !cashSale.getTiers().isEmpty()) {
    // 读取服务端确认的已应用快照，用于展示、核对和现金事件字段取值。
    // 该分支不表示当前允许打开现金入口或验钞器。
    long appliedVersion = cashSale.getConfigurationVersion();
    for (DeviceAppBootstrapResult.CashTierInfo tier : cashSale.getTiers()) {
        String mediumCode = tier.getCashMediumCode();
        int amountInCents = tier.getDenominationAmount();
        int marbleQuantity = tier.getMarbleQuantity();
    }
}

boolean cashEntryAvailable = cashSale != null && cashSale.isAvailable();
String cashEntryUnavailableReason = cashEntryAvailable
        ? null : cashSale == null ? "现金购珠配置不可用" : cashSale.getUnavailableReason();
```

| 字段 | 设备端用途 |
| --- | --- |
| `available` | 是否开放现金购珠入口；不可使用本地配置覆盖该值 |
| `unavailableReason` | 现金入口禁用提示 |
| `configurationVersion` | 设备已回执确认应用的现金配置版本 |
| `itemId/itemCode/itemName/unitName` | 当前现金配置对应的商品及数量单位 |
| `tiers[].cashMediumType` | 介质类型：`1` 纸币、`2` 硬币 |
| `tiers[].cashMediumCode` | 协议介质编码：`banknote` 或 `coin` |
| `tiers[].denominationAmount` | 现金面额，单位为分 |
| `tiers[].marbleQuantity` | 对应出珠数量 |
| `tiers[].tierNo` | 已应用现金档位编号，现金事件上报时原样使用 |

`cashSale` 只返回设备已经确认应用的配置快照。商家刚保存但设备尚未回执的待应用版本不会出现在这里；没有有效快照时 `tiers` 为空，APP 应展示 `unavailableReason`，不得回退到旧缓存继续营业。

`cashSale.available` 不是商家现金开关，也不能替代 MQTT 配置中的
`cashAcceptanceEnabled`。它是服务端计算的当前运行可用状态，还会受到直接购珠授权、配置版本、
MQTT 在线状态、设备运行状态和门店物料库存影响。设备离线、忙碌或库存不足时可能出现
`available=false`，但 `configurationVersion` 和 `tiers` 仍然存在；此时只能关闭现金入口并展示
`unavailableReason`，不得把它解释成“商家关闭现金”。

### 6.1 购珠

```java
DeviceAppNativePurchaseResult purchase = app.createNativePurchase(
        clientRequestNo, purchaseRuleId, priceTierId, null);
// 展示 purchase.getScanUrl()，之后按原 clientRequestNo 轮询：
DeviceAppNativePurchaseResult status = app.queryNativePurchase(clientRequestNo);
```

`priceTierId` 和 `purchaseQuantity` 必须二选一。请求号 16 至 64 位，只能使用字母、数字、
下划线和短横线；重试必须复用原请求号和完全相同的计价参数。

`scanUrl` 可能是微信 Native `weixin://wxpay/...`、服务端聚合扫码地址或微信小程序 URL Link。
APP 必须始终将它原样渲染成二维码。`supportedChannels` 可能包含
`WECHAT_NATIVE`、`WECHAT_MINIAPP`、`WECHAT_JSAPI`、`ALIPAY_PRECREATE`；这些值只用于展示或诊断，设备不得
根据渠道值自行调起支付、修改二维码或启动出珠。现有 SDK 方法和返回类型不变。

### 6.2 会员取珠和内部核销

```java
DeviceAppBootstrapResult bootstrap = app.bootstrap(appVersion);
DeviceAppRedemptionRouting routing = bootstrap.getRedemptionRouting();

DeviceAppMemberWithdrawalResult withdrawal = app.createMemberWithdrawalFromRoutedCode(
        clientRequestNo, routing, scannedMemberWithdrawalCode);
DeviceAppMemberWithdrawalResult withdrawalStatus = app.queryMemberWithdrawal(clientRequestNo);

DeviceAppInternalRedemptionResult redemption = app.createInternalRedemptionFromRoutedCode(
        clientRequestNo, routing, scannedInternalRedemptionCode);
DeviceAppInternalRedemptionResult redemptionStatus = app.queryInternalRedemption(clientRequestNo);
```

会员取珠二维码当前为 `W...`，`W` 属于原始业务码，SDK 验证前缀后原样提交。平台套餐二维码当前为 `XD:1:PICKUP:六位码`，SDK 按服务端 `stripPrefix=true` 剥离协议头，只向 HTTP 接口提交六位 `pickupCode`。店员手工输入六位码时仍可直接调用 `createInternalRedemption`。

创建响应只表示后端受理，不代表设备已经出珠。APP 必须根据状态展示结果，真正出珠只能等待
MQTT 下发合法 `dispense_marbles` 指令。会员存珠由微信小程序创建业务，设备屏只展示入口，
设备硬件执行平台下发的 `collect_marbles`。

### 6.3 第三方团购核销

设备不能硬编码任何内部或第三方核销前缀。第三方扫码使用同一份 bootstrap 路由配置进行渠道分类：

```java
DeviceAppThirdPartyRedemptionResult redemption =
        app.createThirdPartyRedemptionFromRoutedCode(
                clientRequestNo, routing, scannedCode);
DeviceAppThirdPartyRedemptionResult status =
        app.queryThirdPartyRedemption(clientRequestNo);
```

`routing.thirdPartyChannels` 是服务端允许识别的渠道白名单。SDK 精确匹配 `voucherCodePrefix`，剥离前缀后才提交原始核销码；未知前缀和空主体直接拒绝。`routingVersion` 变化时 APP 应整体替换内部和第三方路由缓存，不能合并新旧规则。

路由前缀只减少普通二维码误扫，并不能证明券码真实。只有 bootstrap 的 `THIRD_PARTY_REDEMPTION.available=true` 时才能开放入口，真正出珠仍只能执行平台下发的可信 MQTT 指令。

## 7. MQTT 连接与会话

SDK 已提供 `PahoMqttTransport` 和 `MqttSessionManager`。APP 启动时构建一个长生命周期会话：

```java
MqttSessionManager mqttSession = new MqttSessionManager(
        new PahoMqttTransport(),
        credentialStore,
        pendingMessageStore,
        statusPayloadProvider,
        new MqttSessionListener() {
            @Override
            public void onStateChanged(MqttSessionState state,
                    MqttFailureType failureType, Throwable cause) {
                // 切换在线状态；禁止记录凭证和业务敏感字段。
            }

            @Override
            public void onMessage(String topic, byte[] payload) {
                // 持久化原始消息并完成校验、幂等和硬件执行。
            }
        });
mqttSession.start();
```

管理器会使用激活响应中的 Broker、`clientId`、`username`、`password` 完成 MQTT CONNECT，
自动订阅、上报首次心跳/状态、周期心跳、回放未确认消息，并对网络故障指数退避重连。鉴权、
授权和协议错误进入 `FAILED`，需要排查配置或更新凭证，不能无限重试。

`/api/device/mqtt/auth` 与 `/api/device/mqtt/authz` 是 EMQX 调用后端的 Broker 回调，APP
不得主动请求。凭证刷新或恢复并原子保存后，调用 `reconnectWithLatestCredential()`；永久销毁
会话时调用 `close()`。

`PendingMessageStore` 必须使用本地数据库实现。调用 `publishDurable` 时 SDK 先落入 outbox
再发送；只有收到平台业务 ACK 后才能调用 `markBusinessConfirmed`，MQTT PUBACK 不能删除记录。

outbox 必须保存完整 `MqttReceiptKey`。指令回执的键由 SDK 按
`sourceMessageId + eventNo + resultStatus` 构造，不能只按 eventNo 删除。数据库实现的
`listPendingPage(cursor, limit)` 必须使用稳定游标分页；SDK 会在重连时持续翻页，直到所有待
确认记录均已补发。

## 8. MQTT 指令与硬件

处理 MQTT 指令时先使用协议编解码层：

```java
DeviceMqttCommand<?> command = new DeviceMqttCommandCodec().decode(
        topic, payload, deviceNo, System.currentTimeMillis());

if (DeviceMqttCommandTypes.COMMAND_RESULT_ACK.equals(command.getCommandType())) {
    CommandResultAcknowledgement receipt =
            command.requireData(CommandResultAcknowledgement.class);
    if (receipt.isRecorded()) {
        mqttSession.markBusinessConfirmed(receipt.getReceiptKey());
    }
    return; // command_result_ack 禁止再回复 command-result
}

DeviceHardwareCommandMapper mapper = new DeviceHardwareCommandMapper();
DispenseRequest request = mapper.toDispenseRequest(command, System.currentTimeMillis());
DeviceCommandResult ack = DeviceCommandResult.acknowledgement(
        command, ackEventNo, System.currentTimeMillis());
byte[] ackPayload = new DeviceCommandResultCodec().encode(ack);
PendingOutboundMessage pendingAck = new PendingOutboundMessage(
        ack.getReceiptKey(), commandResultTopic, ackPayload, System.currentTimeMillis());
mqttSession.publishDurable(pendingAck);
```

`DeviceMqttCommandCodec` 会联合校验 Topic、设备号、命令类型、JSON 字段类型、数量和有效期。
不要绕过它直接构造硬件请求。设备仍必须在数据库中原子登记 `messageId`，重复消息只重发已
保存的 ACK/终态，不得再次启动硬件。

收到 `sync_cash_configuration` 时，设备必须通过
`CashConfigurationCommandData.isCashAcceptanceEnabled()` 读取商家下发的现金接收开关。
该值为 `false` 时持久化禁用版本并调用 `CashConfigurationAdapter.disableCashAcceptance()`；
该值为 `true` 时完整持久化 `configVersion` 和全部 `cashSaleItems`，随后调用
`CashConfigurationAdapter.apply`。只有持久化和硬件应用都成功后才能回成功终态并允许验钞器工作。
`bootstrap.cashSale.available` 只负责当前运行门控，不能写回或覆盖这份持久化配置。

### 8.1 只读模型、setter 与安全日志

SDK 从 HTTP 或 MQTT 解析出的模型采用只读设计：提供 getter，不提供 setter。原因是这些对象已经经过
协议字段和业务边界校验，调用方在解析后修改 `messageId`、`configVersion`、数量、档位或
`operationToken` 会破坏幂等和安全约束。

设备 APP 不应把 SDK 响应模型直接作为 Room/SQLite Entity。正确边界是：

```text
SDK 只读模型
-> APP 校验并映射
-> APP 自己的可变数据库 Entity
-> Room/SQLite 原子持久化
-> 硬件适配器
```

APP 自己的 Entity 可以按 Android 工程需要提供 setter、Builder 或构造函数。需要产生平台回执时，使用
SDK 已提供的构造函数、静态工厂或 Builder，例如 `DeviceCommandResult.builder(...)`、
`PendingOutboundMessage(...)` 和 `CashTier(...)`，不要反向修改收到的只读 DTO。

常用 SDK 模型已经实现安全 `toString()`，可以直接记录诊断日志：

```java
log.info("收到设备指令: {}", command);
log.info("现金配置: {}", command.requireData(CashConfigurationCommandData.class));
log.info("设备启动配置: {}", bootstrapResult);
```

安全日志会自动把 MQTT 密码、Secret、Token、认领码、支付链接、券码和原始 payload 显示为
`<redacted>`，并把换行转义、长文本截断、集合限制为最多 20 项。这个输出只适合诊断，不是稳定的 JSON
协议，也不能用于数据库序列化、签名、幂等键计算或业务判断。日志级别仍应按生产要求控制，设备私钥
和原始请求体不得自行拼接到日志中。

其他要求：

1. 只订阅激活返回的 `commandSubscribeTopic`，通常是本机 `command/#`。
2. 连接成功立即上报 heartbeat 和 status，之后使用返回的心跳间隔。
3. 先持久化原始 payload，再 ACK；PUBACK 不是业务确认。
4. 收到相同 `messageId` 只能重发已保存 ACK/终态，不得再次启动电机。
5. Topic 中的 `deviceNo`、信封 `deviceNo`、命令类型都必须校验。

出珠命令由 `DeviceHardwareCommandMapper.toDispenseRequest` 转换，收珠命令由
`toCollectRequest` 转换；收珠请求会保留 `sessionTimeoutSeconds`。协议时间统一按
`Asia/Shanghai` 解析，设备 APP 不需要再次手写日期转换。

`MarbleHardwareAdapter` 只能返回真实物理计数。无法确认动作结果时保留 ACK 并转人工，不能
伪造 `actualQuantity=0`。现金配置只有完整持久化并应用成功后才能开启验钞，失败必须调用
`CashConfigurationAdapter.disableCashAcceptance()`。

## 9. 联调清单

- 新身份私钥、公钥登记和首次恢复成功；重启及覆盖升级后仍可读取。
- 报到、等待认领、首次激活、日常 HMAC 激活、凭证恢复全部成功。
- MQTT 登录、Topic 订阅、QoS、心跳、状态和故障上报成功。
- bootstrap 顶部视频、功能配置、购珠档位、微信存珠码和积分商城码动态刷新。
- 微信/支付宝聚合购珠、会员取珠、内部套餐核销的创建、轮询、超时和重复请求验证。
- 出珠、收珠、现金配置的 ACK、真实终态、断网、重连、进程重启和重复消息验证。
- 日志中无密码、私钥、完整取珠码和一次性令牌。

通过以上清单后，才可将 JAR 和设备 APP 一起交付真机验收。
