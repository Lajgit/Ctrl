package com.gouzhu.redemption;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InternalRedemptionPolicyTest {

    @Test
    public void nonTerminalRemainsPending() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_PENDING,
                InternalRedemptionPolicy.terminalOutcome(false, 10, 0)
        );
    }

    @Test
    public void completeQuantityIsSuccessful() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_SUCCEEDED,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 10)
        );
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_SUCCEEDED,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 12)
        );
    }

    @Test
    public void partialPhysicalDeliveryRequiresManualReview() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_MANUAL_REVIEW,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 3)
        );
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_MANUAL_REVIEW,
                InternalRedemptionPolicy.terminalOutcome(true, 0, 1)
        );
    }

    @Test
    public void terminalWithoutDispenseIsSafeFailure() {
        assertEquals(
                InternalRedemptionPolicy.OUTCOME_FAILED,
                InternalRedemptionPolicy.terminalOutcome(true, 10, 0)
        );
    }
}
