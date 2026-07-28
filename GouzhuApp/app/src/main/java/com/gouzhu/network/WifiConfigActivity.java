package com.gouzhu.network;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.R;
import com.gouzhu.service.DeviceService;

/**
 * 购珠机本地 WiFi 配置页。
 *
 * <p>当前仅实现设备屏幕直接输入 SSID 和密码，避免在业务未确认前额外引入
 * 批量配网广播、热点网页等功能。</p>
 */
public class WifiConfigActivity extends AppCompatActivity {

    private EditText ssidInput;
    private EditText passwordInput;
    private TextView statusText;
    private Button connectButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_config);
        hideSystemUi();

        ssidInput = findViewById(R.id.input_wifi_ssid);
        passwordInput = findViewById(R.id.input_wifi_password);
        statusText = findViewById(R.id.text_wifi_status);
        connectButton = findViewById(R.id.button_connect_wifi);

        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        ssidInput.setText(WifiSupport.getSavedSsid(this));
        passwordInput.setText(WifiSupport.getSavedPassword(this));

        connectButton.setOnClickListener(view -> connectWifi());
        findViewById(R.id.button_clear_wifi).setOnClickListener(view -> {
            WifiSupport.clear(this);
            ssidInput.setText("");
            passwordInput.setText("");
            statusText.setText(R.string.wifi_config_cleared);
        });
    }

    private void connectWifi() {
        String ssid = ssidInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (ssid.isEmpty()) {
            Toast.makeText(this, R.string.wifi_ssid_required, Toast.LENGTH_SHORT).show();
            return;
        }

        connectButton.setEnabled(false);
        statusText.setText(getString(R.string.wifi_connecting, ssid));

        new Thread(() -> {
            boolean invoked = WifiSupport.connectWifi(this, ssid, password);
            boolean connected = invoked && WifiSupport.waitForInternet(this, 60);

            runOnUiThread(() -> {
                connectButton.setEnabled(true);
                if (!connected) {
                    statusText.setText(R.string.wifi_connect_failed);
                    return;
                }

                WifiSupport.save(this, ssid, password);
                statusText.setText(getString(R.string.wifi_connect_success, ssid));

                Intent serviceIntent = new Intent(this, DeviceService.class);
                startForegroundService(serviceIntent);

                getWindow().getDecorView().postDelayed(this::finish, 1000L);
            });
        }, "购珠机-WiFi连接").start();
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
}
