#include "app_sd.h"


FATFS fs;
FIL fil;
uint8_t r_buffer[1024];
FRESULT mount_disk(FATFS *fs, const TCHAR *path, BYTE opt)
{
    FRESULT res = f_mount(fs, path, opt);
#if DEBUG_PRINT
    if (res != FR_OK)
    {
        printf("Disk mount faild\n");
        printf("Error code:%d\n", res);
    }
    else
    {
        printf("Disk mount success\n");
    }
#endif
    return res;
}
FRESULT open_file(FIL *fp, const TCHAR *path, BYTE mode)
{
    FRESULT res = f_open(fp, path, mode);
#if DEBUG_PRINT
    if (res != FR_OK)
    {
        printf("File open faild\n");
        printf("Error code:%d\n", res);
    }
    else
    {
        printf("File open success\n");
    }
#endif
    return res;
}

FRESULT write_file(FIL *fp, const void *buff, UINT btw, UINT *bw)
{
    FRESULT res = f_write(fp, buff, btw, bw);
#if DEBUG_PRINT
    if (res != FR_OK)
    {
        printf("File write faild\n");
        printf("Error code:%d\n", res);
    }
    else
    {
        printf("File write success\n");
        printf("Bytes written:%d\n", *bw);
    }
#endif
    return res;
}

FRESULT read_file(FIL *fp, void *buff, UINT btr, UINT *br)
{
    FRESULT res = f_read(fp, buff, btr, br);
#if DEBUG_PRINT
    if (res != FR_OK)
    {
        printf("File read faild\n");
        printf("Error code:%d\n", res);
    }
    else
    {
        printf("File read success\n");
        printf("Bytes read:%d\n", *br);
    }
#endif
    return res;
}

FRESULT close_file(FIL *fp)
{
    FRESULT res = f_close(fp);
#if DEBUG_PRINT
    if (res != FR_OK)
    {
        printf("File close faild\n");
        printf("Error code:%d\n", res);
    }
    else
    {
        printf("File close success\n");
    }
#endif
    return res;
}
void Test(void)
{
    static uint32_t num;
    HAL_Delay(500);
    mount_disk(&fs, "", 0);
    //open_file(&fil, "badapple.txt", FA_OPEN_EXISTING | FA_WRITE);
    //write_file(&fil, "badapple.txt", 1024, &num);
    //close_file(&fil);
    open_file(&fil, "badapple.txt", FA_OPEN_EXISTING | FA_READ);
    read_file(&fil, r_buffer, 1024, &num);
    close_file(&fil);

    HAL_Delay(1);
}
