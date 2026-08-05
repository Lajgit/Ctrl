package com.gouzhu;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.mqtt.MqttManager;
import com.gouzhu.network.WifiConfigActivity;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.service.DeviceService;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 购珠机启动加载页。
 *
 * <p>页面至少显示5秒，并在此期间启动设备服务、等待网络、SDK认证、MQTT连接，
 * 再读取一次设备屏bootstrap。实际初始化超过5秒时继续停留，直到当前步骤完成。</p>
 */
public final class SplashActivity extends AppCompatActivity {

    private static final int REQUEST_WIFI_CONFIG = 0x31;
    private static final long MIN_DISPLAY_TIME_MS = 5_000L;
    private static final long STATE_CHECK_INTERVAL_MS = 250L;

    private static volatile boolean startupVisible;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long startedAt;
    private boolean receiverRegistered;
    private boolean wifiConfigOpening;
    private boolean bootstrapStarted;
    private boolean bootstrapFinished;
    private boolean leaving;
    private Intent pendingActivationIntent;
    private ObjectAnimator splashAnimator;

    private final Runnable stateCheckRunnable = new Runnable() {
        @Override
        public void run() {
            evaluateStartupState();
        }
    };

    private final BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                return;
            }

            String key = intent.getStringExtra("key");
            String value = intent.getStringExtra("value");
            if ("activation".equals(key) && shouldOpenActivation(intent, value)) {
                pendingActivationIntent = buildActivationIntent(intent);
            }
            evaluateStartupState();
        }
    };

    public static boolean isStartupVisible() {
        return startupVisible;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startupVisible = true;
        startedAt = SystemClock.elapsedRealtime();

        showSplashImage();
        hideSystemUi();
        registerServiceStatusReceiver();

        DeviceAppBootstrapResult cachedBootstrap =
                DeviceSdkManager.get(this).getLastBootstrap();
        bootstrapFinished = cachedBootstrap != null;

        startDeviceService();
        evaluateStartupState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (!wifiConfigOpening) {
            startDeviceService();
            evaluateStartupState();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WIFI_CONFIG) {
            wifiConfigOpening = false;
            startDeviceService();
            evaluateStartupState();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        // 启动初始化完成前不返回桌面，避免设备处于半初始化状态。
    }

    @Override
    protected void onDestroy() {
        startupVisible = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(serviceStatusReceiver);
            receiverRegistered = false;
        }
        if (splashAnimator != null) {
            splashAnimator.cancel();
            splashAnimator = null;
        }
        super.onDestroy();
    }

    private void showSplashImage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(224, 243, 255));

        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bitmap = decodeSplashBitmap();
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        }

        root.addView(
                imageView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
        setContentView(root);

        PropertyValuesHolder scaleX =
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.015f);
        PropertyValuesHolder scaleY =
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.015f);
        PropertyValuesHolder alpha =
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.97f, 1.0f);
        splashAnimator = ObjectAnimator.ofPropertyValuesHolder(
                imageView,
                scaleX,
                scaleY,
                alpha
        );
        splashAnimator.setDuration(2_400L);
        splashAnimator.setRepeatCount(ValueAnimator.INFINITE);
        splashAnimator.setRepeatMode(ValueAnimator.REVERSE);
        splashAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        splashAnimator.start();
    }

    private Bitmap decodeSplashBitmap() {
        int[] resourceIds = {
                R.raw.startup_splash_base64,
                R.raw.startup_splash_base64_2
        };
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            for (int resourceId : resourceIds) {
                try (InputStream input = getResources().openRawResource(resourceId)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
            byte[] encoded = output.toByteArray();
            byte[] imageBytes = Base64.decode(encoded, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void registerServiceStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(AppConfig.ACTION_SERVICE_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    serviceStatusReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(serviceStatusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void startDeviceService() {
        Intent serviceIntent = new Intent(this, DeviceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void evaluateStartupState() {
        mainHandler.removeCallbacks(stateCheckRunnable);
        if (leaving || isFinishing() || isDestroyed()) {
            return;
        }

        if (!WifiSupport.hasSavedWifi(this)) {
            openWifiConfigAfterMinimumTime();
            return;
        }

        if (pendingActivationIntent != null) {
            leaveAfterMinimumTime(pendingActivationIntent);
            return;
        }

        if (MqttManager.get(this).isConnected()) {
            startBootstrapIfNeeded();
            if (bootstrapFinished) {
                leaveAfterMinimumTime(new Intent(this, MainActivity.class));
                return;
            }
        }

        mainHandler.postDelayed(stateCheckRunnable, STATE_CHECK_INTERVAL_MS);
    }

    private void startBootstrapIfNeeded() {
        if (bootstrapStarted || bootstrapFinished) {
            return;
        }
        bootstrapStarted = true;
        DeviceSdkManager.get(this).refreshBootstrap(new DeviceSdkManager.BootstrapCallback() {
            @Override
            public void onSuccess(DeviceAppBootstrapResult result) {
                bootstrapFinished = true;
                evaluateStartupState();
            }

            @Override
            public void onFailure(Throwable error) {
                // 本次真实请求已经结束；主界面仍会按原逻辑显示错误并允许后续重试。
                bootstrapFinished = true;
                evaluateStartupState();
            }
        });
    }

    private void openWifiConfigAfterMinimumTime() {
        long delay = remainingMinimumTime();
        if (delay > 0L) {
            mainHandler.postDelayed(stateCheckRunnable, delay);
            return;
        }
        if (wifiConfigOpening) {
            return;
        }

        wifiConfigOpening = true;
        startActivityForResult(
                new Intent(this, WifiConfigActivity.class),
                REQUEST_WIFI_CONFIG
        );
    }

    private void leaveAfterMinimumTime(Intent target) {
        long delay = remainingMinimumTime();
        if (delay > 0L) {
            mainHandler.postDelayed(stateCheckRunnable, delay);
            return;
        }
        if (leaving) {
            return;
        }

        leaving = true;
        startupVisible = false;
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private long remainingMinimumTime() {
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        return Math.max(0L, MIN_DISPLAY_TIME_MS - elapsed);
    }

    private static boolean shouldOpenActivation(Intent intent, String value) {
        if (intent.getBooleanExtra(
                ActivationActivity.EXTRA_IDENTITY_REGISTRATION_REQUIRED,
                false
        )) {
            return true;
        }
        if (notBlank(intent.getStringExtra(ActivationActivity.EXTRA_CLAIM_QR_CONTENT))
                || notBlank(intent.getStringExtra(ActivationActivity.EXTRA_CLAIM_CODE))
                || notBlank(intent.getStringExtra(
                        ActivationActivity.EXTRA_ACTIVATION_ERROR_DETAIL
                ))) {
            return true;
        }
        return value != null
                && (value.contains("等待设备认领")
                || value.contains("认证失败")
                || value.contains("身份未登记")
                || value.contains("自动登记未成功"));
    }

    private Intent buildActivationIntent(Intent source) {
        Intent target = new Intent(this, ActivationActivity.class);
        target.putExtra(
                ActivationActivity.EXTRA_CLAIM_QR_CONTENT,
                source.getStringExtra(ActivationActivity.EXTRA_CLAIM_QR_CONTENT)
        );
        target.putExtra(
                ActivationActivity.EXTRA_CLAIM_CODE,
                source.getStringExtra(ActivationActivity.EXTRA_CLAIM_CODE)
        );
        target.putExtra(
                ActivationActivity.EXTRA_ACTIVATION_STATUS,
                source.getStringExtra("value")
        );
        target.putExtra(
                ActivationActivity.EXTRA_ACTIVATION_ERROR_DETAIL,
                source.getStringExtra(ActivationActivity.EXTRA_ACTIVATION_ERROR_DETAIL)
        );
        target.putExtra(
                ActivationActivity.EXTRA_IDENTITY_REGISTRATION_REQUIRED,
                source.getBooleanExtra(
                        ActivationActivity.EXTRA_IDENTITY_REGISTRATION_REQUIRED,
                        false
                )
        );
        target.putExtra(
                ActivationActivity.EXTRA_IDENTITY_PUBLIC_KEY,
                source.getStringExtra(ActivationActivity.EXTRA_IDENTITY_PUBLIC_KEY)
        );
        return target;
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
