package com.gouzhu.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 珠仓不足物理结果归一化规则回归测试。 */
public final class SdkCommandDecoderTest {

    @Test
    public void confirmedNoMarblesResult_acceptsDirectEmptyAndDispenseTimeout() {
        assertTrue(SdkCommandDecoder.isConfirmedNoMarblesResult("NO_MARBLES"));
        assertTrue(SdkCommandDecoder.isConfirmedNoMarblesResult("SENSOR_TIMEOUT"));
    }

    @Test
    public void confirmedNoMarblesResult_rejectsOtherFailures() {
        assertFalse(SdkCommandDecoder.isConfirmedNoMarblesResult("JAMMED"));
        assertFalse(SdkCommandDecoder.isConfirmedNoMarblesResult("ABORTED"));
        assertFalse(SdkCommandDecoder.isConfirmedNoMarblesResult(null));
    }
}
