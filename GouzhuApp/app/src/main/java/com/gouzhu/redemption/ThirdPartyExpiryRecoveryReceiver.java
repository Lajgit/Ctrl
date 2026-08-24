package com.gouzhu.redemption;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * prepare 会话在用户点击确认时已经过期，则自动结束旧的未消费会话并按原渠道重开扫码。
 *
 * <p>这里只处理 confirmRequestedAt 尚未写入的 FAILED 会话；一旦越过 confirm 消费边界，
 * 任何超时/失败都必须继续查询原 clientRequestNo，绝不能自动创建新业务。</p>
 */
public final class ThirdPartyExpiryRecoveryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !ThirdPartyRedemptionManager.ACTION_CHANGED.equals(intent.getAction())) {
            return;
        }

        ThirdPartyRedemptionManager manager = ThirdPartyRedemptionManager.get(context);
        ThirdPartyRedemptionManager.UiSnapshot snapshot = manager.snapshot();
        if (snapshot == null
                || !ThirdPartyRedemptionPolicy.STATE_FAILED.equals(snapshot.uiState)
                || snapshot.confirmRequestedAt > 0L
                || snapshot.sessionExpireTime <= 0L
                || snapshot.sessionExpireTime > System.currentTimeMillis() / 1000L
                || snapshot.channelCode == null
                || snapshot.channelCode.trim().isEmpty()) {
            return;
        }

        String channelCode = snapshot.channelCode;
        // prepare 尚未消费券，可以安全结束旧 requestNo；新会话会重新关闭现金入口后进入扫码态。
        if (manager.abandonBeforeConfirm()) {
            manager.startChannel(channelCode);
        }
    }
}
