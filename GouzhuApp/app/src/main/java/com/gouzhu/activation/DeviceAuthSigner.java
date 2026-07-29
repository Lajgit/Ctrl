package com.gouzhu.activation;

import android.content.Context;
import android.util.Base64;

import com.gouzhu.util.DeviceUtil;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 设备认证签名器。
 *
 * <p>调用层只选择明确的签名方法，不自行拼接签名域，避免报到、首次激活、
 * 后续激活和凭证恢复使用错误密钥或错误原文。</p>
 */
public final class DeviceAuthSigner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
                    .toCharArray();

    private DeviceAuthSigner() {
    }

    public static SignedRequest signEnrollment(Context context, String deviceNo) throws Exception {
        return signIdentity(context, "device-enroll|identity", deviceNo);
    }

    public static SignedRequest signActivationWithIdentity(
            Context context,
            String deviceNo
    ) throws Exception {
        return signIdentity(context, "device-activation|identity", deviceNo);
    }

    public static SignedRequest signCredentialRecovery(
            Context context,
            String deviceNo
    ) throws Exception {
        return signIdentity(context, "device-credential-recovery|identity", deviceNo);
    }

    public static SignedRequest signActivationWithMqttPassword(
            String deviceNo,
            String mqttPassword
    ) throws Exception {
        String normalized = requireNormalizedDeviceNo(deviceNo);
        if (mqttPassword == null || mqttPassword.isEmpty()) {
            throw new IllegalArgumentException("MQTT密码为空");
        }

        String nonce = newNonce();
        long timestamp = System.currentTimeMillis();
        String text = "device-activation|mqtt|"
                + normalized + "|" + nonce + "|" + timestamp;
        String signature = hmacSha256Base64Url(text, mqttPassword);
        return new SignedRequest(nonce, timestamp, signature);
    }

    private static SignedRequest signIdentity(
            Context context,
            String domain,
            String deviceNo
    ) throws Exception {
        String normalized = requireNormalizedDeviceNo(deviceNo);
        String nonce = newNonce();
        long timestamp = System.currentTimeMillis();
        String text = domain + "|" + normalized + "|" + nonce + "|" + timestamp;
        byte[] der = DeviceIdentityStore.sign(
                context,
                text.getBytes(StandardCharsets.UTF_8)
        );
        String signature = Base64.encodeToString(
                der,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
        return new SignedRequest(nonce, timestamp, signature);
    }

    private static String hmacSha256Base64Url(String text, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(
                mac.doFinal(text.getBytes(StandardCharsets.UTF_8)),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
    }

    private static String newNonce() {
        char[] chars = new char[32];
        for (int index = 0; index < chars.length; index++) {
            chars[index] = NONCE_CHARS[RANDOM.nextInt(NONCE_CHARS.length)];
        }
        return new String(chars);
    }

    private static String requireNormalizedDeviceNo(String deviceNo) {
        String normalized = DeviceUtil.normalizeDeviceNo(deviceNo);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("deviceNo为空");
        }
        return normalized;
    }

    /** 一次请求对应的一组全新 nonce、毫秒时间戳和签名。 */
    public static final class SignedRequest {
        public final String nonce;
        public final long timestamp;
        public final String signature;

        SignedRequest(String nonce, long timestamp, String signature) {
            this.nonce = nonce;
            this.timestamp = timestamp;
            this.signature = signature;
        }
    }
}
