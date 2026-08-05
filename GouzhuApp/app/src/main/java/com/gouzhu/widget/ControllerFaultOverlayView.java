package com.gouzhu.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.gouzhu.AppConfig;
import com.gouzhu.R;
import com.gouzhu.serial.BoardConnectionMonitor;

/** Full-screen non-interactive fault shield shown whenever the controller stops responding. */
public final class ControllerFaultOverlayView extends FrameLayout {

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
            String reason = intent.getStringExtra(BoardConnectionMonitor.EXTRA_REASON);
            applyState(connected, reason);
        }
    };

    public ControllerFaultOverlayView(Context context) {
        this(context, null);
    }

    public ControllerFaultOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ControllerFaultOverlayView(
            Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        buildContent();
        setVisibility(View.GONE);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setElevation(dp(100));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerReceiver();
        BoardConnectionMonitor monitor = BoardConnectionMonitor.get(getContext());
        monitor.start();
        if (monitor.isStateKnown()) {
            applyState(monitor.isConnected(), monitor.getLastReason());
        }
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

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return true;
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(AppConfig.ACTION_BOARD_CONNECTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void buildContent() {
        setBackgroundColor(Color.argb(218, 0, 0, 0));
        setForegroundGravity(Gravity.CENTER);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(36), dp(32), dp(36), dp(32));
        card.setBackgroundResource(R.drawable.bg_controller_fault_card);

        LayoutParams cardParams = new LayoutParams(
                Math.min(dp(620), getResources().getDisplayMetrics().widthPixels - dp(72)),
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        addView(card, cardParams);

        TextView icon = new TextView(getContext());
        icon.setText("!");
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(36f);
        icon.setTypeface(icon.getTypeface(), android.graphics.Typeface.BOLD);
        icon.setBackgroundResource(R.drawable.bg_controller_fault_icon);
        card.addView(icon, new LinearLayout.LayoutParams(dp(82), dp(82)));

        TextView title = new TextView(getContext());
        title.setText(R.string.controller_fault_title);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getResources().getColor(R.color.text_primary, getContext().getTheme()));
        title.setTextSize(30f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(22);
        card.addView(title, titleParams);

        detailText = new TextView(getContext());
        detailText.setText(R.string.controller_fault_message);
        detailText.setGravity(Gravity.CENTER);
        detailText.setTextColor(getResources().getColor(
                R.color.text_secondary,
                getContext().getTheme()
        ));
        detailText.setTextSize(19f);
        detailText.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(12);
        card.addView(detailText, detailParams);

        ProgressBar progress = new ProgressBar(getContext());
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        progressParams.topMargin = dp(24);
        card.addView(progress, progressParams);

        TextView retry = new TextView(getContext());
        retry.setText(R.string.controller_fault_reconnecting);
        retry.setGravity(Gravity.CENTER);
        retry.setTextColor(getResources().getColor(
                R.color.header_primary,
                getContext().getTheme()
        ));
        retry.setTextSize(17f);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        retryParams.topMargin = dp(12);
        card.addView(retry, retryParams);
    }

    private void applyState(boolean connected, String reason) {
        if (connected) {
            setVisibility(View.GONE);
            clearFocus();
            return;
        }
        String message = reason == null || reason.trim().isEmpty()
                ? getResources().getString(R.string.controller_fault_message)
                : reason.trim() + "\n" + getResources().getString(
                R.string.controller_fault_operation_blocked
        );
        detailText.setText(message);
        setVisibility(View.VISIBLE);
        bringToFront();
        requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
