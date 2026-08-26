package com.chuzhu.network;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.chuzhu.R;
import com.chuzhu.device.DeviceService;
import com.google.android.material.button.MaterialButton;

/**
 * 存珠机本地触屏 WiFi 配置页。
 *
 * <p>用户在设备触屏输入 SSID 和密码。APP 会先保存凭证并尝试直接连接；
 * 若普通 Android 系统拒绝旧 WiFi API，则继续打开系统添加网络/联网面板让用户确认。</p>
 */
public final class WifiConfigActivity extends AppCompatActivity {

    public static final String EXTRA_REASON = "reason";

    private static final String TAG = "CunzhuWifiConfig";
    private static final int REQUEST_NEARBY_WIFI_PERMISSION = 0x51;
    private static final int WIFI_CONNECT_WAIT_SECONDS = 40;

    private EditText ssidInput;
    private EditText passwordInput;
    private CheckBox showPasswordCheck;
    private TextView statusText;
    private MaterialButton connectButton;
    private boolean systemPanelOpening;
    private String pendingSsid = "";
    private String pendingPassword = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_config);
        hideSystemUi();

        ssidInput = findViewById(R.id.input_wifi_ssid);
        passwordInput = findViewById(R.id.input_wifi_password);
        showPasswordCheck = findViewById(R.id.check_show_password);
        statusText = findViewById(R.id.text_wifi_status);
        connectButton = findViewById(R.id.button_connect_wifi);

        NetworkStartupManager network = new NetworkStartupManager(this);
        ssidInput.setText(network.getSavedSsid());
        passwordInput.setText(network.getSavedPassword());

        String reason = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_REASON);
        statusText.setText(reason == null || reason.trim().isEmpty()
                ? "请输入 WiFi 名称和密码，联网后设备会自动继续激活"
                : reason.trim());

        showPasswordCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updatePasswordInputType(isChecked));
        findViewById(R.id.button_clear_wifi).setOnClickListener(view -> clearWifi());
        connectButton.setOnClickListener(view -> connectWifi());
        updatePasswordInputType(false);

        if (network.hasValidatedInternet()) {
            statusText.setText("网络已连接，可以返回主界面");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (systemPanelOpening) {
            systemPanelOpening = false;
            connectButton.setEnabled(false);
            statusText.setText("正在确认系统联网状态...");
            new Thread(() -> {
                boolean connected = new NetworkStartupManager(this).waitForInternet(20);
                runOnUiThread(() -> {
                    connectButton.setEnabled(true);
                    if (connected) {
                        onConnected("网络已连接");
                    } else {
                        statusText.setText("系统网络仍不可用，请检查 WiFi 名称、密码或信号");
                    }
                });
            }, "存珠机-WiFi系统确认").start();
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
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NEARBY_WIFI_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            connectWifi();
        } else {
            statusText.setText("WiFi 权限未授予，已打开系统联网界面，请手动连接网络");
            openSystemAddNetworkPanel(pendingSsid, pendingPassword);
        }
    }

    private void connectWifi() {
        String ssid = ssidInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (ssid.isEmpty()) {
            Toast.makeText(this, "请输入 WiFi 名称", Toast.LENGTH_SHORT).show();
            return;
        }

        pendingSsid = ssid;
        pendingPassword = password;
        NetworkStartupManager network = new NetworkStartupManager(this);
        network.saveWifiCredential(ssid, password);

        if (network.needsNearbyWifiPermission()) {
            statusText.setText("需要 WiFi 权限后才能连接网络");
            requestPermissions(
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                    REQUEST_NEARBY_WIFI_PERMISSION
            );
            return;
        }

        connectButton.setEnabled(false);
        statusText.setText("正在连接 WiFi：" + ssid);
        new Thread(() -> {
            NetworkStartupManager.PrepareResult result =
                    new NetworkStartupManager(this).connectWifiWithCredential(
                            ssid,
                            password,
                            WIFI_CONNECT_WAIT_SECONDS
                    );
            runOnUiThread(() -> {
                connectButton.setEnabled(true);
                statusText.setText(result.message);
                if (result.isOnline()) {
                    onConnected(result.message);
                    return;
                }
                if (result.needsPermission()) {
                    requestPermissions(
                            new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                            REQUEST_NEARBY_WIFI_PERMISSION
                    );
                    return;
                }
                if (result.needsSystemAddNetwork()) {
                    openSystemAddNetworkPanel(ssid, password);
                }
            });
        }, "存珠机-WiFi连接").start();
    }

    private void clearWifi() {
        new NetworkStartupManager(this).clearWifiCredential();
        ssidInput.setText("");
        passwordInput.setText("");
        pendingSsid = "";
        pendingPassword = "";
        statusText.setText("已清除本机保存的 WiFi 信息，请重新输入");
    }

    private void openSystemAddNetworkPanel(String ssid, String password) {
        try {
            systemPanelOpening = true;
            Intent intent = NetworkStartupManager.buildSystemAddNetworkIntent(this, ssid, password);
            startActivity(intent);
        } catch (Throwable error) {
            systemPanelOpening = false;
            Log.e(TAG, "打开系统联网界面失败", error);
            statusText.setText("无法打开系统联网界面：" + messageOf(error));
        }
    }

    private void onConnected(String message) {
        statusText.setText(message + "，正在继续设备初始化");
        Intent serviceIntent = new Intent(this, DeviceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        getWindow().getDecorView().postDelayed(() -> {
            setResult(RESULT_OK);
            finish();
        }, 800L);
    }

    private void updatePasswordInputType(boolean visible) {
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT
                | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        passwordInput.setSelection(passwordInput.getText().length());
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

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }
}
