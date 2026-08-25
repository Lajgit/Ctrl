# 存珠机控制板引脚说明

当前目录代码仅用于说明存珠机控制板引脚，不是完整业务固件。

- PB10 = USART3_TX，连接 Android RX。
- PB11 = USART3_RX，连接 Android TX。
- USART3 波特率 115200，8N1。
- Android 设备节点固定为 `/dev/ttyS5`。
- 正式存珠机串口协议待确认，当前不要移植旧售珠机协议表或臆造存珠协议命令码。
