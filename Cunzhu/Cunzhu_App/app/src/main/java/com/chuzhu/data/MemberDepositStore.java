package com.chuzhu.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.chuzhu.AppConfig;
import com.chuzhu.member.MemberQrRefreshScheduler;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 会员存珠设备屏会话本地存储。
 *
 * <p>Start 前 Redis Session 是权威事实，APP 本地只保存二维码、会员展示快照和
 * clientRequestNo，用于 UI 恢复与 Start 幂等；不能把这里的会员信息当作硬件授权。</p>
 */
public final class MemberDepositStore {

    public static final String STATUS_EMPTY = "EMPTY";
    public static final String STATUS_WAITING_SCAN = "WAITING_SCAN";
    public static final String STATUS_BOUND = "BOUND";
    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_WAITING_COMMAND = "WAITING_COMMAND";

    private static final String PREF = "chuzhu_member_deposit_session_v1";

    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_QR_CONTENT = "qrContent";
    private static final String KEY_STATUS = "status";
    private static final String KEY_MEMBER_NO = "memberNo";
    private static final String KEY_MEMBER_NICKNAME = "memberNickname";
    private static final String KEY_AVAILABLE_QUANTITY = "availableQuantity";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_ITEM_NAME = "itemName";
    private static final String KEY_UNIT_NAME = "unitName";
    private static final String KEY_MAXIMUM_QUANTITY = "maximumDepositQuantity";
    private static final String KEY_EXPIRE_TIME = "expireTime";
    private static final String KEY_REFRESH_AFTER_SECONDS = "refreshAfterSeconds";
    private static final String KEY_CLIENT_REQUEST_NO = "clientRequestNo";
    private static final String KEY_OPERATION_NO = "operationNo";
    private static final String KEY_REFERENCE_NO = "referenceNo";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_UPDATED_AT = "updatedAt";

    private final Context context;

    public MemberDepositStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized Snapshot load() {
        Snapshot snapshot = loadWithoutScheduling();
        /*
         * 页面恢复、广播刷新、进程内重建时都可能只读取本地 Session 而不会重新 save。
         * 因此读取到等待扫码二维码时也要确保 refreshAfterSeconds 自动刷新任务存在。
         */
        MemberQrRefreshScheduler.schedule(context, snapshot);
        return snapshot;
    }

    public synchronized Snapshot loadWithoutScheduling() {
        SharedPreferences pref = preferences();
        return new Snapshot(
                pref.getString(KEY_SESSION_ID, ""),
                pref.getString(KEY_QR_CONTENT, ""),
                pref.getString(KEY_STATUS, STATUS_EMPTY),
                pref.getString(KEY_MEMBER_NO, ""),
                pref.getString(KEY_MEMBER_NICKNAME, ""),
                pref.getString(KEY_AVAILABLE_QUANTITY, ""),
                pref.getString(KEY_ITEM_ID, ""),
                pref.getString(KEY_ITEM_NAME, ""),
                pref.getString(KEY_UNIT_NAME, "颗"),
                pref.getString(KEY_MAXIMUM_QUANTITY, ""),
                pref.getString(KEY_EXPIRE_TIME, ""),
                pref.getInt(KEY_REFRESH_AFTER_SECONDS, 0),
                pref.getString(KEY_CLIENT_REQUEST_NO, ""),
                pref.getString(KEY_OPERATION_NO, ""),
                pref.getString(KEY_REFERENCE_NO, ""),
                pref.getString(KEY_MESSAGE, ""),
                pref.getLong(KEY_UPDATED_AT, 0L)
        );
    }

    public synchronized void saveSession(Snapshot session) {
        if (session == null) {
            return;
        }
        SharedPreferences.Editor editor = preferences().edit();
        editor.putString(KEY_SESSION_ID, safe(session.sessionId));
        editor.putString(KEY_QR_CONTENT, safe(session.qrContent));
        editor.putString(KEY_STATUS, safe(session.status, STATUS_WAITING_SCAN));
        editor.putString(KEY_MEMBER_NO, safe(session.memberNo));
        editor.putString(KEY_MEMBER_NICKNAME, safe(session.memberNickname));
        editor.putString(KEY_AVAILABLE_QUANTITY, safe(session.availableQuantity));
        editor.putString(KEY_ITEM_ID, safe(session.itemId));
        editor.putString(KEY_ITEM_NAME, safe(session.itemName));
        editor.putString(KEY_UNIT_NAME, safe(session.unitName, "颗"));
        editor.putString(KEY_MAXIMUM_QUANTITY, safe(session.maximumDepositQuantity));
        editor.putString(KEY_EXPIRE_TIME, safe(session.expireTime));
        editor.putInt(KEY_REFRESH_AFTER_SECONDS, session.refreshAfterSeconds);
        editor.putString(KEY_MESSAGE, safe(session.message));
        editor.putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        editor.apply();
        broadcast();
        MemberQrRefreshScheduler.schedule(context, loadWithoutScheduling());
    }

    public synchronized void applyBoundCommand(JSONObject envelope) {
        JSONObject data = dataOf(envelope);
        if (data == null) {
            return;
        }
        Snapshot old = loadWithoutScheduling();
        String sessionId = firstString(data, "sessionId", "memberDepositSessionId");
        if (sessionId.isEmpty()) {
            sessionId = old.sessionId;
        }

        /*
         * 新会员扫码绑定代表新的存珠展示周期已经开始。上一笔已经 FINISHED 的硬件快照
         * 只用于上一位会员确认页展示，不能继续给新会员显示“本次已确认 XX 颗”。
         * WAITING_CONFIRM/FAILED/FAULT 等未可靠收口状态不能在这里静默删除。
         */
        HardwareSessionStore hardwareStore = new HardwareSessionStore(context);
        DepositSession previousHardware = hardwareStore.load();
        if (previousHardware != null
                && DepositSession.STATE_FINISHED.equals(previousHardware.state)) {
            hardwareStore.clear();
        }

        Snapshot bound = new Snapshot(
                sessionId,
                "",
                STATUS_BOUND,
                firstString(data, "memberNo", "memberCode", "memberNumber", "memberId"),
                firstString(data, "memberNickname", "nickname", "memberName"),
                firstString(data, "availableQuantity", "availableMarbleQuantity", "balanceQuantity"),
                firstString(data, "itemId", "materialId"),
                firstString(data, "itemName", "materialName"),
                firstString(data, "unitName", "unit"),
                firstString(data, "maximumDepositQuantity", "maximumQuantity"),
                firstString(data, "expireTime", "expiredAt"),
                0,
                old.clientRequestNo,
                old.operationNo,
                old.referenceNo,
                "会员已扫码绑定，可点击开始存珠",
                System.currentTimeMillis()
        );
        /* 会员绑定后二维码已失效，立即清掉二维码与刷新周期，避免进入存珠阶段后重新安排刷新任务。 */
        saveSession(bound);
    }

    public synchronized String loadOrCreateClientRequestNo(String sessionId) {
        Snapshot old = loadWithoutScheduling();
        if (!old.clientRequestNo.isEmpty() && old.sessionId.equals(sessionId)) {
            return old.clientRequestNo;
        }
        String requestNo = "DEP_" + UUID.randomUUID().toString().replace("-", "");
        preferences().edit()
                .putString(KEY_CLIENT_REQUEST_NO, requestNo)
                .putString(KEY_SESSION_ID, safe(sessionId))
                .putString(KEY_QR_CONTENT, "")
                .putInt(KEY_REFRESH_AFTER_SECONDS, 0)
                .putString(KEY_STATUS, STATUS_STARTING)
                .putString(KEY_MESSAGE, "正在提交开始存珠请求")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        broadcast();
        MemberQrRefreshScheduler.cancel();
        return requestNo;
    }

    public synchronized void markWaitingCommand(String operationNo, String referenceNo) {
        preferences().edit()
                .putString(KEY_QR_CONTENT, "")
                .putInt(KEY_REFRESH_AFTER_SECONDS, 0)
                .putString(KEY_STATUS, STATUS_WAITING_COMMAND)
                .putString(KEY_OPERATION_NO, safe(operationNo))
                .putString(KEY_REFERENCE_NO, safe(referenceNo))
                .putString(KEY_MESSAGE, "已提交开始存珠，等待平台下发 collect_marbles")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        broadcast();
        MemberQrRefreshScheduler.cancel();
    }

    /**
     * terminal 结算后由 queryMemberDeposit 返回 Server 最新可用数量时，只更新余额展示字段。
     * Start 后 Redis Session 已不是业务事实，因此不能为了刷新余额而重新创建或覆盖会员 Session。
     */
    public synchronized void updateAvailableQuantity(String availableQuantity, String message) {
        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_AVAILABLE_QUANTITY, safe(availableQuantity))
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        if (message != null) {
            editor.putString(KEY_MESSAGE, safe(message));
        }
        editor.apply();
        broadcast();
    }

    public synchronized void setMessage(String message) {
        String next = safe(message);
        /*
         * ACTION_MEMBER_DEPOSIT_SESSION 的接收方也可能重新检查启动条件。
         * 如果相同文案仍持续广播，会形成“写状态 -> 广播 -> 再写同状态”的消息风暴，
         * 在 RK3566 上表现为连续 Skipped frames。相同值直接返回，只广播真实状态变化。
         */
        if (next.equals(loadWithoutScheduling().message)) {
            return;
        }
        preferences().edit()
                .putString(KEY_MESSAGE, next)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        broadcast();
    }

    public synchronized void clearSession() {
        preferences().edit().clear().apply();
        broadcast();
        MemberQrRefreshScheduler.cancel();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void broadcast() {
        Intent intent = new Intent(AppConfig.ACTION_MEMBER_DEPOSIT_SESSION);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private static JSONObject dataOf(JSONObject envelope) {
        if (envelope == null) {
            return null;
        }
        JSONObject data = envelope.optJSONObject("data");
        return data == null ? envelope : data;
    }

    private static String firstString(JSONObject json, String... keys) {
        if (json == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key != null && json.has(key) && !json.isNull(key)) {
                String value = String.valueOf(json.opt(key));
                if (!value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static String safe(String value) {
        return safe(value, "");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public static final class Snapshot {
        public final String sessionId;
        public final String qrContent;
        public final String status;
        public final String memberNo;
        public final String memberNickname;
        public final String availableQuantity;
        public final String itemId;
        public final String itemName;
        public final String unitName;
        public final String maximumDepositQuantity;
        public final String expireTime;
        public final int refreshAfterSeconds;
        public final String clientRequestNo;
        public final String operationNo;
        public final String referenceNo;
        public final String message;
        public final long updatedAt;

        public Snapshot(
                String sessionId,
                String qrContent,
                String status,
                String memberNo,
                String memberNickname,
                String availableQuantity,
                String itemId,
                String itemName,
                String unitName,
                String maximumDepositQuantity,
                String expireTime,
                int refreshAfterSeconds,
                String clientRequestNo,
                String operationNo,
                String referenceNo,
                String message,
                long updatedAt
        ) {
            this.sessionId = safe(sessionId);
            this.qrContent = safe(qrContent);
            this.status = safe(status, STATUS_EMPTY);
            this.memberNo = safe(memberNo);
            this.memberNickname = safe(memberNickname);
            this.availableQuantity = safe(availableQuantity);
            this.itemId = safe(itemId);
            this.itemName = safe(itemName);
            this.unitName = safe(unitName, "颗");
            this.maximumDepositQuantity = safe(maximumDepositQuantity);
            this.expireTime = safe(expireTime);
            this.refreshAfterSeconds = Math.max(0, refreshAfterSeconds);
            this.clientRequestNo = safe(clientRequestNo);
            this.operationNo = safe(operationNo);
            this.referenceNo = safe(referenceNo);
            this.message = safe(message);
            this.updatedAt = updatedAt;
        }

        public boolean hasSession() {
            return !sessionId.isEmpty();
        }

        public boolean hasQrContent() {
            return !qrContent.isEmpty();
        }

        public boolean isBound() {
            return STATUS_BOUND.equals(status);
        }

        public boolean isWaitingScan() {
            /* 只有平台明确处于 WAITING_SCAN 才允许刷新二维码，不能再用“有二维码且未绑定”推断。 */
            return STATUS_WAITING_SCAN.equals(status);
        }
    }
}
