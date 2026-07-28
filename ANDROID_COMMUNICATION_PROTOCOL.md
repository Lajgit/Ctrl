# 购珠机控制板与安卓通信协议

本文档依据 `购珠机控制板.pdf`、`ICT104U Protocol.pdf`、`ICT106U Protocol V0.2 .pdf` 和当前 `Control` 工程代码整理。

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

## 4. 电机与光眼定义

| 逻辑名称 | 驱动引脚 | 光眼反馈 | 用途 |
|---|---|---|---|
| BeadMotor1 | PE9 / PE11，TIM1_CH1/CH2 | PD3 / EXTI3 | 吐珠 |
| BeadMotor2 | PE13 / PE14，TIM1_CH3/CH4 | PD4 / EXTI4 | 存珠 |

投币器脉冲输入为 PE15。

## 5. 安卓发给主板

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 忽略 | 请求固件版本 |
| `0x01` | BeadMotor1Output | Data3:Data4 = 数量 | 启动吐珠电机执行指定数量 |
| `0x02` | BeadMotor2Output | Data3:Data4 = 数量 | 启动存珠电机执行指定数量 |
| `0x10` | Unlock | 忽略 | 电子锁吸合，默认 800 ms 后关闭 |
| `0x19` | BillCurrencyModeSet | Data4=`0x00/0x01` | 选择人民币/外币上报模式 |
| `0x1A` | BillEnable | 忽略 | 启用 ICT 纸钞机 |
| `0x1B` | BillDisable | 忽略 | 禁用 ICT 纸钞机 |
| `0x1C` | BillReset | 忽略 | 复位 ICT 纸钞机 |
| `0xF0` | BoardRestart | `0x424F5441` 时进 Bootloader | 重启主板；数据为 ASCII `BOTA` 时请求进入 Bootloader |
| `0xFF` | StopAllDevice | 忽略 | 立即停止吐珠电机、存珠电机和电子锁 |

收到 `Code1=0x01` 的合法帧后，主板先原样回传应答，再执行业务逻辑。相同消息 ID 的业务去重窗口为 5 秒，避免安卓重发导致重复吐珠或存珠。

电机运行期间再次收到同一路电机命令时，只追加剩余数量，不重置当前超时和反转重试状态。

## 6. 主板发给安卓

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | `0xMMNNPPBB` | 固件版本 |
| `0x01` | BeadMotor1Feedback | `1` | PD3 吐珠光眼触发一次 |
| `0x02` | CoinInput | `1` | PE15 投币脉冲触发一次 |
| `0x03` | BeadMotor2Feedback | `1` | PD4 存珠光眼触发一次 |
| `0x04` | BillAccepted | Data1=币种模式，Data2=ICT 纸币类型码，Data3=纸币序号，Data4=ICT 完成状态 | ICT 纸钞机入钞完成，带重发确认 |
| `0x05` | RemainingBead | Data1:Data2 = 吐珠剩余，Data3:Data4 = 存珠剩余 | 两路电机待执行数量 |
| `0x07` | BeadMotor1Timeout | Data3:Data4 = 吐珠剩余 | 吐珠电机超时，带重发确认 |
| `0x08` | BeadMotor2Timeout | Data3:Data4 = 存珠剩余 | 存珠电机超时，带重发确认 |
| `0x0D` | AlreadyUnlock | `0` | 已执行开锁命令 |
| `0x0F` | Encoder | `1/2/3` | 编码器 CCW/CW/DOWN 短按 |
| `0x12` | BillStatus | Data1=币种模式，Data2=最近纸币类型码，Data3=纸币序号，Data4=ICT 状态字 | ICT 纸钞机状态或错误 |
| `0x13` | BillCurrencyModeStatus | Data4=币种模式 | 当前纸钞机币种模式，ExpandCode 固定 `0x00` |

币种模式：`0x00` 为人民币，`0x01` 为外币。ICT 协议只给出 `0x40~0x4F` 作为第 N 种纸币类型，没有给出具体面额，因此主板只上报原始类型码和序号，不在固件内换算金额。

`BillAccepted` 的 Data4：

- `0x10`：正常堆叠完成
- `0x83`：卡钞动作导致纸币已经堆叠；后续 `0x3F~0x4F` 币种码仍按已收钞上报

## 7. 量产纸钞机 USART3 TTL

- 量产只使用 USART3 TTL，不启用 USART2 RS232 或纸钞脉冲接口
- MCU 引脚：PB10 = TXD3，PB11 = RXD3
- 串口参数：9600 baud，8 数据位，偶校验，1 停止位
- STM32/APM32 HAL 配置为 `UART_WORDLENGTH_9B + UART_PARITY_EVEN`，实际有效数据格式为 8E1
- 主板每 150 ms 发送 `0x0C` 查询状态，符合 ICT106 的 100~200 ms 轮询要求
- ICT106 规定最大响应时间 50 ms；程序对多字节序列使用 100 ms 超时保护
- 纸钞机上电发送 `0x80 0x8F` 时，主板回复 `0x02`
- 收到 `0x81` 后等待 `0x3F~0x4F` 币种码，再回复 `0x02` 接收
- 收到 `0x10` 后，上报正常 `BillAccepted`
- 收到 `0x83 + 0x3F~0x4F` 后，上报卡钞堆叠完成 `BillAccepted`
- 收到 `0x11` 或 `0x20~0x2F/0x3E/0x5E/0x71/0xA1` 后，上报 `BillStatus`
- 启用、禁用命令在状态未确认时最多重试 3 次，不再永久每秒发送
- 启用和禁用状态下都继续轮询纸钞机状态

安卓纸钞机控制命令：

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x19` | BillCurrencyModeSet | Data4=`0x00/0x01` | 选择人民币/外币上报模式 |
| `0x1A` | BillEnable | 忽略 | 向 ICT 发送 `0x3E` |
| `0x1B` | BillDisable | 忽略 | 向 ICT 发送 `0x5E` |
| `0x1C` | BillReset | 忽略 | 向 ICT 发送 `0x30` |

## 8. 尚需实机确认

- 吐珠、存珠电机的实际转向、PWM 速度、超时和反转重试参数
- PD3、PD4 光眼有效脉冲宽度和边沿方向
- ICT 纸币类型码与具体面额的配置映射
