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
     * A QR session may reserve physical dispense only after the authoritative purchase state
     * has become DISPENSING/WAITING_DISPENSE. This prevents an unrelated or stale
     * dispense_marbles command from consuming an unpaid QR session. Cash is allowed from the
     * accepted/reporting window because platform cash confirmation and the dispense command can
     * cross in transit. Existing physical phases remain allowed for idempotent retransmission.
     */
    public static boolean canReserveDispense(String owner, String phase) {
        if (isBlockingPhase(phase)) {
            return false;
        }
        if (isIdleOwner(owner)) {
            return true;
        }
        if ("QR_PURCHASE".equals(owner)) {
            return "WAITING_DISPENSE".equals(phase)
                    || "DISPENSE_RESERVED".equals(phase)
                    || "DISPENSING".equals(phase)
                    || "FINISHING".equals(phase);
        }
        if ("CASH_PURCHASE".equals(owner)) {
            return "ACCEPTED".equals(phase)
                    || "REPORTING".equals(phase)
                    || "WAITING_DISPENSE".equals(phase)
                    || "DISPENSE_RESERVED".equals(phase)
                    || "DISPENSING".equals(phase)
                    || "FINISHING".equals(phase);
        }
        if ("GENERIC_DISPENSE".equals(owner)) {
            return "DISPENSE_RESERVED".equals(phase)
                    || "DISPENSING".equals(phase)
                    || "FINISHING".equals(phase);
        }
        return false;
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
