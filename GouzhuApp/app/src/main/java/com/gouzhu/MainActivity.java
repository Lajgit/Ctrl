package com.gouzhu;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.gouzhu.network.WifiConfigActivity;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.service.DeviceService;
import com.gouzhu.util.DeviceUtil;

import java.util.Locale;

/**
 * 购珠机主界面。
 *
 * <p>当前完成固定竖屏首页、设备状态显示和控制板事件接收。套餐按钮只保存当前选择，
 * 支付接口尚未提供，因此不会擅自触发吐珠。</p>
 */
public class MainActivity extends AppCompatActivity {

    private TextView selectedPackageText;
    private TextView networkStatusText;
    private TextView mqttStatusText;
    private TextView boardStatusText;
    private TextView stockText;
    private TextView eventText;

    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            if (AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                updateServiceStatus(
                        intent.getStringExtra("key"),
                        intent.getStringExtra("value")
                );
                return;
            }

            if (AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                handleBoardEvent(
                        intent.getIntExtra("code2", -1),
                        intent.getLongExtra("data", 0L),
                        intent.getIntExtra("expandCode", 0)
                );
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemUi();
        bindViews();
        bindActions();
        requestNotificationPermission();
        startDeviceService();

        TextView deviceText = findViewById(R.id.text_device_info);
        deviceText.setText(getString(
                R.string.device_info_format,
                DeviceUtil.getDeviceId(this),
                DeviceUtil.getAppVersion(this)
        ));

        if (!WifiSupport.hasSavedWifi(this)) {
            openWifiConfig();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStatusReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (WifiSupport.isInternetConnected(this)) {
            networkStatusText.setText(getString(
                    R.string.network_connected_format,
                    WifiSupport.getCurrentSsid(this)
            ));
        }
        SerialManager.get(this).sendCommand(0x21, 0L, false);
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void bindViews() {
        selectedPackageText = findViewById(R.id.text_selected_package);
        networkStatusText = findViewById(R.id.text_network_status);
        mqttStatusText = findViewById(R.id.text_mqtt_status);
        boardStatusText = findViewById(R.id.text_board_status);
        stockText = findViewById(R.id.text_stock_status);
        eventText = findViewById(R.id.text_latest_event);
    }

    private void bindActions() {
        bindPackageButton(R.id.button_package_1, 1, 1);
        bindPackageButton(R.id.button_package_5, 5, 5);
        bindPackageButton(R.id.button_package_10, 10, 10);
        bindPackageButton(R.id.button_package_20, 20, 20);
        bindPackageButton(R.id.button_package_50, 50, 50);
        bindPackageButton(R.id.button_package_100, 100, 100);

        findViewById(R.id.button_wifi_settings).setOnClickListener(view -> openWifiConfig());
        findViewById(R.id.button_refresh_status).setOnClickListener(view -> {
            boolean sent = SerialManager.get(this).sendCommand(0x21, 0L, false);
            Toast.makeText(
                    this,
                    sent ? R.string.status_request_sent : R.string.board_not_connected,
                    Toast.LENGTH_SHORT
            ).show();
        });
    }

    private void bindPackageButton(int viewId, int beadCount, int priceYuan) {
        findViewById(viewId).setOnClickListener(view -> {
            selectedPackageText.setText(getString(
                    R.string.package_selected_format,
                    beadCount,
                    priceYuan
            ));
            eventText.setText(R.string.payment_api_pending);
        });
    }

    private void startDeviceService() {
        Intent intent = new Intent(this, DeviceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void openWifiConfig() {
        startActivity(new Intent(this, WifiConfigActivity.class));
    }

    private void registerStatusReceiver() {
        if (receiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
        filter.addAction(AppConfig.ACTION_BOARD_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void updateServiceStatus(String key, String value) {
        String safeValue = value == null ? "" : value;
        if ("network".equals(key)) {
            networkStatusText.setText(safeValue);
        } else if ("mqtt".equals(key) || "activation".equals(key)) {
            mqttStatusText.setText(safeValue);
        } else if ("serial".equals(key)) {
            boardStatusText.setText(safeValue);
        } else if ("service".equals(key)) {
            eventText.setText(safeValue);
        }
    }

    private void handleBoardEvent(int code2, long data, int expandCode) {
        switch (code2) {
            case 0x00:
                boardStatusText.setText(getString(
                        R.string.board_version_format,
                        formatPackedVersion(data)
                ));
                break;
            case 0x01:
                eventText.setText(R.string.board_bead_output_feedback);
                break;
            case 0x02:
                eventText.setText(R.string.board_coin_input);
                break;
            case 0x04:
                int billType = (int) ((data >>> 16) & 0xFF);
                int billState = (int) (data & 0xFF);
                eventText.setText(getString(
                        R.string.board_bill_accepted_format,
                        billType,
                        billState
                ));
                break;
            case 0x07:
                eventText.setText(getString(R.string.board_output_timeout_format, data));
                break;
            case 0x20:
                eventText.setText(getString(
                        R.string.board_price_format,
                        data / 100.0
                ));
                break;
            case 0x21:
                stockText.setText(getString(R.string.stock_format, data));
                break;
            case 0x22:
                eventText.setText(getString(R.string.pending_bead_format, data));
                break;
            case 0x23:
                eventText.setText(getString(
                        R.string.credit_format,
                        data / 100.0
                ));
                break;
            case 0x24:
                stockText.setText(getString(R.string.low_stock_format, data));
                break;
            case 0x25:
                stockText.setText(R.string.board_empty);
                eventText.setText(getString(R.string.pending_bead_format, data));
                break;
            case 0x26:
                stockText.setText(getString(R.string.refilled_format, data));
                break;
            default:
                eventText.setText(getString(
                        R.string.board_event_format,
                        code2,
                        data,
                        expandCode
                ));
                break;
        }
    }

    private String formatPackedVersion(long value) {
        return String.format(
                Locale.ROOT,
                "%d.%d.%d.%d",
                (value >>> 24) & 0xFF,
                (value >>> 16) & 0xFF,
                (value >>> 8) & 0xFF,
                value & 0xFF
        );
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    100
            );
        }
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
}
