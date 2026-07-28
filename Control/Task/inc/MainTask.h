#ifndef __MAINTASK_H__
#define __MAINTASK_H__ 


typedef enum
{
    SCENE_SETTING = 0,
    SCENE_IDLE = 1,
    SCENE_PLAYING = 2,
}Scene_t;

#define Event_SaveSetting (1u << 0) 

void MainTaskInit(void);
void MainTask(void);

#endif