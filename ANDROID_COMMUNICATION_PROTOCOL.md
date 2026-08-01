# 购珠机控制板与 Android 通信协议 V2

当前控制板协议/固件版本：**2.0.0.0**。

本版本是断代升级，不兼容 1.x。控制板已删除本地现金计价、余额累计、欠吐队列、现金自动出珠和退币兼容命令。所有出珠，包括现金购珠、扫码购珠、会员取珠和内部核销，均只能由平台下发的 `dispense_marbles.quantity` 授权。

适用工程：

- Android：`GouzhuApp`，包名 `com.gouzhu`
- Android 控制板串口：`/dev/ttyS5`
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
| 9 | ACKbyte | `0x00` 不要求线路确认；`0x01` 要求原样确认 |
| 10 | ExpandCode | 结果码或扩展字节 |
| 11 | CRC16_H | CRC16 高字节 |
| 12 | CRC16_L | CRC16 低字节 |
| 13 | Tail | 固定 `0x55` |

CRC16 计算字节 0～10，初值 `0xFFFF`，反向多项式 `0xA001`。

方向码：

| Code1 | 方向 |
|---|---|
| `0x00` | 控制板 → Android |
| `0x01` | Android → 控制板 |

## 3. 两级确认

V2 明确区分两种确认：

1. **线路原样 ACK**：只证明 UART 收到帧，不代表 Android 已持久化，也不代表平台已确认。
2. **业务持久化确认**：Android 写入 SQLite 成功后，另发 `CashEventStored` 或 `BoardEventStored`。

控制板对现金事实、硬件启动和硬件终态持续重发，直到收到业务持久化确认。普通状态帧仍可在原样 ACK 或重试次数达到上限后停止。

## 4. 通用操作数据

出珠和存珠操作使用：

```text
Data1       = boardToken，1..255
Data2:Data4 = 24 位数量或真实累计数量
ExpandCode  = 硬件结果码
```

`boardToken` 只是 Android 与控制板之间关联当前操作的短令牌；平台幂等仍以完整 MQTT `messageId`、`operationNo` 和 `operationToken` 为准。

硬件结果码：

| 值 | 名称 | 含义 |
|---:|---|---|
| `0x00` | OK | 完成 |
| `0x01` | BUSY | 控制板存在其他物理动作 |
| `0x02` | NO_BEAD | 无珠 |
| `0x03` | INVALID_QUANTITY | 数量无效 |
| `0x04` | SENSOR_TIMEOUT | 光眼超时，可能部分完成 |
| `0x05` | ABORTED | 紧急停止 |
| `0x06` | NOT_ACTIVE | 当前操作不存在或 token 不匹配 |

## 5. Android → 控制板

| Code2 | 名称 | Data1:Data4 | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 忽略 | 查询固件版本 |
| `0x01` | DispenseStart | Data1=token；Data2:4=平台授权数量 | 启动出珠；不得由现金金额、本地价格或支付页面直接调用 |
| `0x02` | CollectStart | Data1=token；Data2:4=最大数量 | 用户确认现场后启动存珠 |
| `0x03` | CollectStop | Data1=token | 停止当前存珠并形成真实终态 |
| `0x10` | Unlock | 忽略 | 电子锁动作 |
| `0x18` | CashAcceptanceApply | Data1=启用掩码；Data2:4=configVersion | 应用已经完整持久化并验证成功的现金配置 |
| `0x19` | BillReset | 忽略 | 复位纸钞机 |
| `0x1A` | CashEventStored | Data3:4=16 位现金序号 | Android 已把现金事实和原始 payload 写入 SQLite/outbox |
| `0x1B` | BoardEventStored | Data1=原事件 Code2；Data2=token | Android 已持久化关键硬件启动/终态 |
| `0x20` | HardwareStatusRequest | 忽略 | 查询库存、现金接收状态和当前操作状态 |
| `0xF0` | BoardRestart | `0x424F5441` 表示进入 Bootloader | 重启控制板 |
| `0xFF` | EmergencyStop | 忽略 | 立即停止物理动作并关闭现金接收 |

现金启用掩码：

```text
bit0 = 纸币接收
bit1 = 硬币接收
```

上电固定为 `0`。只有平台 `sync_cash_configuration` 完整校验、SQLite 持久化和控制板应用均成功后，Android 才能下发非零掩码。

## 6. 控制板 → Android

| Code2 | 名称 | Data/Expand | 持续重发条件 | 说明 |
|---|---|---|---|---|
| `0x00` | VersionReport | `0x02000000` | 普通确认 | 当前协议版本 2.0.0.0 |
| `0x01` | DispenseStarted | token + 目标数量 | 收到 `BoardEventStored` | 电机已经启动 |
| `0x02` | DispenseProgress | token + actualQuantity | 否 | PD3 每个有效光眼脉冲后的累计真实数量 |
| `0x03` | DispenseCompleted | token + actualQuantity；Expand=`0` | 收到 `BoardEventStored` | 全量完成 |
| `0x04` | DispenseFailed | token + actualQuantity；Expand=结果码 | 收到 `BoardEventStored` | 部分、零出珠或失败，数量必须是真实值 |
| `0x05` | CollectStarted | token + 最大数量 | 收到 `BoardEventStored` | 存珠电机已经启动 |
| `0x06` | CollectProgress | token + actualQuantity | 否 | PD4 累计真实数量 |
| `0x07` | CollectCompleted | token + actualQuantity | 收到 `BoardEventStored` | 正常停止或达到上限 |
| `0x08` | CollectFailed | token + actualQuantity；Expand=结果码 | 收到 `BoardEventStored` | 存珠失败或部分完成 |
| `0x0D` | AlreadyUnlock | 0 | 普通确认 | 已执行开锁 |
| `0x10` | CashAccepted | 见下节 | 收到 `CashEventStored` | 一笔现金已经不可逆进入钱箱 |
| `0x11` | CashAcceptanceStatus | Data1=实际掩码；Data2:4=configVersion | 普通确认 | 控制板应用现金配置结果 |
| `0x12` | CashDeviceStatus | 纸钞类型、状态及实际启用位 | 否 | 仅诊断，不产生订单 |
| `0x20` | BeadStockStatus | 当前库存估计值 | 否 | 由真实 PD3 光眼和 K1 补珠维护 |
| `0x21` | BeadLowStock | 当前库存 | 普通确认 | 低库存告警 |
| `0x22` | BeadEmpty | 当前库存 | 普通确认 | 无珠并关闭现金接收 |
| `0x23` | BeadRefilled | 新库存 | 普通确认 | K1 补珠，只恢复库存，不自动启用现金 |
| `0x27` | BackendSettingsRequest | 1 | 普通确认 | K2（PD13）进入后台设置 |

## 7. 现金事实编码

`CashAccepted` 使用：

```text
Data1       = 现金介质：0=coin，1=banknote
Data2:Data3 = 面额，单位分，16 位无符号整数
Data4       = 现金序号高 8 位
ExpandCode  = 现金序号低 8 位
```

示例：5 元纸币、序号 `0x1234`：

```text
Data1:Data4 = 01 01 F4 12
ExpandCode  = 34
```

同一笔不可逆现金在控制板 Flash 中永久复用同一个介质、面额和序号，直到 Android 先把事件和稳定 `eventNo` 写入 SQLite/outbox，再发送 `0x1A` 确认。普通原样 ACK 不会删除这笔现金事实。

控制板一次只允许一笔未确认现金事实；现金进入钱箱后立即同时关闭纸币和硬币接收。

## 8. 现金与平台完整时序

```text
平台下发 sync_cash_configuration
→ App 用新版 SDK 校验命令
→ App 完整校验现金档位并写入 SQLite
→ App 写入配置 ACK outbox
→ App 发送 0x18 应用配置
→ 控制板回 0x11，成功后才实际开启验钞器
→ 现金不可逆进入钱箱
→ 控制板先写 Flash，再持续发送 0x10 CashAccepted
→ App 按配置快照生成稳定 eventNo 和完全相同 payload
→ App 在同一数据库事务写 cash_events + cash outbox
→ App 发送 0x1A，控制板才删除 pending cash
→ App 发布 report/cash-event
→ 平台返回 cash_event_response
→ App 只更新事件状态，不按 requestedQuantity 出珠
→ 平台另发通过 SDK 校验的 dispense_marbles
→ App 持久化完整命令和“可能启动硬件”状态
→ App 持久化/发布 ACK
→ App 发送 0x01 平台授权数量
→ 控制板回启动、进度和真实终态
→ App 持久化终态并发送 0x1B
→ App 发布真实 actualQuantity
→ 平台 command_result_ack.recorded
→ App 删除对应 command-result outbox
```

任何 `cash_event_response` 状态都不能直接启动电机。`unknown` 使用原 `eventNo` 和原 payload 重发；`manual_review`、`rejected` 和物理结果未知均关闭现金接收。

## 9. 幂等与掉电恢复

- MQTT 相同 `messageId`：只重发已保存 ACK/终态，绝不能再次发 `DispenseStart`。
- Android 在发送 `DispenseStart` 前，先持久化“可能已启动”状态。
- Android 进程在启动请求后崩溃：恢复时禁止自动重启电机，关闭现金并转人工核实。
- 控制板关键启动/终态在收到 `BoardEventStored` 前持续重发。
- MQTT PUBACK 只证明 Broker 收到，不能删除业务 outbox。
- 只有平台 `command_result_ack.recorded` 才删除命令结果 outbox。
- 现金事件只有非 `unknown` 平台响应才移除现金 outbox。
- 旧 1.x Flash 记录不迁移，升级到 2.0.0.0 时擦除本地价格、余额、欠吐和旧订单状态。

## 10. 已删除的 1.x 能力

V2 不再定义或接受：

- 本地 `BeadPriceSet`；
- 本地现金余额和累计支付；
- 本地现金按价格换算珠数；
- `PaidPurchaseOutput` 与现金专用出珠的区分；
- 本地欠吐队列自动恢复出珠；
- 单独 Bill/Coin Enable/Disable 兼容命令；
- CashReturnRequest 以及旧退币兼容路径；
- 旧 `0x25/0x26` 等事件编号。

Android 2.0.0 必须与控制板 2.0.0.0 成套升级，禁止新旧版本混用。

## 11. 联调验收

至少验证：

1. 上电现金默认关闭；
2. 配置禁用、非法、找零开启或控制板未确认时现金保持关闭；
3. 每个面额只上报一次稳定现金事实，不本地出珠；
4. `cash_event_response.requestedQuantity` 不启动电机；
5. 只有 `dispense_marbles.quantity` 能启动出珠；
6. 相同 MQTT `messageId` 只启动一次电机；
7. 全量、部分、零出珠均上报真实 `actualQuantity`；
8. Android 在持久化前断电时，控制板重发同一现金事实；
9. Android 在启动硬件后崩溃时不会自动重启电机；
10. 控制板启动/终态在收到 `0x1B` 前持续重发；
11. MQTT 重连后先订阅，再重发 SQLite outbox；
12. 旧 App 或旧控制板与 V2 混用时应拒绝投入生产。
