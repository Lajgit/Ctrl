package com.gouzhu.payment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;

import com.gouzhu.R;
import com.gouzhu.sdk.DeviceSdkManager;
import com.gouzhu.transaction.TransactionOccupancyManager;
import com.pinball.xiaoda.device.sdk.client.DeviceAppBootstrapResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 付款码被扫入口。
 *
 * <p>该控件以增量方式挂在现有首页，不改动原“点击套餐直接聚合主扫”的代码路径。
 * 顾客只有主动点击本入口并重新选择套餐后，才会创建 AUTH_CODE 订单。</p>
 */
public final class AuthCodePaymentEntryButton extends AppCompatButton {

    private static final int[] MAIN_PACKAGE_BUTTON_IDS = new int[]{
            R.id.button_package_1,
            R.id.button_package_5,
            R.id.button_package_10,
            R.id.button_package_20,
            R.id.button_package_50,
            R.id.button_package_100
    };

    private boolean receiverRegistered;
    private boolean bootstrapLoading;
    private boolean packageButtonsTemporarilyLocked;
    private final boolean[] packageButtonPreviousEnabled =
            new boolean[MAIN_PACKAGE_BUTTON_IDS.length];

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (AuthCodePaymentManager.ACTION_AUTH_CODE_PAYMENT_EVENT.equals(intent.getAction())) {
                String event = safe(intent.getStringExtra(AuthCodePaymentManager.EXTRA_EVENT));
                String message = safe(intent.getStringExtra(AuthCodePaymentManager.EXTRA_MESSAGE));
                if (AuthCodePaymentManager.EVENT_CODE_REJECTED.equals(event)
                        || AuthCodePaymentManager.EVENT_FAILED.equals(event)) {
                    showMessage(message);
                }
            }
            refreshState();
        }
    };

    public AuthCodePaymentEntryButton(@NonNull Context context) {
        super(context);
        init();
    }

    public AuthCodePaymentEntryButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        init();
    }

    public AuthCodePaymentEntryButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnClickListener(view -> onEntryClicked());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerReceiverIfNeeded();
        AuthCodePaymentManager.get(getContext()).resumePendingPayment();
        refreshState();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AuthCodePaymentManager.ACTION_AUTH_CODE_PAYMENT_EVENT);
        filter.addAction(TransactionOccupancyManager.ACTION_CHANGED);
        getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void onEntryClicked() {
        AuthCodePaymentManager manager = AuthCodePaymentManager.get(getContext());
        if (manager.isActive()) {
            cancelActivePayment(manager);
            return;
        }
        if (!TransactionOccupancyManager.get(getContext()).canStartNewTransaction()) {
            showMessage(getContext().getString(R.string.transaction_device_busy));
            return;
        }
        openPackageSelector();
    }

    private void openPackageSelector() {
        DeviceAppBootstrapResult bootstrap = DeviceSdkManager.get(getContext()).getLastBootstrap();
        if (bootstrap != null) {
            showPackageSelector(bootstrap);
            return;
        }
        if (bootstrapLoading) {
            return;
        }
        bootstrapLoading = true;
        setEnabled(false);
        setText(R.string.auth_code_loading_packages);
        DeviceSdkManager.get(getContext()).refreshBootstrap(new DeviceSdkManager.BootstrapCallback() {
            @Override
            public void onSuccess(DeviceAppBootstrapResult result) {
                post(() -> {
                    bootstrapLoading = false;
                    refreshState();
                    showPackageSelector(result);
                });
            }

            @Override
            public void onFailure(Throwable error) {
                post(() -> {
                    bootstrapLoading = false;
                    refreshState();
                    showMessage(getContext().getString(R.string.auth_code_packages_unavailable));
                });
            }
        });
    }

    private void showPackageSelector(DeviceAppBootstrapResult bootstrap) {
        if (bootstrap == null
                || !TransactionOccupancyManager.get(getContext()).canStartNewTransaction()) {
            refreshState();
            return;
        }
        List<PackageOption> options = buildOptions(bootstrap);
        if (options.isEmpty()) {
            showMessage(getContext().getString(R.string.sdk_no_purchase_tier));
            return;
        }
        String[] labels = new String[options.size()];
        for (int index = 0; index < options.size(); index++) {
            PackageOption option = options.get(index);
            labels[index] = getContext().getString(
                    R.string.auth_code_package_format,
                    option.quantity,
                    option.priceFen / 100.0
            );
        }

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.auth_code_choose_package)
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < options.size()) {
                        startPayment(options.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startPayment(PackageOption option) {
        if (option == null
                || !TransactionOccupancyManager.get(getContext()).canStartNewTransaction()) {
            showMessage(getContext().getString(R.string.transaction_device_busy));
            return;
        }
        // AUTH_CODE 预关闭现金的短窗口内同步禁用首页套餐，避免顾客并发启动原主扫流程。
        lockMainPackageButtons();
        setEnabled(false);
        setText(R.string.auth_code_preparing);
        try {
            AuthCodePaymentManager.get(getContext()).startPayment(
                    option.purchaseRuleId,
                    option.priceTierId,
                    option.purchaseQuantity,
                    option.quantity,
                    option.priceFen
            );
        } catch (Throwable error) {
            restoreMainPackageButtons();
            refreshState();
            showMessage(messageOf(error));
        }
    }

    private void cancelActivePayment(AuthCodePaymentManager manager) {
        if (!manager.canCancelCurrentPayment()) {
            showMessage(getContext().getString(R.string.auth_code_cancel_unavailable));
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.auth_code_cancel_title)
                .setMessage(R.string.auth_code_cancel_confirmation)
                .setNegativeButton(R.string.payment_cancel_keep, null)
                .setPositiveButton(R.string.payment_cancel_confirm, (dialog, which) -> {
                    setEnabled(false);
                    if (!manager.cancelCurrentPayment()) {
                        refreshState();
                        showMessage(getContext().getString(R.string.auth_code_cancel_unavailable));
                    }
                })
                .show();
    }

    private void refreshState() {
        AuthCodePaymentManager manager = AuthCodePaymentManager.get(getContext());
        if (manager.isActive()) {
            String paymentStatus = manager.getPaymentStatus();
            if (manager.canCancelCurrentPayment()) {
                setEnabled(true);
                setText("FAILED".equals(paymentStatus)
                        ? R.string.auth_code_rescan_or_cancel
                        : R.string.auth_code_show_code_or_cancel);
            } else {
                setEnabled(false);
                if ("SUCCESS".equals(paymentStatus)) {
                    setText(R.string.auth_code_paid_wait_dispense);
                } else {
                    setText(manager.getDisplayMessage());
                }
            }
            return;
        }

        boolean available = TransactionOccupancyManager.get(getContext()).canStartNewTransaction();
        setEnabled(available && !bootstrapLoading);
        setText(bootstrapLoading
                ? R.string.auth_code_loading_packages
                : R.string.auth_code_payment_mode);
        if (available) {
            restoreMainPackageButtons();
        }
    }

    private void lockMainPackageButtons() {
        if (packageButtonsTemporarilyLocked) {
            return;
        }
        View root = getRootView();
        if (root == null) {
            return;
        }
        for (int index = 0; index < MAIN_PACKAGE_BUTTON_IDS.length; index++) {
            View view = root.findViewById(MAIN_PACKAGE_BUTTON_IDS[index]);
            if (view instanceof Button) {
                packageButtonPreviousEnabled[index] = view.isEnabled();
                view.setEnabled(false);
            }
        }
        packageButtonsTemporarilyLocked = true;
    }

    private void restoreMainPackageButtons() {
        if (!packageButtonsTemporarilyLocked) {
            return;
        }
        View root = getRootView();
        if (root == null) {
            return;
        }
        for (int index = 0; index < MAIN_PACKAGE_BUTTON_IDS.length; index++) {
            View view = root.findViewById(MAIN_PACKAGE_BUTTON_IDS[index]);
            if (view instanceof Button) {
                view.setEnabled(packageButtonPreviousEnabled[index]);
            }
        }
        packageButtonsTemporarilyLocked = false;
    }

    private static List<PackageOption> buildOptions(DeviceAppBootstrapResult bootstrap) {
        List<PackageOption> options = new ArrayList<>();
        if (bootstrap == null || bootstrap.getPurchaseRules() == null) {
            return options;
        }
        for (DeviceAppBootstrapResult.PurchaseRule rule : bootstrap.getPurchaseRules()) {
            if (rule == null || rule.getPurchaseRuleId() == null
                    || rule.getPurchaseRuleId() <= 0L) {
                continue;
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
                    options.add(new PackageOption(
                            rule.getPurchaseRuleId(),
                            tier.getId(),
                            null,
                            tier.getPurchaseQuantity(),
                            tier.getPriceAmount()
                    ));
                    if (options.size() >= 6) {
                        return options;
                    }
                }
                continue;
            }
            if (rule.getPricingUnitQuantity() != null
                    && rule.getPricingUnitQuantity() > 0
                    && rule.getUnitPriceAmount() != null
                    && rule.getUnitPriceAmount() > 0) {
                options.add(new PackageOption(
                        rule.getPurchaseRuleId(),
                        null,
                        rule.getPricingUnitQuantity(),
                        rule.getPricingUnitQuantity(),
                        rule.getUnitPriceAmount()
                ));
                if (options.size() >= 6) {
                    return options;
                }
            }
        }
        return options;
    }

    private void showMessage(String message) {
        String safeMessage = safe(message);
        if (!safeMessage.isEmpty()) {
            Toast.makeText(getContext(), safeMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = safe(error.getMessage());
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
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
