#ifndef _APP_SD_H_
#define _APP_SD_H_

#include "main.h"

#include <stdio.h>
#include "fatfs.h"
#include "ff.h"

#define DEBUG_PRINT 0   //调试输出

#define FileName_Bin "pantao.bin"   //烧录文件名


FRESULT mount_disk(FATFS *fs, const TCHAR *path, BYTE opt);
FRESULT open_file(FIL *fp, const TCHAR *path, BYTE mode);
FRESULT write_file(FIL *fp, const void *buff, UINT btw, UINT *bw);
FRESULT read_file(FIL *fp, void *buff, UINT btr, UINT *br);
FRESULT close_file(FIL *fp);

void Test(void);

#endif
