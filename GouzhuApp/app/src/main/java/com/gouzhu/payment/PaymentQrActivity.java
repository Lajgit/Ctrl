package com.gouzhu.payment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.R;

/**
 * 底部抽屉式二维码支付窗口。
 * 点击关闭按钮或抽屉外的变暗区域都会请求取消当前订单；支付终态也会自动关闭。
 */
public final class PaymentQrActivity extends AppCompatActivity {

    public static final String ACTION_CLOSE = "com.gouzhu.action.PAYMENT_QR_DIALOG_CLOSE";
    public static final String EXTRA_REQUEST_NO = "requestNo";
    public static final String EXTRA_QR_CONTENT = "qrContent";
    public static final String EXTRA_BEAD_COUNT = "beadCount";
    public static final String EXTRA_PRICE_FEN = "priceFen";
    public static final String EXTRA_DEADLINE = "deadline";

    private TextView countdownText;
    private TextView purchaseText;
    private TextView priceText;
    private TextView statusText;
    private ImageView qrImage;

    private CountDownTimer countDownTimer;
    private String requestNo = "";
    private boolean closeReceiverRegistered;
    private boolean timeoutHandled;
    private boolean userCloseInProgress;

    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_CLOSE.equals(intent.getAction())) {
                return;
            }
            String closingRequestNo = safe(intent.getStringExtra(EXTRA_REQUEST_NO));
            if (closingRequestNo.isEmpty() || closingRequestNo.equals(requestNo)) {
                finishSafely();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_qr);
        setFinishOnTouchOutside(false);
        bindViews();
        bindActions();
        registerCloseReceiver();
        handleIntent(getIntent());
        hideSystemUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        userCloseInProgress = false;
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        );
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.BOTTOM;
        attributes.dimAmount = 0.58f;
        attributes.width = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
        attributes.horizontalMargin = 0f;
        attributes.verticalMargin = 0f;
        window.setAttributes(attributes);
    }

    @Override
    protected void onDestroy() {
        stopCountdown();
        if (closeReceiverRegistered) {
            try {
                unregisterReceiver(closeReceiver);
            } catch (Throwable ignored) {
            }
            closeReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 避免系统返回键误取消订单；使用可见关闭按钮或点击抽屉外区域。
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            requestUserClose();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void bindViews() {
        countdownText = findViewById(R.id.text_payment_dialog_countdown);
        purchaseText = findViewById(R.id.text_payment_dialog_purchase);
        priceText = findViewById(R.id.text_payment_dialog_price);
        statusText = findViewById(R.id.text_payment_dialog_status);
        qrImage = findViewById(R.id.image_payment_dialog_qr);
    }

    private void bindActions() {
        findViewById(R.id.button_payment_dialog_close).setOnClickListener(
                view -> requestUserClose()
        );
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finishSafely();
            return;
        }
        requestNo = safe(intent.getStringExtra(EXTRA_REQUEST_NO));
        String qrContent = safe(intent.getStringExtra(EXTRA_QR_CONTENT));
        int beadCount = Math.max(0, intent.getIntExtra(EXTRA_BEAD_COUNT, 0));
        int priceFen = Math.max(0, intent.getIntExtra(EXTRA_PRICE_FEN, 0));
        long deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L);

        if (requestNo.isEmpty() || qrContent.isEmpty()) {
            finishSafely();
            return;
        }

        purchaseText.setText(getString(R.string.payment_dialog_purchase_format, beadCount));
        priceText.setText(getString(
                R.string.payment_dialog_price_format,
                priceFen / 100.0
        ));

        Bitmap bitmap = QrCodeUtil.create(qrContent, 620);
        if (bitmap == null) {
            statusText.setText(R.string.payment_qr_invalid);
            PaymentManager.get(this).cancelCurrentPayment();
            finishSafely();
            return;
        }
        qrImage.setImageBitmap(bitmap);
        statusText.setText(R.string.payment_dialog_scan_hint);

        long effectiveDeadline = deadline > 0L
                ? deadline
                : PaymentQrPopupReceiver.ensureDeadline(this, requestNo);
        startCountdown(effectiveDeadline);
    }

    private void requestUserClose() {
        if (userCloseInProgress) {
            return;
        }
        userCloseInProgress = true;
        boolean accepted = PaymentManager.get(this).cancelCurrentPayment();
        if (!accepted) {
            userCloseInProgress = false;
            Toast.makeText(
                    this,
                    R.string.payment_cancel_unavailable,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        statusText.setText(R.string.payment_dialog_closing);
        PaymentQrPopupReceiver.clearDeadline(this, requestNo);
        finishSafely();
    }

    private void startCountdown(long deadline) {
        stopCountdown();
        timeoutHandled = false;
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
            handleTimeout();
            return;
        }
        updateCountdown(remaining);
        countDownTimer = new CountDownTimer(remaining, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateCountdown(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                updateCountdown(0L);
                handleTimeout();
            }
        }.start();
    }

    private void updateCountdown(long remainingMs) {
        long seconds = remainingMs <= 0L
                ? 0L
                : Math.max(1L, (remainingMs + 999L) / 1000L);
        countdownText.setText(getString(
                R.string.payment_dialog_countdown_format,
                seconds
        ));
    }

    private void handleTimeout() {
        if (timeoutHandled) {
            return;
        }
        timeoutHandled = true;
        PaymentQrPopupReceiver.clearDeadline(this, requestNo);
        PaymentManager.get(this).cancelCurrentPayment();
        Toast.makeText(this, R.string.payment_dialog_timeout, Toast.LENGTH_SHORT).show();
        finishSafely();
    }

    private void stopCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    private void finishSafely() {
        stopCountdown();
        if (!isFinishing()) {
            finish();
        }
    }

    private void registerCloseReceiver() {
        if (closeReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_CLOSE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, filter);
        }
        closeReceiverRegistered = true;
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
