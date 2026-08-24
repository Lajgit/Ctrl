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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.gouzhu.mqtt.DeviceCommandManager;
import com.gouzhu.network.WifiConfigActivity;
import com.gouzhu.network.WifiSupport;
import com.gouzhu.payment.PaymentManager;
import com.gouzhu.payment.QrCodeUtil;
import com.gouzhu.redemption.RedemptionActivity;
import com.gouzhu.redemption.RedemptionCapabilityResolver;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.service.DeviceService;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;

import java.util.ArrayList;
import java.util.List;

/** Customer-facing vending UI driven by the persisted global transaction occupancy state. */
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
    private Button cancelPaymentButton;
    private ImageView paymentQrImage;
    private Button[] packageButtons;

    private View memberWithdrawEntry;
    private View thirdPartyRedemptionEntry;
    private TextView memberWithdrawHint;
    private TextView thirdPartyRedemptionHint;
    private boolean memberWithdrawVisible;
    private boolean memberWithdrawAvailable;
    private boolean thirdPartyVisible;
    private boolean thirdPartyAvailable;

    private LinearLayout collectionLayout;
    private TextView collectionStatusText;
    private Button collectionStartButton;
    private Button collectionFinishButton;
    private LinearLayout dispenseOverlay;
    private ProgressBar dispenseProgress;
    private TextView dispenseOverlayTitle;
    private TextView dispenseOverlayStatus;

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
            if (AppConfig.ACTION_DISPENSE_ORDER_EVENT.equals(intent.getAction())) {
                handleDispenseOrderEvent(intent);
                return;
            }
            if (TransactionOccupancyManager.ACTION_CHANGED.equals(intent.getAction())
                    || AppConfig.ACTION_TRANSACTION_OCCUPANCY_CHANGED.equals(intent.getAction())) {
                applyTransactionOccupancy();
                return;
            }
            if (AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())
                    && "mqtt".equals(intent.getStringExtra("key"))) {
                String value = intent.getStringExtra("value");
                if (value != null && value.contains("已连接")) {
                    loadBootstrap(false);
                    PaymentManager.get(MainActivity.this).resumePendingPayment();
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
        PaymentManager.get(this).resumePendingPayment();
        if (DeviceCommandManager.get(this).hasPendingCollection()) {
            collectionLayout.setVisibility(View.VISIBLE);
        }
        DeviceCommandManager.get(this).requestActivePhysicalOrderState();
        applyTransactionOccupancy();
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

    @Override
    public void onBackPressed() {
        TransactionOccupancyManager.Snapshot snapshot =
                TransactionOccupancyManager.get(this).current();
        if (snapshot != null
                && TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(snapshot.ownerType)) {
            confirmCancelPayment();
            return;
        }
        super.onBackPressed();
    }

    private void bindViews() {
        packageSectionTitle = findViewById(R.id.text_package_section_title);
        packageSectionHint = findViewById(R.id.text_package_section_hint);
        selectedPackageText = findViewById(R.id.text_selected_package);
        paymentStatusText = findViewById(R.id.text_payment_status);
        paymentButton = findViewById(R.id.button_start_payment);
        cancelPaymentButton = findViewById(R.id.button_cancel_payment);
        paymentQrImage = findViewById(R.id.image_payment_qr);
        memberWithdrawEntry = findViewById(R.id.card_member_withdraw);
        thirdPartyRedemptionEntry = findViewById(R.id.card_third_party_redemption);
        memberWithdrawHint = findViewById(R.id.text_member_withdraw_hint);
        thirdPartyRedemptionHint = findViewById(R.id.text_third_party_redemption_hint);

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
        cancelPaymentButton.setVisibility(View.GONE);

        collectionLayout = findViewById(R.id.layout_collection);
        collectionStatusText = findViewById(R.id.text_collection_status);
        collectionStartButton = findViewById(R.id.button_collection_start);
        collectionFinishButton = findViewById(R.id.button_collection_finish);
        dispenseOverlay = findViewById(R.id.layout_dispense_overlay);
        dispenseProgress = findViewById(R.id.progress_dispense_order);
        dispenseOverlayTitle = findViewById(R.id.text_dispense_overlay_title);
        dispenseOverlayStatus = findViewById(R.id.text_dispense_overlay_status);
    }

    private void bindActions() {
        for (int index = 0; index < packageButtons.length; index++) {
            final int optionIndex = index;
            packageButtons[index].setOnClickListener(view -> {
                selectPackage(optionIndex);
                if (paymentButton.isEnabled()) {
                    startPayment();
                }
            });
        }

        paymentButton.setOnClickListener(view -> startPayment());
        cancelPaymentButton.setOnClickListener(view -> confirmCancelPayment());
        findViewById(R.id.button_backend_settings).setOnClickListener(
                view -> openBackendSettings()
        );
        memberWithdrawEntry.setOnClickListener(view ->
                openRedemption(RedemptionActivity.MODE_MEMBER)
        );
        thirdPartyRedemptionEntry.setOnClickListener(view ->
                openRedemption(RedemptionActivity.MODE_THIRD_PARTY)
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

    private void confirmCancelPayment() {
        String requestNo = PaymentManager.get(this).getCurrentOrderId();
        if (requestNo.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.payment_cancel_title)
                .setMessage(R.string.payment_cancel_confirmation)
                .setNegativeButton(R.string.payment_cancel_keep, null)
                .setPositiveButton(R.string.payment_cancel_confirm, (dialog, which) -> {
                    cancelPaymentButton.setEnabled(false);
                    paymentQrImage.setVisibility(View.GONE);
                    if (!PaymentManager.get(this).cancelCurrentPayment()) {
                        cancelPaymentButton.setEnabled(true);
                        Toast.makeText(
                                this,
                                R.string.payment_cancel_unavailable,
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
                .show();
    }

    private void loadBootstrap(boolean force) {
        // 核销 feature/routingVersion 可能由服务端动态变化，回首页和 MQTT 重连都重新读取。
        if (bootstrapLoading) {
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
                disableRedemptionEntries();
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
            disableRedemptionEntries();
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

        applyRedemptionCapabilities(bootstrap);

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
        paymentQrImage.setImageDrawable(null);
        paymentQrImage.setVisibility(View.GONE);

        if (packageOptions.isEmpty()) {
            disablePackages();
            paymentStatusText.setText(R.string.sdk_no_purchase_tier);
            applyTransactionOccupancy();
            return;
        }

        for (int index = 0; index < packageButtons.length; index++) {
            Button button = packageButtons[index];
            if (index < packageOptions.size()) {
                PackageOption option = packageOptions.get(index);
                button.setVisibility(View.VISIBLE);
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
        paymentStatusText.setText("");
        applyTransactionOccupancy();
    }

    private void applyRedemptionCapabilities(DeviceAppBootstrapResult bootstrap) {
        RedemptionCapabilityResolver.FeatureGate member =
                RedemptionCapabilityResolver.memberWithdrawal(bootstrap);
        RedemptionCapabilityResolver.FeatureGate third =
                RedemptionCapabilityResolver.thirdPartyRedemption(bootstrap);

        memberWithdrawVisible = member.visible;
        memberWithdrawAvailable = member.visible && member.available;
        thirdPartyVisible = third.visible;
        thirdPartyAvailable = third.visible && third.available;

        memberWithdrawEntry.setVisibility(member.visible ? View.VISIBLE : View.GONE);
        thirdPartyRedemptionEntry.setVisibility(third.visible ? View.VISIBLE : View.GONE);
        memberWithdrawEntry.setEnabled(memberWithdrawAvailable);
        thirdPartyRedemptionEntry.setEnabled(thirdPartyAvailable);
        memberWithdrawEntry.setAlpha(memberWithdrawAvailable ? 1f : 0.5f);
        thirdPartyRedemptionEntry.setAlpha(thirdPartyAvailable ? 1f : 0.5f);
        memberWithdrawHint.setText(member.available
                ? firstNonBlank(member.description, getString(R.string.member_withdraw_entry_hint))
                : firstNonBlank(member.unavailableReason, getString(R.string.redemption_unavailable)));
        thirdPartyRedemptionHint.setText(third.available
                ? firstNonBlank(third.description, getString(R.string.third_party_redemption_entry_hint))
                : firstNonBlank(third.unavailableReason, getString(R.string.redemption_unavailable)));
    }

    private void disableRedemptionEntries() {
        memberWithdrawVisible = false;
        memberWithdrawAvailable = false;
        thirdPartyVisible = false;
        thirdPartyAvailable = false;
        memberWithdrawEntry.setEnabled(false);
        thirdPartyRedemptionEntry.setEnabled(false);
        memberWithdrawEntry.setAlpha(0.5f);
        thirdPartyRedemptionEntry.setAlpha(0.5f);
    }

    private void openRedemption(String mode) {
        TransactionOccupancyManager.Snapshot snapshot =
                TransactionOccupancyManager.get(this).current();
        boolean resumingMember = snapshot != null
                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType)
                && RedemptionActivity.MODE_MEMBER.equals(mode);
        boolean resumingThird = snapshot != null
                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType)
                && RedemptionActivity.MODE_THIRD_PARTY.equals(mode);
        if (!resumingMember && RedemptionActivity.MODE_MEMBER.equals(mode)
                && !memberWithdrawAvailable) {
            Toast.makeText(this, R.string.redemption_start_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!resumingThird && RedemptionActivity.MODE_THIRD_PARTY.equals(mode)
                && !thirdPartyAvailable) {
            Toast.makeText(this, R.string.redemption_start_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!resumingMember && !resumingThird
                && !TransactionOccupancyManager.get(this).canStartNewTransaction()) {
            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, RedemptionActivity.class);
        intent.putExtra(RedemptionActivity.EXTRA_MODE, mode);
        startActivity(intent);
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
        if (!TransactionOccupancyManager.get(this).canStartNewTransaction()) {
            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (index < 0 || index >= packageOptions.size()) {
            return;
        }
        selectedOption = packageOptions.get(index);
        selectedPackageText.setText(getString(
                R.string.package_selected_dynamic_format,
                selectedOption.quantity,
                selectedOption.priceFen / 100.0
        ));
        paymentStatusText.setText("");
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
        if (!TransactionOccupancyManager.get(this).canStartNewTransaction()) {
            Toast.makeText(this, R.string.transaction_device_busy, Toast.LENGTH_SHORT).show();
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
//            paymentStatusText.setText(getString(
//                    R.string.payment_waiting_qr_format,
//                    request.orderId
//            ));
            paymentQrImage.setImageDrawable(null);
            paymentQrImage.setVisibility(View.GONE);
            paymentButton.setEnabled(false);
        } catch (Throwable error) {
            paymentStatusText.setText(messageOf(error));
            applyTransactionOccupancy();
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
            cancelPaymentButton.setVisibility(View.VISIBLE);
            cancelPaymentButton.setEnabled(true);
            paymentStatusText.setText(R.string.payment_scan_hint);
            applyTransactionOccupancy();
            return;
        }

        paymentStatusText.setText(message == null ? "" : message);
        if (PaymentManager.EVENT_CANCELLING.equals(event)) {
            paymentQrImage.setVisibility(View.GONE);
            cancelPaymentButton.setVisibility(View.VISIBLE);
            cancelPaymentButton.setEnabled(false);
        } else if (PaymentManager.EVENT_CLOSED.equals(event)) {
            paymentQrImage.setImageDrawable(null);
            paymentQrImage.setVisibility(View.GONE);
            cancelPaymentButton.setVisibility(View.GONE);
            cancelPaymentButton.setEnabled(true);
        } else if (PaymentManager.EVENT_SUCCESS.equals(event)) {
            paymentButton.setEnabled(false);
            paymentQrImage.setVisibility(View.GONE);
            cancelPaymentButton.setVisibility(View.GONE);
        }
        applyTransactionOccupancy();
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
        applyTransactionOccupancy();
    }

    private void handleDispenseOrderEvent(Intent intent) {
        String eventType = intent.getStringExtra("eventType");
        int requested = intent.getIntExtra("requestedQuantity", 0);
        int actual = intent.getIntExtra("actualQuantity", 0);

        if ("started".equals(eventType) || "progress".equals(eventType)) {
            showDispenseOverlay(true);
            dispenseProgress.setVisibility(View.VISIBLE);
            dispenseOverlayTitle.setText(R.string.dispense_order_started);
            dispenseOverlayStatus.setVisibility(View.VISIBLE);
            dispenseOverlayStatus.setText(formatDispenseCount(actual, requested));
            paymentButton.setEnabled(false);
            return;
        }
        if ("finished".equals(eventType)) {
            dispenseOverlayTitle.setText(R.string.dispense_order_finished);
            dispenseOverlayStatus.setVisibility(View.VISIBLE);
            dispenseOverlayStatus.setText(formatDispenseCount(actual, requested));
            showDispenseOverlay(false);
            paymentStatusText.setText(R.string.dispense_order_finished);
            applyTransactionOccupancy();
            return;
        }
        if ("finishing".equals(eventType)) {
            showDispenseOverlay(true);
            dispenseProgress.setVisibility(View.GONE);
            dispenseOverlayTitle.setText(R.string.dispense_order_finishing);
            dispenseOverlayStatus.setVisibility(View.VISIBLE);
            dispenseOverlayStatus.setText(formatDispenseCount(actual, requested));
            paymentButton.setEnabled(false);
            return;
        }
        if ("blocked".equals(eventType)) {
            showDispenseOverlay(true);
            dispenseProgress.setVisibility(View.GONE);
            dispenseOverlayTitle.setText(R.string.dispense_order_blocked);
            // 顾客界面只显示明确的中文提示，不展示控制板内部英文结果码或诊断原因。
            dispenseOverlayStatus.setVisibility(View.GONE);
            paymentButton.setEnabled(false);
            paymentStatusText.setText(R.string.machine_temporarily_unavailable);
            return;
        }
        if ("recovering".equals(eventType)) {
            showDispenseOverlay(true);
            dispenseProgress.setVisibility(View.GONE);
            dispenseOverlayTitle.setText(R.string.dispense_order_recovering);
            dispenseOverlayStatus.setVisibility(View.VISIBLE);
            dispenseOverlayStatus.setText(formatDispenseCount(actual, requested));
            paymentButton.setEnabled(false);
            return;
        }
        if ("idle".equals(eventType)) {
            dispenseOverlayStatus.setVisibility(View.VISIBLE);
            showDispenseOverlay(false);
            applyTransactionOccupancy();
        }
    }

    private void applyTransactionOccupancy() {
        TransactionOccupancyManager manager = TransactionOccupancyManager.get(this);
        TransactionOccupancyManager.Snapshot snapshot = manager.current();
        boolean idle = snapshot == null;
        boolean available = idle
                && TransactionOccupancyManager.get(this).canStartNewTransaction()
                && DeviceCommandManager.get(this).getRunningStatus() == 0;

        for (int index = 0; index < packageButtons.length; index++) {
            packageButtons[index].setEnabled(available && index < packageOptions.size());
        }
        paymentButton.setEnabled(available && selectedOption != null);

        boolean memberOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(snapshot.ownerType);
        boolean thirdOwned = snapshot != null
                && TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(snapshot.ownerType);
        boolean memberEnabled = memberWithdrawVisible
                && ((available && memberWithdrawAvailable) || memberOwned);
        boolean thirdEnabled = thirdPartyVisible
                && ((available && thirdPartyAvailable) || thirdOwned);
        memberWithdrawEntry.setEnabled(memberEnabled);
        thirdPartyRedemptionEntry.setEnabled(thirdEnabled);
        memberWithdrawEntry.setAlpha(memberEnabled ? 1f : 0.5f);
        thirdPartyRedemptionEntry.setAlpha(thirdEnabled ? 1f : 0.5f);

        if (idle) {
            cancelPaymentButton.setVisibility(View.GONE);
            boolean pendingCollection = DeviceCommandManager.get(this).hasPendingCollection();
            if (!pendingCollection) {
                collectionLayout.setVisibility(View.GONE);
                collectionStartButton.setEnabled(false);
                collectionFinishButton.setEnabled(false);
            }
            if (!available) {
                paymentStatusText.setText(R.string.machine_temporarily_unavailable);
            }
            return;
        }

        paymentButton.setEnabled(false);
        String owner = snapshot.ownerType;
        String phase = snapshot.phase;
        if (TransactionOccupancyManager.OWNER_QR_PURCHASE.equals(owner)) {
            cancelPaymentButton.setVisibility(View.VISIBLE);
            cancelPaymentButton.setEnabled(
                    !TransactionOccupancyManager.PHASE_CANCELLING.equals(phase)
                            && !TransactionOccupancyManager.PHASE_CONFIRMING_CLOSE.equals(phase)
                            && !TransactionOccupancyManager.PHASE_BLOCKED.equals(phase)
            );
            collectionStartButton.setEnabled(false);
            collectionFinishButton.setEnabled(false);
            if (!TransactionOccupancyManager.PHASE_DISPENSING.equals(phase)
                    && !TransactionOccupancyManager.PHASE_FINISHING.equals(phase)) {
                paymentStatusText.setText(manager.displayMessage(snapshot));
            }
            return;
        }

        cancelPaymentButton.setVisibility(View.GONE);
        paymentQrImage.setVisibility(View.GONE);
        if (TransactionOccupancyManager.OWNER_CASH_PURCHASE.equals(owner)) {
            paymentStatusText.setText(manager.displayMessage(snapshot));
            collectionStartButton.setEnabled(false);
            collectionFinishButton.setEnabled(false);
        } else if (TransactionOccupancyManager.OWNER_MEMBER_DEPOSIT.equals(owner)) {
            collectionLayout.setVisibility(View.VISIBLE);
            paymentStatusText.setText(R.string.transaction_member_deposit_active);
        } else {
            collectionStartButton.setEnabled(false);
            collectionFinishButton.setEnabled(false);
            if (TransactionOccupancyManager.OWNER_MEMBER_WITHDRAWAL.equals(owner)
                    || TransactionOccupancyManager.OWNER_THIRD_PARTY_REDEMPTION.equals(owner)) {
                paymentStatusText.setText(manager.displayMessage(snapshot));
            }
        }
    }

    private void showDispenseOverlay(boolean visible) {
        dispenseOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private String formatDispenseCount(int actual, int requested) {
        if (requested > 0) {
            return getString(
                    R.string.dispense_order_count_format,
                    Math.max(0, actual),
                    requested
            );
        }
        return getString(R.string.dispense_order_waiting);
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
                applyTransactionOccupancy();
                if (TransactionOccupancyManager.get(this).isIdle()) {
                    paymentStatusText.setText(R.string.machine_ready);
                }
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
        filter.addAction(AppConfig.ACTION_DISPENSE_ORDER_EVENT);
        filter.addAction(TransactionOccupancyManager.ACTION_CHANGED);
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

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
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
