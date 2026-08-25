#ifndef __COMM_TASK_H__
#define __COMM_TASK_H__

#include "stdint.h"
#include "port_communicate.h"
#include "app_list.h"

/* 存珠机控制板固件版本号 */
#define VERSION 20260825

/* 14字节存珠协议方向码：字段功能与售珠机保持一致，仅通过Code1区分方向。 */
#define Board_to_Android 0x00U
#define Android_to_Board 0x01U

/* 兼容旧控台源码的方向码定义，新的存珠协议不再使用。 */
#define Board_to_Ctrl 0x02U
#define Ctrl_to_Board 0x03U
#define Relay_to_Android 0x04U
#define Android_to_Relay 0x05U

/* 存珠机Code2功能码。 */
#define CZ_CODE2_START_COLLECT 0x10U
#define CZ_CODE2_STOP_COLLECT 0x11U
#define CZ_CODE2_STATUS 0x12U
#define CZ_CODE2_CLEAR_FAULT 0x13U
#define CZ_CODE2_HEARTBEAT 0x14U
#define CZ_CODE2_COUNT_CHANGED 0x20U
#define CZ_CODE2_COLLECT_FINISHED 0x21U
#define CZ_CODE2_FAULT_EVENT 0x22U
#define CZ_CODE2_BOARD_BOOT 0x23U

/* ACKbyte。 */
#define CZ_ACK_NONE 0x00U
#define CZ_ACK_ECHO 0x01U

/* currentState。 */
#define CZ_STATE_IDLE 0x00U
#define CZ_STATE_COLLECTING 0x01U
#define CZ_STATE_FAULT 0x02U
#define CZ_STATE_STOPPING 0x03U
#define CZ_STATE_MAINTENANCE 0x04U
#define CZ_STATE_SELF_TEST 0x05U

/* ExpandCode结果码/故障码。 */
#define CZ_RESULT_OK 0x00U
#define CZ_RESULT_BUSY 0x01U
#define CZ_RESULT_PARAM_INVALID 0x02U
#define CZ_RESULT_STATE_INVALID 0x03U
#define CZ_RESULT_CRC_ERROR 0x04U
#define CZ_RESULT_UNKNOWN_CODE2 0x05U
#define CZ_RESULT_NOT_READY 0x06U
#define CZ_RESULT_SENSOR_ERROR 0x07U
#define CZ_RESULT_TIMEOUT 0x08U
#define CZ_RESULT_MOTOR_ERROR 0x09U
#define CZ_RESULT_JAM 0x0AU
#define CZ_RESULT_EMERGENCY_STOP 0x0BU
#define CZ_RESULT_STORAGE_FULL 0x0CU
#define CZ_RESULT_DUPLICATE_ACCEPTED 0x0DU
#define CZ_RESULT_UNKNOWN 0xFFU

/* 收珠结束原因。 */
#define CZ_FINISH_ANDROID_STOP 0x00U
#define CZ_FINISH_REACH_MAX 0x01U
#define CZ_FINISH_SESSION_TIMEOUT 0x02U
#define CZ_FINISH_NATURAL_END 0x03U
#define CZ_FINISH_MANUAL_END 0x04U

/* 固定14字节消息帧。CRC16按新协议为低字节在前、高字节在后。 */
typedef struct
{
    uint8_t Head;
    uint8_t ResendID;
    uint8_t ID;
    uint8_t Code1;
    uint8_t Code2;
    uint8_t Data1;
    uint8_t Data2;
    uint8_t Data3;
    uint8_t Data4;
    uint8_t ACKbyte;
    uint8_t ExpandCode;
    uint8_t CRC16_L;
    uint8_t CRC16_H;
    uint8_t Tail;
} Mesg_TypeDef;

/* 兼容旧控台文件保留的发送接口。 */
uint8_t Comm_SendMesg_FillData(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode);
uint8_t Comm_SendMesg_FillData_withResend(Tx_HandleTypeDef *Tx, uint8_t code_1, uint8_t code_2, uint32_t data, uint8_t expandCode, ListHandle_t *List);
void Resend_Task(void);
void MesgDeal_Task(void);

void CommInit(void);
void CommTask(void);

/* 中断回调由InterruptTask统一转入通信模块。 */
void Comm_UART_RxCpltCallback(UART_HandleTypeDef *huart);
void Comm_UART_ErrorCallback(UART_HandleTypeDef *huart);
void Comm_HoolleOutput2IRQ(void);

#endif
