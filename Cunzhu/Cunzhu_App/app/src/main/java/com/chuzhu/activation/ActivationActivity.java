package com.chuzhu.activation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.chuzhu.AppConfig;
import com.chuzhu.MainActivity;
import com.chuzhu.R;
import com.chuzhu.data.ActivationStore;
import com.chuzhu.device.DeviceUtil;

/**
 * 存珠机注册激活页面。
 */
public final class ActivationActivity extends AppCompatActivity {

    public static final String EXTRA_CLAIM_QR_CONTENT = "claimQrContent";
    public static final String EXTRA_CLAIM_CODE = "claimCode";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";

    private TextView deviceText;
    private TextView statusText;
    private TextView claimCodeText;
    private TextView qrText;
    private TextView errorText;
    private boolean receiverRegistered;

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

    private void apply(String status, String claimCode, String qrContent, String error) {
        statusText.setText("激活状态: " + firstNotBlank(status, "等待注册激活"));
        claimCodeText.setText("认领码: " + firstNotBlank(claimCode, "等待平台返回"));
        qrText.setText("认领二维码内容: " + firstNotBlank(qrContent, "等待平台返回"));
        errorText.setText("错误: " + firstNotBlank(error, "无"));
    }

    private static String firstNotBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second == null || second.trim().isEmpty() ? "" : second;
    }
}
