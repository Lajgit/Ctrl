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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.gouzhu.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * 顾客核销页面：同一页面承载“会员取珠”和“团购核销”两种显式入口。
 *
 * <p>团购必须先选择抖音/美团再扫码；prepare 后展示候选券，只有顾客二次确认才调用
 * confirm。confirm 一旦发起，关闭页面也不会重新提交或清除业务，会由后台按原请求号恢复。</p>
 */
public final class RedemptionActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_MEMBER = "member";
    public static final String MODE_THIRD_PARTY = "thirdParty";

    private TextView titleText;
    private TextView subtitleText;
    private TextView statusText;
    private LinearLayout channelSection;
    private LinearLayout douyinButton;
    private LinearLayout meituanButton;
    private TextView douyinStatusText;
    private TextView meituanStatusText;
    private LinearLayout scanSection;
    private TextView scanHintText;
    private LinearLayout candidateSection;
    private RadioGroup candidateGroup;
    private Button confirmButton;
    private TextView resultDetailText;
    private Button backButton;

    private String mode = "";
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (ThirdPartyRedemptionManager.ACTION_CHANGED.equals(action)
                    || MemberWithdrawalManager.ACTION_CHANGED.equals(action)) {
                refresh();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_redemption);
        mode = safe(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODE));
        bindViews();
        bindActions();
        registerReceiverIfNeeded();
        hideSystemUi();

        if (MODE_MEMBER.equals(mode)) {
            titleText.setText(R.string.member_withdraw_title);
            subtitleText.setText(R.string.member_withdraw_subtitle);
            if (MemberWithdrawalManager.get(this).snapshot() == null) {
                MemberWithdrawalManager.get(this).beginScan();
            } else {
                MemberWithdrawalManager.get(this).resumePending();
            }
        } else if (MODE_THIRD_PARTY.equals(mode)) {
            titleText.setText(R.string.third_party_redemption_title);
            subtitleText.setText(R.string.third_party_redemption_subtitle);
            ThirdPartyRedemptionManager.get(this).resumePending();
        } else {
            finish();
            return;
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
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
        titleText = findViewById(R.id.text_redemption_title);
        subtitleText = findViewById(R.id.text_redemption_subtitle);
        statusText = findViewById(R.id.text_redemption_status);
        channelSection = findViewById(R.id.layout_redemption_channels);
        douyinButton = findViewById(R.id.button_channel_douyin);
        meituanButton = findViewById(R.id.button_channel_meituan);
        douyinStatusText = findViewById(R.id.text_channel_douyin_status);
        meituanStatusText = findViewById(R.id.text_channel_meituan_status);
        scanSection = findViewById(R.id.layout_redemption_scan);
        scanHintText = findViewById(R.id.text_redemption_scan_hint);
        candidateSection = findViewById(R.id.layout_redemption_candidates);
        candidateGroup = findViewById(R.id.group_redemption_candidates);
        confirmButton = findViewById(R.id.button_redemption_confirm);
        resultDetailText = findViewById(R.id.text_redemption_result_detail);
        backButton = findViewById(R.id.button_redemption_back);
    }

    private void bindActions() {
        findViewById(R.id.button_redemption_close).setOnClickListener(view -> requestClose());
        backButton.setOnClickListener(view -> requestClose());
        douyinButton.setOnClickListener(view -> startThirdPartyChannel(
                RedemptionCapabilityResolver.CHANNEL_DOUYIN
        ));
        meituanButton.setOnClickListener(view -> startThirdPartyChannel(
                RedemptionCapabilityResolver.CHANNEL_MEITUAN
        ));
        confirmButton.setOnClickListener(view -> confirmSelectedCandidate());
    }

    private void startThirdPartyChannel(String channelCode) {
        if (!ThirdPartyRedemptionManager.get(this).startChannel(channelCode)) {
            Toast.makeText(this, R.string.redemption_start_failed, Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void confirmSelectedCandidate() {
        int checked = candidateGroup.getCheckedRadioButtonId();
        if (checked == View.NO_ID) {
            Toast.makeText(this, R.string.redemption_choose_candidate, Toast.LENGTH_SHORT).show();
            return;
        }
        RadioButton button = findViewById(checked);
        Object tag = button == null ? null : button.getTag();
        String certificateId = tag == null ? "" : String.valueOf(tag);
        if (certificateId.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.redemption_confirm_title)
                .setMessage(R.string.redemption_confirm_message)
                .setNegativeButton(R.string.redemption_confirm_cancel, null)
                .setPositiveButton(R.string.redemption_confirm_action, (dialog, which) -> {
                    confirmButton.setEnabled(false);
                    if (!ThirdPartyRedemptionManager.get(this)
                            .confirmCandidate(certificateId)) {
                        confirmButton.setEnabled(true);
                        Toast.makeText(
                                this,
                                R.string.redemption_confirm_unavailable,
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
                .show();
    }

    private void refresh() {
        if (MODE_MEMBER.equals(mode)) {
            refreshMember();
        } else if (MODE_THIRD_PARTY.equals(mode)) {
            refreshThirdParty();
        }
    }

    private void refreshMember() {
        channelSection.setVisibility(View.GONE);
        candidateSection.setVisibility(View.GONE);
        confirmButton.setVisibility(View.GONE);

        MemberWithdrawalManager.UiSnapshot snapshot =
                MemberWithdrawalManager.get(this).snapshot();
        if (snapshot == null) {
            scanSection.setVisibility(View.VISIBLE);
            scanHintText.setText(R.string.member_withdraw_scan_hint);
            statusText.setText(R.string.member_withdraw_preparing);
            resultDetailText.setText("");
            backButton.setText(R.string.redemption_back_home);
            return;
        }

        statusText.setText(snapshot.message);
        boolean scanning = MemberWithdrawalManager.STATE_STARTING.equals(snapshot.uiState)
                || MemberWithdrawalManager.STATE_SCANNING.equals(snapshot.uiState);
        scanSection.setVisibility(scanning ? View.VISIBLE : View.GONE);
        scanHintText.setText(R.string.member_withdraw_scan_hint);

        if (snapshot.requestedQuantity > 0) {
            resultDetailText.setText(getString(
                    R.string.member_withdraw_quantity_format,
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

    private void refreshThirdParty() {
        ThirdPartyRedemptionManager manager = ThirdPartyRedemptionManager.get(this);
        ThirdPartyRedemptionManager.UiSnapshot snapshot = manager.snapshot();
        if (snapshot == null) {
            showChannelSelection(manager.getAvailableChannels());
            scanSection.setVisibility(View.GONE);
            candidateSection.setVisibility(View.GONE);
            confirmButton.setVisibility(View.GONE);
            statusText.setText(R.string.third_party_choose_channel_hint);
            resultDetailText.setText("");
            backButton.setText(R.string.redemption_back_home);
            return;
        }

        channelSection.setVisibility(View.GONE);
        statusText.setText(snapshot.message);
        boolean scanning = ThirdPartyRedemptionPolicy.STATE_STARTING.equals(snapshot.uiState)
                || ThirdPartyRedemptionPolicy.STATE_SCANNING.equals(snapshot.uiState)
                || ThirdPartyRedemptionPolicy.STATE_PREPARING.equals(snapshot.uiState);
        scanSection.setVisibility(scanning ? View.VISIBLE : View.GONE);
        scanHintText.setText(getString(
                R.string.third_party_scan_channel_format,
                safe(snapshot.channelName)
        ));

        if (ThirdPartyRedemptionPolicy.STATE_CANDIDATE_CONFIRMING.equals(snapshot.uiState)) {
            showCandidates(snapshot);
        } else {
            candidateSection.setVisibility(View.GONE);
            confirmButton.setVisibility(View.GONE);
        }

        resultDetailText.setText(buildThirdPartyDetail(snapshot));
        backButton.setText(snapshot.terminal
                ? R.string.redemption_finish
                : R.string.redemption_back_home);
    }

    private void showChannelSelection(List<RedemptionCapabilityResolver.ChannelOption> channels) {
        channelSection.setVisibility(View.VISIBLE);
        boolean douyinAvailable = false;
        boolean meituanAvailable = false;
        if (channels != null) {
            for (RedemptionCapabilityResolver.ChannelOption channel : channels) {
                if (RedemptionCapabilityResolver.CHANNEL_DOUYIN.equals(channel.code)) {
                    douyinAvailable = true;
                } else if (RedemptionCapabilityResolver.CHANNEL_MEITUAN.equals(channel.code)) {
                    meituanAvailable = true;
                }
            }
        }

        // 两个平台入口始终并列展示；服务端未开放的渠道只置灰，绝不绕过 bootstrap 发起核销。
        applyChannelCardState(douyinButton, douyinStatusText, douyinAvailable);
        applyChannelCardState(meituanButton, meituanStatusText, meituanAvailable);
        statusText.setText(!douyinAvailable && !meituanAvailable
                ? R.string.third_party_no_channel
                : R.string.third_party_choose_channel_hint);
    }

    private void applyChannelCardState(LinearLayout card, TextView status, boolean available) {
        card.setEnabled(available);
        card.setClickable(available);
        card.setAlpha(available ? 1.0f : 0.42f);
        status.setText(available
                ? R.string.third_party_channel_available
                : R.string.third_party_channel_unavailable);
        status.setTextColor(getColor(available
                ? R.color.header_primary
                : R.color.text_hint));
    }

    private void showCandidates(ThirdPartyRedemptionManager.UiSnapshot snapshot) {
        candidateSection.setVisibility(View.VISIBLE);
        confirmButton.setVisibility(View.VISIBLE);
        confirmButton.setEnabled(snapshot.confirmRequestedAt <= 0L);
        candidateGroup.removeAllViews();

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (ThirdPartyRedemptionManager.CandidateSnapshot candidate : snapshot.candidates) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setTag(candidate.certificateId);
            String validity = candidate.expireTime > 0L
                    ? format.format(new Date(candidate.expireTime * 1000L))
                    : getString(R.string.redemption_validity_unknown);
            StringBuilder text = new StringBuilder();
            text.append(firstNonBlank(candidate.title, "团购券"));
            if (!candidate.itemName.isEmpty()) {
                text.append("\n").append(candidate.itemName);
            }
            text.append(" · ").append(candidate.marbleQuantity).append("珠");
            text.append("\n有效期至 ").append(validity);
            if (!candidate.redeemable && !candidate.unavailableReason.isEmpty()) {
                text.append("\n").append(candidate.unavailableReason);
            }
            button.setText(text.toString());
            button.setTextSize(18f);
            button.setPadding(12, 16, 12, 16);
            button.setEnabled(candidate.redeemable);
            candidateGroup.addView(button);
        }
    }

    private String buildThirdPartyDetail(ThirdPartyRedemptionManager.UiSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        if (!snapshot.channelName.isEmpty()) {
            builder.append("渠道：").append(snapshot.channelName);
        }
        if (snapshot.requestedQuantity > 0) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("应出：").append(snapshot.requestedQuantity).append("珠");
        }
        if (snapshot.actualQuantity >= 0) {
            builder.append("  实出：").append(snapshot.actualQuantity).append("珠");
        }
        if (ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW.equals(snapshot.uiState)) {
            builder.append("\n当前业务需要工作人员处理，请勿重复核销同一张券");
        } else if (ThirdPartyRedemptionPolicy.STATE_SUCCEEDED.equals(snapshot.uiState)) {
            builder.append("\n核销和出珠已完成");
        }
        return builder.toString();
    }

    private void requestClose() {
        if (MODE_THIRD_PARTY.equals(mode)) {
            ThirdPartyRedemptionManager manager = ThirdPartyRedemptionManager.get(this);
            ThirdPartyRedemptionManager.UiSnapshot snapshot = manager.snapshot();
            if (snapshot == null) {
                finish();
                return;
            }
            if (snapshot.terminal) {
                manager.acknowledgeTerminal();
                finish();
                return;
            }
            if (manager.abandonBeforeConfirm()) {
                finish();
                return;
            }
            // confirm 已发起后页面可以返回首页，但业务继续按原请求号查询，不能本地取消。
            finish();
            return;
        }

        MemberWithdrawalManager manager = MemberWithdrawalManager.get(this);
        MemberWithdrawalManager.UiSnapshot snapshot = manager.snapshot();
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
        // 会员取珠请求已经提交后，返回首页不等于取消服务端操作。
        finish();
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ThirdPartyRedemptionManager.ACTION_CHANGED);
        filter.addAction(MemberWithdrawalManager.ACTION_CHANGED);
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }
}
