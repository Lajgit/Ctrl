# 存珠机控制板说明

当前目录为存珠机控制板 MCU 工程。

## 串口连接

- PB10 = USART3_TX，连接 Android RX。
- PB11 = USART3_RX，连接 Android TX。
- USART3 波特率 `115200`，`8N1`。
- Android 设备节点固定为 `/dev/ttyS5`。

## 已同步的 Android ↔ 控制板协议

MCU 端已同步 `Cunzhu/存珠机控制板通信协议_V0.1.md` 中定义的固定 14 字节协议：

- `Code1=0x00`：控制板 → Android。
- `Code1=0x01`：Android → 控制板。
- `Code2=0x10`：开始收珠。
- `Code2=0x11`：停止收珠。
- `Code2=0x12`：状态查询/状态上报。
- `Code2=0x13`：清故障。
- `Code2=0x14`：心跳。
- `Code2=0x20`：计数变化。
- `Code2=0x21`：收珠结束终态。
- `Code2=0x22`：故障终态。
- `Code2=0x23`：控制板启动/复位上报。

## 当前 MCU 端实现范围

- Android 下发 `0x10 START_COLLECT` 后，控制板回 ACK echo 并启动收珠电机。
- 光眼有效脉冲计数后，控制板通过 `0x20 COUNT_CHANGED` 上报 `actualQuantity`。
- 达到 `maximumQuantity`、Android 主动停止或会话超时时，控制板通过 `0x21 COLLECT_FINISHED` 上报终态。
- 堵转/长时间无有效脉冲重试失败时，控制板通过 `0x22 FAULT_EVENT` 上报故障终态。
- `0x21` 和 `0x22` 会等待 Android ACK echo，未确认时同一 `ID` 重发，`ResendID` 递增。
- CRC16 已改为低字节在前、高字节在后，与 Android 端协议一致。

## 说明

当前实现只处理 USART3 直连 Android 的存珠控制协议，不再移植旧售珠机协议表，也不再走旧 UART 透明转发逻辑。
