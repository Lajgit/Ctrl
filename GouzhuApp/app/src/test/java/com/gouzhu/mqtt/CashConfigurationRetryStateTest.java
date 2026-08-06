package com.gouzhu.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 现金配置失败版本占位释放规则回归测试。 */
public final class CashConfigurationRetryStateTest {

    @Test
    public void exactFailedVersionWithoutPending_canRetry() {
        assertTrue(CashConfigurationRetryState.shouldReleaseFailedReservation(
                10,
                0,
                10,
                0,
                false
        ));
    }

    @Test
    public void appliedPendingOrDifferentVersion_mustNotBeReleased() {
        assertFalse(CashConfigurationRetryState.shouldReleaseFailedReservation(
                10,
                10,
                10,
                0,
                false
        ));
        assertFalse(CashConfigurationRetryState.shouldReleaseFailedReservation(
                10,
                0,
                10,
                10,
                true
        ));
        assertFalse(CashConfigurationRetryState.shouldReleaseFailedReservation(
                9,
                0,
                10,
                0,
                false
        ));
    }
}
