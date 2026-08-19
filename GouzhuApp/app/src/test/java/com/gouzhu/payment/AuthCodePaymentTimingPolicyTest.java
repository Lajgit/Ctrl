package com.gouzhu.payment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AuthCodePaymentTimingPolicyTest {

    @Test
    public void waitingCodeTimeout_isSixtySeconds() {
        assertEquals(60_000L, AuthCodePaymentTimingPolicy.WAITING_CODE_TIMEOUT_MS);
    }

    @Test
    public void queryFailureBackoff_matchesExpectedSequence() {
        assertEquals(2L, AuthCodePaymentTimingPolicy.queryRetryDelaySeconds(1));
        assertEquals(4L, AuthCodePaymentTimingPolicy.queryRetryDelaySeconds(2));
        assertEquals(8L, AuthCodePaymentTimingPolicy.queryRetryDelaySeconds(3));
        assertEquals(15L, AuthCodePaymentTimingPolicy.queryRetryDelaySeconds(4));
        assertEquals(30L, AuthCodePaymentTimingPolicy.queryRetryDelaySeconds(5));
        assertEquals(30L, AuthCodePaymentTimingPolicy.queryRetryDelaySeconds(20));
    }
}
