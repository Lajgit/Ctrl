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
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.service.DeviceService;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 购珠机顾客主界面。
 *
 * <p>套餐、价格、购珠区标题和说明来自 SDK bootstrap。设备只提交服务端返回的
 * purchaseRuleId/priceTierId，不自行上传自定义金额或租户信息。支付结果本身不
 * 直接驱动控制板，真实出珠只等待 MQTT dispense_marbles。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final int CODE_BACKEND_SETTINGS_REQUEST = 0x27;
    private static final int CODE_DISPENSE_FAILED = 0x04;
    private static final int CODE_BEAD_EMPTY = 0x22;
    private static final int CODE_BEAD_REFILLED = 0x23;

    private final List<PackageOption> packageOptions = new ArrayList<>();

    private TextView packageSectionTitle;
    private TextView packageSectionHint;
    private TextView selectedPackageText;
    private TextView paymentStatusText;
    private Button paymentButton;
    private ImageView paymentQrImage;
    private Button[] packageButtons;

    private LinearLayout collectionLayout;
    private TextView collectionStatusText;
    private Button collectionStartButton;
    private Button collectionFinishButton;

    private PackageOption selectedOption;
    private boolean receiverRegistered;
    private boolean backendOpening;
    private boolean bootstrapLoading;

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
                return;
            }
            if (AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())
                    && "mqtt".equals(intent.getStringExtra("key"))) {
                String value = intent.getStringExtra("value");
                if (value != null && value.contains("已连接")) {
                    loadBootstrap(false);
                }
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
        loadBootstrap(false);
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
        packageSectionTitle = findViewById(R.id.text_package_section_title);
        packageSectionHint = findViewById(R.id.text_package_section_hint);
        selectedPackageText = findViewById(R.id.text_selected_package);
        paymentStatusText = findViewById(R.id.text_payment_status);
        paymentButton = findViewById(R.id.button_start_payment);
        paymentQrImage = findViewById(R.id.image_payment_qr);

        packageButtons = new Button[]{
                findViewById(R.id.button_package_1),
                findViewById(R.id.button_package_5),
                findViewById(R.id.button_package_10),
                findViewById(R.id.button_package_20),
                findViewById(R.id.button_package_50),
                findViewById(R.id.button_package_100)
        };
        for (Button button : packageButtons) {
            button.setEnabled(false);
        }
        paymentButton.setEnabled(false);

        collectionLayout = findViewById(R.id.layout_collection);
        collectionStatusText = findViewById(R.id.text_collection_status);
        collectionStartButton = findViewById(R.id.button_collection_start);
        collectionFinishButton = findViewById(R.id.button_collection_finish);
    }

    private void bindActions() {
        for (int index = 0; index < packageButtons.length; index++) {
            final int optionIndex = index;
            packageButtons[index].setOnClickListener(view -> selectPackage(optionIndex));
        }

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

    private void loadBootstrap(boolean force) {
        if (bootstrapLoading || (!force && !packageOptions.isEmpty())) {
            return;
        }

        bootstrapLoading = true;
        paymentStatusText.setText(R.string.sdk_bootstrap_loading);
        DeviceSdkManager.get(this).refreshBootstrap(new DeviceSdkManager.BootstrapCallback() {
            @Override
            public void onSuccess(DeviceAppBootstrapResult result) {
                bootstrapLoading = false;
                applyBootstrap(result);
            }

            @Override
            public void onFailure(Throwable error) {
                bootstrapLoading = false;
                disablePackages();
                paymentStatusText.setText(getString(
                        R.string.sdk_bootstrap_failed_format,
                        messageOf(error)
                ));
            }
        });
    }

    private void applyBootstrap(DeviceAppBootstrapResult bootstrap) {
        if (bootstrap == null) {
            disablePackages();
            paymentStatusText.setText(R.string.sdk_bootstrap_empty);
            return;
        }

        DeviceAppBootstrapResult.PresentationInfo presentation = bootstrap.getPresentation();
        if (presentation != null && presentation.getPurchaseSection() != null) {
            DeviceAppBootstrapResult.PurchaseSectionInfo section =
                    presentation.getPurchaseSection();
            if (notBlank(section.getTitle())) {
                packageSectionTitle.setText(section.getTitle());
            }
            if (notBlank(section.getDescription())) {
                packageSectionHint.setText(section.getDescription());
            }
        }

        packageOptions.clear();
        List<DeviceAppBootstrapResult.PurchaseRule> rules = bootstrap.getPurchaseRules();
        if (rules != null) {
            for (DeviceAppBootstrapResult.PurchaseRule rule : rules) {
                appendRuleOptions(rule);
                if (packageOptions.size() >= packageButtons.length) {
                    break;
                }
            }
        }

        selectedOption = null;
        selectedPackageText.setText(R.string.package_not_selected);
        paymentButton.setEnabled(false);
        paymentQrImage.setImageDrawable(null);
        paymentQrImage.setVisibility(View.GONE);

        if (packageOptions.isEmpty()) {
            disablePackages();
            paymentStatusText.setText(R.string.sdk_no_purchase_tier);
            return;
        }

        for (int index = 0; index < packageButtons.length; index++) {
            Button button = packageButtons[index];
            if (index < packageOptions.size()) {
                PackageOption option = packageOptions.get(index);
                button.setVisibility(View.VISIBLE);
                button.setEnabled(true);
                button.setText(getString(
                        R.string.package_button_dynamic_format,
                        option.quantity,
                        option.priceFen / 100.0
                ));
            } else {
                button.setEnabled(false);
                button.setVisibility(View.GONE);
            }
        }
        paymentStatusText.setText(R.string.payment_select_package);
    }

    private void appendRuleOptions(DeviceAppBootstrapResult.PurchaseRule rule) {
        if (rule == null || rule.getPurchaseRuleId() == null
                || rule.getPurchaseRuleId() <= 0L) {
            return;
        }

        List<DeviceAppBootstrapResult.PriceTier> tiers = rule.getPriceTiers();
        if (tiers != null && !tiers.isEmpty()) {
            for (DeviceAppBootstrapResult.PriceTier tier : tiers) {
                if (tier == null || tier.getId() == null || tier.getId() <= 0L
                        || tier.getPurchaseQuantity() == null
                        || tier.getPurchaseQuantity() <= 0
                        || tier.getPriceAmount() == null
                        || tier.getPriceAmount() <= 0) {
                    continue;
                }
                packageOptions.add(new PackageOption(
                        rule.getPurchaseRuleId(),
                        tier.getId(),
                        null,
                        tier.getPurchaseQuantity(),
                        tier.getPriceAmount()
                ));
                if (packageOptions.size() >= packageButtons.length) {
                    return;
                }
            }
            return;
        }

        if (rule.getPricingUnitQuantity() != null
                && rule.getPricingUnitQuantity() > 0
                && rule.getUnitPriceAmount() != null
                && rule.getUnitPriceAmount() > 0) {
            packageOptions.add(new PackageOption(
                    rule.getPurchaseRuleId(),
                    null,
                    rule.getPricingUnitQuantity(),
                    rule.getPricingUnitQuantity(),
                    rule.getUnitPriceAmount()
            ));
        }
    }

    private void disablePackages() {
        packageOptions.clear();
        selectedOption = null;
        for (Button button : packageButtons) {
            button.setEnabled(false);
        }
        paymentButton.setEnabled(false);
    }

    private void selectPackage(int index) {
        if (index < 0 || index >= packageOptions.size()) {
            return;
        }
        selectedOption = packageOptions.get(index);
        selectedPackageText.setText(getString(
                R.string.package_selected_dynamic_format,
                selectedOption.quantity,
                selectedOption.priceFen / 100.0
        ));
        paymentStatusText.setText(R.string.payment_ready);
        paymentQrImage.setImageDrawable(null);
        paymentQrImage.setVisibility(View.GONE);
        paymentButton.setEnabled(true);
    }

    private void startPayment() {
        PackageOption option = selectedOption;
        if (option == null) {
            Toast.makeText(this, R.string.package_not_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            PaymentManager.PaymentRequest request = PaymentManager.get(this).startPayment(
                    option.purchaseRuleId,
                    option.priceTierId,
                    option.purchaseQuantity,
                    option.quantity,
                    option.priceFen
            );
            paymentStatusText.setText(getString(
                    R.string.payment_waiting_qr_format,
                    request.orderId
            ));
            paymentQrImage.setImageDrawable(null);
            paymentQrImage.setVisibility(View.GONE);
            paymentButton.setEnabled(false);
        } catch (Throwable error) {
            paymentStatusText.setText(messageOf(error));
            paymentButton.setEnabled(true);
        }
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
        } else if (PaymentManager.EVENT_FAILED.equals(event) && selectedOption != null) {
            paymentButton.setEnabled(true);
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
            case CODE_DISPENSE_FAILED:
            case CODE_BEAD_EMPTY:
                paymentStatusText.setText(R.string.machine_temporarily_unavailable);
                paymentButton.setEnabled(false);
                break;
            case CODE_BEAD_REFILLED:
                paymentStatusText.setText(R.string.machine_ready);
                paymentButton.setEnabled(selectedOption != null);
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
        filter.addAction(AppConfig.ACTION_SERVICE_STATUS);
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

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static final class PackageOption {
        final long purchaseRuleId;
        final Long priceTierId;
        final Integer purchaseQuantity;
        final int quantity;
        final int priceFen;

        PackageOption(
                long purchaseRuleId,
                Long priceTierId,
                Integer purchaseQuantity,
                int quantity,
                int priceFen
        ) {
            this.purchaseRuleId = purchaseRuleId;
            this.priceTierId = priceTierId;
            this.purchaseQuantity = purchaseQuantity;
            this.quantity = quantity;
            this.priceFen = priceFen;
        }
    }
}
