# 购珠机控制板与安卓通信协议

本文档依据 `购珠机控制板.pdf`、`ICT104U Protocol.pdf`、`ICT106U Protocol V0.2 .pdf` 和当前 `Control`、`GouzhuApp` 工程代码整理。

当前协议版本：**1.2.0.0**。

## 1. 串口参数

- 接口：USART1，原理图标注“串口1接安卓板”
- MCU 引脚：PA9 = TXD1，PA10 = RXD1
- 安卓设备节点：`/dev/ttyS5`
- 波特率：115200
- 数据位：8
- 停止位：1
- 校验：无
- 硬件流控：无

## 2. 帧格式

固定 14 字节：

| 字节 | 字段 | 说明 |
|---|---|---|
| 0 | Head | 固定 `0xAA` |
| 1 | ResendID | 重发次数，首发为 `0x00` |
| 2 | ID | 消息 ID，发送端自增 |
| 3 | Code1 | 方向码 |
| 4 | Code2 | 功能码 |
| 5 | Data1 | 数据最高字节 |
| 6 | Data2 | 数据次高字节 |
| 7 | Data3 | 数据次低字节 |
| 8 | Data4 | 数据最低字节 |
| 9 | ACKbyte | `0x00` 不要求重发确认，`0x01` 要求确认 |
| 10 | ExpandCode | 扩展码 |
| 11 | CRC16_H | CRC16 高字节 |
| 12 | CRC16_L | CRC16 低字节 |
| 13 | Tail | 固定 `0x55` |

CRC16 计算范围为字节 0 到字节 10，初值 `0xFFFF`，反向多项式 `0xA001`。

## 3. 方向码

| Code1 | 方向 |
|---|---|
| `0x00` | 主板 → 安卓 |
| `0x01` | 安卓 → 主板 |

同一 `Code2` 可以在两个方向表示不同含义，必须结合 `Code1` 判断。

## 4. 硬件定义

| 名称 | 引脚 | 用途 |
|---|---|---|
| USART1 | PA9 / PA10 | 安卓板通信 |
| BeadMotor1 | PE9 / PE11 | 吐珠 |
| BeadMotor1 光眼 | PD3 / EXTI3 | 确认成功吐出一颗 |
| BeadMotor2 | PE13 / PE14 | 存珠 |
| BeadMotor2 光眼 | PD4 / EXTI4 | 存珠反馈 |
| CoinInput | PE15 | 投币器脉冲 |
| K1 | KeyBoard3 / PD12 | 补珠确认 |
| K2 | SettingButton / PD11 | 请求进入安卓后台设置 |

K2 为低电平按下，固件执行 15 ms 消抖；一次短按只上报一次后台设置请求。

## 5. 金额单位

控制板与安卓之间的价格、余额统一使用**人民币分**：

- `1` 表示 `0.01` 元；
- `100` 表示 `1.00` 元；
- `10000` 表示 `100.00` 元。

禁止发送浮点数、IEEE 754 数据或十进制字符串。

## 6. 安卓发给主板

| Code2 | 名称 | Data1:Data4 | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 忽略 | 请求固件版本 |
| `0x01` | BeadMotor1Output | Data3:Data4=数量 | 维护/调试直接吐珠 |
| `0x02` | BeadMotor2Output | Data3:Data4=数量 | 启动存珠电机 |
| `0x10` | Unlock | 忽略 | 电子锁吸合约 800 ms |
| `0x19` | BillCurrencyModeSet | Data4=`0x00/0x01` | 人民币/外币模式 |
| `0x1A` | BillEnable | 忽略 | 启用 ICT 纸钞机 |
| `0x1B` | BillDisable | 忽略 | 禁用 ICT 纸钞机 |
| `0x1C` | BillReset | 忽略 | 复位 ICT 纸钞机 |
| `0x20` | BeadPriceSet | 32 位价格，单位分 | 设置单颗价格，范围 `1～10000` |
| `0x21` | PurchaseStatusRequest | 忽略 | 请求价格、库存、欠吐和余额 |
| `0x27` | PaidPurchaseOutput | 32 位吐珠数量 | 仅服务器确认支付成功后使用 |
| `0xF0` | BoardRestart | `0x424F5441` 时进 Bootloader | ASCII `BOTA` |
| `0xFF` | StopAllDevice | 忽略 | 立即停止设备，欠吐数量保留 |

收到合法的安卓帧后，控制板先原样回传，再执行业务逻辑。相同消息 ID 在 5 秒内只执行一次。

### 6.1 PaidPurchaseOutput

`PaidPurchaseOutput` 是扫码支付的正式吐珠命令：

1. 服务器确认订单支付成功，并下发实际吐珠数量；
2. 安卓校验订单号和数量，避免重复处理；
3. 安卓发送 `Code1=0x01`、`Code2=0x27`；
4. `Data1:Data4` 为吐珠数量，大端无符号整数；
5. 控制板把数量累加到 `PendingBeads` 并请求 Flash 保存；
6. 控制板复用现有欠吐、无珠、补珠恢复和掉电恢复流程。

即使当前无珠，已付款数量也必须保存在 `PendingBeads` 中，补珠后继续兑现。

## 7. 主板发给安卓

| Code2 | 名称 | Data | ACK | 说明 |
|---|---|---|---|---|
| `0x00` | VersionRequest | `0xMMNNPPBB` | 需要 | 当前版本 `1.2.0.0` |
| `0x01` | BeadMotor1Feedback | `1` | 不需要 | 成功吐出一颗 |
| `0x02` | CoinInput | `1` | 不需要 | 一个 1 元硬币脉冲 |
| `0x03` | BeadMotor2Feedback | `1` | 不需要 | 存珠反馈一次 |
| `0x04` | BillAccepted | 币种/类型/序号/状态 | 需要 | 纸币入钞完成 |
| `0x05` | RemainingBead | 两路电机剩余数量 | 不需要 | 当前排队数 |
| `0x07` | BeadMotor1Timeout | 剩余数量 | 需要 | 吐珠清障后仍超时 |
| `0x08` | BeadMotor2Timeout | 剩余数量 | 需要 | 存珠电机超时 |
| `0x0D` | AlreadyUnlock | `0` | 不需要 | 已执行开锁 |
| `0x0F` | Encoder | `1/2/3` | 不需要 | CCW/CW/DOWN；DOWN 同时为 K1 |
| `0x12` | BillStatus | 纸钞机状态 | 不需要 | ICT 状态或错误 |
| `0x13` | BillCurrencyModeStatus | Data4=模式 | 不需要 | 当前币种模式 |
| `0x20` | BeadPriceStatus | 当前价格（分） | 不需要 | ExpandCode 为设置结果 |
| `0x21` | BeadStockStatus | 当前库存 | 不需要 | 每吐一颗后同步 |
| `0x22` | PurchasePendingStatus | 欠吐数量 | 不需要 | 已付款未完成数量 |
| `0x23` | PurchaseCreditStatus | 余额（分） | 不需要 | 不足一颗的余额 |
| `0x24` | BeadLowStock | 当前库存 | 需要 | 首次降到 3000 或以下 |
| `0x25` | BeadEmpty | 欠吐数量 | 需要 | 无珠并禁用纸钞机 |
| `0x26` | BeadRefilled | 新库存 | 需要 | K1 补珠确认 |
| `0x27` | BackendSettingsRequest | `1` | 需要 | K2 短按，请求进入后台设置 |

### 7.1 BackendSettingsRequest

控制板检测到 K2（`SettingButton/PD11`）短按后发送：

```text
Code1       = 0x00
Code2       = 0x27
Data1:Data4 = 0x00000001
ACKbyte     = 0x01
ExpandCode  = 0x00
```

安卓收到并原样确认后打开 `BackendSettingsActivity`。顾客主界面不显示设备状态。

## 8. 现金购买流程

1. 安卓通过 `BeadPriceSet` 设置单颗价格；
2. 硬币每个有效脉冲累计 100 分；
3. 人民币纸币 `0x40～0x45` 分别累计 1、5、10、20、50、100 元；
4. 控制板按累计金额除以单颗价格计算新增欠吐数量；
5. 控制板直接执行现金吐珠，安卓不得再次发送吐珠命令；
6. 每次 PD3 确认后扣减库存和欠吐数量。

## 9. 扫码支付预留流程

当前服务器接口尚未确定，App 仅固定以下内部接口：

1. 用户选择套餐并按“立即支付”；
2. App 生成支付请求 JSON，等待正式 HTTPS/MQTT 接口接入；
3. 服务器下发 `orderId` 和二维码字符串；
4. App 使用二维码字符串生成付款二维码；
5. 扫码模块解析到字符串后通过 `ScannerBridge.onQrDecoded()` 交给 App；
6. 服务器下发支付结果和吐珠数量；
7. App 对同一 `orderId` 去重后发送 `PaidPurchaseOutput`；
8. 控制板保存欠吐数量并执行吐珠。

预留服务器消息格式：

```json
{"type":"paymentQr","orderId":"订单号","qrContent":"付款二维码原始字符串"}
```

```json
{"type":"paymentResult","orderId":"订单号","success":true,"beadCount":10,"message":"支付成功"}
```

这些 JSON 仅作为当前 App 内部适配边界，最终 Topic、URL、签名和字段必须以后端正式接口文档为准。

## 10. 无珠和补珠恢复

1. 正转 3 秒无 PD3；
2. 反转 300 ms 清障；
3. 再次正转；
4. 再次 3 秒无反馈，判定无珠；
5. 保留 `PendingBeads`，库存校准为 0；
6. 禁用纸钞机；
7. K1 补珠后库存恢复为 10000；
8. 等待 10 秒关门，再继续欠吐任务；
9. 掉电后从 Flash 恢复欠吐和无珠状态。

## 11. 联调要求

- K2 必须确认对应 `SettingButton/PD11`；
- 首页不得显示网络、MQTT、串口、库存和版本等设备信息；
- 支付结果重复下发不得重复吐珠；
- `0x27 PaidPurchaseOutput` 必须保存到 Flash；
- 无珠时支付成功数量必须保留，补珠后继续；
- App 与控制板均需验证 CRC、ACK、5 秒去重和掉电恢复；
- 正式服务器接口接入前，不得把预留 JSON 当作已确认的生产协议。
