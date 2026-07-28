# GouzhuApp

购珠机 RK3566 Android 13 单应用工程，包名为 `com.gouzhu`。

## 当前功能

- 固定竖屏购珠首页和设备状态界面；
- `/dev/ttyS5`、115200、8N1 控制板通信；
- 14 字节购珠机协议解析、CRC16 校验和 ACK；
- 已保存 WiFi 的自动连接及屏幕 WiFi 配置页；
- 设备注册、自动认领和激活；
- MQTT 自动连接、自动重连、订阅、心跳和升级进度上报；
- `type=ota` 的 GouzhuApp 自升级；
- `type=ball` 的控制板 bin 下载、MD5 校验和串口 Bootloader 升级；
- 开机及应用自升级完成后的服务恢复。

## 暂未实现

- 扫码支付、订单查询和支付回调；
- 会员取珠、会员存珠、团购核销和积分商城；
- 正式运营后台业务配置。

因此首页套餐按钮当前只记录选择，不会发送吐珠命令，避免在支付接口未接入时误吐珠。

## 构建

在 `GouzhuApp` 目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.gouzhu/.MainActivity
```

## RK3566 系统配置要求

### ttyS5

量产镜像应在 `ueventd` 和 SELinux 中允许 `com.gouzhu` 访问 `/dev/ttyS5`。应用会尝试通过 `stty` 配置 115200、8N1；调试镜像存在 `su` 时，也会尝试临时修改串口权限。

检查命令：

```powershell
adb shell "ls -lZ /dev/ttyS5"
adb shell "toybox stty -F /dev/ttyS5 -a"
```

### WiFi

Android 13 普通第三方应用不能直接保存和连接任意 WiFi。本工程沿用 OTA_XLH3566 在 RK3566 系统镜像中的系统权限方案，量产 APK应作为系统或特权应用预装，并授予 Manifest 中声明的系统 WiFi 权限。

### 自升级

当前通过以下命令静默覆盖安装：

```text
su 0 sh -c "pm install -r --user 0 <apk>"
```

量产要求：

- 系统允许该命令执行，或后续改为系统静默安装接口；
- 所有正式 APK 使用同一签名证书；
- 新 APK 的 `versionCode` 必须高于已安装版本。

## MQTT 升级类型

只接受：

```text
ota  - GouzhuApp 自升级
ball - 控制板 bin 升级
```

原 OTA_XLH3566 的 `game` 升级分支未移植。

## 控制板升级流程

```text
下载 bin → MD5 校验 → 普通协议发送 BOTA → HELLO → BEGIN
→ 1024 字节 DATA 分包 → END 校验 → INSTALL → 控制板重启
→ VersionRequest 确认新版本 → MQTT 上报成功
```

Bootloader 帧使用 `AA 5A` 帧头、CRC32、16 位小端序号和长度。

## 回归检查

1. 空白安装后进入 WiFi 配置页，连接正式 WiFi；
2. 检查注册激活及 MQTT 心跳；
3. 检查 `/dev/ttyS5` 能读取控制板版本、库存和现金事件；
4. 发送 `type=ota` 指令，确认下载、MD5、安装及新版本成功补报；
5. 发送 `type=ball` 指令，确认 BOTA、分包、CRC32、安装、重启和版本确认；
6. 在下载中断、串口断开、MD5 错误和 APK 签名不一致场景下检查失败上报。
