# 反扫模块 ttyS6 接入说明

## 1. 串口配置

```text
设备节点：/dev/ttyS6
默认波特率：9600
数据位：8
停止位：1
校验：无
硬件流控：关闭
```

配置位置：

```text
GouzhuApp/app/src/main/java/com/gouzhu/AppConfig.java
```

ttyS6 由 `ReverseScannerManager` 独占，和控制板 ttyS5 使用独立文件描述符、接收线程和协议缓冲区。

## 2. 分帧与空闲噪声

以下任一条件结束一条扫码帧：

- CR（0x0D）
- LF（0x0A）
- ETX（0x03）
- 连续约 180 ms 没有收到新字节

STX（0x02）表示新帧开始，会清空未完整结束的旧缓冲。NUL 和其他 ASCII 控制字符直接过滤。

单条扫码内容最大 2048 字节。少于 4 个可打印字符的短帧按线路空闲、模块状态字节或串口毛刺处理：

- 不进入业务路由；
- 不参与重复扫码判断；
- 不打印连续 Info 日志；
- 仅每分钟输出一次 Debug 级统计。

因此模块未扫码时周期性输出单个可打印字节，不再出现持续的“忽略短时间重复上报，长度=1”。

## 3. 动态业务路由

设备端不得硬编码 `W`、`XD:1:PICKUP:`、美团、抖音、快手等前缀，也不得根据六位数字、长度或历史缓存猜测业务类型。

当前处理链：

```text
ttyS6完整扫码帧
→ 最近一次bootstrap.redemptionRouting
→ 精确匹配会员、内部套餐或第三方渠道前缀
→ 检查bootstrap.features中的visible和available
→ 调用新版SDK routed-code方法
→ 等待服务端业务结果
→ 等待MQTT dispense_marbles
```

调用的 SDK 方法：

```text
createMemberWithdrawalFromRoutedCode
createInternalRedemptionFromRoutedCode
createThirdPartyRedemptionFromRoutedCode
```

未匹配路由、同时匹配多个路由、前缀后没有业务码主体，或对应功能不可用时，直接拒绝提交。

手工输入六位平台取珠码仍可走原始 `createInternalRedemption`，但反扫二维码必须使用服务端路由配置。

## 4. 去重与隐私

相同完整二维码在 1.5 秒内只提交一次。去重只保存长度和不可逆摘要，不长期保存完整码值。

日志和后台状态只允许显示：

- 码类型；
- 字符长度；
- 末尾最多 4 位的脱敏结果；
- 请求号和业务状态。

不得记录完整会员取珠码、平台套餐码、第三方券码、支付链接或 operationToken。

bootstrap 调试日志必须使用 SDK 模型的安全 `toString()`，禁止用 Gson 反射序列化完整对象，以免绕过 SDK 脱敏。

## 5. 故障上报

以下情况使用统一 MQTT `report/fault` 上报：

```text
faultCode = SCANNER_ERROR
faultName = 扫码器异常
faultLevel = 2
```

触发场景包括：

- ttyS6 不存在或无法打开；
- 串口参数配置失败；
- 接收线程异常退出；
- 单帧超过 2048 字节。

普通空闲短帧不是扫码器故障，不上报 fault。

## 6. 真机验证

```sh
su 0 ls -l /dev/ttyS6
su 0 toybox stty -F /dev/ttyS6 -a
```

安装 App 后确认：

```text
反扫模块：已连接 /dev/ttyS6，9600 8N1
```

至少测试：

1. 不扫码静置 10 分钟，确认没有周期性长度 1 的 Info 日志；
2. 扫描 bootstrap 路由中的会员取珠码；
3. 扫描 bootstrap 路由中的平台套餐二维码；
4. 第三方入口不可用时扫描第三方码，确认不调用接口；
5. 连续扫描相同码，确认 1.5 秒内不重复提交；
6. 扫描未知前缀，确认不猜测业务类型；
7. 断开 ttyS6，确认后台显示异常并上报 `SCANNER_ERROR`；
8. 所有扫码请求均不直接发送 ttyS5 出珠命令，真实出珠只响应 MQTT `dispense_marbles`。
