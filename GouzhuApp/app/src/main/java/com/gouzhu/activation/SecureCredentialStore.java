package com.gouzhu.activation;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * MQTT 凭证安全存储。
 *
 * <p>整组凭证序列化后一次加密并写入一个 SharedPreferences 字段，避免出现
 * “已激活标志已写入但密码尚未写入”的中间状态。当前实现不迁移旧凭证。</p>
 */
public final class SecureCredentialStore {

    private static final String PREF = "secure_mqtt_credential_v1";
    private static final String KEY_BLOB = "credential_blob";
    private static final String KEY_ALIAS = "com.gouzhu.mqtt.credential.aes.v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final Gson GSON = new Gson();

    private SecureCredentialStore() {
    }

    public static synchronized boolean save(
            Context context,
            ActivationManager.MqttCredential credential
    ) {
        if (credential == null || !credential.isValid()) {
            return false;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(
                    GSON.toJson(credential).getBytes(StandardCharsets.UTF_8)
            );
            String blob = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + "."
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            return preferences(context).edit().putString(KEY_BLOB, blob).commit();
        } catch (Throwable error) {
            return false;
        }
    }

    public static synchronized ActivationManager.MqttCredential load(Context context) {
        String blob = preferences(context).getString(KEY_BLOB, "");
        if (blob == null || blob.isEmpty()) {
            return null;
        }
        try {
            String[] parts = blob.split("\\.", 2);
            if (parts.length != 2) {
                return null;
            }
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            String json = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            ActivationManager.MqttCredential credential =
                    GSON.fromJson(json, ActivationManager.MqttCredential.class);
            return credential != null && credential.isValid() ? credential : null;
        } catch (Throwable error) {
            return null;
        }
    }

    public static synchronized void clear(Context context) {
        preferences(context).edit().clear().commit();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
