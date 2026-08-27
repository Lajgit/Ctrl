package com.chuzhu;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.chuzhu.activation.BootstrapRepository;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.data.DepositSession;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.device.DeviceService;
import com.chuzhu.device.DeviceStateRepository;
import com.chuzhu.member.MemberDepositRepository;
import com.chuzhu.member.QrCodeUtil;
import com.chuzhu.mqtt.MqttManager;
import com.chuzhu.network.NetworkStartupManager;
import com.chuzhu.network.WifiConfigActivity;
import com.chuzhu.serial.BoardSerialPort;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯存珠机正式会员存珠首页。
 *
 * <p>启动顺序固定为：联网检查 -> 激活/重新激活 -> MQTT 连接并订阅 -> bootstrap 校验
 * deviceType=3 -> 恢复/创建会员 Session。无网络时绝不能提前进入 enroll/reactivate 或
 * 会员存珠 HTTP。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CunzhuMain";
    private static final int REQUEST_NEARBY_WIFI_PERMISSION = 0x41;
    private static final int SAVED_WIFI_WAIT_SECONDS = 15;
    private static final int START_QUERY_MAX_ATTEMPTS = 3;
    private static final long START_QUERY_RETRY_DELAY_MS = 1500L;
    private static final long MEMBER_IDLE_TIMEOUT_MS = 60_000L;

    private View qrPage;
    private View memberPage;
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
    private final ExecutorService qrWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean networkCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean bootstrapCheckRunning = new AtomicBoolean(false);
    private final Runnable memberIdleTimeoutRunnable = this::handleMemberIdleTimeout;

    private MemberDepositStore memberStore;
    private boolean receiverRegistered;
    private boolean networkCallbackRegistered;
    private boolean autoSessionRequested;
    private boolean deviceServiceStarted;
    private boolean wifiPanelOpening;
    private boolean wifiPermissionRequested;
    private boolean memberPageVisible;
    private boolean memberLogoutRunning;
    private long lastMemberInteractionAt;
    private volatile boolean bootstrapVerified;
    private volatile boolean bootstrapRejected;
    private volatile boolean destroyed;
    private ConnectivityManager.NetworkCallback networkCallback;

    private String mqttStatusMessage = "未连接";
    private String serviceStatusMessage = "服务尚未上报状态";
    private String serialStatusMessage = "串口未上报状态";
    private String networkStatusMessage = "正在检查网络";
    private String lastRuntimeMessage = "";
    private String lastQrContent = "";
    private String qrGeneratingContent = "";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isUiAlive() || intent == null) {
                return;
            }
            if (AppConfig.ACTION_NETWORK_REQUIRED.equals(intent.getAction())) {
                String reason = intent.getStringExtra("reason");
                if (reason != null && !reason.trim().isEmpty()) {
                    networkStatusMessage = reason.trim();
                }
                ensureNetworkBeforeDeviceFlow(true);
                refreshStatus();
                return;
            }
            if (AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
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
        destroyed = false;
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
        refreshStatus();

        /* 首屏先过联网门禁，不能像旧代码一样直接启动 DeviceService 去请求激活接口。 */
        ensureNetworkBeforeDeviceFlow(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStatusReceiver();
        registerNetworkCallback();
        refreshStatus();
        ensureNetworkBeforeDeviceFlow(false);
        maybeRestoreOrCreateSession();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wifiPanelOpening) {
            /* WiFi 配置页或系统网络页返回后，稍等 DHCP/网络验证完成再继续。 */
            mainHandler.postDelayed(() -> {
                if (!isUiAlive()) {
                    return;
                }
                wifiPanelOpening = false;
                ensureNetworkBeforeDeviceFlow(false);
            }, 1000L);
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        unregisterNetworkCallback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        qrWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            /* 会员操作页任意触摸都视为有效操作，重新计算 60 秒无操作退出时间。 */
            resetMemberIdleTimerFromUser();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        MemberDepositStore.Snapshot snapshot = memberStore == null
                ? null
                : memberStore.loadWithoutScheduling();
        if (isMemberLoggedIn(snapshot)) {
            /* Android 返回键在会员页只退出当前会员，不直接关闭存珠机 APP。 */
            cancelCurrentSession(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!isUiAlive() || requestCode != REQUEST_NEARBY_WIFI_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            ensureNetworkBeforeDeviceFlow(false);
        } else {
            /* 拒绝附近 WiFi 权限时仍进入触屏配网页，配网页会继续引导系统联网确认。 */
            openWifiPanel("WiFi 权限未授予，请输入 WiFi 并按提示完成系统确认");
        }
    }

    private void registerStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
        filter.addAction(AppConfig.ACTION_DEPOSIT_STATE);
        filter.addAction(AppConfig.ACTION_MEMBER_DEPOSIT_SESSION);
        filter.addAction(AppConfig.ACTION_NETWORK_REQUIRED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void registerNetworkCallback() {
        if (networkCallbackRegistered) {
            return;
        }
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                checkValidatedNetworkAsync();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    postToMainIfAlive(MainActivity.this::onValidatedNetworkAvailable);
                }
            }

            @Override
            public void onLost(Network network) {
                postToMainIfAlive(() -> {
                    if (!new NetworkStartupManager(MainActivity.this).hasValidatedInternet()) {
                        networkStatusMessage = "网络已断开";
                        autoSessionRequested = false;
                        refreshStatus();
                    }
                });
            }
        };
        try {
            manager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (Throwable error) {
            Log.w(TAG, "注册网络监听失败", error);
        }
    }

    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || networkCallback == null) {
            return;
        }
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (manager != null) {
                manager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {
        }
        networkCallbackRegistered = false;
        networkCallback = null;
    }

    private void checkValidatedNetworkAsync() {
        postToMainIfAlive(() -> {
            if (new NetworkStartupManager(this).hasValidatedInternet()) {
                onValidatedNetworkAvailable();
            }
        });
    }

    private void onValidatedNetworkAvailable() {
        if (!isUiAlive()) {
            return;
        }
        networkStatusMessage = "网络已连接";
        wifiPanelOpening = false;
        ensureNetworkBeforeDeviceFlow(false);
        refreshStatus();
    }

    private void ensureNetworkBeforeDeviceFlow(boolean requestedByService) {
        if (!isUiAlive()) {
            return;
        }
        NetworkStartupManager network = new NetworkStartupManager(this);
        if (network.hasValidatedInternet()) {
            networkStatusMessage = "网络已连接";
            onNetworkReady();
            return;
        }

        if (network.needsNearbyWifiPermission()) {
            networkStatusMessage = "等待 WiFi 权限";
            if (!wifiPermissionRequested) {
                wifiPermissionRequested = true;
                requestPermissions(
                        new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                        REQUEST_NEARBY_WIFI_PERMISSION
                );
            } else if (requestedByService) {
                openWifiPanel("请连接 WiFi 后再激活设备");
            }
            refreshStatus();
            return;
        }

        if (!networkCheckRunning.compareAndSet(false, true)) {
            return;
        }
        networkStatusMessage = network.hasSavedWifiInformation()
                ? "正在尝试连接已保存 WiFi"
                : "未保存 WiFi，准备打开配网页";
        refreshStatus();

        executeWorker("联网检查", () -> {
            NetworkStartupManager.PrepareResult result =
                    new NetworkStartupManager(this)
                            .prepareBeforeActivation(SAVED_WIFI_WAIT_SECONDS);
            postToMainIfAlive(() -> {
                networkCheckRunning.set(false);
                networkStatusMessage = result.message;
                if (result.isOnline()) {
                    onNetworkReady();
                } else if (result.needsPermission()) {
                    if (!wifiPermissionRequested) {
                        wifiPermissionRequested = true;
                        requestPermissions(
                                new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                                REQUEST_NEARBY_WIFI_PERMISSION
                        );
                    } else {
                        openWifiPanel(result.message);
                    }
                } else {
                    openWifiPanel(result.message);
                }
                refreshStatus();
            });
        });
    }

    private void onNetworkReady() {
        if (!isUiAlive() || !new NetworkStartupManager(this).hasValidatedInternet()) {
            return;
        }
        if (!deviceServiceStarted) {
            deviceServiceStarted = true;
            startDeviceService();
        }
        /* 已激活设备也要等待 reactivate 后的新凭证和 MQTT 订阅完成。 */
        maybeRestoreOrCreateSession();
    }

    private void openWifiPanel(String reason) {
        if (!isUiAlive() || wifiPanelOpening) {
            return;
        }
        wifiPanelOpening = true;
        networkStatusMessage = reason == null ? "请连接 WiFi" : reason;
        refreshStatus();

        Intent intent = new Intent(this, WifiConfigActivity.class);
        intent.putExtra(WifiConfigActivity.EXTRA_REASON, networkStatusMessage);
        try {
            startActivity(intent);
        } catch (Throwable error) {
            wifiPanelOpening = false;
            showError("无法打开存珠机 WiFi 配置界面：" + messageOf(error));
        }
    }

    private void bindViews() {
        qrPage = findViewById(R.id.page_qr);
        memberPage = findViewById(R.id.page_member);
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
        cancelButton.setOnClickListener(v -> cancelCurrentSession(false));
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
        if (!isUiAlive()) {
            return;
        }
        if (!new NetworkStartupManager(this).hasValidatedInternet()) {
            autoSessionRequested = false;
            return;
        }
        if (!new ActivationStore(this).isActivated()) {
            autoSessionRequested = false;
            memberStore.setMessage("设备尚未完成激活");
            return;
        }
        if (!MqttManager.get(this).isConnected() || !MqttManager.get(this).isSubscribed()) {
            /*
             * DeviceAppClient 的 HMAC 密钥来自 reactivate 后的新 MQTT password。
             * 必须等 MQTT 使用新凭证完成连接和命令订阅，再创建会员 Session。
             */
            autoSessionRequested = false;
            memberStore.setMessage("设备已激活，正在等待 MQTT 就绪");
            return;
        }
        if (bootstrapRejected) {
            return;
        }
        if (!bootstrapVerified) {
            verifyBootstrapIfNeeded();
            return;
        }
        int runningStatus = DeviceStateRepository.get(this).getRunningStatus();
        if (runningStatus != AppConfig.STATUS_IDLE) {
            /*
             * 收珠、故障、维护等非空闲状态只刷新界面，不自动恢复/创建二维码 Session。
             * 等设备真正回到 IDLE 后由 ACTION_DEPOSIT_STATE 再触发，避免故障广播形成请求风暴。
             */
            autoSessionRequested = false;
            return;
        }
        if (autoSessionRequested) {
            return;
        }
        MemberDepositStore.Snapshot snapshot = memberStore.load();
        if (isMemberLoggedIn(snapshot)
                || (snapshot.hasSession() && snapshot.hasQrContent())) {
            /* 已登录会员的状态可能已经从 BOUND 进入 WAITING_COMMAND，仍不能后台创建新二维码。 */
            autoSessionRequested = true;
            return;
        }
        autoSessionRequested = true;
        requestSession(false);
    }

    private void verifyBootstrapIfNeeded() {
        if (!isUiAlive()
                || bootstrapVerified || bootstrapRejected
                || !bootstrapCheckRunning.compareAndSet(false, true)) {
            return;
        }
        memberStore.setMessage("正在校验存珠机设备类型");
        executeWorker("bootstrap 校验", () -> {
            try {
                new BootstrapRepository(this).requireMarbleDepositMachine();
                postToMainIfAlive(() -> {
                    bootstrapCheckRunning.set(false);
                    bootstrapVerified = true;
                    bootstrapRejected = false;
                    autoSessionRequested = false;
                    refreshStatus();
                    maybeRestoreOrCreateSession();
                });
            } catch (Throwable error) {
                Log.e(TAG, "bootstrap 存珠机类型校验失败", error);
                postToMainIfAlive(() -> {
                    bootstrapCheckRunning.set(false);
                    bootstrapVerified = false;
                    bootstrapRejected = true;
                    showError("设备类型校验失败，已关闭营业：" + messageOf(error));
                });
            }
        });
    }

    private void refreshMemberSession() {
        if (!isUiAlive()) {
            return;
        }
        if (!bootstrapVerified) {
            bootstrapRejected = false;
            verifyBootstrapIfNeeded();
            return;
        }
        autoSessionRequested = true;
        requestSession(true);
    }

    private void requestSession(boolean forceNew) {
        if (!isUiAlive()) {
            return;
        }
        if (!new NetworkStartupManager(this).hasValidatedInternet()) {
            autoSessionRequested = false;
            showError("网络未连接，不能创建会员存珠二维码");
            ensureNetworkBeforeDeviceFlow(false);
            return;
        }
        if (!new ActivationStore(this).isActivated()) {
            autoSessionRequested = false;
            showError("设备未激活，不能创建会员存珠二维码");
            return;
        }
        if (!MqttManager.get(this).isConnected() || !MqttManager.get(this).isSubscribed()) {
            autoSessionRequested = false;
            showError("MQTT 尚未就绪，暂不能创建会员存珠二维码");
            return;
        }
        if (!bootstrapVerified) {
            autoSessionRequested = false;
            showError("尚未通过存珠机设备类型校验");
            verifyBootstrapIfNeeded();
            return;
        }
        memberStore.setMessage(forceNew ? "正在刷新二维码" : "正在恢复或创建二维码");
        executeWorker("会员存珠 Session", () -> {
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
                Log.i(TAG, "会员存珠 Session 已恢复/创建，status=" + session.status);
            } catch (Throwable error) {
                autoSessionRequested = false;
                Log.e(TAG, "创建会员存珠二维码失败", error);
                showError("创建会员存珠二维码失败：" + messageOf(error));
            }
        });
    }

    private void cancelCurrentSession(boolean idleTimeout) {
        if (!isUiAlive() || memberLogoutRunning) {
            return;
        }
        MemberDepositStore.Snapshot snapshot = memberStore.loadWithoutScheduling();
        if (!isMemberLoggedIn(snapshot)) {
            memberStore.clearSession();
            autoSessionRequested = false;
            refreshStatus();
            maybeRestoreOrCreateSession();
            return;
        }
        if (DeviceStateRepository.get(this).getRunningStatus() == AppConfig.STATUS_COLLECTING) {
            showError("正在收珠，完成后才能退出会员登录");
            return;
        }
        if (!new NetworkStartupManager(this).hasValidatedInternet()) {
            showError("网络未连接，暂不能退出会员登录");
            ensureNetworkBeforeDeviceFlow(false);
            if (idleTimeout) {
                lastMemberInteractionAt = SystemClock.elapsedRealtime();
                scheduleMemberIdleTimeout();
            }
            return;
        }

        memberLogoutRunning = true;
        cancelMemberIdleTimeout();
        memberStore.setMessage(idleTimeout ? "60秒无操作，正在退出会员登录" : "正在退出会员登录");
        executeWorker("退出会员登录", () -> {
            try {
                if (snapshot.hasSession()) {
                    new MemberDepositRepository(this).cancelSession(snapshot.sessionId);
                }
            } catch (Throwable error) {
                Log.w(TAG, "退出会员登录时取消 Session 失败，本地保留现场", error);
                postToMainIfAlive(() -> {
                    memberLogoutRunning = false;
                    showError("退出会员登录失败：" + messageOf(error));
                    lastMemberInteractionAt = SystemClock.elapsedRealtime();
                    scheduleMemberIdleTimeout();
                });
                return;
            }
            memberStore.clearSession();
            postToMainIfAlive(() -> {
                memberLogoutRunning = false;
                memberPageVisible = false;
                lastMemberInteractionAt = 0L;
                autoSessionRequested = false;
                refreshStatus();
                maybeRestoreOrCreateSession();
            });
        });
    }

    private void startMemberDeposit() {
        if (!isUiAlive()) {
            return;
        }
        MemberDepositStore.Snapshot snapshot = memberStore.load();
        if (!new NetworkStartupManager(this).hasValidatedInternet()) {
            showError("网络未连接，不能开始存珠");
            ensureNetworkBeforeDeviceFlow(false);
            return;
        }
        if (!bootstrapVerified) {
            showError("设备类型尚未通过存珠机校验，不能开始存珠");
            return;
        }
        if (!snapshot.isBound()) {
            showError("请先让会员扫码绑定后再开始存珠");
            return;
        }
        if (DeviceStateRepository.get(this).getRunningStatus() == AppConfig.STATUS_COLLECTING) {
            showError("当前正在收珠，不能重复开始");
            return;
        }
        String requestNo = memberStore.loadOrCreateClientRequestNo(snapshot.sessionId);
        executeWorker("开始会员存珠", () -> {
            MemberDepositRepository repository;
            try {
                repository = new MemberDepositRepository(this);
                MemberDepositRepository.OperationSnapshot operation =
                        repository.startMemberDeposit(requestNo, snapshot.sessionId);
                if (!hasOperationResult(operation)) {
                    throw new IllegalStateException("平台开始存珠响应缺少 Operation 信息");
                }
                memberStore.markWaitingCommand(operation.operationNo, operation.referenceNo);
            } catch (Throwable error) {
                if (isAmbiguousStartResult(error)) {
                    recoverAmbiguousStartResult(requestNo, error);
                    return;
                }
                Log.e(TAG, "开始会员存珠失败", error);
                showError("开始存珠失败：" + messageOf(error));
            }
        });
    }

    private void recoverAmbiguousStartResult(String requestNo, Throwable originalError) {
        Log.w(TAG, "开始存珠返回结果不明确，改为按 clientRequestNo 查询恢复：" + requestNo, originalError);
        showError("开始存珠结果不明确，正在查询平台处理结果，请勿重复操作");
        try {
            MemberDepositRepository repository = new MemberDepositRepository(this);
            MemberDepositRepository.OperationSnapshot operation = queryMemberDepositWithRetry(repository, requestNo);
            memberStore.markWaitingCommand(operation.operationNo, operation.referenceNo);
            Log.i(TAG, "开始存珠结果已通过查询恢复：clientRequestNo=" + requestNo
                    + "，operationNo=" + operation.operationNo
                    + "，referenceNo=" + operation.referenceNo
                    + "，status=" + operation.status);
        } catch (Throwable queryError) {
            Log.e(TAG, "开始存珠结果不明确，按 clientRequestNo 查询仍失败", queryError);
            showError("开始存珠结果不明确，已保留请求号，请勿重复操作；查询失败：" + messageOf(queryError));
        }
    }

    private MemberDepositRepository.OperationSnapshot queryMemberDepositWithRetry(
            MemberDepositRepository repository,
            String requestNo
    ) throws Exception {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= START_QUERY_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(START_QUERY_RETRY_DELAY_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
            try {
                MemberDepositRepository.OperationSnapshot operation =
                        repository.queryMemberDeposit(requestNo);
                if (hasOperationResult(operation)) {
                    return operation;
                }
                lastError = new IllegalStateException("queryMemberDeposit 未返回有效 Operation 信息");
                Log.w(TAG, "queryMemberDeposit 第 " + attempt + " 次未返回有效 Operation，继续重试");
            } catch (Throwable error) {
                lastError = error;
                Log.w(TAG, "queryMemberDeposit 第 " + attempt + " 次失败", error);
            }
        }
        if (lastError instanceof Exception) {
            throw (Exception) lastError;
        }
        throw new IllegalStateException(lastError == null ? "queryMemberDeposit 未返回结果" : lastError.getMessage(), lastError);
    }

    private boolean hasOperationResult(MemberDepositRepository.OperationSnapshot operation) {
        return operation != null
                && (!empty(operation.operationNo)
                || !empty(operation.referenceNo)
                || !empty(operation.operationId)
                || !empty(operation.status));
    }

    private boolean isAmbiguousStartResult(Throwable error) {
        String message = messageOf(error);
        return message.contains("结果不明确")
                || message.contains("请勿重复操作")
                || message.contains("不要重复操作")
                || message.contains("不明确");
    }

    private void updateRuntimeMessage(String key, String value) {
        if (!isUiAlive() || value == null || value.trim().isEmpty()) {
            return;
        }
        String text = value.trim();
        if ("mqtt".equals(key)) {
            mqttStatusMessage = text;
            if (text.contains("已订阅")) {
                /* 新 MQTT 凭证和订阅已就绪后重新读取 bootstrap，不依赖旧运行缓存。 */
                bootstrapVerified = false;
                bootstrapRejected = false;
                autoSessionRequested = false;
                postToMainIfAlive(this::maybeRestoreOrCreateSession);
            }
        } else if ("service".equals(key)) {
            serviceStatusMessage = text;
        } else if ("serial".equals(key)) {
            serialStatusMessage = text;
        } else if ("network".equals(key)) {
            networkStatusMessage = text;
        }
        if (text.contains("失败") || text.contains("错误") || text.contains("断开")) {
            lastRuntimeMessage = text;
        }
    }

    private void refreshStatus() {
        if (!isUiAlive()) {
            return;
        }
        MemberDepositStore.Snapshot member = memberStore.load();
        ActivationStore activationStore = new ActivationStore(this);
        DeviceStateRepository stateRepository = DeviceStateRepository.get(this);
        DepositSession hardwareSession = stateRepository.getSession();
        int runningStatus = stateRepository.getRunningStatus();
        boolean internet = new NetworkStartupManager(this).hasValidatedInternet();
        boolean activated = activationStore.isActivated();
        boolean mqttConnected = MqttManager.get(this).isConnected();
        boolean mqttSubscribed = MqttManager.get(this).isSubscribed();
        boolean serialOpen = BoardSerialPort.get(this).isOpen();
        boolean deviceIdle = runningStatus == AppConfig.STATUS_IDLE;
        boolean collecting = runningStatus == AppConfig.STATUS_COLLECTING
                || (hardwareSession != null && DepositSession.STATE_COLLECTING.equals(hardwareSession.state));

        updatePageState(member, collecting);
        headerStatusText.setText(headerStatus(internet, activated, member, collecting));
        mainHintText.setText(mainHint(internet, member, collecting));
        updateQr(internet, member, collecting);

        /* 会员页面只展示平台 memberDisplayNo，不再使用 memberNickname。 */
        memberNoText.setText("会员ID：" + memberDisplayNo(member));
        memberBalanceText.setText("余额：" + emptyAsDash(member.availableQuantity) + " " + member.unitName);
        memberLimitText.setText("可存上限：" + emptyAsDash(member.maximumDepositQuantity) + " " + member.unitName);
        int actual = hardwareSession == null ? 0 : hardwareSession.actualQuantity;
        actualQuantityText.setText("本次已确认：" + actual + " 颗");

        sessionStateText.setText("会话：" + describeMemberStatus(member.status)
                + " · " + emptyAsDash(member.message));
        String operationNo = hardwareSession != null && !empty(hardwareSession.operationNo)
                ? hardwareSession.operationNo
                : member.operationNo;
        operationText.setText("业务单号：" + emptyAsDash(operationNo));
        deviceStatusText.setText("设备：" + describeRunningStatus(runningStatus)
                + " · 网络 " + (internet ? "已连接" : networkStatusMessage)
                + " · MQTT " + (mqttConnected ? (mqttSubscribed ? "已订阅" : "已连接") : mqttStatusMessage)
                + " · 类型 " + (bootstrapVerified ? "存珠机" : (bootstrapRejected ? "校验失败" : "待校验"))
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

        boolean canStart = internet
                && activated
                && mqttConnected
                && mqttSubscribed
                && bootstrapVerified
                && serialOpen
                && deviceIdle
                && member.isBound()
                && !collecting
                && !memberLogoutRunning
                && !MemberDepositStore.STATUS_WAITING_COMMAND.equals(member.status)
                && !MemberDepositStore.STATUS_STARTING.equals(member.status);
        startButton.setEnabled(canStart);
        startButton.setText(collecting ? "正在收珠" : "开始存珠");
        refreshButton.setEnabled(internet && activated && mqttConnected
                && mqttSubscribed && bootstrapVerified && deviceIdle && !collecting && !memberLogoutRunning);
        cancelButton.setEnabled(internet && bootstrapVerified && !collecting
                && !memberLogoutRunning && member.hasSession());
    }

    private void updatePageState(MemberDepositStore.Snapshot member, boolean collecting) {
        boolean loggedIn = isMemberLoggedIn(member);
        qrPage.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        memberPage.setVisibility(loggedIn ? View.VISIBLE : View.GONE);

        if (!loggedIn) {
            memberPageVisible = false;
            lastMemberInteractionAt = 0L;
            cancelMemberIdleTimeout();
            return;
        }

        if (!memberPageVisible) {
            memberPageVisible = true;
            lastMemberInteractionAt = SystemClock.elapsedRealtime();
        }

        /* 只有“已绑定且尚未开始收珠”的会员页参与 60 秒无操作退出，业务执行期间不强制取消 Session。 */
        if (member.isBound() && !collecting && !memberLogoutRunning) {
            scheduleMemberIdleTimeout();
        } else {
            cancelMemberIdleTimeout();
        }
    }

    private void resetMemberIdleTimerFromUser() {
        if (!memberPageVisible || memberLogoutRunning || memberStore == null) {
            return;
        }
        MemberDepositStore.Snapshot snapshot = memberStore.loadWithoutScheduling();
        if (!snapshot.isBound()
                || DeviceStateRepository.get(this).getRunningStatus() == AppConfig.STATUS_COLLECTING) {
            return;
        }
        lastMemberInteractionAt = SystemClock.elapsedRealtime();
        scheduleMemberIdleTimeout();
    }

    private void scheduleMemberIdleTimeout() {
        mainHandler.removeCallbacks(memberIdleTimeoutRunnable);
        if (!memberPageVisible || memberLogoutRunning || lastMemberInteractionAt <= 0L) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - lastMemberInteractionAt;
        long delay = Math.max(1L, MEMBER_IDLE_TIMEOUT_MS - elapsed);
        mainHandler.postDelayed(memberIdleTimeoutRunnable, delay);
    }

    private void cancelMemberIdleTimeout() {
        mainHandler.removeCallbacks(memberIdleTimeoutRunnable);
    }

    private void handleMemberIdleTimeout() {
        if (!isUiAlive() || memberLogoutRunning || memberStore == null) {
            return;
        }
        MemberDepositStore.Snapshot snapshot = memberStore.loadWithoutScheduling();
        if (!snapshot.isBound()) {
            return;
        }
        if (DeviceStateRepository.get(this).getRunningStatus() == AppConfig.STATUS_COLLECTING) {
            cancelMemberIdleTimeout();
            return;
        }
        long idle = SystemClock.elapsedRealtime() - lastMemberInteractionAt;
        if (idle < MEMBER_IDLE_TIMEOUT_MS) {
            scheduleMemberIdleTimeout();
            return;
        }
        cancelCurrentSession(true);
    }

    private void updateQr(boolean internet, MemberDepositStore.Snapshot member, boolean collecting) {
        if (!isUiAlive()) {
            return;
        }
        if (!internet) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("网络未连接\n请连接 WiFi");
            return;
        }
        if (bootstrapRejected) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("设备类型校验失败\n已关闭营业");
            return;
        }
        if (collecting) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("正在收珠\n请等待完成");
            return;
        }
        if (isMemberLoggedIn(member)) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("会员已登录\n请在会员页面操作");
            return;
        }
        if (!member.hasQrContent()) {
            lastQrContent = "";
            qrGeneratingContent = "";
            qrImage.setImageBitmap(null);
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText(bootstrapVerified ? "正在获取二维码" : "正在校验设备配置");
            return;
        }
        if (member.qrContent.equals(lastQrContent) && qrImage.getDrawable() != null) {
            qrImage.setVisibility(View.VISIBLE);
            qrPlaceholderText.setVisibility(View.GONE);
            return;
        }
        if (member.qrContent.equals(qrGeneratingContent)) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("正在生成二维码");
            return;
        }

        final String qrContent = member.qrContent;
        qrGeneratingContent = qrContent;
        qrImage.setVisibility(View.GONE);
        qrPlaceholderText.setVisibility(View.VISIBLE);
        qrPlaceholderText.setText("正在生成二维码");

        /* 二维码编码和 Bitmap 构建全部放后台线程，避免 RK3566 UI 连续丢帧。 */
        executeQrWorker("会员存珠二维码", () -> {
            try {
                Bitmap bitmap = QrCodeUtil.create(qrContent, 420);
                postToMainIfAlive(() -> {
                    MemberDepositStore.Snapshot current = memberStore.load();
                    qrGeneratingContent = "";
                    if (!qrContent.equals(current.qrContent) || isMemberLoggedIn(current)) {
                        return;
                    }
                    qrImage.setImageBitmap(bitmap);
                    lastQrContent = qrContent;
                    qrImage.setVisibility(View.VISIBLE);
                    qrPlaceholderText.setVisibility(View.GONE);
                });
            } catch (Throwable error) {
                Log.e(TAG, "二维码生成失败", error);
                postToMainIfAlive(() -> {
                    qrGeneratingContent = "";
                    qrImage.setVisibility(View.GONE);
                    qrPlaceholderText.setVisibility(View.VISIBLE);
                    qrPlaceholderText.setText("二维码生成失败");
                    showError("二维码生成失败：" + messageOf(error));
                });
            }
        });
    }

    private String headerStatus(
            boolean internet,
            boolean activated,
            MemberDepositStore.Snapshot member,
            boolean collecting
    ) {
        if (!internet) {
            return "等待联网";
        }
        if (!activated) {
            return "等待激活";
        }
        if (bootstrapRejected) {
            return "不可营业";
        }
        if (!bootstrapVerified) {
            return "校验设备";
        }
        if (collecting) {
            return "收珠中";
        }
        if (isMemberLoggedIn(member)) {
            return "会员已登录";
        }
        if (member.hasQrContent()) {
            return "待扫码";
        }
        return "待机";
    }

    private String mainHint(boolean internet, MemberDepositStore.Snapshot member, boolean collecting) {
        if (!internet) {
            return "请连接 WiFi，联网后设备会自动继续初始化";
        }
        if (bootstrapRejected) {
            return "平台设备类型不是存珠机，请检查设备档案";
        }
        if (!bootstrapVerified) {
            return "正在校验平台设备类型与存珠能力";
        }
        if (collecting) {
            return "请投入弹珠，系统正在计数";
        }
        if (isMemberLoggedIn(member)) {
            return "会员已登录，请在会员页面操作";
        }
        if (member.hasQrContent()) {
            return "请使用微信扫描二维码登录会员";
        }
        return "正在准备会员登录二维码";
    }

    private String memberDisplayNo(MemberDepositStore.Snapshot member) {
        if (!isMemberLoggedIn(member)) {
            return "等待扫码";
        }
        return emptyAsDash(member.memberDisplayNo);
    }

    private static boolean isMemberLoggedIn(MemberDepositStore.Snapshot member) {
        if (member == null || !member.hasSession()) {
            return false;
        }
        return member.isBound()
                || !empty(member.memberNo)
                || !empty(member.memberNickname)
                || !empty(member.memberDisplayNo);
    }

    private void showError(String message) {
        if (memberStore != null) {
            memberStore.setMessage(message);
        }
        postToMainIfAlive(() -> {
            lastRuntimeMessage = message;
            refreshStatus();
        });
    }

    private boolean isUiAlive() {
        return !destroyed && !isFinishing() && !isDestroyed();
    }

    private void postToMainIfAlive(Runnable action) {
        if (action == null || destroyed) {
            return;
        }
        mainHandler.post(() -> {
            if (isUiAlive()) {
                action.run();
            }
        });
    }

    private void executeWorker(String name, Runnable action) {
        execute(worker, name, action);
    }

    private void executeQrWorker(String name, Runnable action) {
        execute(qrWorker, name, action);
    }

    private void execute(ExecutorService executor, String name, Runnable action) {
        if (executor == null || action == null || destroyed) {
            return;
        }
        try {
            executor.execute(() -> {
                if (destroyed) {
                    return;
                }
                action.run();
            });
        } catch (RejectedExecutionException error) {
            /*
             * 页面跳转/销毁后，旧的 MQTT/bootstrap 回调可能仍然到达。
             * 这类回调不能再向已关闭线程池投递任务，否则会在主线程崩溃。
             */
            Log.w(TAG, "页面已销毁，忽略后台任务：" + name, error);
        }
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
