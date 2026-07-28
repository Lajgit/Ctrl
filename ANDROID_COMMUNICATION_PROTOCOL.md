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

投币器脉冲输入为 PE15，每个完整有效脉冲固定计为人民币 1 元。

## 5. 安卓发给主板

| Code2 | 名称 | Data | 说明 |
|---|---|---|---|
| `0x00` | VersionRequest | 忽略 | 请求固件版本 |
| `0x01` | BeadMotor1Output | Data3:Data4 = 数量 | 直接启动吐珠电机执行指定数量，保留作调试或维护命令 |
| `0x02` | BeadMotor2Output | Data3:Data4 = 数量 | 启动存珠电机执行指定数量 |
| `0x10` | Unlock | 忽略 | 电子锁吸合，默认 800 ms 后关闭 |
| `0x19` | BillCurrencyModeSet | Data4=`0x00/0x01` | 选择人民币/外币上报模式；当前自动购买只实现人民币 |
| `0x1A` | BillEnable | 忽略 | 启用 ICT 纸钞机 |
| `0x1B` | BillDisable | 忽略 | 禁用 ICT 纸钞机 |
| `0x1C` | BillReset | 忽略 | 复位 ICT 纸钞机 |
| `0x20` | BeadPriceSet | Data1:Data4 = 单颗价格 | 设置单颗珠子价格，单位为人民币元，有效范围 1~10000 |
| `0x21` | PurchaseStatusRequest | 忽略 | 请求价格、库存、欠吐数量和累计余额 |
| `0xF0` | BoardRestart | `0x424F5441` 时进 Bootloader | 重启主板；数据为 ASCII `BOTA` 时请求进入 Bootloader |
| `0xFF` | StopAllDevice | 忽略 | 立即停止吐珠电机、存珠电机和电子锁；已付款欠吐数量不清除，10 秒后允许恢复 |

收到 `Code1=0x01` 的合法帧后，主板先原样回传应答，再执行业务逻辑。相同消息 ID 的业务去重窗口为 5 秒，避免安卓重发导致重复执行。

`BeadPriceSet` 返回 `BeadPriceStatus`：

- `ExpandCode=0x00`：设置成功；
- `ExpandCode=0x01`：价格无效，Data1:Data4 返回当前有效价格。

新价格立即应用于控制板中尚未换算成珠子的累计人民币余额。

## 6. 主板发给安卓

| Code2 | 名称 | Data | ACK | 说明 |
|---|---|---|---|---|
| `0x00` | VersionRequest | `0xMMNNPPBB` | 需要 | 固件版本，当前购买功能版本为 1.1.0.0 |
| `0x01` | BeadMotor1Feedback | `1` | 不需要 | PD3 吐珠光眼确认成功吐出一颗 |
| `0x02` | CoinInput | `1` | 不需要 | PE15 检测到一个 1 元硬币脉冲 |
| `0x03` | BeadMotor2Feedback | `1` | 不需要 | PD4 存珠光眼触发一次 |
| `0x04` | BillAccepted | Data1=币种模式，Data2=ICT 类型码，Data3=纸币序号，Data4=完成状态 | 需要 | ICT 纸钞机入钞完成 |
| `0x05` | RemainingBead | Data1:Data2=吐珠电机剩余，Data3:Data4=存珠电机剩余 | 不需要 | 当前两路电机已排队数量 |
| `0x07` | BeadMotor1Timeout | Data1:Data4=吐珠电机剩余数量 | 需要 | 吐珠电机完成一次反转清障后再次正转仍超时 |
| `0x08` | BeadMotor2Timeout | Data1:Data4=存珠剩余数量 | 需要 | 存珠电机超时 |
| `0x0D` | AlreadyUnlock | `0` | 不需要 | 已执行开锁命令 |
| `0x0F` | Encoder | `1/2/3` | 不需要 | 编码器 CCW/CW/DOWN 短按；DOWN 同时作为 K1 补珠确认 |
| `0x12` | BillStatus | Data1=币种模式，Data2=最近纸币类型码，Data3=纸币序号，Data4=ICT 状态字 | 不需要 | ICT 纸钞机状态或错误 |
| `0x13` | BillCurrencyModeStatus | Data4=币种模式 | 不需要 | 当前纸钞机币种模式，ExpandCode 固定 `0x00` |
| `0x20` | BeadPriceStatus | Data1:Data4=单颗价格 | 不需要 | 当前单颗价格；ExpandCode 表示价格设置结果 |
| `0x21` | BeadStockStatus | Data1:Data4=当前库存 | 不需要 | 每成功吐出一颗后同步最新库存 |
| `0x22` | PurchasePendingStatus | Data1:Data4=欠吐数量 | 不需要 | 已收款但尚未成功吐出的珠子数量 |
| `0x23` | PurchaseCreditStatus | Data1:Data4=累计余额 | 不需要 | 尚不足一颗珠子的人民币余额，单位：元 |
| `0x24` | BeadLowStock | Data1:Data4=当前库存 | 需要 | 库存首次降到 3000 或以下 |
| `0x25` | BeadEmpty | Data1:Data4=欠吐数量 | 需要 | 吐珠清障重试仍失败，纸钞机已被禁用 |
| `0x26` | BeadRefilled | Data1:Data4=新库存 | 需要 | K1 补珠确认，安卓应恢复购买界面 |

币种模式：`0x00` 为人民币，`0x01` 为外币。

`BillAccepted` 的 Data4：

- `0x10`：正常堆叠完成；
- `0x83`：卡钞动作导致纸币已经堆叠，仍按已收款处理。

## 7. 人民币纸币金额映射

当前固件只对人民币模式下的以下类型码进行自动购买换算：

| ICT 类型码 | 人民币金额 |
|---|---:|
| `0x40` | 1 元 |
| `0x41` | 5 元 |
| `0x42` | 10 元 |
| `0x43` | 20 元 |
| `0x44` | 50 元 |
| `0x45` | 100 元 |

`0x46~0x4F` 仍会通过 `BillAccepted` 原样上报安卓，但当前人民币购买逻辑不计算吐珠数量。

## 8. 固件购买流程

1. 安卓通过 `BeadPriceSet` 设置单颗珠子价格；首次使用默认价格为 1 元/颗。
2. 硬币机每个有效脉冲累计 1 元。
3. 纸币机在收到正常堆叠 `0x10` 或卡钞已堆叠 `0x83` 后，按 `0x40~0x45` 映射累计金额。
4. 控制板按 `累计金额 / 单颗价格` 计算新增待吐数量，不能整除的余额继续保留。
5. 控制板直接驱动 BeadMotor1 吐珠，不需要安卓再发送吐珠命令。
6. 每次 PD3 光眼确认一颗后：
   - 电机剩余数量减一；
   - 已付款欠吐数量减一；
   - 库存减一；
   - 向安卓发送 `BeadMotor1Feedback`、`BeadStockStatus` 和 `PurchasePendingStatus`。

## 9. 无珠、反转重试和补珠恢复

吐珠电机流程：

1. 正转后 3 秒没有 PD3 光眼反馈；
2. 反转 300 ms 清障；
3. 再次正转；
4. 再次 3 秒无反馈，判定无珠；
5. 停止电机，保留未完成吐珠数量；
6. 将软件库存校准为 0；
7. 向纸钞机发送 `0x5E` 禁用命令；
8. 向安卓发送 `BeadMotor1Timeout` 和 `BeadEmpty`。

K1 编码器按键对应 `Encoder.Data4=3`，按下表示已经补充珠子：

- 库存暂定重置为 10000；
- 清除无珠锁定；
- 向纸钞机发送 `0x3E` 重新启用；
- 向安卓发送 `BeadRefilled`，安卓应恢复购买界面；
- 若仍有已付款欠吐数量，等待 10 秒供操作人员关门，然后自动继续吐珠。

库存从 3001 降到 3000，或首次降到 3000 以下时，发送 `BeadLowStock`。K1 补珠后允许下一次重新触发低库存通知。

## 10. 掉电保存

控制板使用 STM32F407 内部 Flash Sector 2：

- 地址范围：`0x08008000~0x0800BFFF`；
- APP 起始地址：`0x0800C000`，不与 APP 代码重叠；
- 保存内容：单颗价格、库存、已付款欠吐数量、累计余额、无珠锁定状态；
- 每次支付、每颗吐珠反馈、价格修改和补珠操作后保存；
- 使用 32 字节追加日志，校验字最后写入，避免普通掉电产生的半条记录覆盖上一条有效状态；
- 日志区写满后擦除 Sector 2，并写入最新完整状态。

掉电重启后：

- 无珠锁定仍存在时继续禁用纸钞机；
- 存在欠吐数量且未处于无珠锁定时，启动后等待 10 秒再继续吐珠。

## 11. 量产纸钞机 USART3 TTL

- 量产只使用 USART3 TTL，不启用 USART2 RS232 或纸钞脉冲接口；
- MCU 引脚：PB10 = TXD3，PB11 = RXD3；
- 串口参数：9600 baud，8 数据位，偶校验，1 停止位；
- STM32/APM32 HAL 配置为 `UART_WORDLENGTH_9B + UART_PARITY_EVEN`，实际有效数据格式为 8E1；
- 主板每 150 ms 发送 `0x0C` 查询状态；
- ICT106 规定最大响应时间 50 ms，程序对多字节序列使用 100 ms 超时保护；
- 纸钞机上电发送 `0x80 0x8F` 时，主板回复 `0x02`；
- 收到 `0x81` 后等待 `0x3F~0x4F` 币种码，再回复 `0x02` 接收；
- 收到 `0x10` 后确认正常收款；
- 收到 `0x83 + 0x3F~0x4F` 后确认卡钞但纸币已经堆叠；
- 收到 `0x11` 或 `0x20~0x2F/0x3E/0x5E/0x71/0xA1` 后，上报 `BillStatus`；
- 启用、禁用命令在状态未确认时最多重试 3 次；
- 启用和禁用状态下都继续轮询纸钞机状态。

## 12. 尚需实机确认

- `0x40~0x45` 与实际纸钞机人民币配置是否完全一致；
- K1 编码器按键是否确实对应 KeyBoard3 / DOWN / 协议值 `3`；
- 吐珠电机正反转方向、85% PWM、3 秒超时和 300 ms 反转时间是否适合实物；
- PD3 光眼有效边沿、脉冲宽度及一颗珠子是否只产生一个有效反馈；
- 内部 Flash Sector 2 在当前 Bootloader 中是否确实保留且不会被升级流程擦除；
- 安卓收到 `BeadEmpty` 后关闭购买界面，收到 `BeadRefilled` 后恢复购买界面的实现。
