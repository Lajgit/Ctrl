package com.gouzhu.payment;

/** 付款码等待和查单节奏，保持纯 Java 以便单元测试。 */
final class AuthCodePaymentTimingPolicy {

    static final long WAITING_CODE_TIMEOUT_MS = 60_000L;
    static final long MAX_QUERY_DURATION_MS = 10L * 60L * 1000L;
    static final long NORMAL_QUERY_INTERVAL_SECONDS = 2L;

    private AuthCodePaymentTimingPolicy() {
    }

    /**
     * 查询失败后的退避：2s、4s、8s、15s，之后固定 30s。
     */
    static long queryRetryDelaySeconds(int consecutiveFailures) {
        if (consecutiveFailures <= 1) {
            return 2L;
        }
        if (consecutiveFailures == 2) {
            return 4L;
        }
        if (consecutiveFailures == 3) {
            return 8L;
        }
        if (consecutiveFailures == 4) {
            return 15L;
        }
        return 30L;
    }
}
