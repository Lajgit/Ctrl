package com.gouzhu.transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransactionOccupancyPolicyTest {

    @Test
    public void onlyIdleOrSameOwnerCanAcquire() {
        assertTrue(TransactionOccupancyPolicy.canAcquire("", "QR_PURCHASE"));
        assertTrue(TransactionOccupancyPolicy.canAcquire("QR_PURCHASE", "QR_PURCHASE"));
        assertFalse(TransactionOccupancyPolicy.canAcquire("CASH_PURCHASE", "QR_PURCHASE"));
        assertFalse(TransactionOccupancyPolicy.canAcquire("", "NONE"));
    }

    @Test
    public void signedDispenseMayTakeOverPurchaseButNotDepositOrFault() {
        assertFalse(TransactionOccupancyPolicy.canReserveDispense("MEMBER_DEPOSIT", "READY"));
        assertFalse(TransactionOccupancyPolicy.canReserveDispense("QR_PURCHASE", "BLOCKED"));
        assertFalse(TransactionOccupancyPolicy.canReserveDispense("CASH_PURCHASE", "REFUNDING"));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "QR_PURCHASE",
                "WAITING_PAYMENT"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "QR_PURCHASE",
                "CANCELLING"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "CASH_PURCHASE",
                "ACCEPTED"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "MEMBER_WITHDRAWAL",
                "WAITING_DISPENSE"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense(
                "THIRD_PARTY_REDEMPTION",
                "WAITING_DISPENSE"
        ));
        assertTrue(TransactionOccupancyPolicy.canReserveDispense("", ""));
    }

    @Test
    public void waitingContinuationRemainsPhysicalButNotFaultBlocked() {
        assertTrue(TransactionOccupancyPolicy.isPhysicalPhase("WAITING_CONTINUATION"));
        assertFalse(TransactionOccupancyPolicy.isBlockingPhase("WAITING_CONTINUATION"));
    }

    @Test
    public void paymentReleaseOnlyUsesAuthoritativeTerminalStates() {
        assertFalse(TransactionOccupancyPolicy.shouldReleasePayment("WAITING_PAYMENT"));
        assertFalse(TransactionOccupancyPolicy.shouldReleasePayment("EXPIRED"));
        assertFalse(TransactionOccupancyPolicy.shouldReleasePayment("DISPENSING"));
        assertTrue(TransactionOccupancyPolicy.shouldReleasePayment("CANCELED"));
        assertTrue(TransactionOccupancyPolicy.shouldReleasePayment("CLOSED"));
        assertTrue(TransactionOccupancyPolicy.shouldReleasePayment("COMPLETED"));
        assertTrue(TransactionOccupancyPolicy.shouldReleasePayment("REFUNDED"));
    }

    @Test
    public void lateHttpStatusDoesNotRegressPhysicalPhase() {
        assertTrue(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSING", "WAITING_PAYMENT"));
        assertTrue(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "FINISHING", "DISPENSING"));
        assertTrue(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSE_RESERVED", "EXPIRED"));
        assertFalse(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "WAITING_PAYMENT", "WAITING_PAYMENT"));
        assertFalse(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSING", "COMPLETED"));
        assertFalse(TransactionOccupancyPolicy.shouldPreservePhysicalPhase(
                "DISPENSING", "REFUNDING"));
    }

    @Test
    public void paymentStatusMapsToExpectedPhase() {
        assertEquals("WAITING_PAYMENT",
                TransactionOccupancyPolicy.paymentPhase("WAITING_PAYMENT"));
        assertEquals("CONFIRMING_CLOSE",
                TransactionOccupancyPolicy.paymentPhase("EXPIRED"));
        assertEquals("WAITING_DISPENSE",
                TransactionOccupancyPolicy.paymentPhase("DISPENSING"));
        assertEquals("REFUNDING",
                TransactionOccupancyPolicy.paymentPhase("REFUNDING"));
        assertEquals("TERMINAL",
                TransactionOccupancyPolicy.paymentPhase("CLOSED"));
    }
}
