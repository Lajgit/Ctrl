# 反扫模块 ttyS6 接入说明

## 串口配置

```text
设备节点：/dev/ttyS6
默认波特率：9600
数据位：8
停止位：1
校验：无
硬件流控：关闭
```

对应配置位于：

```text
GouzhuApp/app/src/main/java/com/gouzhu/AppConfig.java
```

若实际反扫模组不是 9600，只修改：

```java
AppConfig.REVERSE_SCANNER_BAUD_RATE
```

## 代码入口

反扫串口由以下类独占管理：

```text
GouzhuApp/app/src/main/java/com/gouzhu/scanner/ReverseScannerManager.java
```

`DeviceService` 启动时打开 ttyS6，服务停止时关闭。ttyS6 与控制板 ttyS5 使用完全独立的文件描述符和读线程，不共享协议缓冲区。

## 分帧规则

以下任一条件表示一条扫码内容结束：

- CR（0x0D）
- LF（0x0A）
- ETX（0x03）
- 连续约 180 ms 没有收到新字节

单条扫码内容最大 2048 字节。相同内容在 1.5 秒内重复上报时只处理一次。

## 当前业务路由

当前服务端 SDK 已有的扫码业务按以下规则路由：

```text
6位纯数字      → 内部套餐取珠码
W开头+数字     → 会员取珠码
其他格式       → 只显示“不支持该格式”，不提交业务，不驱动电机
```

反扫读取成功后只调用服务端 SDK 创建业务请求。真实出珠仍必须等待 MQTT `dispense_marbles` 指令，反扫串口不能直接向控制板发送出珠命令。

## 隐私与日志

后台设置只显示扫码内容长度和末尾最多 4 位，不显示完整会员码或取珠码。

## 后台检查

后台设置页新增“反扫模块”状态，可查看：

- ttyS6 是否成功打开
- 当前波特率
- 读取异常
- 扫码格式是否被支持
- 扫码是否已提交服务端

## 真机验证

```sh
su 0 ls -l /dev/ttyS6
su 0 toybox stty -F /dev/ttyS6 -a
```

安装 App 后进入后台设置，确认显示：

```text
反扫模块：已连接 /dev/ttyS6，9600 8N1
```

然后分别测试：

1. 扫描 6 位数字取珠码；
2. 扫描 W 开头会员取珠码；
3. 扫描普通 URL，确认只提示格式不支持；
4. 连续扫描相同内容，确认 1.5 秒内不会重复提交；
5. 断开 ttyS6，确认后台显示读取异常或未连接。
