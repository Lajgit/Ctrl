package com.gouzhu.redemption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThirdPartyRedemptionPolicyTest {

    @Test
    public void confirmRequiresPreparedRedeemableUnexpiredCandidate() {
        long now = 1_787_200_000_000L;
        assertTrue(ThirdPartyRedemptionPolicy.canConfirm(
                ThirdPartyRedemptionPolicy.STATE_CANDIDATE_CONFIRMING,
                true,
                now / 1000L + 60L,
                now,
                0L
        ));
        assertFalse(ThirdPartyRedemptionPolicy.canConfirm(
                ThirdPartyRedemptionPolicy.STATE_CANDIDATE_CONFIRMING,
                false,
                now / 1000L + 60L,
                now,
                0L
        ));
        assertFalse(ThirdPartyRedemptionPolicy.canConfirm(
                ThirdPartyRedemptionPolicy.STATE_CANDIDATE_CONFIRMING,
                true,
                now / 1000L,
                now,
                0L
        ));
        assertFalse(ThirdPartyRedemptionPolicy.canConfirm(
                ThirdPartyRedemptionPolicy.STATE_CONFIRMING,
                true,
                now / 1000L + 60L,
                now,
                now
        ));
    }

    @Test
    public void confirmBoundaryCannotBeLocallyAbandoned() {
        assertTrue(ThirdPartyRedemptionPolicy.canAbandonBeforeConfirm(
                ThirdPartyRedemptionPolicy.STATE_SCANNING, 0L));
        assertTrue(ThirdPartyRedemptionPolicy.canAbandonBeforeConfirm(
                ThirdPartyRedemptionPolicy.STATE_CANDIDATE_CONFIRMING, 0L));
        assertFalse(ThirdPartyRedemptionPolicy.canAbandonBeforeConfirm(
                ThirdPartyRedemptionPolicy.STATE_CONFIRMING, 100L));
        assertFalse(ThirdPartyRedemptionPolicy.canAbandonBeforeConfirm(
                ThirdPartyRedemptionPolicy.STATE_WAITING_FINAL_STATUS, 100L));
    }

    @Test
    public void terminalStateRequiresFullDeliveryForSuccess() {
        assertEquals(ThirdPartyRedemptionPolicy.STATE_SUCCEEDED,
                ThirdPartyRedemptionPolicy.terminalUiState(
                        true, "REDEEMED", "FULL_DELIVERY", "NORMAL"));
        assertEquals(ThirdPartyRedemptionPolicy.STATE_FAILED,
                ThirdPartyRedemptionPolicy.terminalUiState(
                        true, "REDEEM_FAILED", "CANCELED", "NORMAL"));
        assertEquals(ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW,
                ThirdPartyRedemptionPolicy.terminalUiState(
                        true, "REDEEMED", "PARTIAL_DELIVERY", "NORMAL"));
        assertEquals(ThirdPartyRedemptionPolicy.STATE_FAILED,
                ThirdPartyRedemptionPolicy.terminalUiState(
                        true, "REDEEMED", "PARTIAL_DELIVERY", "RESOLVED"));
        assertEquals(ThirdPartyRedemptionPolicy.STATE_MANUAL_REVIEW,
                ThirdPartyRedemptionPolicy.terminalUiState(
                        false, "REDEEMED", "DISPENSING", "MANUAL_REVIEW"));
    }

    @Test
    public void queryBackoffUsesTwoThenFiveSeconds() {
        assertEquals(2_000L, ThirdPartyRedemptionPolicy.retryDelayMs(0));
        assertEquals(5_000L, ThirdPartyRedemptionPolicy.retryDelayMs(1));
        assertEquals(5_000L, ThirdPartyRedemptionPolicy.retryDelayMs(8));
    }
}
