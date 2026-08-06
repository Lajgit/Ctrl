#ifndef __MAINTASK_H__
#define __MAINTASK_H__ 

/* 控制板主循环场景状态，当前默认运行于空闲场景。 */
typedef enum
{
    SCENE_SETTING = 0,
    SCENE_IDLE = 1,
    SCENE_PLAYING = 2,
}Scene_t;

/* 请求将当前硬件事实和配置写入内部 Flash。 */
#define Event_SaveSetting (1u << 0) 

/* 初始化各子任务及上电状态。 */
void MainTaskInit(void);
/* 按固定顺序周期调度通信、输入、中断、Flash、硬件和消息任务。 */
void MainTask(void);

#endif