package com.gouzhu;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.activation.ActivationLogStore;
import com.gouzhu.network.WifiConfigActivity;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.scanner.ReverseScannerManager;
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

    private static final long BOARD_POLL_INTERVAL_MS = 2_000L;
    private static final long BOARD_RESPONSE_TIMEOUT_MS = 5_000L;
    private static final String STATUS_PREFS = "backend_status";
    private static final String KEY_PENDING_KNOWN = "pending_known";
    private static final String KEY_PENDING_BEADS = "pending_beads";

    private TextView networkStatusText;
    private TextView mqttStatusText;
    private TextView boardStatusText;
    private TextView reverseScannerStatusText;
    private TextView stockText;
    private TextView pendingText;
    private TextView eventText;
    private boolean receiverRegistered;
    private boolean boardResponsive;
    private long boardMonitorStartedAt;
    private long lastBoardResponseAt;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());

    private final Runnable boardStatusMonitor = new Runnable() {
        @Override
        public void run() {
            SerialManager serial = SerialManager.get(BackendSettingsActivity.this);
            if (!serial.isOpen()) {
                setBoardDisconnected(R.string.board_serial_not_connected);
            } else {
                // ttyS5 即使控制板物理断开仍可写入，因此必须通过版本应答判断在线状态。
                serial.sendCommand(0x00, 0L, false);
                evaluateBoardResponseTimeout();
            }
            refreshReverseScannerStatus();
            statusHandler.postDelayed(this, BOARD_POLL_INTERVAL_MS);
        }
    };

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

            if (AppConfig.ACTION_REVERSE_SCANNER_EVENT.equals(intent.getAction())) {
                handleReverseScannerEvent(intent);
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
        reverseScannerStatusText = findViewById(R.id.text_reverse_scanner_status);
        stockText = findViewById(R.id.text_stock_status);
        pendingText = findViewById(R.id.text_pending_status);
        eventText = findViewById(R.id.text_latest_event);

        restorePendingStatus();

        TextView deviceText = findViewById(R.id.text_device_info);
        deviceText.setText(getString(
                R.string.device_info_format,
                DeviceUtil.getDeviceId(this),
                DeviceUtil.getAppVersion(this)
        ));

        findViewById(R.id.button_wifi_settings).setOnClickListener(
                view -> startActivity(new Intent(this, WifiConfigActivity.class))
        );
        findViewById(R.id.button_refresh_status).setOnClickListener(view -> {
            requestBoardStatus(true);
            ReverseScannerManager.get(this).open();
            refreshReverseScannerStatus();
        });
        findViewById(R.id.button_activation_logs).setOnClickListener(
                view -> showActivationLogs()
        );
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

        startBoardStatusMonitor();
        requestBoardStatus(false);
        refreshReverseScannerStatus();
    }

    @Override
    protected void onStop() {
        statusHandler.removeCallbacks(boardStatusMonitor);
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void startBoardStatusMonitor() {
        statusHandler.removeCallbacks(boardStatusMonitor);
        boardResponsive = false;
        lastBoardResponseAt = 0L;
        boardMonitorStartedAt = SystemClock.elapsedRealtime();
        boardStatusText.setText(R.string.board_checking_response);
        statusHandler.post(boardStatusMonitor);
    }

    private void requestBoardStatus(boolean showToast) {
        SerialManager serial = SerialManager.get(this);
        boolean versionSent = serial.sendCommand(0x00, 0L, false);
        boolean statusSent = serial.sendCommand(0x21, 0L, false);
        boolean sent = versionSent || statusSent;

        if (!sent) {
            setBoardDisconnected(R.string.board_serial_not_connected);
        }

        if (showToast) {
            Toast.makeText(
                    this,
                    sent ? R.string.status_request_sent : R.string.board_not_connected,
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void evaluateBoardResponseTimeout() {
        long now = SystemClock.elapsedRealtime();
        long reference = lastBoardResponseAt > 0L
                ? lastBoardResponseAt
                : boardMonitorStartedAt;
        if ((now - reference) >= BOARD_RESPONSE_TIMEOUT_MS) {
            setBoardDisconnected(R.string.board_disconnected);
        }
    }

    private void markBoardResponsive() {
        lastBoardResponseAt = SystemClock.elapsedRealtime();
        if (!boardResponsive) {
            boardResponsive = true;
            boardStatusText.setText(R.string.board_connected_waiting_version);
        }
    }

    private void setBoardDisconnected(int textResId) {
        boardResponsive = false;
        boardStatusText.setText(textResId);
    }

    private void refreshReverseScannerStatus() {
        ReverseScannerManager scanner = ReverseScannerManager.get(this);
        if (scanner.isOpen()) {
            reverseScannerStatusText.setText(getString(
                    R.string.reverse_scanner_connected_format,
                    AppConfig.REVERSE_SCANNER_DEVICE,
                    AppConfig.REVERSE_SCANNER_BAUD_RATE
            ));
        } else {
            reverseScannerStatusText.setText(getString(
                    R.string.reverse_scanner_disconnected_format,
                    AppConfig.REVERSE_SCANNER_DEVICE
            ));
        }
    }

    private void handleReverseScannerEvent(Intent intent) {
        String event = intent.getStringExtra(ReverseScannerManager.EXTRA_EVENT);
        String message = intent.getStringExtra(ReverseScannerManager.EXTRA_MESSAGE);
        String safeMessage = message == null || message.trim().isEmpty()
                ? getString(R.string.reverse_scanner_event_unknown)
                : message;
        reverseScannerStatusText.setText(safeMessage);

        if (!ReverseScannerManager.EVENT_CONNECTED.equals(event)
                && !ReverseScannerManager.EVENT_DISCONNECTED.equals(event)) {
            eventText.setText(safeMessage);
        }
    }

    private void registerStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
        filter.addAction(AppConfig.ACTION_BOARD_EVENT);
        filter.addAction(AppConfig.ACTION_REVERSE_SCANNER_EVENT);
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
            /*
             * “ttyS5 已打开”不能代表控制板在线。只有收到合法控制板帧才显示已连接；
             * 串口打开失败或读写异常仍立即显示为断开。
             */
            if (safeValue.contains("失败")
                    || safeValue.contains("异常")
                    || safeValue.contains("未连接")) {
                setBoardDisconnected(R.string.board_serial_not_connected);
            } else if (!boardResponsive) {
                boardStatusText.setText(R.string.board_waiting_response);
            }
        } else if ("service".equals(key)) {
            eventText.setText(safeValue);
        }
    }

    private void handleBoardEvent(int code2, long data, int expandCode) {
        markBoardResponsive();

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
                updatePendingStatus(data);
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
                updatePendingStatus(data);
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
                updatePendingStatus(data);
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

    private void restorePendingStatus() {
        SharedPreferences preferences = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_PENDING_KNOWN, false)) {
            pendingText.setText(R.string.pending_status_unknown);
            return;
        }
        updatePendingText(preferences.getLong(KEY_PENDING_BEADS, 0L));
    }

    private void updatePendingStatus(long pendingBeads) {
        long safeValue = Math.max(0L, pendingBeads);
        updatePendingText(safeValue);
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_KNOWN, true)
                .putLong(KEY_PENDING_BEADS, safeValue)
                .apply();
    }

    private void updatePendingText(long pendingBeads) {
        if (pendingBeads > 0L) {
            pendingText.setText(getString(
                    R.string.pending_status_warning_format,
                    pendingBeads
            ));
        } else {
            pendingText.setText(getString(R.string.pending_status_format, 0L));
        }
    }

    private void showActivationLogs() {
        TextView logView = new TextView(this);
        int padding = dpToPx(16);
        logView.setPadding(padding, padding, padding, padding);
        logView.setTextSize(14f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        updateActivationLogView(logView);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(logView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.activation_log_title)
                .setView(scrollView)
                .setPositiveButton(
                        R.string.activation_log_copy,
                        (ignored, which) -> copyActivationLogs(logView.getText().toString())
                )
                .setNeutralButton(R.string.activation_log_clear, null)
                .setNegativeButton(R.string.activation_log_close, null)
                .create();

        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> {
                    ActivationLogStore.clear(this);
                    updateActivationLogView(logView);
                    Toast.makeText(
                            this,
                            R.string.activation_log_cleared,
                            Toast.LENGTH_SHORT
                    ).show();
                }));
        dialog.show();
    }

    private void updateActivationLogView(TextView logView) {
        String logs = ActivationLogStore.read(this);
        logView.setText(logs.isEmpty() ? getString(R.string.activation_log_empty) : logs);
    }

    private void copyActivationLogs(String logs) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("购珠机注册激活日志", logs));
        Toast.makeText(this, R.string.activation_log_copied, Toast.LENGTH_SHORT).show();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
