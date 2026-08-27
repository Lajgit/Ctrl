package com.chuzhu;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.chuzhu.data.DepositSession;
import com.chuzhu.data.HardwareSessionStore;
import com.chuzhu.data.MemberDepositStore;
import com.chuzhu.mqtt.PendingDepositController;
import com.chuzhu.serial.BoardFrameCodec;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * 控制板自然停止后的确认页。
 *
 * <p>确认：按当前累计数量一次性提交 terminal 并刷新账户余额；继续存珠：复用同一会员、
 * 同一 Operation，控制板重新启动后数量继续累计；返回：先按当前事实收口业务再退出会员。
 * 当累计数量为 0 时不允许提交成功存珠，只提供“继续存珠 / 返回”。确认页 60 秒无操作时，
 * 有珠子自动按当前数量确认并返回，0 颗只结束 Operation 后返回。</p>
 */
public final class DepositConfirmActivity extends AppCompatActivity {

    private static final long CONFIRM_IDLE_TIMEOUT_MS = 60_000L;

    private TextView memberText;
    private TextView balanceText;
    private TextView quantityText;
    private TextView hintText;
    private MaterialButton confirmButton;
    private MaterialButton continueButton;
    private MaterialButton returnButton;
    private boolean busy;
    private boolean confirmed;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable confirmIdleTimeoutRunnable = this::handleConfirmIdleTimeout;
    private long lastInteractionAt;

    private MemberDepositStore memberStore;
    private HardwareSessionStore sessionStore;
    private PendingDepositController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        memberStore = new MemberDepositStore(this);
        sessionStore = new HardwareSessionStore(this);
        controller = PendingDepositController.get(this);
        setContentView(buildContentView());
        lastInteractionAt = SystemClock.elapsedRealtime();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        scheduleConfirmIdleTimeout();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            resetConfirmIdleTimeoutFromUser();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            return;
        }
        if (confirmed) {
            exitConfirmedMember();
            return;
        }
        /* 返回由控制器按当前数量收口；0 颗只结束 Operation，不会提交成功存珠。 */
        confirmAndFinish(true);
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(26), dp(28), dp(26));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.chuzhu_page_bg));

        TextView title = text("本次存珠已停止", 28, true);
        title.setTextColor(ContextCompat.getColor(this, R.color.chuzhu_primary_dark));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text("请确认当前数量，或继续投入弹珠", 15, false);
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.chuzhu_text_sub));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(6);
        root.addView(subtitle, subtitleParams);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.chuzhu_panel_blue));
        card.setRadius(dp(22));
        card.setCardElevation(dp(3));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(22);
        root.addView(card, cardParams);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(26), dp(24), dp(26), dp(24));
        card.addView(panel);

        memberText = text("会员：-", 21, true);
        memberText.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        memberText.setGravity(Gravity.CENTER);
        panel.addView(memberText, matchWrap());

        balanceText = text("账户余额：-", 18, true);
        balanceText.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        balanceText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams balanceParams = matchWrap();
        balanceParams.topMargin = dp(12);
        panel.addView(balanceText, balanceParams);

        quantityText = text("本次累计：0 颗", 34, true);
        quantityText.setTextColor(ContextCompat.getColor(this, R.color.chuzhu_accent));
        quantityText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams quantityParams = matchWrap();
        quantityParams.topMargin = dp(24);
        panel.addView(quantityText, quantityParams);

        hintText = text("等待确认", 15, false);
        hintText.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        hintText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(10);
        panel.addView(hintText, hintParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionsParams.topMargin = dp(24);
        panel.addView(actions, actionsParams);

        confirmButton = button("确认");
        confirmButton.setOnClickListener(v -> confirmAndFinish(false));
        actions.addView(confirmButton, weightedButtonParams(1f, 0));

        continueButton = button("继续存珠");
        continueButton.setOnClickListener(v -> continueDeposit());
        actions.addView(continueButton, weightedButtonParams(1f, dp(12)));

        returnButton = button("返回");
        returnButton.setOnClickListener(v -> {
            if (confirmed) {
                exitConfirmedMember();
            } else {
                confirmAndFinish(true);
            }
        });
        actions.addView(returnButton, weightedButtonParams(1f, dp(12)));

        return root;
    }

    private void refreshUi() {
        if (memberStore == null || sessionStore == null) {
            return;
        }
        DepositSession session = sessionStore.load();
        if (confirmed) {
            showConfirmedState();
            cancelConfirmIdleTimeout();
            return;
        }
        if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
            cancelConfirmIdleTimeout();
            if (!busy) {
                finish();
            }
            return;
        }

        MemberDepositStore.Snapshot member = memberStore.loadWithoutScheduling();
        String name = !member.memberNickname.isEmpty()
                ? member.memberNickname
                : (!member.memberNo.isEmpty() ? member.memberNo : "当前会员");
        memberText.setText("会员：" + name);
        balanceText.setText("当前账户余额：" + dash(member.availableQuantity) + " " + member.unitName);
        quantityText.setText("本次累计：" + session.actualQuantity + " 颗");

        boolean emptyDeposit = session.actualQuantity <= 0;
        boolean maximumReached = session.actualQuantity >= session.maximumQuantity
                || session.finishReason == BoardFrameCodec.FINISH_REASON_MAXIMUM_REACHED;
        if (emptyDeposit) {
            hintText.setText("未检测到珠子，本次不会提交存珠；60 秒无操作将自动返回");
        } else if (maximumReached) {
            hintText.setText("已达到本次可存上限；60 秒无操作将自动确认并返回");
        } else {
            hintText.setText("确认后一次性入账；60 秒无操作将自动按 "
                    + session.actualQuantity + " 颗确认并返回");
        }
        continueButton.setVisibility(View.VISIBLE);
        /* 0 颗没有可结算数量，隐藏“确认”，避免产生 0 颗成功提交。 */
        confirmButton.setVisibility(emptyDeposit ? View.GONE : View.VISIBLE);
        continueButton.setEnabled(!busy && !maximumReached);
        confirmButton.setEnabled(!busy && !emptyDeposit);
        returnButton.setEnabled(!busy);
        scheduleConfirmIdleTimeout();
    }

    private void showConfirmedState() {
        MemberDepositStore.Snapshot member = memberStore.loadWithoutScheduling();
        DepositSession session = sessionStore.load();
        String name = !member.memberNickname.isEmpty()
                ? member.memberNickname
                : (!member.memberNo.isEmpty() ? member.memberNo : "当前会员");
        memberText.setText("会员：" + name);
        balanceText.setText("最新账户余额：" + dash(member.availableQuantity) + " " + member.unitName);
        quantityText.setText("本次已确认：" + (session == null ? 0 : session.actualQuantity) + " 颗");
        hintText.setText(member.message.isEmpty() ? "存珠已确认" : member.message);
        confirmButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
        returnButton.setEnabled(true);
        returnButton.setText("返回");
    }

    private void confirmAndFinish(boolean returnAfter) {
        if (busy) {
            return;
        }
        cancelConfirmIdleTimeout();
        DepositSession session = sessionStore.load();
        boolean emptyDeposit = session != null && session.actualQuantity <= 0;
        busy = true;
        setButtonsEnabled(false);
        if (emptyDeposit) {
            hintText.setText(returnAfter
                    ? "本次 0 颗，正在结束本次操作并返回..."
                    : "当前为 0 颗，本次不提交");
        } else {
            hintText.setText(returnAfter
                    ? "正在确认当前数量并返回..."
                    : "正在确认并刷新账户余额...");
        }
        controller.confirm(returnAfter, (success, message) -> runOnUiThread(() -> {
            busy = false;
            hintText.setText(message);
            if (!success) {
                /* 收口失败时不能立刻反复重试，重新给现场 60 秒处理窗口。 */
                lastInteractionAt = SystemClock.elapsedRealtime();
                refreshUi();
                return;
            }
            if (returnAfter) {
                /* 本次已经完成结算/空存珠收口，退出会员前同步清掉 FINISHED 硬件快照。 */
                sessionStore.clear();
                restartMainScreen();
                return;
            }
            /* 确认后不立刻退回首页，先把 Server 最新余额明确展示给会员。 */
            confirmed = true;
            showConfirmedState();
        }));
    }

    private void continueDeposit() {
        if (busy) {
            return;
        }
        cancelConfirmIdleTimeout();
        busy = true;
        setButtonsEnabled(false);
        hintText.setText("正在重新启动收珠机构...");
        controller.continueDeposit((success, message) -> runOnUiThread(() -> {
            busy = false;
            hintText.setText(message);
            if (success) {
                finish();
            } else {
                lastInteractionAt = SystemClock.elapsedRealtime();
                refreshUi();
            }
        }));
    }

    private void exitConfirmedMember() {
        cancelConfirmIdleTimeout();
        /* 余额展示结束后同时清理会员快照与已完成硬件快照，下一位会员从 0 颗开始。 */
        sessionStore.clear();
        memberStore.clearSession();
        restartMainScreen();
    }

    /**
     * 确认页任意触摸都视为有效操作，从该次触摸重新计算 60 秒无操作时间。
     * 仅 WAITING_CONFIRM 参与自动收口，已经确认或正在请求平台时不重复触发。
     */
    private void resetConfirmIdleTimeoutFromUser() {
        if (busy || confirmed || sessionStore == null) {
            return;
        }
        DepositSession session = sessionStore.load();
        if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
            return;
        }
        lastInteractionAt = SystemClock.elapsedRealtime();
        scheduleConfirmIdleTimeout();
    }

    private void scheduleConfirmIdleTimeout() {
        mainHandler.removeCallbacks(confirmIdleTimeoutRunnable);
        if (busy || confirmed || sessionStore == null || lastInteractionAt <= 0L) {
            return;
        }
        DepositSession session = sessionStore.load();
        if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - lastInteractionAt;
        long delay = Math.max(1L, CONFIRM_IDLE_TIMEOUT_MS - elapsed);
        mainHandler.postDelayed(confirmIdleTimeoutRunnable, delay);
    }

    private void cancelConfirmIdleTimeout() {
        mainHandler.removeCallbacks(confirmIdleTimeoutRunnable);
    }

    private void handleConfirmIdleTimeout() {
        if (busy || confirmed || sessionStore == null) {
            return;
        }
        DepositSession session = sessionStore.load();
        if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
            cancelConfirmIdleTimeout();
            return;
        }
        long idle = SystemClock.elapsedRealtime() - lastInteractionAt;
        if (idle < CONFIRM_IDLE_TIMEOUT_MS) {
            scheduleConfirmIdleTimeout();
            return;
        }
        /*
         * 有珠子按当前累计数量自动确认并返回；0 颗复用现有空存珠收口，绝不提交 success+0。
         * 两种情况都释放当前会员和页面，避免无人操作时长期占用设备。
         */
        hintText.setText(session.actualQuantity > 0
                ? "60 秒无操作，正在自动确认当前数量并返回..."
                : "60 秒无操作，本次 0 颗，正在结束操作并返回...");
        confirmAndFinish(true);
    }

    /**
     * MainActivity 在上一位会员登录期间会保留自动 Session 门禁状态。
     * 返回二维码页时用 CLEAR_TOP 重建首页，让下一位会员立即得到新的二维码，而不是停在“正在获取”。
     */
    private void restartMainScreen() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void setButtonsEnabled(boolean enabled) {
        confirmButton.setEnabled(enabled);
        continueButton.setEnabled(enabled);
        returnButton.setEnabled(enabled);
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        if (bold) {
            text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return text;
    }

    private MaterialButton button(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextSize(17);
        button.setTextColor(ContextCompat.getColor(this, R.color.chuzhu_text_main));
        button.setCornerRadius(dp(14));
        button.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.chuzhu_accent));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight, int startMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), weight);
        params.setMarginStart(startMargin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String dash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }
}
