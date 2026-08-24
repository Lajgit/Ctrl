package com.gouzhu.redemption;

import java.util.Locale;

/** 第三方团购核销的纯状态判定，便于对终态、确认门禁和查询节奏做单元测试。 */
public final class ThirdPartyRedemptionPolicy {

    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_SCANNING = "SCANNING";
    public static final String STATE_PREPARING = "PREPARING";
    public static final String STATE_CANDIDATE_CONFIRMING = "CANDIDATE_CONFIRMING";
    public static final String STATE_CONFIRMING = "CONFIRMING";
    public static final String STATE_WAITING_DISPENSE_COMMAND = "WAITING_DISPENSE_COMMAND";
    public static final String STATE_DISPENSING = "DISPENSING";
    public static final String STATE_WAITING_FINAL_STATUS = "WAITING_FINAL_STATUS";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_MANUAL_REVIEW = "MANUAL_REVIEW";

    public static final long NORMAL_QUERY_DELAY_MS = 2_000L;
    public static final long SLOW_QUERY_DELAY_MS = 5_000L;

    private ThirdPartyRedemptionPolicy() {
    }

    public static boolean canConfirm(
            String uiState,
            boolean redeemable,
            long sessionExpireTimeSeconds,
            long nowMillis,
            long confirmRequestedAt
    ) {
        return STATE_CANDIDATE_CONFIRMING.equals(normalize(uiState))
                && redeemable
                && sessionExpireTimeSeconds > nowMillis / 1000L
                && confirmRequestedAt <= 0L;
    }

    public static boolean canAbandonBeforeConfirm(String uiState, long confirmRequestedAt) {
        if (confirmRequestedAt > 0L) {
            return false;
        }
        String state = normalize(uiState);
        return STATE_STARTING.equals(state)
                || STATE_SCANNING.equals(state)
                || STATE_PREPARING.equals(state)
                || STATE_CANDIDATE_CONFIRMING.equals(state)
                || STATE_FAILED.equals(state);
    }

    public static boolean shouldKeepQuerying(boolean terminal, String resolutionStatus) {
        if (terminal) {
            return false;
        }
        String resolution = normalize(resolutionStatus);
        return true;
    }

    public static String terminalUiState(
            boolean terminal,
            String channelStatus,
            String fulfillmentStatus,
            String resolutionStatus
    ) {
        String channel = normalize(channelStatus);
        String fulfillment = normalize(fulfillmentStatus);
        String resolution = normalize(resolutionStatus);
        if (!terminal) {
            if ("MANUAL_REVIEW".equals(resolution)) {
                return STATE_MANUAL_REVIEW;
            }
            if ("DISPENSING".equals(fulfillment)) {
                return STATE_DISPENSING;
            }
            if ("REDEEMED".equals(channel)
                    || "PREPARED".equals(fulfillment)
                    || "NOT_PREPARED".equals(fulfillment)) {
                return STATE_WAITING_DISPENSE_COMMAND;
            }
            return STATE_WAITING_FINAL_STATUS;
        }
        if ("FULL_DELIVERY".equals(fulfillment)) {
            return STATE_SUCCEEDED;
        }
        if ("REDEEM_FAILED".equals(channel) && "CANCELED".equals(fulfillment)) {
            return STATE_FAILED;
        }
        if ("MANUAL_REVIEW".equals(resolution)
                || "PARTIAL_DELIVERY".equals(fulfillment)
                || "RESULT_UNKNOWN".equals(fulfillment)) {
            return STATE_MANUAL_REVIEW;
        }
        return STATE_FAILED;
    }

    public static long retryDelayMs(int consecutiveFailures) {
        return consecutiveFailures <= 0 ? NORMAL_QUERY_DELAY_MS : SLOW_QUERY_DELAY_MS;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
