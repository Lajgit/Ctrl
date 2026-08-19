package com.gouzhu.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UnifiedPurchasePolicyTest {

    @Test
    public void authCodeOnlyAllowedBeforePaymentMethodIsSelected() {
        assertTrue(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "", "", false, false, true));
        assertTrue(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "WAITING", "", false, false, true));
        assertFalse(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "PROCESSING", "", false, false, true));
        assertFalse(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "", "SCAN", false, false, true));
        assertFalse(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "", "AUTH_CODE", false, false, true));
        assertFalse(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "", "", true, false, true));
        assertFalse(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "", "", false, true, true));
        assertFalse(UnifiedPurchasePolicy.canSubmitAuthCode(
                "WAITING_PAYMENT", "", "", false, false, false));
    }

    @Test
    public void userTimeoutOnlyCancelsUnselectedWaitingOrder() {
        assertTrue(UnifiedPurchasePolicy.canAutoCancelForUserTimeout(
                "WAITING_PAYMENT", "WAITING", "", false, false));
        assertFalse(UnifiedPurchasePolicy.canAutoCancelForUserTimeout(
                "WAITING_PAYMENT", "PROCESSING", "", false, false));
        assertFalse(UnifiedPurchasePolicy.canAutoCancelForUserTimeout(
                "WAITING_PAYMENT", "WAITING", "SCAN", false, false));
        assertFalse(UnifiedPurchasePolicy.canAutoCancelForUserTimeout(
                "WAITING_PAYMENT", "WAITING", "AUTH_CODE", false, false));
        assertFalse(UnifiedPurchasePolicy.canAutoCancelForUserTimeout(
                "WAITING_PAYMENT", "WAITING", "", false, true));
    }

    @Test
    public void selectedOrProcessingPaymentBlocksNewPaymentEntry() {
        assertTrue(UnifiedPurchasePolicy.blocksNewPayment("", "SCAN"));
        assertTrue(UnifiedPurchasePolicy.blocksNewPayment("", "AUTH_CODE"));
        assertTrue(UnifiedPurchasePolicy.blocksNewPayment("PROCESSING", ""));
        assertTrue(UnifiedPurchasePolicy.blocksNewPayment("ORDER_ALREADY_PAID", ""));
        assertFalse(UnifiedPurchasePolicy.blocksNewPayment("FAILED", ""));
    }

    @Test
    public void onlyExplicitUnselectedFailureRearmsPayment() {
        assertTrue(UnifiedPurchasePolicy.canRearmAfterExplicitFailure(
                "WAITING_PAYMENT", "FAILED", "", "DEVICE_PURCHASE"));
        assertFalse(UnifiedPurchasePolicy.canRearmAfterExplicitFailure(
                "WAITING_PAYMENT", "FAILED", "AUTH_CODE", "WECHAT_MICROPAY"));
        assertFalse(UnifiedPurchasePolicy.canRearmAfterExplicitFailure(
                "WAITING_PAYMENT", "PROCESSING", "", "DEVICE_PURCHASE"));
        assertFalse(UnifiedPurchasePolicy.canRearmAfterExplicitFailure(
                "DISPENSING", "FAILED", "", "DEVICE_PURCHASE"));
    }

    @Test
    public void networkBackoffMatchesServerGuide() {
        assertEquals(1L, UnifiedPurchasePolicy.queryRetryDelaySeconds(1));
        assertEquals(2L, UnifiedPurchasePolicy.queryRetryDelaySeconds(2));
        assertEquals(4L, UnifiedPurchasePolicy.queryRetryDelaySeconds(3));
        assertEquals(8L, UnifiedPurchasePolicy.queryRetryDelaySeconds(4));
        assertEquals(10L, UnifiedPurchasePolicy.queryRetryDelaySeconds(5));
        assertEquals(10L, UnifiedPurchasePolicy.queryRetryDelaySeconds(30));
    }
}
