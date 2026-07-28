# 购珠机控制板固件说明

## 硬件依据

依据文件：`购珠机控制板.pdf`。

已实现硬件：

| 功能 | MCU 引脚 | 程序符号 |
|---|---|---|
| 安卓串口 TX/RX | PA9 / PA10 | USART1 |
| 吐珠电机 1 | PE9 / PE11 | TIM1 CH1 / CH2 |
| 吐珠电机 2 | PE14 / PE13 | TIM1 CH4 / CH3 |
| 吐珠反馈 1 | PC1 | `HoolleOutput_Pin` |
| 吐珠反馈 2 | PC2 | `CardFeedback_Pin` |
| 投币脉冲 | PC3 | `CoinInput_Pin` |
| ICT 纸钞机 TTL 串口 | PB10 / PB11 | USART3 TX/RX |
| 电子锁 | PC15 | `Lock_Valve_Pin` |
| 编码器 CCW/CW/DOWN | PD14 / PD13 / PD12 | `KeyBoard1/2/3` |
| 心跳灯 | PC13 | `LED_Pin` |

## 主流程

`MainTask()` 只执行购珠机需要的任务：

1. `Communicate_Task()`：处理安卓 USART1 收包。
2. `Key_Task()`：扫描投币、纸钞脉冲和编码器输入。
3. `CtrlTask()`：控制两路吐珠电机和电子锁。
4. `Mesg_Task()`：向安卓上报事件和处理重发。
5. `System_Task()`：PC13 心跳灯。

旧蟠桃的灯带、数码管、Flash 设置、控台串口等模块文件仍保留在工程中，但主流程不再调用。

## 编译

当前已用 Keil 命令行验证：

```powershell
cd "E:\单片机项目\ChatGPT单片机项目\购珠机\Control\MDK-ARM"
& "E:\Keil5\UV4\UV4.exe" -b ".\PanTao_2.uvprojx" -j0 -o ".\build\purchase_machine_build.log"
```

结果：

- `0 Error(s), 64 Warning(s)`
- 警告主要来自旧文件缺少末尾换行、旧灯光函数未引用、旧底层枚举类型混用。

## 烧录

工程配置使用 STLink，APP 起始地址为 `0x0800C000`，与现有 Bootloader 布局一致。

VS Code/EIDE：

```powershell
cd "E:\单片机项目\ChatGPT单片机项目\购珠机\Control\MDK-ARM"
# 在 VS Code 中执行任务：build and flash
```

Keil 图形界面：

1. 打开 `Control\MDK-ARM\PanTao_2.uvprojx`。
2. 选择目标 `PanTao_2`。
3. 编译通过后执行 Download。

## 回归验证步骤

1. 上电后确认 PC13 心跳灯约 500 ms 翻转一次。
2. 安卓按协议发送版本请求，主板应返回 `Code1=0x00, Code2=0x00, Data=0x01000000`。
3. 安卓发送电机 1 出珠数量，PE9/PE11 应驱动电机 1；PC1 反馈一次后剩余数量减 1 并上报。
4. 安卓发送电机 2 出珠数量，PE14/PE13 应驱动电机 2；PC2 反馈一次后剩余数量减 1 并上报。
5. PC3 输入一个有效低脉冲，主板上报 `CoinInput`。
6. ICT 纸钞机接 PB10/PB11，串口参数为 9600 8E1；纸钞机发送 `80 8F` 时主板回复 `02`。
7. 投入纸币后，纸钞机发送 `81` 和纸币类型码 `3F~4F`，主板回复 `02`；收到 `10` 后上报 `BillAccepted`。
8. 安卓发送开锁命令，PC15 吸合约 800 ms 后关闭。
9. 安卓发送停止命令，两路电机和电子锁立即关闭。

## 未实现项

- RS232/SP3232 纸钞接口作为预留，不参与当前程序。
- ICT 纸币类型码与具体面额的映射未在协议 PDF 中给出，当前固件只上报币种模式、原始类型码和序号。
- 未删除旧蟠桃模块文件，目的是避免一次性大改工程文件；当前主流程已经不依赖旧业务。
