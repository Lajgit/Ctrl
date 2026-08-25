package com.chuzhu.activation;

import android.content.Context;

/**
 * Bootstrap 占位仓库。
 *
 * 当前存珠机第一阶段只依赖生命周期接口返回的 MQTT 凭证；
 * 待平台确认存珠机设备屏 bootstrap 字段后再补充强类型读取。
 */
public final class BootstrapRepository {

    private final Context context;

    public BootstrapRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public Context getContext() {
        return context;
    }
}
