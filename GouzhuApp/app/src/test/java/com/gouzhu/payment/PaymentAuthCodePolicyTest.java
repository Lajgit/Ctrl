package com.gouzhu.payment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PaymentAuthCodePolicyTest {

    @Test
    public void recognizesWechatPaymentCodes() {
        assertEquals(PaymentAuthCodePolicy.CHANNEL_WECHAT,
                PaymentAuthCodePolicy.classify("100000000000000000"));
        assertEquals(PaymentAuthCodePolicy.CHANNEL_WECHAT,
                PaymentAuthCodePolicy.classify("159999999999999999"));
        assertEquals("", PaymentAuthCodePolicy.classify("169999999999999999"));
        assertEquals("", PaymentAuthCodePolicy.classify("10000000000000000"));
    }

    @Test
    public void recognizesAlipayPaymentCodes() {
        assertEquals(PaymentAuthCodePolicy.CHANNEL_ALIPAY,
                PaymentAuthCodePolicy.classify("2500000000000000"));
        assertEquals(PaymentAuthCodePolicy.CHANNEL_ALIPAY,
                PaymentAuthCodePolicy.classify("300000000000000000000000"));
        assertEquals("", PaymentAuthCodePolicy.classify("2400000000000000"));
        assertEquals("", PaymentAuthCodePolicy.classify("3100000000000000"));
    }

    @Test
    public void rejectsNonNumericPaymentCodes() {
        assertEquals("", PaymentAuthCodePolicy.classify("10ABC0000000000000"));
        assertEquals("", PaymentAuthCodePolicy.classify(""));
        assertEquals("", PaymentAuthCodePolicy.classify(null));
    }
}
