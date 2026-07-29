# 购珠机控制板与安卓通信协议

当前协议版本：**1.3.0.0**。

适用工程：

- Android：`GouzhuApp`，包名 `com.gouzhu`
- Android 串口：`/dev/ttyS5`
- 控制板：`Control`
- MCU：APM32F407VGT6

## 1. 串口参数

| 参数 | 数值 |
|---|---|
| MCU 接口 | USART1，PA9=TXD1、PA10=RXD1 |
| Android 节点 | `/dev/ttyS5` |
| 波特率 | 115200 |
| 数据位 | 8 |
| 停止位 | 1 |
| 校验 | 无 |
| 硬件流控 | 无 |

## 2. 固定 14 字节帧

| 字节 | 字段 | 说明 |
|---:|---|---|
| 0 | Head | 固定 `0xAA` |
| 1 | ResendID | 首发 `0x00`，重发递增 |
| 2 | ID | 发送端消息 ID |
| 3 | Code1 | 方向码 |
| 4 | Code2 | 功能码 |
| 5 | Data1 | 32 位数据最高字节 |
| 6 | Data2 | 32 位数据次高字节 |
| 7 | Data3 | 32 位数据次低字节 |
| 8 | Data4 | 32 位数据最低字节 |
| 9 | ACKbyte | `0x00` 不要求确认，`0x01` 要求原样确认 |
| 10 | ExpandCode | 扩展状态 |
| 11 | CRC16_H | CRC16 高字节 |
| 12 | CRC16_L | CRC16 低字节 |
| 13 | Tail | 固定 `0x55` |

CRC16 计算范围为字节 0～10，初值 `0xFFFF`，反向多项式 `0xA001`。

## 3. 方向码

| Code1 | 方向 |
|---|---|
| `0x00` | 控制板 → Android |
| `0x01` | Android → 控制板 |

同一个 `Code2` 可以在不同方向表示不同含义，解析时必须同时判断 `Code1`。

## 4. 金额单位

本协议区分两类金额：

1. **现金事实金额**：使用整数人民币元。`1` 表示 1 元，`100` 表示 100 元。
2. **控制板本地单价和余额**：继续使用人民币分，避免 MCU 使用浮点数。`100` 表示 1 元。

现金事实帧使用以下 32 位打包：

```text
Data1       = 现金介质：0=硬币，1=纸币
Data2:Data4 = 24 位无符号整数金额，单位人民币元
```

示例：收到 5 元纸币时，Data1:Data4=`01 00 00 05`。

## 5. Android 发给控制板

| Code2 | 名称 | Data1:Data4 | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 忽略 | 查询固件版本 |
| `0x01` | BeadMotor1Output | Data3:Data4=数量 | 维护调试直接吐珠 |
| `0x02` | BeadMotor2Output | Data3:Data4=最大剩余数量 | 用户点击“开始存珠”后启动存珠电机 |
| `0x03` | BeadMotor2Stop | 忽略 | 用户点击“完成存珠”后仅停止存珠电机 |
| `0x10` | Unlock | 忽略 | 电子锁动作 |
| `0x19` | BillCurrencyModeSet | Data4=`0/1` | 人民币/外币模式 |
| `0x1A` | BillEnable | 忽略 | 启用纸钞机 |
| `0x1B` | BillDisable | 忽略 | 禁用纸钞机 |
| `0x1C` | BillReset | 忽略 | 复位纸钞机 |
| `0x1D` | CoinEnable | 忽略 | 启用投币器接收 |
| `0x1E` | CoinDisable | 忽略 | 禁用投币器接收 |
| `0x1F` | CashReturnRequest | 现金事实打包格式 | 请求真实退币；仅硬件动作成功才上报 returned |
| `0x20` | BeadPriceSet | 32 位单价，单位分 | 设置控制板本地现金单颗价格 |
| `0x21` | PurchaseStatusRequest | 忽略 | 查询价格、库存、欠吐和余额 |
| `0x27` | PaidPurchaseOutput | 32 位吐珠数量 | 平台 MQTT `dispense_marbles` 转发；现金购珠不使用 |
| `0xF0` | BoardRestart | `0x424F5441` 进入 Bootloader | 重启控制板 |
| `0xFF` | StopAllDevice | 忽略 | 立即停止全部执行机构 |

控制板收到合法 Android 帧后先原样回传，再执行业务。相同消息 ID 在 5 秒内只执行一次。

## 6. 控制板发给 Android

| Code2 | 名称 | Data | ACK | 说明 |
|---|---|---|---|---|
| `0x00` | VersionRequest | `0xMMNNPPBB` | 是 | 当前为 `1.3.0.0` |
| `0x01` | BeadMotor1Feedback | `1` | 否 | PD3 确认吐出一颗 |
| `0x02` | CoinInput | `1` | 否 | 保留的 1 元硬币脉冲事件 |
| `0x03` | BeadMotor2Feedback | `1` | 否 | PD4 确认存入一颗 |
| `0x04` | BillAccepted | ICT 类型和状态 | 是 | 原始纸钞诊断事件 |
| `0x05` | RemainingBead | 两路电机剩余数量 | 否 | Data1:2=吐珠，Data3:4=存珠 |
| `0x07` | BeadMotor1Timeout | 剩余数量 | 是 | 吐珠超时 |
| `0x08` | BeadMotor2Timeout | 剩余数量 | 是 | 存珠超时 |
| `0x0D` | AlreadyUnlock | `0` | 否 | 已执行开锁 |
| `0x0F` | Encoder | `1/2/3` | 否 | CCW/CW/DOWN |
| `0x12` | BillStatus | ICT 状态 | 否 | 纸钞机状态或错误 |
| `0x13` | BillCurrencyModeStatus | Data4=模式 | 否 | 当前币种模式 |
| `0x20` | BeadPriceStatus | 单价，单位分 | 否 | 本地现金单价 |
| `0x21` | BeadStockStatus | 当前库存 | 否 | 库存状态 |
| `0x22` | PurchasePendingStatus | 欠吐数量 | 否 | 本地现金或平台任务尚未完成的数量 |
| `0x23` | PurchaseCreditStatus | 余额，单位分 | 否 | 本地现金余额 |
| `0x24` | BeadLowStock | 当前库存 | 是 | 低库存 |
| `0x25` | BeadEmpty | 欠吐数量 | 是 | 无珠 |
| `0x26` | BeadRefilled | 新库存 | 是 | K1 补珠确认 |
| `0x27` | BackendSettingsRequest | `1` | 是 | K2 请求进入后台 |
| `0x28` | CashAcceptedAmount | 现金事实打包格式 | 是 | 现金不可逆接收后，上报介质和整数元金额 |
| `0x29` | CashReturnedAmount | 现金事实打包格式 | 是 | 真实退币机构动作成功后上报 |
| `0x2A` | CashReturnFailed | 现金事实打包格式 | 是 | 真实退币动作失败，ExpandCode=`1` |

## 7. 现金购珠流程

现金购珠完全由控制板负责，不等待服务器授权：

```text
纸币或硬币有效接收
→ 控制板按本地单价累计金额并计算数量
→ 控制板保存欠吐状态
→ 控制板驱动吐珠并按 PD3 扣减
→ 控制板另外发送 0x28 金额事实
→ Android 将现金事实通过 MQTT 上报服务器
```

Android 或服务器不得根据 `0x28` 再次发送一遍现金吐珠命令，否则会重复吐珠。

现金接收开关由 Android 通过 `BillEnable/BillDisable` 和 `CoinEnable/CoinDisable` 同步。投币器 inhibit 引脚及真实退币机构的具体驱动必须由量产硬件适配代码实现；当前公共任务代码不会猜测未确认引脚。

## 8. 平台固定数量出珠

```text
MQTT收到 dispense_marbles
→ Android持久化messageId和操作数据
→ Android上报ack
→ Android发送0x27数量
→ 控制板执行并逐颗上报0x01
→ Android生成真实actualQuantity终态
→ 收到平台command_result_ack.recorded后删除待补发回执
```

支付页面、二维码字符串、支付结果和核销响应本身均不能直接发 `0x27`。

## 9. 会员存珠流程

```text
MQTT收到 collect_marbles
→ Android持久化任务并上报ack
→ 页面提示用户先倒珠
→ 用户点击“开始存珠”
→ Android发送0x02，控制板启动存珠电机
→ PD4每确认一颗，控制板发送0x03
→ Android每颗更新并持久化actualQuantity
→ 用户点击“完成存珠”或达到上限
→ Android发送0x03停止电机
→ Android上报唯一终态
```

应用重启后从本地任务记录恢复页面和已确认数量，不自动重复启动存珠电机；必须由用户重新确认现场后继续。

## 10. 硬件适配边界

`KeyTask.c` 提供两个弱函数：

```c
bool CashHardware_SetCoinEnable(bool enable);
bool CashHardware_DoReturn(uint8_t medium, uint32_t amount_yuan);
```

量产工程必须根据最终原理图和退币机构协议提供同名强实现。默认退币弱实现返回失败，避免误驱动未确认 GPIO。

## 11. 联调重点

1. 确认 `/dev/ttyS5` 为 115200、8N1。
2. 现金 1/5/10/20/50/100 元上报金额必须为整数元。
3. `0x28` 仅上报现金事实，不能触发 Android 二次吐珠。
4. 禁用现金时纸钞机和投币器均不得继续收现。
5. 真实退币成功才允许发送 `0x29`。
6. 存珠必须由用户点击按钮后启动，PD4 每个有效脉冲只计一颗。
7. App 重启后能恢复未完成存珠任务和已确认数量，但不得自动再次启动电机。
8. 相同 MQTT `messageId` 不得产生第二次物理动作。
9. 所有要求 ACK 的串口帧必须原样确认。
