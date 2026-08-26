package com.chuzhu.activation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.chuzhu.AppConfig;
import com.chuzhu.MainActivity;
import com.chuzhu.R;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.device.DeviceUtil;
import com.chuzhu.member.QrCodeUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 存珠机注册激活页面。
 */
public final class ActivationActivity extends AppCompatActivity {

    public static final String EXTRA_CLAIM_QR_CONTENT = "claimQrContent";
    public static final String EXTRA_CLAIM_CODE = "claimCode";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";

    private static final String TAG = "CunzhuActivation";

    private TextView deviceText;
    private TextView statusText;
    private TextView claimCodeText;
    private TextView qrText;
    private TextView qrPlaceholderText;
    private ImageView qrImage;
    private TextView errorText;
    private boolean receiverRegistered;
    private String lastQrContent = "";
    private String qrGeneratingContent = "";
    private final ExecutorService qrWorker = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver activationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !AppConfig.ACTION_SERVICE_STATUS.equals(intent.getAction())
                    || !"activation".equals(intent.getStringExtra("key"))) {
                return;
            }
            apply(
                    intent.getStringExtra(EXTRA_STATUS) == null
                            ? intent.getStringExtra("value")
                            : intent.getStringExtra(EXTRA_STATUS),
                    intent.getStringExtra(EXTRA_CLAIM_CODE),
                    intent.getStringExtra(EXTRA_CLAIM_QR_CONTENT),
                    intent.getStringExtra(EXTRA_ERROR)
            );
            if (new ActivationStore(ActivationActivity.this).isActivated()) {
                startActivity(new Intent(ActivationActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activation);
        deviceText = findViewById(R.id.text_activation_device);
        statusText = findViewById(R.id.text_activation_status);
        claimCodeText = findViewById(R.id.text_activation_claim_code);
        qrText = findViewById(R.id.text_activation_qr);
        qrPlaceholderText = findViewById(R.id.text_activation_qr_placeholder);
        qrImage = findViewById(R.id.image_activation_qr);
        errorText = findViewById(R.id.text_activation_error);
        deviceText.setText("deviceNo: " + DeviceUtil.getDeviceNo(this));
        ActivationStore store = new ActivationStore(this);
        apply(
                getIntent().getStringExtra(EXTRA_STATUS),
                firstNotBlank(getIntent().getStringExtra(EXTRA_CLAIM_CODE), store.getClaimCode()),
                firstNotBlank(getIntent().getStringExtra(EXTRA_CLAIM_QR_CONTENT), store.getClaimQr()),
                firstNotBlank(getIntent().getStringExtra(EXTRA_ERROR), store.getLastError())
        );
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AppConfig.ACTION_SERVICE_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(activationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(activationReceiver, filter);
        }
        receiverRegistered = true;
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
        qrWorker.shutdownNow();
        super.onDestroy();
    }

    private void apply(String status, String claimCode, String qrContent, String error) {
        String normalizedQr = firstNotBlank(qrContent, "");
        statusText.setText("激活状态: " + firstNotBlank(status, "等待注册激活"));
        claimCodeText.setText("认领码: " + firstNotBlank(claimCode, "等待平台返回"));
        qrText.setText("认领二维码内容: " + firstNotBlank(normalizedQr, "等待平台返回"));
        errorText.setText("错误: " + firstNotBlank(error, "无"));
        updateQr(normalizedQr);
    }

    private void updateQr(String qrContent) {
        if (qrContent == null || qrContent.trim().isEmpty()) {
            lastQrContent = "";
            qrGeneratingContent = "";
            qrImage.setImageBitmap(null);
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("等待平台返回认领二维码");
            return;
        }
        final String normalized = qrContent.trim();
        if (normalized.equals(lastQrContent) && qrImage.getDrawable() != null) {
            qrImage.setVisibility(View.VISIBLE);
            qrPlaceholderText.setVisibility(View.GONE);
            return;
        }
        if (normalized.equals(qrGeneratingContent)) {
            qrImage.setVisibility(View.GONE);
            qrPlaceholderText.setVisibility(View.VISIBLE);
            qrPlaceholderText.setText("正在生成认领二维码");
            return;
        }

        qrGeneratingContent = normalized;
        qrImage.setVisibility(View.GONE);
        qrPlaceholderText.setVisibility(View.VISIBLE);
        qrPlaceholderText.setText("正在生成认领二维码");

        /* 认领二维码也在后台线程生成，避免 RK3566 首页/激活页掉帧。 */
        qrWorker.execute(() -> {
            try {
                Bitmap bitmap = QrCodeUtil.create(normalized, 480);
                runOnUiThread(() -> {
                    qrGeneratingContent = "";
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String currentText = qrText.getText() == null ? "" : qrText.getText().toString();
                    if (!currentText.contains(normalized)) {
                        return;
                    }
                    qrImage.setImageBitmap(bitmap);
                    lastQrContent = normalized;
                    qrImage.setVisibility(View.VISIBLE);
                    qrPlaceholderText.setVisibility(View.GONE);
                });
            } catch (Throwable error) {
                Log.e(TAG, "生成认领二维码失败", error);
                runOnUiThread(() -> {
                    qrGeneratingContent = "";
                    qrImage.setVisibility(View.GONE);
                    qrPlaceholderText.setVisibility(View.VISIBLE);
                    qrPlaceholderText.setText("认领二维码生成失败");
                    errorText.setText("错误: 认领二维码生成失败：" + messageOf(error));
                });
            }
        });
    }

    private static String firstNotBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        return second == null || second.trim().isEmpty() ? "" : second.trim();
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }
}
