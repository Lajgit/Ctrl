package com.gouzhu.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K1补珠时部分出珠结果归一化和继续资格规则回归测试。 */
public final class RefillStateReceiverTest {

    @Test
    public void sensorTimeoutWithPartialActual_isNormalized() {
        assertTrue(RefillStateReceiver.shouldNormalizeSensorTimeout(4, 7, 10));
    }

    @Test
    public void zeroFullAndOtherFailures_areNotNormalized() {
        assertFalse(RefillStateReceiver.shouldNormalizeSensorTimeout(4, 0, 10));
        assertFalse(RefillStateReceiver.shouldNormalizeSensorTimeout(4, 10, 10));
        assertFalse(RefillStateReceiver.shouldNormalizeSensorTimeout(2, 7, 10));
        assertFalse(RefillStateReceiver.shouldNormalizeSensorTimeout(3, 7, 10));
    }

    @Test
    public void noMarblesAndNormalizedSensorTimeout_areContinuableOnlyWhenPartial() {
        assertTrue(RefillStateReceiver.isContinuableStockInsufficiency(2, 3, 10));
        assertTrue(RefillStateReceiver.isContinuableStockInsufficiency(4, 3, 10));
        assertFalse(RefillStateReceiver.isContinuableStockInsufficiency(2, 0, 10));
        assertFalse(RefillStateReceiver.isContinuableStockInsufficiency(4, 10, 10));
        assertFalse(RefillStateReceiver.isContinuableStockInsufficiency(3, 3, 10));
    }
}
