package com.chuzhu.kiosk;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 全局 Kiosk 生命周期入口，确保主界面、确认页、激活页和 WiFi 配置页统一保持全屏与 LockTask。
 */
public final class KioskApplication extends Application implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        KioskMode.apply(activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        KioskMode.apply(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        KioskMode.apply(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
