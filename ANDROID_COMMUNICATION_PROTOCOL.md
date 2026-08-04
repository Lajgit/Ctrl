# 购珠机控制板与 Android 通信协议 V2.2

当前控制板物理出珠协议采用单订单状态机：同一时间只允许一笔 `DispenseOrder`，不再兼容 V2.1 的 8 位 token、Started、Completed/Failed 多事件和 `BoardEventStored` 物理确认。

## 串口帧

固定 14 字节：

| Byte | 字段 | 说明 |
|---:|---|---|
| 0 | Head | `0xAA` |
| 1 | ResendID | 首发 0，重发递增 |
| 2 | ID | frameId，线路 echo 和 TerminalAck 使用 |
| 3 | Code1 | `0x00` 控制板到 Android，`0x01` Android 到控制板 |
| 4 | Code2 | 功能码 |
| 5..8 | Data1..Data4 | 大端业务数据 |
| 9 | ACKbyte | `1` 表示接收方原帧 echo |
| 10 | ExpandCode | 结果码 |
| 11..12 | CRC16 | 覆盖 Byte0..10 |
| 13 | Tail | `0x55` |

## Android -> Board

| Code2 | 名称 | Data | ACK | 说明 |
|---:|---|---|---:|---|
| `0x00` | VersionRequest | 0 | 0 | 查询固件版本 |
| `0x10` | Unlock | 0 | 1 | 开锁 |
| `0x19` | BillReset | 0 | 1 | 复位纸钞机 |
| `0x1A` | CashEventStored | Data3:4=现金 sequence16 | 1 | Android 已持久化现金事实 |
| `0x20` | HardwareStatusRequest | 0 | 0 | 查询状态 |
| `0x30` | DispenseStartOrder | Data1:2=orderSequence16，Data3:4=requestedQuantity16 | 1 | 启动唯一出珠订单 |
| `0x31` | DispenseTerminalAck | Data1:2=orderSequence16，Data3=terminalFrameId，Data4=0 | 1 | Android 已持久化并处理 Terminal |
| `0x33` | CashAcceptanceApplyV22 | Data1=现金启用 mask，Data2:4=configVersion24 | 1 | 唯一允许启用现金的命令 |
| `0xF0` | BoardRestart | `0x424F5441` 进入 Bootloader | 1 | 重启 |
| `0xFF` | EmergencyStop | 0 | 1 | 停止电机并关闭现金 |

旧 `0x18 CashAcceptanceApply` 只能关闭现金，不能启用现金。旧 V2.1 物理命令不再作为平台出珠订单协议使用。

## Board -> Android

| Code2 | 名称 | Data | ACK | 说明 |
|---:|---|---|---:|---|
| `0x00` | VersionReport | `0x02020000` | 0 | 固件 2.2.0.0 |
| `0x10` | CashAccepted | 见现金事实编码 | 1 | 单笔现金事实 |
| `0x11` | CashAcceptanceStatus | Data1=实际 mask，Data2:4=configVersion24 | 0 | 现金配置状态 |
| `0x12` | CashDeviceStatus | 诊断 bitfield | 0 | 现金设备诊断 |
| `0x20` | BeadStockStatus | 当前库存 | 0 | 库存状态 |
| `0x21` | BeadLowStock | 当前库存 | 0 | 低库存 |
| `0x22` | BeadEmpty | 当前库存 | 0 | 无珠，现金保持关闭 |
| `0x23` | BeadRefilled | 当前库存 | 0 | 已补珠 |
| `0x27` | BackendSettingsRequest | 1 | 0 | K2 请求进入后台 |
| `0x40` | DispenseProgress | Data1:2=orderSequence16，Data3:4=actualQuantity16 | 0 | 非可靠进度，仅用于显示 |
| `0x41` | DispenseTerminal | Data1:2=orderSequence16，Data3:4=actualQuantity16，ExpandCode=resultCode | 1 | 唯一 durable 物理终态 |

## 出珠订单规则

控制板状态：`IDLE -> RUNNING -> WAIT_TERMINAL_ACK -> IDLE/BLOCKED`。

- 只有 `IDLE` 接受 `DispenseStartOrder`。
- `RUNNING`、`WAIT_TERMINAL_ACK`、`BLOCKED` 均拒绝新订单且不启动电机。
- 达到 requested 后发送 `DispenseTerminal(resultCode=0, actual=requested)`。
- 超时、无珠、故障发送 `DispenseTerminal(resultCode!=0, actual=真实 PD4 累计)`。
- `DispenseTerminal` 持续重发，直到收到匹配 `orderSequence + terminalFrameId` 的 `DispenseTerminalAck`。
- 成功终态确认后回 `IDLE`，不自动启用现金，等待 Android 重新应用 `0x33`。
- 零出珠、部分出珠、无珠或故障确认后进入 `BLOCKED`，现金保持关闭，等待补珠或人工复位。

Android 状态：`IDLE -> DISPENSING -> FINISHING -> IDLE/BLOCKED`。

- SQLite 只允许一条 `active_physical_order`。
- 分配 `orderSequence`、保存 command、创建 active order 必须在同一事务。
- App 重启后如果存在 active order，不自动重发 `DispenseStartOrder`。
- 收到 `0x40 Progress` 只更新动画和诊断，不参与成功判定。
- 收到 `0x41 Terminal` 后先保存原始证据和 command-result outbox，提交成功后才发送 `0x31 TerminalAck`。
- 只有 `TerminalAck` 获得线路 echo 后，成功订单才能清除 active order 并允许下一单。

成功判定唯一条件：

```text
controllerResultCode == 0
&& terminalActual > 0
&& terminalActual == requestedQuantity
```

其他情况全部失败。若 `terminalActual < lastProgressActual`，对外 `actualQuantity=max(lastProgressActual, terminalActual)`，本地诊断码为 `CONTROLLER_ACTUAL_REGRESSION`，结果仍为 failed。

## 结果码

| 值 | 名称 |
|---:|---|
| `0x00` | OK |
| `0x01` | BUSY |
| `0x02` | NO_MARBLES |
| `0x03` | INVALID_QUANTITY |
| `0x04` | SENSOR_TIMEOUT |
| `0x05` | ABORTED |
| `0x06` | NOT_ACTIVE |
| `0x07` | BLOCKED |
| `0x08` | ORDER_SEQUENCE_MISMATCH |

Android 本地诊断可使用：`CONTROLLER_ACTUAL_REGRESSION`、`PHYSICAL_TERMINAL_CONFLICT`、`ACTUAL_QUANTITY_MISMATCH`、`PREVIOUS_PHYSICAL_ORDER_ACTIVE`。

## 现金

现金事实仍使用 `CashAccepted` + `CashEventStored(sequence16)`。Android 必须先写入 SQLite/cash outbox，再确认控制板。

现金启用统一走 `0x33 CashAcceptanceApplyV22`。满足以下条件才允许启用：

- `active_physical_order` 不存在；
- 本地没有 physical blocked；
- 库存和控制板状态允许；
- 当前现金配置有效。

订单创建后立即关闭现金。`DISPENSING`、`FINISHING`、`BLOCKED`、TerminalAck 未 echo 时均保持现金关闭。

## 光眼映射

- PD4 / EXTI4 -> 出珠光眼 -> `HoolleOutput_Pin` -> `Hardware_OnDispensePulse`
- PD3 / EXTI3 -> 存珠光眼 -> `CardFeedback_Pin` -> `Hardware_OnCollectPulse`

存珠保留为本地维护功能，不再作为平台 active physical order。

## V2.2 安全补充

- Android 必须先确认 `VersionReport >= 0x02020000`（2.2.0.0）后，才能启用现金或发送 `DispenseStartOrder`。
- 版本未知或版本过低时，Android 只能使用旧 `0x18 CashAcceptanceApply(mask=0)` 关闭现金；不得发送现金启用请求，不得创建 `active_physical_order`，不得发送 `0x30`。
- 版本确认后，现金启用和关闭统一使用 `0x33 CashAcceptanceApplyV22`。
- `active_physical_order` 必须保存原始 MQTT source topic，用于 App 重启后恢复 SDK command 上下文；历史数据缺少 source topic 时进入 `BLOCKED/MANUAL_REVIEW`，不发送 TerminalAck。
- 如果控制板永久缺失 `DispenseTerminal`，Android 生成一次 `status=failed`、`errorCode=CONTROLLER_TERMINAL_MISSING` 的 command-result；`actualQuantity` 使用最后观察到的 `lastProgressActual`，不保证最终真实数量。
- Terminal 缺失后设备保持 `BLOCKED`，现金保持关闭，不允许下一单。
- 如果之后收到迟到 `DispenseTerminal`，Android 只保存原始证据并发送匹配 `orderSequence + terminalFrameId` 的 `DispenseTerminalAck`，不得覆盖已生成的平台结果，不得发布第二个 command-result。
- `DispenseTerminalAck` 获得线路 echo 前，成功订单不得清除 active order，也不得允许下一单。
