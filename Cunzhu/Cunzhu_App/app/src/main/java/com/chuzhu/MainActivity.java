package com.chuzhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.chuzhu.activation.ActivationActivity;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.device.DeviceService;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.member.MemberDepositRepository;
import com.chuzhu.member.QrCodeUtil;
import com.chuzhu.mqtt.MqttManager;
import com.chuzhu.serial.BoardSerialPort;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView headerStatusText;
    private TextView mainHintText;
    private ImageView qrImage;
    private TextView qrPlaceholderText;
    private TextView memberNoText;
    private TextView memberBalanceText;
    private TextView memberLimitText;
    private TextView actualQuantityText;
    private TextView sessionStateText;
    private TextView operationText;
    private TextView deviceStatusText;
    private TextView errorText;
    private MaterialButton startButton;
    private MaterialButton refreshButton;
    private MaterialButton cancelButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MemberDepositStore memberStore;
    private boolean receiverRegistered;
    private boolean autoSessionRequested;
    private String mqttStatusMessage = "未连接";
    private String serviceStatusMessage = "服务尚未上报状态";
    private String serialStatusMessage = "串口未上报状态";
    private String lastRuntimeMessage = "";
    private String lastQrContent = "";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                updateRuntimeMessage(
                        intent.getStringExtra("key"),
                        intent.getStringExtra("value")
                );
            }
            refreshStatus();
            maybeRestoreOrCreateSession();
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
        memberStore = new MemberDepositStore(this);
        bindViews();
        bindButtons();
        startDeviceService();
        if (!new ActivationStore(this).isActivated()) {
            startActivity(new Intent(this, ActivationActivity.class));
        }
        refreshStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
        filter.addAction(AppConfig.ACTION_DEPOSIT_STATE);
        filter.addAction(AppConfig.ACTION_MEMBER_DEPOSIT_SESSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        refreshStatus();
        maybeRestoreOrCreateSession();
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
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        headerStatusText = findViewById(R.id.text_header_status);
        mainHintText = findViewById(R.id.text_main_hint);
        qrImage = findViewById(R.id.image_qr);
        qrPlaceholderText = findViewById(R.id.text_qr_placeholder);
        memberNoText = findViewById(R.id.text_member_no);
        memberBalanceText = findViewById(R.id.text_member_balance);
        memberLimitText = findViewById(R.id.text_member_limit);
        actualQuantityText = findViewById(R.id.text_actual_quantity);
        sessionStateText = findViewById(R.id.text_session_state);
        operationText = findViewById(R.id.text_operation_no);
        deviceStatusText = findViewById(R.id.text_device_status);
        errorText = findViewById(R.id.text_last_error);
        startButton = findViewById(R.id.button_start_deposit);
        refreshButton = findViewById(R.id.button_refresh_session);
        cancelButton = findViewById(R.id.button_cancel_session);
    }

    private void bindButtons() {
        startButton.setOnClickListener(v -> startMemberDeposit());
        refreshButton.setOnClickListener(v -> refreshMemberSession());
        cancelButton.setOnClickListener(v -> cancelCurrentSession());
    }

    private void startDeviceService() {
        Intent intent = new Intent(this, DeviceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void maybeRestoreOrCreateSession() {
        if (!new ActivationStore(this).isActivated()) {
            return;
        }
        if (autoSessionRequested) {
            return;
        }
        MemberDepositStore.Snapshot snapshot = memberStore.load();
        if (snapshot.hasSession() && (snapshot.isBound() || snapshot.hasQrContent())) {
            autoSessionRequested = true;
            return;
        }
        autoSessionRequested = true;
        requestSession(false);
    }

    private void refreshMemberSession() {
        autoSessionRequested = true;
        requestSession(true);
    }

    private void requestSession(boolean forceNew) {
        if (!new ActivationStore(this).isActivated()) {
            showError("设备未激活，不能创建会员存珠二维码");
            return;
        }
        memberStore.setMessage(forceNew ? "正在刷新二维码" : "正在恢复或创建二维码");
        worker.execute(() -> {
            try {
                MemberDepositRepository repository = new MemberDepositRepository(this);
                if (forceNew) {
                    MemberDepositStore.Snapshot old = memberStore.load();
                    if (old.hasSession()) {
                        repository.cancelSession(old.sessionId);
                    }
                    memberStore.clearSession();
                }
                MemberDepositStore.Snapshot session = forceNew
                        ? repository.createSession()
                        : repository.currentOrCreateSession();
                if (session == null || !session.hasSession()) {
                    throw new IllegalStateException("平台未返回有效会员存珠 Session");
                }
                memberStore.saveSession(session);
            } catch (Throwable error) {
                autoSessionRequested = false;
                showError("创建会员存珠二维码失败：" + messageOf(error));
            }
        });
    }

    private void cancelCurrentSession() {
        MemberDepositStore.Snapshot snapshot = memberStore.load();
        if (!snapshot.hasSession()) {
            memberStore.clearSession();
            refreshStatus();
            return;
        }
        memberStore.setMessage("正在返回待机");
        worker.execute(() -> {
            try {
                new MemberDepositRepository(this).cancelSession(snapshot.sessionId);
            } catch (Throwable ignored) {
                // 取消失败不阻塞设备本地回到待扫码界面，下次会重新创建或恢复服务端 Session。
            }
            memberStore.clearSession();
            autoSessionRequested = false;
            mainHandler.post(this::maybeRestoreOrCreateSession);
        });
    }

    private void startMemberDeposit() {
        MemberDepositStore.Snapshot snapshot = memberStore.load();
        if (!snapshot.isBound()) {
            showError("请先让会员扫码绑定后再开始存珠");
            return;
        }
        if (DeviceStateRepository.get(this).getRunningStatus() == AppConfig.STATUS_COLLECTING) {
            showError("当前正在收珠，不能重复开始");
            return;
        }
        String requestNo = memberStore.loadOrCreateClientRequestNo(snapshot.sessionId);
        worker.execute(() -> {
            try {
                MemberDepositRepository.OperationSnapshot operation =
                        new MemberDepositRepository(this).startMemberDeposit(requestNo, snapshot.sessionId);
                memberStore.markWaitingCommand(operation.operationNo, operation.referenceNo);
            } catch (Throwable error) {
                showError("开始存珠失败：" + messageOf(error));
            }
        });
    }

    private void updateRuntimeMessage(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String text = value.trim();
        if ("mqtt".equals(key)) {
            mqttStatusMessage = text;
        } else if ("service".equals(key)) {
            serviceStatusMessage = text;
        } else if ("serial".equals(key)) {
            serialStatusMessage = text;
        }
        if (text.contains("失败") || text.contains("错误") || text.contains("断开")) {
            lastRuntimeMessage = text;
        }
    }

    private void refreshStatus() {
        MemberDepositStore.Snapshot member = memberStore.load();
        ActivationStore activationStore = new ActivationStore(this);
        DeviceStateRepository stateRepository = DeviceStateRepository.get(this);
        DepositSession hardwareSession = stateRepository.getSession();
        int runningStatus = stateRepository.getRunningStatus();
        boolean activated = activationStore.isActivated();
        boolean mqttConnected = MqttManager.get(this).isConnected();
        boolean serialOpen = BoardSerialPort.get(this).isOpen();
        boolean collecting = runningStatus == AppConfig.STATUS_COLLECTING
                || (hardwareSession != null && DepositSession.STATE_COLLECTING.equals(hardwareSession.state));

        headerStatusText.setText(headerStatus(activated, member, collecting));
        mainHintText.setText(mainHint(member, collecting));
        updateQr(member, collecting);

        memberNoText.setText("会员：" + memberName(member));
        memberBalanceText.setText("余额：" + emptyAsDash(member.availableQuantity) + " " + member.unitName);
        memberLimitText.setText("上限：" + emptyAsDash(member.maximumDepositQuantity) + " " + member.unitName);
        int actual = hardwareSession == null ? 0 : hardwareSession.actualQuantity;
        actualQuantityText.setText("本次已确认：" + actual + " 颗");

        sessionStateText.setText("会话：" + describeMemberStatus(member.status)
                + " · " + emptyAsDash(member.message));
        String operationNo = hardwareSession != null && !empty(hardwareSession.operationNo)
                ? hardwareSession.operationNo
                : member.operationNo;
        operationText.setText("业务单号：" + emptyAsDash(operationNo));
        deviceStatusText.setText("设备：" + describeRunningStatus(runningStatus)
                + " · MQTT " + (mqttConnected ? "已连接" : mqttStatusMessage)
                + " · 串口 " + (serialOpen ? "已打开" : serialStatusMessage)
                + " · " + serviceStatusMessage);

        String error = stateRepository.getLastError();
        if ((error == null || error.isEmpty()) && hardwareSession != null) {
            error = hardwareSession.errorMessage;
        }
        if (error == null || error.isEmpty()) {
            error = BoardSerialPort.get(this).getLastError();
        }
        if ((error == null || error.isEmpty()) && !lastRuntimeMessage.isEmpty()) {
            error = lastRuntimeMessage;
        }
        if (error == null || error.isEmpty()) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setVisibility(View.VISIBLE);
            errorText.setText("最后错误：" + error);
        }

        boolean canStart = activated
                && mqttConnected
                && serialOpen
                && member.isBound()
                && !collecting
                && !MemberDepositStore.STATUS_WAITING_COMMAND.equals(member.status)
                && !MemberDepositStore.STATUS_STARTING.equals(member.status);
        startButton.setEnabled(canStart);
        startButton.setText(collecting ? "正在收珠" : "开始存珠");
        refreshButton.setEnabled(activated && !collecting);
        cancelButton.setEnabled(!collecting && member.hasSession());
    }

    private void updateQr(MemberDepositStore.Snapshot member, boolean collecting) {
        if (collecting) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("正在收珠\n请等待完成");
            return;
        }
        if (member.isBound()) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("会员已绑定\n请点击开始存珠");
            return;
        }
        if (!member.hasQrContent()) {
            lastQrContent = "";
            qrImage.setImageBitmap(null);
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("正在获取二维码");
            return;
        }
        try {
            if (!member.qrContent.equals(lastQrContent)) {
                Bitmap bitmap = QrCodeUtil.create(member.qrContent, 420);
                qrImage.setImageBitmap(bitmap);
                lastQrContent = member.qrContent;
            }
            qrImage.setVisibility(View.VISIBLE);
            qrPlaceholderText.setVisibility(View.GONE);
        } catch (Throwable error) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("二维码生成失败");
            showError("二维码生成失败：" + messageOf(error));
        }
    }

    private String headerStatus(boolean activated, MemberDepositStore.Snapshot member, boolean collecting) {
        if (!activated) {
            return "未激活";
        }
        if (collecting) {
            return "收珠中";
        }
        if (member.isBound()) {
            return "已绑定";
        }
        if (member.hasQrContent()) {
            return "待扫码";
        }
        return "待机";
    }

    private String mainHint(MemberDepositStore.Snapshot member, boolean collecting) {
        if (collecting) {
            return "请投入弹珠，系统正在计数";
        }
        if (member.isBound()) {
            return "会员已绑定，确认后点击开始存珠";
        }
        if (member.hasQrContent()) {
            return "请使用微信扫描二维码";
        }
        return "正在准备会员存珠二维码";
    }

    private String memberName(MemberDepositStore.Snapshot member) {
        if (!member.isBound()) {
            return "等待扫码";
        }
        if (!member.memberNickname.isEmpty() && !member.memberNo.isEmpty()) {
            return member.memberNickname + "（" + member.memberNo + "）";
        }
        if (!member.memberNickname.isEmpty()) {
            return member.memberNickname;
        }
        return emptyAsDash(member.memberNo);
    }

    private void showError(String message) {
        memberStore.setMessage(message);
        mainHandler.post(() -> {
            lastRuntimeMessage = message;
            refreshStatus();
        });
    }

    private static String describeMemberStatus(String status) {
        if (MemberDepositStore.STATUS_WAITING_SCAN.equals(status)) {
            return "等待扫码";
        }
        if (MemberDepositStore.STATUS_BOUND.equals(status)) {
            return "会员已绑定";
        }
        if (MemberDepositStore.STATUS_STARTING.equals(status)) {
            return "正在开始";
        }
        if (MemberDepositStore.STATUS_WAITING_COMMAND.equals(status)) {
            return "等待收珠指令";
        }
        if (MemberDepositStore.STATUS_EMPTY.equals(status)) {
            return "未创建";
        }
        return emptyAsDash(status);
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

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable cursor = error;
        String message = "";
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().trim().isEmpty()) {
                message = cursor.getMessage().trim();
            }
            cursor = cursor.getCause();
        }
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
