package com.chuzhu;

import android.os.Bundle;
import android.view.Gravity;
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
 * 同一 Operation，控制板重新启动后数量继续累计；返回：先确认并结算当前数量，再退出会员。</p>
 */
public final class DepositConfirmActivity extends AppCompatActivity {

    private TextView memberText;
    private TextView balanceText;
    private TextView quantityText;
    private TextView hintText;
    private MaterialButton confirmButton;
    private MaterialButton continueButton;
    private MaterialButton returnButton;
    private boolean busy;

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
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    public void onBackPressed() {
        /* 已经真实收到珠子时不能直接丢弃业务；系统返回键与“返回”按钮语义一致：先确认结算。 */
        if (!busy) {
            confirmAndFinish(true);
        }
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
        returnButton.setOnClickListener(v -> confirmAndFinish(true));
        actions.addView(returnButton, weightedButtonParams(1f, dp(12)));

        return root;
    }

    private void refreshUi() {
        if (memberStore == null || sessionStore == null) {
            return;
        }
        MemberDepositStore.Snapshot member = memberStore.loadWithoutScheduling();
        DepositSession session = sessionStore.load();
        if (session == null || !DepositSession.STATE_WAITING_CONFIRM.equals(session.state)) {
            if (!busy) {
                finish();
            }
            return;
        }

        String name = !member.memberNickname.isEmpty()
                ? member.memberNickname
                : (!member.memberNo.isEmpty() ? member.memberNo : "当前会员");
        memberText.setText("会员：" + name);
        balanceText.setText("当前账户余额：" + dash(member.availableQuantity) + " " + member.unitName);
        quantityText.setText("本次累计：" + session.actualQuantity + " 颗");

        boolean maximumReached = session.actualQuantity >= session.maximumQuantity
                || session.finishReason == BoardFrameCodec.FINISH_REASON_MAXIMUM_REACHED;
        if (maximumReached) {
            hintText.setText("已达到本次可存上限，请确认入账或返回");
        } else {
            hintText.setText("确认后一次性入账；继续存珠会从 " + session.actualQuantity + " 颗继续累计");
        }
        continueButton.setEnabled(!busy && !maximumReached);
        confirmButton.setEnabled(!busy);
        returnButton.setEnabled(!busy);
    }

    private void confirmAndFinish(boolean returnAfter) {
        if (busy) {
            return;
        }
        busy = true;
        setButtonsEnabled(false);
        hintText.setText(returnAfter
                ? "正在确认当前数量并返回..."
                : "正在确认并刷新账户余额...");
        controller.confirm(returnAfter, (success, message) -> runOnUiThread(() -> {
            busy = false;
            hintText.setText(message);
            if (success) {
                finish();
            } else {
                refreshUi();
            }
        }));
    }

    private void continueDeposit() {
        if (busy) {
            return;
        }
        busy = true;
        setButtonsEnabled(false);
        hintText.setText("正在重新启动收珠机构...");
        controller.continueDeposit((success, message) -> runOnUiThread(() -> {
            busy = false;
            hintText.setText(message);
            if (success) {
                finish();
            } else {
                refreshUi();
            }
        }));
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
