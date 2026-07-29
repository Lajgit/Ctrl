package com.gouzhu.activation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * 单设备 P-256 身份密钥存储。
 *
 * <p>私钥由最终 com.gouzhu 应用在设备本机生成并保存在 Android Keystore，
 * 不读取旧应用、旧数据库或历史测试私钥。</p>
 */
public final class DeviceIdentityStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "com.gouzhu.device.identity.p256.v1";

    private DeviceIdentityStore() {
    }

    /** 确保本机已经生成独立身份密钥。 */
    public static synchronized void ensureKeyPair(Context context) throws Exception {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE
        );
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
        )
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build();
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    /** 导出 X.509 SubjectPublicKeyInfo 公钥，供平台登记。 */
    public static String getPublicKeyBase64(Context context) throws Exception {
        ensureKeyPair(context);
        PublicKey publicKey = loadKeyStore().getCertificate(KEY_ALIAS).getPublicKey();
        return Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
    }

    /** 使用身份私钥执行 SHA256withECDSA，返回 ASN.1 DER 签名。 */
    public static byte[] sign(Context context, byte[] content) throws Exception {
        ensureKeyPair(context);
        PrivateKey privateKey = (PrivateKey) loadKeyStore().getKey(KEY_ALIAS, null);
        if (privateKey == null) {
            throw new IllegalStateException("设备身份私钥不存在");
        }

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(content);
        return signature.sign();
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }
}
