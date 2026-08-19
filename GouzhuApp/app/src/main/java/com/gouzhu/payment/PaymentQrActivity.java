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
 * 底部抽屉式统一支付窗口。
 *
 * <p>同一订单同时展示主扫和付款码反扫能力。点击 X 或抽屉外空白区域都调用
 * cancelPurchase；取消发起后两个支付入口立即同步停止，最终仍以服务端订单状态为准。</p>
 */
public final class PaymentQrActivity extends AppCompatActivity {

    public static final String ACTION_CLOSE = "com.gouzhu.action.PAYMENT_QR_DIALOG_CLOSE";
    public static final String EXTRA_REQUEST_NO = "requestNo";
    public static final String EXTRA_QR_CONTENT = "qrContent";
    public static final String EXTRA_BEAD_COUNT = "beadCount";
    public static final String EXTRA_PRICE_FEN = "priceFen";
    public static final String EXTRA_DEADLINE = "deadline";

    private View scrimView;
    private View drawerView;
    private TextView countdownText;
    private TextView purchaseText;
    private TextView priceText;
    private TextView methodTitleText;
    private TextView methodHintText;
    private TextView statusText;
    private ImageView qrImage;

    private CountDownTimer countDownTimer;
    private String requestNo = "";
    private String fallbackQrContent = "";
    private long selectionDeadline;
    private boolean receiverRegistered;
    private boolean timeoutHandled;
    private boolean userCloseInProgress;
    private boolean enterAnimationStarted;
    private boolean finishAnimationStarted;

    private final BroadcastReceiver paymentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (ACTION_CLOSE.equals(intent.getAction())) {
                String closingRequestNo = safe(intent.getStringExtra(EXTRA_REQUEST_NO));
                if (closingRequestNo.isEmpty() || closingRequestNo.equals(requestNo)) {
                    finishSafely();
                }
                return;
            }
            if (!PaymentManager.ACTION_PAYMENT_EVENT.equals(intent.getAction())) {
                return;
            }
            String eventRequestNo = safe(
                    intent.getStringExtra(PaymentManager.EXTRA_ORDER_ID)
            );
            if (!eventRequestNo.isEmpty() && !eventRequestNo.equals(requestNo)) {
                return;
            }
            String event = safe(intent.getStringExtra(PaymentManager.EXTRA_EVENT));
            if (PaymentManager.EVENT_CANCELLING.equals(event)
                    || PaymentManager.EVENT_CLOSED.equals(event)
                    || PaymentManager.EVENT_SUCCESS.equals(event)
                    || PaymentManager.EVENT_FAILED.equals(event)) {
                finishSafely();
                return;
            }
            refreshPaymentState();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        setContentView(R.layout.activity_payment_qr);
        setFinishOnTouchOutside(false);
        bindViews();
        bindActions();
        registerPaymentReceiver();
        handleIntent(getIntent());
        if (!isFinishing()) {
            startEnterAnimation();
        }
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
        window.clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        );
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.width = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.height = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.horizontalMargin = 0f;
        attributes.verticalMargin = 0f;
        window.setAttributes(attributes);
    }

    @Override
    protected void onDestroy() {
        stopCountdown();
        if (scrimView != null) {
            scrimView.animate().cancel();
        }
        if (drawerView != null) {
            drawerView.animate().cancel();
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(paymentReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 系统返回键不直接关闭；顾客使用可见 X 或点击抽屉外空白区域，二者都走安全取消。
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void bindViews() {
        scrimView = findViewById(R.id.payment_dialog_scrim);
        drawerView = findViewById(R.id.payment_dialog_drawer);
        countdownText = findViewById(R.id.text_payment_dialog_countdown);
        purchaseText = findViewById(R.id.text_payment_dialog_purchase);
        priceText = findViewById(R.id.text_payment_dialog_price);
        methodTitleText = findViewById(R.id.text_payment_dialog_method_title);
        methodHintText = findViewById(R.id.text_payment_dialog_method_hint);
        statusText = findViewById(R.id.text_payment_dialog_status);
        qrImage = findViewById(R.id.image_payment_dialog_qr);
    }

    private void bindActions() {
        // 用户要求 X 和空白区域行为一致：都同步关闭主扫/反扫入口并请求服务端取消订单。
        scrimView.setOnClickListener(view -> requestUserClose());
        drawerView.setOnClickListener(view -> {
            // 抽屉本体消费点击，避免内部空白穿透到外层遮罩。
        });
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
        fallbackQrContent = safe(intent.getStringExtra(EXTRA_QR_CONTENT));
        int beadCount = Math.max(0, intent.getIntExtra(EXTRA_BEAD_COUNT, 0));
        int priceFen = Math.max(0, intent.getIntExtra(EXTRA_PRICE_FEN, 0));
        selectionDeadline = intent.getLongExtra(EXTRA_DEADLINE, 0L);

        if (requestNo.isEmpty()) {
            finishSafely();
            return;
        }

        purchaseText.setText(getString(R.string.payment_dialog_purchase_format, beadCount));
        priceText.setText(getString(
                R.string.payment_dialog_price_format,
                priceFen / 100.0
        ));
        if (selectionDeadline <= 0L) {
            selectionDeadline = PaymentQrPopupReceiver.ensureDeadline(this, requestNo);
        }
        refreshPaymentState();
    }

    private void refreshPaymentState() {
        PaymentManager manager = PaymentManager.get(this);
        String currentRequestNo = manager.getCurrentOrderId();
        if (currentRequestNo.isEmpty() || !requestNo.equals(currentRequestNo)) {
            finishSafely();
            return;
        }

        String selectedMode = manager.getCurrentSelectedPaymentMode();
        String paymentStatus = manager.getCurrentPaymentStatus();
        String qrContent = manager.getCurrentScanUrl();
        if (qrContent.isEmpty()) {
            qrContent = fallbackQrContent;
        }

        boolean authAvailable = manager.canSubmitAuthCode(PaymentAuthCodePolicy.CHANNEL_WECHAT)
                || manager.canSubmitAuthCode(PaymentAuthCodePolicy.CHANNEL_ALIPAY);
        boolean showQr = manager.shouldShowQrCode() && !qrContent.isEmpty();

        if ("AUTH_CODE".equals(selectedMode) || manager.isAuthCodeSubmitted()) {
            showQr = false;
            methodTitleText.setText(R.string.payment_dialog_code_pay_title);
            methodHintText.setText(R.string.payment_dialog_auth_selected);
        } else if ("SCAN".equals(selectedMode)) {
            methodTitleText.setText(R.string.payment_dialog_scan_pay_title);
            methodHintText.setText(R.string.payment_dialog_scan_selected);
        } else if (showQr && authAvailable) {
            methodTitleText.setText(R.string.payment_dialog_dual_pay_title);
            methodHintText.setText(R.string.payment_dialog_dual_pay_hint);
        } else if (showQr) {
            methodTitleText.setText(R.string.payment_dialog_scan_pay_title);
            methodHintText.setText(R.string.payment_dialog_scan_pay_channels);
        } else if (authAvailable) {
            methodTitleText.setText(R.string.payment_dialog_code_pay_title);
            methodHintText.setText(R.string.payment_dialog_code_pay_hint);
        } else {
            methodTitleText.setText(R.string.payment_dialog_title);
            methodHintText.setText(R.string.payment_dialog_no_channel);
        }

        if (showQr) {
            Bitmap bitmap = QrCodeUtil.create(qrContent, 620);
            if (bitmap != null) {
                qrImage.setImageBitmap(bitmap);
                qrImage.setVisibility(View.VISIBLE);
            } else {
                // 二维码异常不应破坏同一订单的付款码能力；只关闭主扫显示。
                qrImage.setImageDrawable(null);
                qrImage.setVisibility(View.GONE);
                if (authAvailable) {
                    methodTitleText.setText(R.string.payment_dialog_code_pay_title);
                    methodHintText.setText(R.string.payment_dialog_code_pay_hint);
                }
            }
        } else {
            qrImage.setImageDrawable(null);
            qrImage.setVisibility(View.GONE);
        }

        if (manager.isCancelPending()) {
            statusText.setText(R.string.payment_dialog_closing);
        } else if ("SUCCESS".equals(paymentStatus)
                || "ORDER_ALREADY_PAID".equals(paymentStatus)) {
            statusText.setText(R.string.payment_dialog_paid);
        } else {
            statusText.setText(manager.getDisplayMessage());
        }

        if (manager.canAutoCancelForUserTimeout()) {
            countdownText.setVisibility(View.VISIBLE);
            // 明确 FAILED 后若服务端回到未选支付方式状态，重新给顾客完整 60 秒。
            selectionDeadline = PaymentQrPopupReceiver.ensureDeadline(this, requestNo);
            startCountdown(selectionDeadline);
        } else {
            stopCountdown();
            countdownText.setVisibility(View.GONE);
            // 一旦任一入口已被选择/提交，60 秒只是顾客选择窗口，不能再触发本地超时取消。
            PaymentQrPopupReceiver.clearDeadline(this, requestNo);
        }
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
        PaymentManager manager = PaymentManager.get(this);
        if (!manager.canAutoCancelForUserTimeout()) {
            refreshPaymentState();
            return;
        }
        PaymentQrPopupReceiver.clearDeadline(this, requestNo);
        boolean accepted = manager.cancelCurrentPayment();
        if (accepted) {
            Toast.makeText(this, R.string.payment_dialog_timeout, Toast.LENGTH_SHORT).show();
            finishSafely();
        } else {
            timeoutHandled = false;
            refreshPaymentState();
        }
    }

    private void startEnterAnimation() {
        if (enterAnimationStarted || scrimView == null || drawerView == null) {
            return;
        }
        enterAnimationStarted = true;
        scrimView.setAlpha(0f);
        drawerView.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        drawerView.post(() -> {
            if (isFinishing() || finishAnimationStarted) {
                return;
            }
            drawerView.animate()
                    .translationY(0f)
                    .setDuration(260L)
                    .start();
            scrimView.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .start();
        });
    }

    private void stopCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    private void finishSafely() {
        stopCountdown();
        if (isFinishing() || finishAnimationStarted) {
            return;
        }
        if (drawerView == null
                || scrimView == null
                || drawerView.getHeight() <= 0) {
            finishWithoutAnimation();
            return;
        }

        finishAnimationStarted = true;
        drawerView.animate().cancel();
        scrimView.animate().cancel();
        drawerView.animate()
                .translationY(drawerView.getHeight())
                .setDuration(220L)
                .withEndAction(this::finishWithoutAnimation)
                .start();
        scrimView.animate()
                .alpha(0f)
                .setDuration(180L)
                .start();
    }

    private void finishWithoutAnimation() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void registerPaymentReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CLOSE);
        filter.addAction(PaymentManager.ACTION_PAYMENT_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(paymentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(paymentReceiver, filter);
        }
        receiverRegistered = true;
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
