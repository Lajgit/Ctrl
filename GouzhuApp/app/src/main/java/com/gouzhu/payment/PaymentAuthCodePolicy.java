package com.gouzhu.payment;

import java.util.Locale;

/** 付款码格式和本地提交门禁；不保存、不输出完整付款码。 */
public final class PaymentAuthCodePolicy {

    public static final String CHANNEL_WECHAT = "WECHAT_MICROPAY";
    public static final String CHANNEL_ALIPAY = "ALIPAY_BARCODE";

    private PaymentAuthCodePolicy() {
    }

    public static String classify(String authCode) {
        if (authCode == null || !isDigits(authCode)) {
            return "";
        }
        int length = authCode.length();
        if (length == 18) {
            int prefix = twoDigitPrefix(authCode);
            if (prefix >= 10 && prefix <= 15) {
                return CHANNEL_WECHAT;
            }
        }
        if (length >= 16 && length <= 24) {
            int prefix = twoDigitPrefix(authCode);
            if ((prefix >= 25 && prefix <= 29) || prefix == 30) {
                return CHANNEL_ALIPAY;
            }
        }
        return "";
    }

    public static boolean canSubmit(String paymentStatus) {
        String status = normalize(paymentStatus);
        return status.isEmpty() || "FAILED".equals(status);
    }

    public static boolean blocksRescan(String paymentStatus) {
        String status = normalize(paymentStatus);
        return "PROCESSING".equals(status)
                || "SUCCESS".equals(status)
                || "SUBMITTING".equals(status);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return false;
            }
        }
        return true;
    }

    private static int twoDigitPrefix(String value) {
        if (value == null || value.length() < 2) {
            return -1;
        }
        return (value.charAt(0) - '0') * 10 + (value.charAt(1) - '0');
    }
}
