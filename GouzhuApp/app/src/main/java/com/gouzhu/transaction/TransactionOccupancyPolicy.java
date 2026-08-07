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

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
