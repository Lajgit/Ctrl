package com.gouzhu;

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
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.activation.ActivationLogStore;
import com.gouzhu.payment.QrCodeUtil;
import com.gouzhu.util.DeviceUtil;

/**
 * 首次设备报到后的扫码认领界面。
 *
 * <p>二维码内容和认领码只使用服务端 enroll 响应，不在设备端自行拼接。
 * 当平台尚未登记设备身份公钥时，本页显示设备号和 X.509 公钥，供平台先完成身份登记；
 * 身份登记成功后服务会自动重试 enroll，并在本页切换为服务端返回的认领二维码。</p>
 */
public final class ActivationActivity extends AppCompatActivity {

    public static final String EXTRA_CLAIM_QR_CONTENT = "claimQrContent";
    public static final String EXTRA_CLAIM_CODE = "claimCode";
    public static final String EXTRA_ACTIVATION_STATUS = "activationStatus";
    public static final String EXTRA_IDENTITY_REGISTRATION_REQUIRED =
            "identityRegistrationRequired";
    public static final String EXTRA_IDENTITY_PUBLIC_KEY = "identityPublicKey";

    private static final long SUCCESS_CLOSE_DELAY_MS = 1_200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageView claimQrImage;
    private TextView qrUnavailableText;
    private TextView claimCodeText;
    private TextView activationStatusText;
    private TextView identityRegistrationText;
    private String deviceNo;
    private boolean receiverRegistered;
    private boolean completing;

    private final BroadcastReceiver activationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())
                    || !"activation".equals(intent.getStringExtra("key"))) {
                return;
            }

            String status = intent.getStringExtra("value");
            boolean identityRegistrationRequired = intent.getBooleanExtra(
                    EXTRA_IDENTITY_REGISTRATION_REQUIRED,
                    false
            );
            if (identityRegistrationRequired) {
                renderIdentityRegistration(
                        intent.getStringExtra(EXTRA_IDENTITY_PUBLIC_KEY),
                        status
                );
            } else {
                String qrContent = intent.getStringExtra(EXTRA_CLAIM_QR_CONTENT);
                String claimCode = intent.getStringExtra(EXTRA_CLAIM_CODE);
                if (notBlank(qrContent) || notBlank(claimCode)) {
                    renderClaim(qrContent, claimCode);
                }
                if (notBlank(status)) {
                    activationStatusText.setText(status);
                }
            }

            if (status != null && status.contains("认证成功")) {
                completeActivation();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activation);
        hideSystemUi();

        claimQrImage = findViewById(R.id.image_activation_qr);
        qrUnavailableText = findViewById(R.id.text_activation_qr_unavailable);
        claimCodeText = findViewById(R.id.text_activation_claim_code);
        activationStatusText = findViewById(R.id.text_activation_status);
        identityRegistrationText = findViewById(R.id.text_activation_identity_registration);

        deviceNo = DeviceUtil.getDeviceId(this);
        TextView deviceText = findViewById(R.id.text_activation_device);
        deviceText.setText(getString(R.string.activation_device_format, deviceNo));

        applyIntent(getIntent());
        ActivationLogStore.append(this, "注册/激活界面", "已显示设备注册激活界面");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AppConfig.ACTION_SERVICE_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                        activationReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                registerReceiver(activationReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(activationReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 未完成认领时不允许返回顾客购珠页，避免展示不可用套餐。
    }

    private void applyIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String status = intent.getStringExtra(EXTRA_ACTIVATION_STATUS);
        if (intent.getBooleanExtra(EXTRA_IDENTITY_REGISTRATION_REQUIRED, false)) {
            renderIdentityRegistration(
                    intent.getStringExtra(EXTRA_IDENTITY_PUBLIC_KEY),
                    status
            );
            return;
        }

        renderClaim(
                intent.getStringExtra(EXTRA_CLAIM_QR_CONTENT),
                intent.getStringExtra(EXTRA_CLAIM_CODE)
        );
        if (notBlank(status)) {
            activationStatusText.setText(status);
        }
    }

    private void renderClaim(String qrContent, String claimCode) {
        identityRegistrationText.setVisibility(View.GONE);

        Bitmap bitmap = QrCodeUtil.create(qrContent, 520);
        if (bitmap != null) {
            claimQrImage.setImageBitmap(bitmap);
            claimQrImage.setVisibility(View.VISIBLE);
            qrUnavailableText.setVisibility(View.GONE);
        } else {
            claimQrImage.setImageDrawable(null);
            claimQrImage.setVisibility(View.GONE);
            qrUnavailableText.setText(R.string.activation_qr_waiting);
            qrUnavailableText.setVisibility(View.VISIBLE);
        }

        claimCodeText.setText(notBlank(claimCode)
                ? getString(R.string.activation_claim_code_format, claimCode)
                : getString(R.string.activation_claim_code_waiting));
    }

    private void renderIdentityRegistration(String publicKey, String status) {
        claimQrImage.setImageDrawable(null);
        claimQrImage.setVisibility(View.GONE);
        qrUnavailableText.setText(R.string.activation_identity_not_registered);
        qrUnavailableText.setVisibility(View.VISIBLE);
        claimCodeText.setText(R.string.activation_claim_code_unavailable);

        identityRegistrationText.setText(getString(
                R.string.activation_identity_info_format,
                deviceNo == null ? "" : deviceNo,
                notBlank(publicKey) ? publicKey : getString(R.string.activation_identity_key_unavailable)
        ));
        identityRegistrationText.setVisibility(View.VISIBLE);
        activationStatusText.setText(notBlank(status)
                ? status
                : getString(R.string.activation_identity_registration_hint));
    }

    private void completeActivation() {
        if (completing) {
            return;
        }
        completing = true;
        activationStatusText.setText(R.string.activation_success);
        mainHandler.postDelayed(() -> {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(mainIntent);
            finish();
        }, SUCCESS_CLOSE_DELAY_MS);
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
}
