#ifndef __MAINTASK_H__
#define __MAINTASK_H__

#define Event_SaveSetting (1u << 0)
#define Event_Happy30s_Win (1u << 1)
#define Event_Happy30s_Lose (1u << 2)
#define Event_HoolleInput (1u << 3)
#define Event_SceneChange (1u << 4)
#define Event_LeftHoleInside (1u << 5)
#define Event_RightHoleInside (1u << 6)
#define Event_USART1_IDLE (1u << 7)
#define Event_USART3_IDLE (1u << 8)

typedef enum
{
    SCENE_LESSLIGHT = 0,
    SCENE_IDLE = 1,
    SCENE_PLAYING = 2,
    SCENE_VICTORY = 3,
}Scene_t;


void System_Reset(void);
void Main_Init(void);
void Main_Task(void);

#endif
