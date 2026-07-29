package com.gouzhu;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.gouzhu.mqtt.DeviceCommandManager;
import com.gouzhu.network.WifiConfigActivity;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.payment.QrCodeUtil;
import com.gouzhu.service.DeviceService;

/**
 * 购珠机顾客主界面。
 *
 * <p>顾客页隐藏内部设备状态；K2 或调试按钮进入后台。会员存珠任务到达后，
 * 用户倒完珠子并点击“开始存珠”，才启动控制板存珠电机。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final int CODE_BACKEND_SETTINGS_REQUEST = 0x27;
    private static final int CODE_BEAD_OUTPUT_TIMEOUT = 0x07;
    private static final int CODE_BEAD_EMPTY = 0x25;
    private static final int CODE_BEAD_REFILLED = 0x26;

    private TextView selectedPackageText;
    private TextView paymentStatusText;
    private Button paymentButton;
    private ImageView paymentQrImage;

    private LinearLayout collectionLayout;
    private TextView collectionStatusText;
    private Button collectionStartButton;
    private Button collectionFinishButton;

    private int selectedBeadCount;
    private int selectedPriceFen;
    private boolean receiverRegistered;
    private boolean backendOpening;

    private final BroadcastReceiver appReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            if (AppConfig.ACTION_BOARD_EVENT.equals(intent.getAction())) {
                handleBoardEvent(intent.getIntExtra("code2", -1));
                return;
            }

            if (PaymentManager.ACTION_PAYMENT_EVENT.equals(intent.getAction())) {
                handlePaymentEvent(intent);
                return;
            }

            if (AppConfig.ACTION_COLLECTION_EVENT.equals(intent.getAction())) {
                handleCollectionEvent(intent);
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

        if (!WifiSupport.hasSavedWifi(this)) {
            startActivity(new Intent(this, WifiConfigActivity.class));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerAppReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        backendOpening = false;
        hideSystemUi();
        if (DeviceCommandManager.get(this).hasPendingCollection()) {
            collectionLayout.setVisibility(View.VISIBLE);
            collectionStatusText.setText(R.string.collection_ready_hint);
            collectionStartButton.setEnabled(true);
            collectionFinishButton.setEnabled(false);
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(appReceiver);
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
        paymentStatusText = findViewById(R.id.text_payment_status);
        paymentButton = findViewById(R.id.button_start_payment);
        paymentQrImage = findViewById(R.id.image_payment_qr);
        paymentButton.setEnabled(false);

        collectionLayout = findViewById(R.id.layout_collection);
        collectionStatusText = findViewById(R.id.text_collection_status);
        collectionStartButton = findViewById(R.id.button_collection_start);
        collectionFinishButton = findViewById(R.id.button_collection_finish);
    }

    private void bindActions() {
        bindPackageButton(R.id.button_package_1, 1, 100);
        bindPackageButton(R.id.button_package_5, 5, 500);
        bindPackageButton(R.id.button_package_10, 10, 1000);
        bindPackageButton(R.id.button_package_20, 20, 2000);
        bindPackageButton(R.id.button_package_50, 50, 5000);
        bindPackageButton(R.id.button_package_100, 100, 10000);

        paymentButton.setOnClickListener(view -> startPayment());
        findViewById(R.id.button_backend_settings).setOnClickListener(
                view -> openBackendSettings()
        );
        collectionStartButton.setOnClickListener(view -> {
            if (DeviceCommandManager.get(this).startPendingCollection()) {
                collectionStartButton.setEnabled(false);
                collectionFinishButton.setEnabled(true);
            }
        });
        collectionFinishButton.setOnClickListener(view ->
                DeviceCommandManager.get(this).finishPendingCollection()
        );
    }

    private void bindPackageButton(int viewId, int beadCount, int priceFen) {
        findViewById(viewId).setOnClickListener(view -> {
            selectedBeadCount = beadCount;
            selectedPriceFen = priceFen;
            selectedPackageText.setText(getString(
                    R.string.package_selected_format,
                    beadCount,
                    priceFen / 100
            ));
            paymentStatusText.setText(R.string.payment_ready);
            paymentQrImage.setImageDrawable(null);
            paymentQrImage.setVisibility(View.GONE);
            paymentButton.setEnabled(true);
        });
    }

    private void startPayment() {
        if (selectedBeadCount <= 0 || selectedPriceFen <= 0) {
            Toast.makeText(this, R.string.package_not_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        PaymentManager.PaymentRequest request = PaymentManager.get(this).startPayment(
                selectedBeadCount,
                selectedPriceFen
        );
        paymentStatusText.setText(getString(
                R.string.payment_waiting_qr_format,
                request.orderId
        ));
        paymentQrImage.setImageDrawable(null);
        paymentQrImage.setVisibility(View.GONE);
    }

    private void handlePaymentEvent(Intent intent) {
        String event = intent.getStringExtra(PaymentManager.EXTRA_EVENT);
        String message = intent.getStringExtra(PaymentManager.EXTRA_MESSAGE);

        if (PaymentManager.EVENT_QR_READY.equals(event)) {
            String qrContent = intent.getStringExtra(PaymentManager.EXTRA_QR_CONTENT);
            Bitmap bitmap = QrCodeUtil.create(qrContent, 520);
            if (bitmap == null) {
                paymentStatusText.setText(R.string.payment_qr_invalid);
                return;
            }
            paymentQrImage.setImageBitmap(bitmap);
            paymentQrImage.setVisibility(View.VISIBLE);
            paymentStatusText.setText(R.string.payment_scan_hint);
            return;
        }

        paymentStatusText.setText(message == null ? "" : message);
        if (PaymentManager.EVENT_SUCCESS.equals(event)) {
            paymentButton.setEnabled(false);
            paymentQrImage.setVisibility(View.GONE);
        }
    }

    private void handleCollectionEvent(Intent intent) {
        String event = intent.getStringExtra(DeviceCommandManager.EXTRA_COLLECTION_EVENT);
        String message = intent.getStringExtra(DeviceCommandManager.EXTRA_COLLECTION_MESSAGE);
        collectionLayout.setVisibility(View.VISIBLE);
        collectionStatusText.setText(message == null ? "" : message);

        if (DeviceCommandManager.COLLECTION_READY.equals(event)) {
            collectionStartButton.setEnabled(true);
            collectionFinishButton.setEnabled(false);
        } else if (DeviceCommandManager.COLLECTION_STARTED.equals(event)
                || DeviceCommandManager.COLLECTION_PROGRESS.equals(event)) {
            collectionStartButton.setEnabled(false);
            collectionFinishButton.setEnabled(true);
        } else if (DeviceCommandManager.COLLECTION_FINISHED.equals(event)
                || DeviceCommandManager.COLLECTION_FAILED.equals(event)) {
            collectionStartButton.setEnabled(false);
            collectionFinishButton.setEnabled(false);
        }
    }

    private void handleBoardEvent(int code2) {
        switch (code2) {
            case CODE_BACKEND_SETTINGS_REQUEST:
                openBackendSettings();
                break;
            case CODE_BEAD_OUTPUT_TIMEOUT:
            case CODE_BEAD_EMPTY:
                paymentStatusText.setText(R.string.machine_temporarily_unavailable);
                paymentButton.setEnabled(false);
                break;
            case CODE_BEAD_REFILLED:
                paymentStatusText.setText(R.string.machine_ready);
                paymentButton.setEnabled(selectedBeadCount > 0);
                break;
            default:
                break;
        }
    }

    private void openBackendSettings() {
        if (backendOpening) {
            return;
        }
        backendOpening = true;
        startActivity(new Intent(this, BackendSettingsActivity.class));
    }

    private void startDeviceService() {
        Intent intent = new Intent(this, DeviceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void registerAppReceiver() {
        if (receiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConfig.ACTION_BOARD_EVENT);
        filter.addAction(PaymentManager.ACTION_PAYMENT_EVENT);
        filter.addAction(AppConfig.ACTION_COLLECTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(appReceiver, filter);
        }
        receiverRegistered = true;
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
