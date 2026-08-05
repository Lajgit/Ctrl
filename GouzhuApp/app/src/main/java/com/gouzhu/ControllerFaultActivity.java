package com.gouzhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.serial.BoardConnectionMonitor;

/** Full-screen modal fault page that blocks all customer operations while the controller is offline. */
public final class ControllerFaultActivity extends AppCompatActivity {

    private TextView detailText;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !AppConfig.ACTION_BOARD_CONNECTION_CHANGED.equals(intent.getAction())) {
                return;
            }
            boolean connected = intent.getBooleanExtra(
                    BoardConnectionMonitor.EXTRA_CONNECTED,
                    false
            );
            if (connected) {
                finishSafely();
                return;
            }
            updateReason(intent.getStringExtra(BoardConnectionMonitor.EXTRA_REASON));
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller_fault);
        setFinishOnTouchOutside(false);
        detailText = findViewById(R.id.text_controller_fault_detail);
        registerConnectionReceiver();
        BoardConnectionMonitor monitor = BoardConnectionMonitor.get(this);
        monitor.start();
        if (monitor.isConnected()) {
            finishSafely();
            return;
        }
        updateReason(monitor.getLastReason());
        hideSystemUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.width = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.height = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.dimAmount = 0.72f;
        window.setAttributes(attributes);
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
        // Controller recovery is the only way to close this blocking page.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void registerConnectionReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_CONNECTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void updateReason(String reason) {
        String safeReason = reason == null ? "" : reason.trim();
        detailText.setText(safeReason.isEmpty()
                ? getString(R.string.controller_fault_message)
                : safeReason + "\n" + getString(R.string.controller_fault_operation_blocked));
    }

    private void finishSafely() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
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
