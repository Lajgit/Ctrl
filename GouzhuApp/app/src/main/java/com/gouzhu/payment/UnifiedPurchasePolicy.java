package com.gouzhu.payment;

import java.util.Locale;

/**
 * 统一购珠订单的纯 Java 本地门禁策略。
 *
 * <p>服务端负责真实支付渠道的原子竞争；本类只负责设备端是否还能接受新的付款动作、
 * 顾客 60 秒等待是否还能自动取消，以及网络失败后的查单退避。</p>
 */
final class UnifiedPurchasePolicy {

    static final long USER_SELECTION_TIMEOUT_MS = 60_000L;
    static final long MAX_UNKNOWN_QUERY_DURATION_MS = 10L * 60L * 1000L;
    static final long NORMAL_QUERY_INTERVAL_SECONDS = 2L;

    private UnifiedPurchasePolicy() {
    }

    static boolean canSubmitAuthCode(
            String purchaseStatus,
            String paymentStatus,
            String selectedPaymentMode,
            boolean cancelPending,
            boolean authCodeAlreadySubmitted,
            boolean channelSupported
    ) {
        if (!channelSupported || cancelPending || authCodeAlreadySubmitted) {
            return false;
        }
        if (!"WAITING_PAYMENT".equals(normalize(purchaseStatus))) {
            return false;
        }
        if (!normalize(selectedPaymentMode).isEmpty()) {
            return false;
        }
        String payment = normalize(paymentStatus);
        return payment.isEmpty()
                || "WAITING".equals(payment)
                || "FAILED".equals(payment);
    }

    /** 仅支付入口尚未被任何一方选中时，60 秒顾客等待才允许触发自动取消。 */
    static boolean canAutoCancelForUserTimeout(
            String purchaseStatus,
            String paymentStatus,
            String selectedPaymentMode,
            boolean cancelPending,
            boolean authCodeAlreadySubmitted
    ) {
        if (cancelPending || authCodeAlreadySubmitted) {
            return false;
        }
        if (!"WAITING_PAYMENT".equals(normalize(purchaseStatus))) {
            return false;
        }
        if (!normalize(selectedPaymentMode).isEmpty()) {
            return false;
        }
        String payment = normalize(paymentStatus);
        return payment.isEmpty()
                || "WAITING".equals(payment)
                || "FAILED".equals(payment);
    }

    static boolean blocksNewPayment(String paymentStatus, String selectedPaymentMode) {
        if (!normalize(selectedPaymentMode).isEmpty()) {
            return true;
        }
        String payment = normalize(paymentStatus);
        return "SUBMITTING".equals(payment)
                || "PROCESSING".equals(payment)
                || "SUCCESS".equals(payment)
                || "PAYMENT_METHOD_ALREADY_SELECTED".equals(payment)
                || "ORDER_ALREADY_PAID".equals(payment)
                || "ORDER_CLOSED".equals(payment);
    }

    /** 服务端明确回到未选支付方式的待支付态时，允许下一次新付款尝试。 */
    static boolean canRearmAfterExplicitFailure(
            String purchaseStatus,
            String paymentStatus,
            String selectedPaymentMode,
            String payChannel
    ) {
        return "WAITING_PAYMENT".equals(normalize(purchaseStatus))
                && "FAILED".equals(normalize(paymentStatus))
                && normalize(selectedPaymentMode).isEmpty()
                && (normalize(payChannel).isEmpty()
                || "DEVICE_PURCHASE".equals(normalize(payChannel)));
    }

    /** 连续网络失败退避：1s、2s、4s、8s，之后最大 10s。 */
    static long queryRetryDelaySeconds(int consecutiveFailures) {
        if (consecutiveFailures <= 1) {
            return 1L;
        }
        if (consecutiveFailures == 2) {
            return 2L;
        }
        if (consecutiveFailures == 3) {
            return 4L;
        }
        if (consecutiveFailures == 4) {
            return 8L;
        }
        return 10L;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
