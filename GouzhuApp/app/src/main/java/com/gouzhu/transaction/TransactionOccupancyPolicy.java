package com.gouzhu.transaction;

import java.util.Locale;

/** Pure transition policy shared by the persisted transaction occupancy manager. */
public final class TransactionOccupancyPolicy {

    private TransactionOccupancyPolicy() {
    }

    public static boolean isIdleOwner(String owner) {
        return owner == null || owner.trim().isEmpty() || "NONE".equals(owner);
    }

    public static boolean canAcquire(String currentOwner, String requestedOwner) {
        if (isIdleOwner(requestedOwner)) {
            return false;
        }
        return isIdleOwner(currentOwner) || requestedOwner.equals(currentOwner);
    }

    /**
     * dispense_marbles is the platform's signed physical authorization and may arrive before
     * the device's two-second HTTP payment poll observes DISPENSING. Therefore an active QR or
     * cash purchase may be taken over by that command, including the payment/cancel boundary.
     * A member-deposit or fault-blocked session can never be taken over. MessageId, operation
     * token, expiry validation and the one-active-physical-order store remain the stale-command
     * defenses; local HTTP polling must not cause a valid physical command to be rejected.
     */
    public static boolean canReserveDispense(String owner, String phase) {
        if (isBlockingPhase(phase)) {
            return false;
        }
        if ("MEMBER_WITHDRAWAL".equals(owner)
                || "THIRD_PARTY_REDEMPTION".equals(owner)) {
            // prepare/扫码阶段绝不接受出珠；请求已提交后先切 WAITING_DISPENSE，
            // 允许平台 MQTT 比对应 HTTP 响应更早到达。
            return "WAITING_DISPENSE".equals(phase) || isPhysicalPhase(phase);
        }
        return isIdleOwner(owner)
                || "QR_PURCHASE".equals(owner)
                || "CASH_PURCHASE".equals(owner)
                || "GENERIC_DISPENSE".equals(owner);
    }

    public static boolean isBlockingPhase(String phase) {
        return "BLOCKED".equals(phase)
                || "REFUNDING".equals(phase)
                || "MAINTENANCE".equals(phase);
    }

    public static boolean isPhysicalPhase(String phase) {
        return "DISPENSE_RESERVED".equals(phase)
                || "DISPENSING".equals(phase)
                || "FINISHING".equals(phase)
                || "WAITING_CONTINUATION".equals(phase)
                || "COLLECTING".equals(phase);
    }

    public static String paymentPhase(String purchaseStatus) {
        String status = normalize(purchaseStatus);
        switch (status) {
            case "WAITING_PAYMENT":
                return "WAITING_PAYMENT";
            case "EXPIRED":
                return "CONFIRMING_CLOSE";
            case "DISPENSING":
                return "WAITING_DISPENSE";
            case "REFUNDING":
                return "REFUNDING";
            case "COMPLETED":
            case "CANCELED":
            case "CLOSED":
            case "REFUNDED":
                return "TERMINAL";
            default:
                return "WAITING_PAYMENT";
        }
    }

    public static boolean shouldReleasePayment(String purchaseStatus) {
        String status = normalize(purchaseStatus);
        return "CANCELED".equals(status)
                || "CLOSED".equals(status)
                || "COMPLETED".equals(status)
                || "REFUNDED".equals(status);
    }

    public static boolean isCancellationSuccess(String purchaseStatus) {
        String status = normalize(purchaseStatus);
        return "CANCELED".equals(status) || "CLOSED".equals(status);
    }

    /**
     * MQTT 物理授权可能先于 HTTP 查单到达。已进入物理阶段后，迟到的普通非终态
     * WAITING_PAYMENT / EXPIRED / DISPENSING / 未识别状态都不能把占用阶段回退。
     * 终态和 REFUNDING 由上层显式处理，不在这里静默忽略。
     */
    public static boolean shouldPreservePhysicalPhase(
            String currentPhase,
            String purchaseStatus
    ) {
        if (!isPhysicalPhase(currentPhase)) {
            return false;
        }
        String status = normalize(purchaseStatus);
        return !("CANCELED".equals(status)
                || "CLOSED".equals(status)
                || "COMPLETED".equals(status)
                || "REFUNDING".equals(status)
                || "REFUNDED".equals(status));
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
