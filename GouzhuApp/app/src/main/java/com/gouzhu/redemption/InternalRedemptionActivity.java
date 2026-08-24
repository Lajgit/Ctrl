package com.gouzhu.redemption;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.R;

/** 官方小程序套餐券核销页面。 */
public final class InternalRedemptionActivity extends AppCompatActivity {

    private TextView statusText;
    private LinearLayout scanSection;
    private TextView resultDetailText;
    private Button backButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null
                    && InternalRedemptionManager.ACTION_CHANGED.equals(intent.getAction())) {
                refresh();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_internal_redemption);
        bindViews();
        findViewById(R.id.button_internal_redemption_close)
                .setOnClickListener(view -> requestClose());
        backButton.setOnClickListener(view -> requestClose());
        registerReceiverIfNeeded();
        hideSystemUi();

        InternalRedemptionManager manager = InternalRedemptionManager.get(this);
        if (manager.snapshot() == null) {
            manager.beginScan();
        } else {
            manager.resumePending();
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        InternalRedemptionManager.get(this).resumePending();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        requestClose();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void bindViews() {
        statusText = findViewById(R.id.text_internal_redemption_status);
        scanSection = findViewById(R.id.layout_internal_redemption_scan);
        resultDetailText = findViewById(R.id.text_internal_redemption_result_detail);
        backButton = findViewById(R.id.button_internal_redemption_back);
    }

    private void refresh() {
        InternalRedemptionManager.UiSnapshot snapshot =
                InternalRedemptionManager.get(this).snapshot();
        if (snapshot == null) {
            statusText.setText(R.string.internal_redemption_preparing);
            scanSection.setVisibility(View.VISIBLE);
            resultDetailText.setText("");
            backButton.setText(R.string.redemption_back_home);
            return;
        }

        statusText.setText(snapshot.message);
        boolean scanning = InternalRedemptionManager.STATE_STARTING.equals(snapshot.uiState)
                || InternalRedemptionManager.STATE_SCANNING.equals(snapshot.uiState);
        scanSection.setVisibility(scanning ? View.VISIBLE : View.GONE);

        if (InternalRedemptionManager.STATE_MANUAL_REVIEW.equals(snapshot.uiState)) {
            resultDetailText.setText(R.string.internal_redemption_manual_review);
        } else if (snapshot.requestedQuantity > 0) {
            resultDetailText.setText(getString(
                    R.string.internal_redemption_quantity_format,
                    snapshot.dispensedQuantity,
                    snapshot.requestedQuantity
            ));
        } else {
            resultDetailText.setText("");
        }
        backButton.setText(snapshot.terminal
                ? R.string.redemption_finish
                : R.string.redemption_back_home);
    }

    private void requestClose() {
        InternalRedemptionManager manager = InternalRedemptionManager.get(this);
        InternalRedemptionManager.UiSnapshot snapshot = manager.snapshot();
        if (snapshot == null) {
            finish();
            return;
        }
        if (snapshot.terminal) {
            manager.acknowledgeTerminal();
            finish();
            return;
        }
        if (manager.abandonBeforeSubmit()) {
            finish();
            return;
        }
        // HTTP 请求已经提交后返回首页不等于取消核销，后台继续查询原 requestNo。
        finish();
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(InternalRedemptionManager.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
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
}
