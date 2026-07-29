# GouzhuApp

购珠机 RK3566 Android 13 单应用工程，包名为 `com.gouzhu`。

## 当前功能

- 顾客首页隐藏网络、MQTT、串口、库存、设备号和版本等设备状态；
- 控制板 K2（SettingButton/PD11）请求进入后台设置；
- 顾客首页保留“后台设置（调试入口）”按钮，量产时可再隐藏；
- 套餐选择、支付按钮和付款二维码显示；
- 服务器支付消息、扫码模块结果的 App 内部接入接口；
- 服务器确认支付成功后，通过控制板 `Code2=0x27` 下发吐珠数量；
- `/dev/ttyS5`、115200、8N1 控制板通信；
- WiFi、设备注册激活、MQTT、App 自升级和控制板 bin 升级。

## 支付接口状态

正式服务器 URL、Topic、鉴权、签名和字段尚未提供，因此当前只预留代码边界，不伪造生产接口。

### 创建支付请求

用户选择套餐并按下“立即支付”后：

```java
PaymentManager.PaymentRequest request =
        PaymentManager.get(context).startPayment(beadCount, priceFen);
```

`request.requestJson` 是待发送给服务器的 JSON。

### 服务器返回付款二维码

网络层收到服务器字符串后调用：

```java
PaymentManager.get(context).handleServerQrString(orderId, qrContent);
```

App 使用 ZXing 在本机生成付款二维码。

### 扫码模块结果

扫码模块 SDK 或串口驱动得到字符串后调用：

```java
ScannerBridge.onQrDecoded(context, decodedText);
```

当前返回待上报 JSON；正式接口确定后在 `PaymentManager.submitScannerQrString()` 中上报。

### 服务器支付结果

```java
PaymentManager.get(context).handleServerPaymentResult(
        orderId,
        true,
        beadCount,
        "支付成功"
);
```

App 对 `orderId` 去重，再发送控制板正式命令：

```text
Code1 = 0x01
Code2 = 0x27
Data1:Data4 = 吐珠数量
```

控制板把数量计入掉电保存的 `PendingBeads`，复用现有欠吐、无珠和补珠恢复流程。

## 后台设置

正式设备短按 K2：

```text
K2 / SettingButton / PD11
→ 控制板上报 Code1=0x00、Code2=0x27
→ GouzhuApp 打开 BackendSettingsActivity
```

后台页显示：

- 网络状态；
- 激活/MQTT 状态；
- 控制板串口和固件版本；
- 库存、欠吐和最近事件；
- 设备号和 App 版本；
- WiFi 设置和状态刷新。

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

## 尚未验证

- RK3566 上 K2 实际电平与 PD11 映射；
- 后台页在 1024×1280 实屏上的完整布局；
- 正式服务器支付接口；
- 扫码模块型号和通信接口；
- 支付成功后 `0x27` 的串口联调、订单去重和掉电边界；
- App 自升级和控制板 bin 升级回归。
