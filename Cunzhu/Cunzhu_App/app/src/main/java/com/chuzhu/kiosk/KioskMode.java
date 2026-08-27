package com.chuzhu.kiosk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.chuzhu.MainActivity;

/**
 * 存珠机 Kiosk 模式统一入口。
 *
 * <p>普通安装状态下至少启用沉浸式全屏；当 APP 被配置为 Device Owner 后，
 * 同时启用 LockTask、禁用状态栏/锁屏并将 HOME 固定到本 APP，防止顾客进入系统桌面。</p>
 */
public final class KioskMode {

    private static final String TAG = "CunzhuKiosk";
    private static boolean policyConfigured;
    private static boolean nonOwnerWarningLogged;

    private KioskMode() {
    }

    public static void apply(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        configureDeviceOwnerPolicy(activity.getApplicationContext());
        hideSystemBars(activity);
        enterLockTaskIfPermitted(activity);
    }

    private static synchronized void configureDeviceOwnerPolicy(Context context) {
        if (policyConfigured || context == null) {
            return;
        }
        DevicePolicyManager manager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (manager == null) {
            return;
        }
        if (!manager.isDeviceOwnerApp(context.getPackageName())) {
            if (!nonOwnerWarningLogged) {
                nonOwnerWarningLogged = true;
                Log.w(TAG, "当前 APP 不是 Device Owner，仅启用沉浸式全屏；如需彻底屏蔽 HOME/最近任务/状态栏，请先配置 Device Owner");
            }
            return;
        }

        ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);
        try {
            /* 只允许存珠机自身进入 LockTask，HOME/最近任务键不会再切出到系统桌面。 */
            manager.setLockTaskPackages(admin, new String[]{context.getPackageName()});
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }
            manager.setStatusBarDisabled(admin, true);
            manager.setKeyguardDisabled(admin, true);

            /* 即使 LockTask 因异常退出，HOME 也固定回到存珠机主界面，不进入 Launcher3。 */
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            manager.addPersistentPreferredActivity(
                    admin,
                    homeFilter,
                    new ComponentName(context, MainActivity.class)
            );
            policyConfigured = true;
            Log.i(TAG, "Device Owner Kiosk 策略已启用：状态栏已禁用，HOME 已锁定，LockTask 已授权");
        } catch (Throwable error) {
            Log.e(TAG, "配置 Device Owner Kiosk 策略失败", error);
        }
    }

    private static void enterLockTaskIfPermitted(Activity activity) {
        try {
            DevicePolicyManager manager =
                    (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (manager == null || !manager.isLockTaskPermitted(activity.getPackageName())) {
                return;
            }
            ActivityManager activityManager =
                    (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null
                    && activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                return;
            }
            activity.startLockTask();
            Log.i(TAG, "已进入 LockTask Kiosk 模式");
        } catch (Throwable error) {
            Log.w(TAG, "进入 LockTask 失败，继续保持沉浸式全屏", error);
        }
    }

    private static void hideSystemBars(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decorView = window.getDecorView();

        /* Android 13 目标机使用 WindowInsetsController，同时保留 legacy flags 作为系统定制兼容兜底。 */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        }
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        /* 系统弹窗/软键盘结束后若系统栏短暂恢复，立即重新隐藏。 */
        decorView.setOnSystemUiVisibilityChangeListener(visibility ->
                decorView.postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        hideSystemBars(activity);
                    }
                }, 120L)
        );
    }
}
