package com.gouzhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.network.WifiConfigActivity;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.serial.SerialManager;
import com.gouzhu.util.DeviceUtil;

import java.util.Locale;

/**
 * 购珠机后台设置页。
 *
 * <p>设备状态只在本页显示。正式设备通过控制板 K2 进入；
 * 顾客首页保留的后台按钮仅供当前调试阶段使用。</p>
 */
public class BackendSettingsActivity extends AppCompatActivity {

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
        setContentView(R.layout.activity_backend_settings);
        hideSystemUi();

        networkStatusText = findViewById(R.id.text_network_status);
        mqttStatusText = findViewById(R.id.text_mqtt_status);
        boardStatusText = findViewById(R.id.text_board_status);
        stockText = findViewById(R.id.text_stock_status);
        eventText = findViewById(R.id.text_latest_event);

        TextView deviceText = findViewById(R.id.text_device_info);
        deviceText.setText(getString(
                R.string.device_info_format,
                DeviceUtil.getDeviceId(this),
                DeviceUtil.getAppVersion(this)
        ));

        findViewById(R.id.button_wifi_settings).setOnClickListener(
                view -> startActivity(new Intent(this, WifiConfigActivity.class))
        );
        findViewById(R.id.button_refresh_status).setOnClickListener(view -> requestBoardStatus());
        findViewById(R.id.button_exit_backend).setOnClickListener(view -> finish());
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
        requestBoardStatus();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void requestBoardStatus() {
        boolean sent = SerialManager.get(this).sendCommand(0x21, 0L, false);
        Toast.makeText(
                this,
                sent ? R.string.status_request_sent : R.string.board_not_connected,
                Toast.LENGTH_SHORT
        ).show();
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
            case 0x27:
                eventText.setText(R.string.backend_entered_by_k2);
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
