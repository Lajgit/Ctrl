# 购珠机控制板与安卓通信协议

本文档依据 `购珠机控制板.pdf` 和当前 `Control` 工程代码生成。

## 1. 串口参数

- 接口：USART1，原理图标注“串口1接安卓板”
- MCU 引脚：PA9 = TXD1，PA10 = RXD1
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
| 5 | Data1 | 数据高字节 |
| 6 | Data2 | 数据 |
| 7 | Data3 | 数据 |
| 8 | Data4 | 数据低字节 |
| 9 | ACKbyte | `0x00` 不要求重发确认，`0x01` 要求确认 |
| 10 | ExpandCode | 扩展码 |
| 11 | CRC16_H | CRC16 高字节 |
| 12 | CRC16_L | CRC16 低字节 |
| 13 | Tail | 固定 `0x55` |

CRC16 计算范围为字节 0 到字节 10，初值 `0xFFFF`，多项式反向 `0xA001`。

## 3. 方向码

| Code1 | 方向 |
|---|---|
| `0x00` | 主板 -> 安卓 |
| `0x01` | 安卓 -> 主板 |

## 4. 安卓发给主板

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 忽略 | 请求固件版本 |
| `0x01` | BeadMotor1Output | Data3:Data4 = 数量 | 启动吐珠电机 1 出指定数量 |
| `0x02` | BeadMotor2Output | Data3:Data4 = 数量 | 启动吐珠电机 2 出指定数量 |
| `0x10` | Unlock | 忽略 | 电子锁吸合，默认 800 ms 后关闭 |
| `0x19` | BillCurrencyModeSet | Data4=`0x00/0x01` | 选择纸钞机人民币/外币上报模式 |
| `0x1A` | BillEnable | 忽略 | 启用 ICT 纸钞机 |
| `0x1B` | BillDisable | 忽略 | 禁用 ICT 纸钞机 |
| `0x1C` | BillReset | 忽略 | 复位 ICT 纸钞机 |
| `0xF0` | BoardRestart | `0x424F5441` 时进 Bootloader | 重启主板；数据为 ASCII `BOTA` 时请求进入 Bootloader |
| `0xFF` | StopAllDevice | 忽略 | 立即停止两路电机和电子锁 |

收到 `Code1=0x01` 的合法帧后，主板会先原样回传一帧作为应答，然后再执行业务逻辑。短时间内相同 ID 会被去重。

## 5. 主板发给安卓

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | `0xMMNNPPBB` | 固件版本 |
| `0x01` | BeadMotor1Feedback | `1` | 电机 1 出珠反馈触发一次 |
| `0x02` | CoinInput | `1` | 投币脉冲触发一次 |
| `0x03` | BeadMotor2Feedback | `1` | 电机 2 出珠反馈触发一次 |
| `0x04` | BillAccepted | Data1=币种模式，Data2=ICT 纸币类型码，Data3=纸币序号，Data4=`0x10` | ICT 纸钞机入钞完成，带重发确认 |
| `0x05` | RemainingBead | Data1:Data2 = 电机 1 剩余，Data3:Data4 = 电机 2 剩余 | 两路待吐珠数量 |
| `0x07` | BeadMotor1Timeout | Data3:Data4 = 电机 1 剩余 | 电机 1 超时，带重发确认 |
| `0x08` | BeadMotor2Timeout | Data3:Data4 = 电机 2 剩余 | 电机 2 超时，带重发确认 |
| `0x0D` | AlreadyUnlock | `0` | 已执行开锁命令 |
| `0x0F` | Encoder | `1/2/3` | 编码器 CCW/CW/DOWN 短按 |
| `0x12` | BillStatus | Data1=币种模式，Data2=最近纸币类型码，Data3=纸币序号，Data4=ICT 状态字 | ICT 纸钞机状态或错误 |
| `0x13` | BillCurrencyModeStatus | Data4=币种模式 | 当前纸钞机币种模式 |

币种模式：`0x00` 为人民币，`0x01` 为外币。ICT 协议只给出 `0x40~0x4F` 作为第 N 种纸币类型，没有给出具体面额，因此主板只上报原始类型码和序号，不在固件内换算金额。

## 6. 纸钞机 ICT TTL 串口

- 接口：USART3，原理图 TTL 串口，PB10 = TXD3，PB11 = RXD3
- RS232/SP3232 接口为预留，不作为实际纸钞机接口使用
- 协议依据：`ICT104U Protocol.pdf`、`ICT106U Protocol V0.2 .pdf`
- 串口参数：9600 baud，8 数据位，偶校验，1 停止位
- 主板轮询：每 150 ms 发送 `0x0C`
- 主板默认发送 `0x3E` 启用纸钞机，并每 1 s 重发当前启用/禁用状态
- 纸钞机上电发送 `0x80 0x8F` 时，主板回复 `0x02`
- 纸钞机 escrow：收到 `0x81` 后等待纸币类型码 `0x3F~0x4F`，主板回复 `0x02` 接收
- 入钞完成：收到 `0x10` 后，主板上报 `BillAccepted`
- 退钞或错误状态：收到 `0x11` 或 `0x20~0x2F/0x3E/0x5E/0x71/0xA1` 后，上报 `BillStatus`

安卓新增纸钞机控制命令：

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x19` | BillCurrencyModeSet | Data4=`0x00/0x01` | 选择人民币/外币上报模式 |
| `0x1A` | BillEnable | 忽略 | 向 ICT 发送 `0x3E` |
| `0x1B` | BillDisable | 忽略 | 向 ICT 发送 `0x5E` |
| `0x1C` | BillReset | 忽略 | 向 ICT 发送 `0x30` |

## 7. 已知未确认项

- 出珠反馈脉冲电平宽度、电机速度和反转重试参数需要上机实测后确认。
- ICT 纸币类型码与具体面额的映射未在协议 PDF 中给出，需要由纸钞机配置或安卓后台配置提供。
