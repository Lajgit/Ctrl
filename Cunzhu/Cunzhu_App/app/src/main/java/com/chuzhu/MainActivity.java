package com.chuzhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.chuzhu.activation.ActivationActivity;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.data.DepositSession;
import com.chuzhu.device.DeviceService;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.device.DeviceUtil;
import com.chuzhu.hardware.SerialMarbleCollectHardwareAdapter;
import com.chuzhu.mqtt.DeviceStatusReporter;
import com.chuzhu.mqtt.MqttManager;
import com.chuzhu.serial.BoardSerialPort;

public class MainActivity extends AppCompatActivity {

    private TextView activationText;
    private TextView deviceNoText;
    private TextView mqttText;
    private TextView serialText;
    private TextView runningText;
    private TextView operationText;
    private TextView maximumText;
    private TextView actualText;
    private TextView errorText;
    private boolean receiverRegistered;
    private String activationStatusMessage = "等待激活状态";
    private String mqttStatusMessage = "未连接";
    private String serviceStatusMessage = "服务尚未上报状态";
    private String lastRuntimeMessage = "";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                String key = intent.getStringExtra("key");
                String value = intent.getStringExtra("value");
                updateRuntimeMessage(key, value);
            }
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        bindViews();
        startDeviceService();
        if (!new ActivationStore(this).isActivated()) {
            startActivity(new Intent(this, ActivationActivity.class));
        }
        bindButtons();
        refreshStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
        filter.addAction(AppConfig.ACTION_DEPOSIT_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        refreshStatus();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void bindViews() {
        activationText = findViewById(R.id.text_activation_state);
        deviceNoText = findViewById(R.id.text_device_no);
        mqttText = findViewById(R.id.text_mqtt_state);
        serialText = findViewById(R.id.text_serial_state);
        runningText = findViewById(R.id.text_running_status);
        operationText = findViewById(R.id.text_operation_no);
        maximumText = findViewById(R.id.text_maximum_quantity);
        actualText = findViewById(R.id.text_actual_quantity);
        errorText = findViewById(R.id.text_last_error);
    }

    private void bindButtons() {
        findViewById(R.id.button_open_serial).setOnClickListener(v -> {
            BoardSerialPort.get(this).open();
            refreshStatus();
        });
        findViewById(R.id.button_close_serial).setOnClickListener(v -> {
            BoardSerialPort.get(this).close();
            refreshStatus();
        });
        findViewById(R.id.button_sim_start).setOnClickListener(v -> startLocalSimulation());
        findViewById(R.id.button_sim_add).setOnClickListener(v -> {
            // 仅本地调试使用：模拟控制板上报收珠数量 +1，不写入正式 MQTT 终态。
            SerialMarbleCollectHardwareAdapter.get(this).simulateCountIncrement();
            refreshStatus();
        });
        findViewById(R.id.button_sim_finish).setOnClickListener(v -> {
            // 仅本地调试使用：模拟控制板结束当前本地收珠任务。
            SerialMarbleCollectHardwareAdapter.get(this).simulateFinish();
            DeviceStateRepository.get(this).markIdle();
            refreshStatus();
        });
        findViewById(R.id.button_report_status).setOnClickListener(v -> {
            new DeviceStatusReporter(this).report();
            refreshStatus();
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

    private void startLocalSimulation() {
        SerialMarbleCollectHardwareAdapter adapter =
                SerialMarbleCollectHardwareAdapter.get(this);
        boolean started = adapter.startLocalDebugCollect(
                20,
                new SerialMarbleCollectHardwareAdapter.Listener() {
                    @Override
                    public void onCountChanged(int actualQuantity) {
                        updateLocalDebugSession(DepositSession.STATE_COLLECTING, actualQuantity, "");
                    }

                    @Override
                    public void onFinished(int actualQuantity) {
                        updateLocalDebugSession(DepositSession.STATE_FINISHED, actualQuantity, "");
                    }

                    @Override
                    public void onFault(String errorCode, String errorMessage, int actualQuantity) {
                        updateLocalDebugSession(DepositSession.STATE_FAULT, actualQuantity, errorMessage);
                    }
                }
        );
        if (started) {
            updateLocalDebugSession(DepositSession.STATE_COLLECTING, 0, "");
            DeviceStateRepository.get(this).markCollecting();
        } else {
            DeviceStateRepository.get(this).markFault("本地模拟启动失败，可能已有收珠任务");
        }
        refreshStatus();
    }

    private void updateLocalDebugSession(String state, int actualQuantity, String error) {
        DepositSession session = new DepositSession();
        session.messageId = "LOCAL_DEBUG";
        session.operationNo = "LOCAL_DEBUG";
        session.maximumQuantity = 20;
        session.actualQuantity = actualQuantity;
        session.state = state;
        session.localDebug = true;
        session.startedAt = System.currentTimeMillis();
        session.updatedAt = session.startedAt;
        session.errorMessage = error == null ? "" : error;
        new com.chuzhu.data.HardwareSessionStore(this).save(session);
        refreshStatus();
    }

    private void updateRuntimeMessage(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String text = value.trim();
        if ("mqtt".equals(key)) {
            mqttStatusMessage = text;
        } else if ("activation".equals(key)) {
            activationStatusMessage = text;
        } else if ("service".equals(key)) {
            serviceStatusMessage = text;
        }
        if (text.contains("失败") || text.contains("错误") || text.contains("断开")) {
            lastRuntimeMessage = text;
        }
    }

    private void refreshStatus() {
        ActivationStore activationStore = new ActivationStore(this);
        DeviceStateRepository stateRepository = DeviceStateRepository.get(this);
        DepositSession session = stateRepository.getSession();
        int runningStatus = stateRepository.getRunningStatus();
        activationText.setText("激活\n" + (activationStore.isActivated() ? "已激活" : "未激活")
                + " · " + activationStatusMessage);
        deviceNoText.setText("设备号\n" + emptyAsDash(DeviceUtil.getDeviceNo(this)));
        mqttText.setText("MQTT\n" + (MqttManager.get(this).isConnected() ? "已连接" : mqttStatusMessage));
        serialText.setText("控制板串口\n" + AppConfig.DEFAULT_BOARD_SERIAL_PORT
                + " · " + AppConfig.DEFAULT_BOARD_BAUD_RATE + "bps · "
                + (BoardSerialPort.get(this).isOpen() ? "已打开" : "未打开"));
        runningText.setText("运行状态\n" + runningStatus + " · " + describeRunningStatus(runningStatus)
                + " · " + serviceStatusMessage);
        operationText.setText("业务单号\n" + (session == null ? "-" : emptyAsDash(session.operationNo)));
        maximumText.setText("最大收珠数\n" + (session == null ? 0 : session.maximumQuantity));
        actualText.setText("已确认收珠\n" + (session == null ? 0 : session.actualQuantity));
        String error = stateRepository.getLastError();
        if ((error == null || error.isEmpty()) && session != null) {
            error = session.errorMessage;
        }
        if (error == null || error.isEmpty()) {
            error = BoardSerialPort.get(this).getLastError();
        }
        if ((error == null || error.isEmpty()) && !lastRuntimeMessage.isEmpty()) {
            error = lastRuntimeMessage;
        }
        errorText.setText("最后错误\n" + (error == null || error.isEmpty() ? "无" : error));
    }

    private static String describeRunningStatus(int status) {
        switch (status) {
            case AppConfig.STATUS_IDLE:
                return "空闲";
            case AppConfig.STATUS_FAULT:
                return "故障";
            case AppConfig.STATUS_MAINTENANCE:
                return "维护";
            case AppConfig.STATUS_UPGRADING:
                return "升级";
            case AppConfig.STATUS_COLLECTING:
                return "收珠中";
            default:
                return "未知";
        }
    }

    private static String emptyAsDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }
}
