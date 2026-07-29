# GouzhuApp

RK3566 Android 13 售珠机单应用工程，最终包名 `com.gouzhu`。

## 当前架构

- 只安装 `com.gouzhu`，不依赖或守护 `com.zeda.ota`；
- `/dev/ttyS5`、115200、8N1 与控制板通信；
- Android Keystore 生成单设备 ECDSA P-256 身份密钥；
- 支持身份签名报到、首次激活、MQTT HMAC 后续激活和授权凭证恢复；
- Broker、订阅 Topic 和上报 Topic 使用激活接口返回值；
- MQTT 心跳、状态、故障、指令回执、现金事件、扫码核销和升级；
- App 自升级和控制板 bin 升级；
- K2 进入后台设置。

## 业务边界

### 扫码和平台业务出珠

支付结果、付款二维码和核销响应不直接驱动控制板。只有合法 MQTT：

```text
commandType=dispense_marbles
```

才会在持久化并上报 ACK 后，经串口 `Code2=0x27` 下发数量。终态数量来自 PD3 光眼。

### 现金购珠

现金购珠由控制板独立计价、保存欠吐并吐珠。控制板使用 `Code2=0x28` 上报已收现金：

```text
Data1       = 0硬币 / 1纸币
Data2:Data4 = 整数人民币元
```

Android 只将现金事实保存并上报 `report/cash-event`，不得再次驱动现金吐珠。

### 会员存珠

平台下发 `collect_marbles` 后，页面先提示用户倒珠。用户点击“开始存珠”才发送 `Code2=0x02`，PD4 每颗计数并写入本地持久化；用户点击“完成存珠”发送 `Code2=0x03` 停止。App 重启后恢复任务和计数，但不自动重启电机。

## 硬件适配

控制板公共代码提供弱函数：

```c
bool CashHardware_SetCoinEnable(bool enable);
bool CashHardware_DoReturn(uint8_t medium, uint32_t amount_yuan);
```

量产前必须根据最终投币器 inhibit 引脚和真实退币机构协议提供强实现。默认退币实现安全失败，不会猜测或误驱动未知 GPIO。

## 构建

```powershell
cd GouzhuApp
.\gradlew.bat clean
.\gradlew.bat :app:assembleDebug
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.gouzhu/.MainActivity
```

## 联调资料

- `../售珠机设备端统一对接文档_20260729.md`
- `../ANDROID_COMMUNICATION_PROTOCOL.md`
- `../购珠机控制板_安卓通信协议表_V1.1.1.xlsx`
