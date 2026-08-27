package com.chuzhu.data;

import org.json.JSONObject;

/**
 * 当前收珠任务的本地状态快照。
 */
public final class DepositSession {

    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_COLLECTING = "COLLECTING";
    /** 控制板已停止，但业务 terminal 尚未提交，等待用户确认或继续存珠。 */
    public static final String STATE_WAITING_CONFIRM = "WAITING_CONFIRM";
    public static final String STATE_FINISHED = "FINISHED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_FAULT = "FAULT";

    public String messageId = "";
    public String operationNo = "";
    public String operationToken = "";
    public int maximumQuantity;
    public int actualQuantity;
    /**
     * 当前控制板分段收珠前已经确认的累计数量。
     * “继续存珠”会重新 START 控制板，板端分段计数从 0 开始，Android 用该偏移恢复总累计值。
     */
    public int segmentBaseQuantity;
    /** 平台 collect_marbles 下发的总会话超时，继续存珠仍沿用同一业务时间窗。 */
    public int sessionTimeoutSeconds;
    /** 最近一次 0x21 的结束原因；-1 表示尚未收到结束原因。 */
    public int finishReason = -1;
    public String state = STATE_IDLE;
    public long startedAt;
    public long updatedAt;
    public long finishedAt;
    public String errorCode = "";
    public String errorMessage = "";
    public boolean localDebug;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("messageId", messageId);
            json.put("operationNo", operationNo);
            json.put("operationToken", operationToken);
            json.put("maximumQuantity", maximumQuantity);
            json.put("actualQuantity", actualQuantity);
            json.put("segmentBaseQuantity", segmentBaseQuantity);
            json.put("sessionTimeoutSeconds", sessionTimeoutSeconds);
            json.put("finishReason", finishReason);
            json.put("state", state);
            json.put("startedAt", startedAt);
            json.put("updatedAt", updatedAt);
            json.put("finishedAt", finishedAt);
            json.put("errorCode", errorCode);
            json.put("errorMessage", errorMessage);
            json.put("localDebug", localDebug);
        } catch (Throwable ignored) {
        }
        return json;
    }

    public static DepositSession fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        DepositSession session = new DepositSession();
        session.messageId = json.optString("messageId", "");
        session.operationNo = json.optString("operationNo", "");
        session.operationToken = json.optString("operationToken", "");
        session.maximumQuantity = json.optInt("maximumQuantity", 0);
        session.actualQuantity = json.optInt("actualQuantity", 0);
        session.segmentBaseQuantity = json.optInt("segmentBaseQuantity", 0);
        session.sessionTimeoutSeconds = json.optInt("sessionTimeoutSeconds", 0);
        session.finishReason = json.optInt("finishReason", -1);
        session.state = json.optString("state", STATE_IDLE);
        session.startedAt = json.optLong("startedAt", 0L);
        session.updatedAt = json.optLong("updatedAt", 0L);
        session.finishedAt = json.optLong("finishedAt", 0L);
        session.errorCode = json.optString("errorCode", "");
        session.errorMessage = json.optString("errorMessage", "");
        session.localDebug = json.optBoolean("localDebug", false);
        return session;
    }
}
