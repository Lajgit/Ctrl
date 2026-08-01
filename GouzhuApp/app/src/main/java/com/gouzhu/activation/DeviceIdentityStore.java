package com.gouzhu.activation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyPair;
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

    /**
     * 返回同一个 AndroidKeyStore alias 对应的完整密钥对。
     *
     * <p>新版 SDK 首次报到需要同时使用公钥和私钥：私钥负责签名，公钥由 SDK
     * 自动写入 identityPublicKey。私钥仍然只是不可导出的 KeyStore 句柄。</p>
     */
    public static KeyPair getKeyPair(Context context) throws Exception {
        ensureKeyPair(context);
        KeyStore keyStore = loadKeyStore();
        if (keyStore.getCertificate(KEY_ALIAS) == null) {
            throw new IllegalStateException("设备身份公钥证书不存在");
        }

        PublicKey publicKey = keyStore.getCertificate(KEY_ALIAS).getPublicKey();
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
        if (publicKey == null) {
            throw new IllegalStateException("设备身份公钥不存在");
        }
        if (privateKey == null) {
            throw new IllegalStateException("设备身份私钥不存在");
        }
        return new KeyPair(publicKey, privateKey);
    }

    /** 导出 X.509 SubjectPublicKeyInfo 公钥，供调试和预登记兼容流程使用。 */
    public static String getPublicKeyBase64(Context context) throws Exception {
        PublicKey publicKey = getKeyPair(context).getPublic();
        return Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
    }

    /** 返回 AndroidKeyStore 私钥句柄供服务端 SDK 签名，私钥材料不可导出。 */
    public static PrivateKey getPrivateKey(Context context) throws Exception {
        return getKeyPair(context).getPrivate();
    }

    /** 兼容现有工具代码的本地 SHA256withECDSA 签名入口。 */
    public static byte[] sign(Context context, byte[] content) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(getPrivateKey(context));
        signature.update(content);
        return signature.sign();
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }
}
