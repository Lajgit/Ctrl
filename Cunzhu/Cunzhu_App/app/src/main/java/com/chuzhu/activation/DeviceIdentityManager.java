package com.chuzhu.activation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

/**
 * 存珠机独立身份密钥管理。
 */
public final class DeviceIdentityManager {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "com.chuzhu.device.identity.p256.v1";

    private DeviceIdentityManager() {
    }

    /** 确保本机已有 P-256 身份密钥，私钥不导出。 */
    public static synchronized void ensureKeyPair(Context context) throws Exception {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE
        );
        generator.initialize(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
        )
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build());
        generator.generateKeyPair();
    }

    public static KeyPair getKeyPair(Context context) throws Exception {
        ensureKeyPair(context);
        KeyStore keyStore = loadKeyStore();
        if (keyStore.getCertificate(KEY_ALIAS) == null) {
            throw new IllegalStateException("设备身份公钥证书不存在");
        }
        PublicKey publicKey = keyStore.getCertificate(KEY_ALIAS).getPublicKey();
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
        if (publicKey == null || privateKey == null) {
            throw new IllegalStateException("设备身份密钥不完整");
        }
        return new KeyPair(publicKey, privateKey);
    }

    public static PrivateKey getPrivateKey(Context context) throws Exception {
        return getKeyPair(context).getPrivate();
    }

    public static String getPublicKeyBase64(Context context) throws Exception {
        return Base64.encodeToString(
                getKeyPair(context).getPublic().getEncoded(),
                Base64.NO_WRAP
        );
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }
}
