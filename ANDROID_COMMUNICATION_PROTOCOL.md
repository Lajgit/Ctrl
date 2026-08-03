# 购珠机控制板与 Android 通信协议 V2.1

当前控制板固件版本：**2.1.0.0**。Android App 版本：**2.1.3**。

V2.1 不兼容 1.x。本地价格、现金余额、欠吐队列、现金自动出珠和旧退币兼容逻辑均已删除。

> 当前 V2.1 控制板和 Android 均使用 `Code1=0x00、Code2=0x10` 上报 `CashAccepted`。旧 V1.3 表格中的 `0x28 CashAcceptedAmount` 不适用于当前代码。

核心原则：

```text
每张纸币/每次有效投币脉冲
→ 控制板上报本笔金额
→ Android持久化并上报平台
→ 平台按设备现金会话累计金额
→ 平台下发dispense_marbles.quantity
→ Android转发平台授权数量
→ 控制板按真实光眼计数出珠
```

控制板和 Android 都不能根据现金金额自行计算珠数。

## 1. 串口参数

| 参数 | 数值 |
|---|---|
| MCU 接口 | USART1，PA9=TX、PA10=RX |
| Android 节点 | `/dev/ttyS5` |
| 波特率 | 115200 |
| 数据格式 | 8N1 |
| 硬件流控 | 无 |

## 2. 固定14字节帧

| 字节 | 字段 | 说明 |
|---:|---|---|
| 0 | Head | 固定 `0xAA` |
| 1 | ResendID | 首发0，重发递增 |
| 2 | ID | 消息ID |
| 3 | Code1 | 方向码 |
| 4 | Code2 | 功能码 |
| 5 | Data1 | 数据最高字节 |
| 6 | Data2 | 数据次高字节 |
| 7 | Data3 | 数据次低字节 |
| 8 | Data4 | 数据最低字节 |
| 9 | ACKbyte | 0不要求线路ACK；1要求原样ACK |
| 10 | ExpandCode | 结果码或扩展字节 |
| 11 | CRC16_H | CRC高字节 |
| 12 | CRC16_L | CRC低字节 |
| 13 | Tail | 固定 `0x55` |

CRC覆盖字节0～10，初值 `0xFFFF`，反向多项式 `0xA001`。

方向码：

| Code1 | 方向 |
|---|---|
| `0x00` | 控制板 → Android |
| `0x01` | Android → 控制板 |

同一个 `Code2` 可以在不同方向复用。例如：

```text
Code1=0x01、Code2=0x10：Unlock
Code1=0x00、Code2=0x10：CashAccepted
```

## 3. 两级确认

1. **线路ACK**：原样回传收到的14字节业务字段，只证明串口收到。
2. **持久化确认**：Android写入SQLite成功后发送 `CashEventStored` 或 `BoardEventStored`。

现金事实、硬件启动和硬件终态在收到持久化确认前持续重发。

## 4. Android → 控制板

| Code2 | 名称 | 数据 | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 0 | 查询版本 |
| `0x01` | DispenseStart | Data1=token；Data2:4=数量 | 仅平台 `dispense_marbles.quantity` 可调用 |
| `0x02` | CollectStart | Data1=token；Data2:4=最大数量 | 启动存珠 |
| `0x03` | CollectStop | Data1=token | 停止存珠 |
| `0x10` | Unlock | 0 | 开锁 |
| `0x18` | CashAcceptanceApply | Data1=状态掩码；Data2:4=configVersion | 应用现金配置 |
| `0x19` | BillReset | 0 | 复位纸钞机 |
| `0x1A` | CashEventStored | Data3:4=现金序号 | Android已持久化该笔现金 |
| `0x1B` | BoardEventStored | Data1=原事件Code2；Data2=token | Android已持久化关键硬件事件 |
| `0x20` | HardwareStatusRequest | 0 | 查询硬件状态和未确认现金 |
| `0xF0` | BoardRestart | `0x424F5441`进入Bootloader | 重启 |
| `0xFF` | EmergencyStop | 0 | 停止电机；关闭纸钞机和PB13硬币器电源 |

### 4.1 现金状态掩码

```text
bit0 = 纸钞机实际接收状态
bit1 = 硬币器12V电源状态
```

硬币器接口仍为12V、GND和投币脉冲，但12V供电由PB13控制低边MOS：

- PB13高电平：MOS导通，硬币器上电，bit1=1；
- PB13低电平：MOS截止，硬币器断电，bit1=0；
- PB13控制极性与PC15电子锁一致；
- 上电默认关闭纸钞机和硬币器；
- 新configVersion只有在纸钞机返回`0x3E/0x5E`且实际掩码匹配后才提交；
- 未启用硬币器时忽略PE15脉冲，不生成现金事实。

## 5. 控制板 → Android

| Code2 | 名称 | 数据 | 说明 |
|---|---|---|---|
| `0x00` | VersionReport | `0x02010000` | 固件2.1.0.0 |
| `0x01` | DispenseStarted | token+目标数量 | 出珠启动 |
| `0x02` | DispenseProgress | token+真实数量 | PD3累计 |
| `0x03` | DispenseCompleted | token+真实数量 | 出珠完成 |
| `0x04` | DispenseFailed | token+真实数量；Expand=结果码 | 失败或部分完成 |
| `0x05` | CollectStarted | token+上限 | 存珠启动 |
| `0x06` | CollectProgress | token+真实数量 | PD4累计 |
| `0x07` | CollectCompleted | token+真实数量 | 存珠完成 |
| `0x08` | CollectFailed | token+真实数量；Expand=结果码 | 存珠失败 |
| `0x0D` | AlreadyUnlock | 0 | 已开锁 |
| `0x10` | CashAccepted | 见第6节 | 一笔现金不可逆进入设备 |
| `0x11` | CashAcceptanceStatus | Data1=实际掩码；Data2:4=configVersion | 现金配置状态 |
| `0x12` | CashDeviceStatus | 队列/纸钞状态/启用位 | 诊断状态 |
| `0x20` | BeadStockStatus | 当前库存 | 库存状态 |
| `0x21` | BeadLowStock | 当前库存 | 低库存 |
| `0x22` | BeadEmpty | 当前库存 | 无珠 |
| `0x23` | BeadRefilled | 当前库存 | 已补珠 |
| `0x27` | BackendSettingsRequest | 1 | K2（PD13）进入后台 |

硬件结果码：

| 值 | 含义 |
|---:|---|
| `0x00` | 完成 |
| `0x01` | 忙 |
| `0x02` | 无珠 |
| `0x03` | 数量无效 |
| `0x04` | 光眼超时 |
| `0x05` | 紧急停止 |
| `0x06` | 操作不存在/token不匹配 |

## 6. 现金事实编码

每笔现金单独发送：

```text
Code1       = 0x00
Code2       = 0x10
Data1       = 0硬币，1纸币
Data2:Data3 = 本笔金额，单位分
Data4       = 16位现金序号高8位
ExpandCode  = 16位现金序号低8位
```

100元纸币示例：

```text
Code1/Code2 = 00 10
Data1       = 01
Data2:Data3 = 27 10    // 10000分
Data4/Expand= 12 34    // 序号0x1234
```

投入两张100元时，控制板发送两笔独立事实：

```text
序号0x1234：banknote，10000分
序号0x1235：banknote，10000分
```

Android上报两次 `cash-event`。平台累计为20000分，再决定下发多少珠。

### 6.1 Android `report/cash-event` JSON

Android收到 `CashAccepted` 后，按控制板现金序号去重，匹配当前现金配置档位，并构造以下JSON：

```json
{
  "eventNo": "CE-A-20260803160400123-1234-a1b2c3",
  "eventType": "accepted",
  "cashMediumType": "banknote",
  "denominationAmount": 10000,
  "cashCount": 1,
  "boardSequence": 4660,
  "cashSaleTierNo": "服务端下发的现金档位编号",
  "configVersion": 29,
  "timestamp": 1785744240123
}
```

字段定义：

| 字段 | 说明 |
|---|---|
| `eventNo` | Android生成的现金事件号，格式为时间+板端序号+随机串 |
| `eventType` | 固定为 `accepted` |
| `cashMediumType` | `coin` 或 `banknote` |
| `denominationAmount` | 本笔金额，单位分 |
| `cashCount` | 固定为1，每笔物理现金单独上报 |
| `boardSequence` | 控制板生成的16位现金序号，十进制写入JSON |
| `cashSaleTierNo` | 当前配置中匹配到的现金档位编号；未匹配时为空字符串 |
| `configVersion` | 匹配档位对应的现金配置版本；未匹配时使用本地当前配置版本 |
| `timestamp` | Android生成该现金事实时的Unix毫秒时间戳 |

实际MQTT Topic由SDK凭证中的 `reportTopics["cash-event"]` 决定，通常对应：

```text
pxd/v1/device/{deviceNo}/report/cash-event
```

Android处理顺序：

```text
构造JSON
→ 写入cash_events和cash outbox
→ 发送CashEventStored(sequence)确认控制板
→ 尝试发布report/cash-event
→ 失败时保留原始JSON，后续从outbox重发
```

App日志使用Tag `GouzhuPlatformV2`，首次构造并准备上报时打印：

```text
现金事实上报JSON={...}
```

## 7. 多笔现金队列

控制板Flash保存最多16笔尚未被Android确认的现金事实：

```text
CashSequenceCounter
CashQueueHead
CashQueueCount
CashQueueSequence[16]
CashQueuePacked[16]
```

流程：

1. 现金进入后先加入队列并请求Flash保存；
2. 主循环完成Flash写入后发送队列头；
3. Android写入SQLite和cash outbox；
4. Android发送 `CashEventStored(sequence)`；
5. 控制板删除队列头并发送下一笔；
6. Android断线或重启时，控制板继续重发同一序号和金额。

队列满时：

- 纸钞机在escrow阶段退回纸币；
- 三线硬币器无法物理拒收，控制板置队列溢出告警；
- 正常生产必须保证Android持续在线并监控队列数量。

`CashDeviceStatus`：

```text
bit31       = 现金队列溢出
bit24..30   = 队列数量
bit16..23   = 最近纸币类型
bit8..15    = 最近纸钞机状态
bit1        = 纸钞机实际启用状态
bit0        = 硬币器PB13电源实际启用状态
```

常见低两位：

```text
0x0 = 纸钞机关闭，硬币器关闭
0x1 = 纸钞机关闭，硬币器开启
0x2 = 纸钞机开启，硬币器关闭
0x3 = 纸钞机开启，硬币器开启
```

注意：`CashDeviceStatus` 的低两位顺序与 `CashAcceptanceApply/CashAcceptanceStatus` 的状态掩码定义不同：

```text
现金配置掩码：bit0纸钞机，bit1硬币器
诊断状态低位：bit1纸钞机，bit0硬币器
```

## 8. 平台累计现金时序

```text
投入第一张100元
→ 控制板Flash保存并上报10000分/seq1
→ Android SQLite保存并确认seq1
→ Android上报cash-event1

投入第二张100元
→ 控制板Flash保存并上报10000分/seq2
→ Android SQLite保存并确认seq2
→ Android上报cash-event2

平台将同一设备现金会话累计为20000分
→ 平台计算珠数
→ 平台下发dispense_marbles(quantity=N)
→ Android持久化命令和ACK
→ Android发送DispenseStart(token,N)
→ 控制板按PD3真实计数执行
→ Android上报actualQuantity
```

`cash_event_response.requestedQuantity` 不能直接启动电机。唯一出珠授权仍是 `dispense_marbles`。

平台必须定义现金会话结束规则，例如：

- 距最后一笔现金2～5秒无新现金；
- 达到某个金额档位后结算；
- 平台订单明确关闭；
- 单笔现金分别生成多个出珠命令。

若平台收到每笔现金后立即下发出珠，则两张100元会形成两次出珠；若需要合并成一次出珠，必须由平台延迟结算并累计。

## 9. 幂等与掉电恢复

- 相同MQTT `messageId` 不得重复启动电机；
- Android发送电机命令前先保存“可能已启动”状态；
- Android进程重启后禁止自动重启电机；
- 控制板关键启动/终态在收到 `BoardEventStored` 前持续重发；
- MQTT PUBACK不能删除业务outbox；
- 平台 `command_result_ack.recorded` 才能删除命令结果outbox；
- 现金 `unknown` 响应必须重发原eventNo和原payload；
- 每笔现金以板端sequence和App端eventNo双重幂等。

## 10. 版本约束

```text
Android：2.1.3
控制板：2.1.0.0
协议：V2.1
```

必须成套升级，禁止与2.0的单笔现金阻塞固件或1.x本地现金出珠固件混用。
