package com.gouzhu.redemption;

/**
 * 官方小程序套餐券的本地结算判定。
 *
 * <p>HTTP 终态只用于收敛业务状态，真实出珠仍必须来自 MQTT 物理授权。
 * 只要服务端终态显示已经出过部分珠但没有完整履约，就进入人工处理，禁止自动放行下一笔。</p>
 */
public final class InternalRedemptionPolicy {

    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_MANUAL_REVIEW = "MANUAL_REVIEW";

    private InternalRedemptionPolicy() {
    }

    public static String terminalOutcome(
            boolean terminal,
            int requestedQuantity,
            int dispensedQuantity
    ) {
        if (!terminal) {
            return OUTCOME_PENDING;
        }
        int requested = Math.max(0, requestedQuantity);
        int dispensed = Math.max(0, dispensedQuantity);
        if (requested > 0 && dispensed >= requested) {
            return OUTCOME_SUCCEEDED;
        }
        if (dispensed > 0) {
            return OUTCOME_MANUAL_REVIEW;
        }
        return OUTCOME_FAILED;
    }
}
