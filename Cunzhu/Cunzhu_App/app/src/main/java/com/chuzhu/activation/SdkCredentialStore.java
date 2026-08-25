package com.chuzhu.activation;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.pinball.xiaoda.device.sdk.client.CredentialStore;
import com.pinball.xiaoda.device.sdk.core.MqttCredential;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 存珠机 MQTT 凭证安全存储。
 */
public final class SdkCredentialStore implements CredentialStore {

    private static final String PREF = "chuzhu_sdk_credential_v1";
    private static final String KEY_BLOB = "credential_blob";
    private static final String KEY_ALIAS = "com.chuzhu.sdk.mqtt.credential.aes.v1";
    private static final String KEYSTORE = "AndroidKeyStore";

    private static volatile SdkCredentialStore instance;

    private final Context context;

    private SdkCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public static SdkCredentialStore get(Context context) {
        if (instance == null) {
            synchronized (SdkCredentialStore.class) {
                if (instance == null) {
                    instance = new SdkCredentialStore(context);
                }
            }
        }
        return instance;
    }

    @Override
    public synchronized MqttCredential load() {
        String blob = preferences().getString(KEY_BLOB, "");
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
            JSONObject json = new JSONObject(new String(
                    cipher.doFinal(ciphertext),
                    StandardCharsets.UTF_8
            ));
            JSONObject topicJson = json.getJSONObject("reportTopics");
            Map<String, String> reportTopics = new LinkedHashMap<>();
            Iterator<String> keys = topicJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                reportTopics.put(key, topicJson.getString(key));
            }
            return new MqttCredential(
                    json.getString("deviceNo"),
                    json.getString("brokerUrl"),
                    json.getString("clientId"),
                    json.getString("username"),
                    json.getString("password"),
                    json.getInt("heartbeatIntervalSeconds"),
                    json.getInt("keepAliveSeconds"),
                    json.getInt("configVersion"),
                    json.getString("commandSubscribeTopic"),
                    reportTopics,
                    json.getInt("qos")
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public synchronized void replaceAtomically(MqttCredential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("MQTT 凭证不能为空");
        }
        try {
            JSONObject json = new JSONObject();
            json.put("deviceNo", credential.getDeviceNo());
            json.put("brokerUrl", credential.getBrokerUrl());
            json.put("clientId", credential.getClientId());
            json.put("username", credential.getUsername());
            json.put("password", credential.getPassword());
            json.put("heartbeatIntervalSeconds", credential.getHeartbeatIntervalSeconds());
            json.put("keepAliveSeconds", credential.getKeepAliveSeconds());
            json.put("configVersion", credential.getConfigVersion());
            json.put("commandSubscribeTopic", credential.getCommandSubscribeTopic());
            json.put("qos", credential.getQos());

            JSONObject topicJson = new JSONObject();
            for (Map.Entry<String, String> entry : credential.getReportTopics().entrySet()) {
                topicJson.put(entry.getKey(), entry.getValue());
            }
            json.put("reportTopics", topicJson);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + "."
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            if (!preferences().edit().putString(KEY_BLOB, blob).commit()) {
                throw new IllegalStateException("MQTT 凭证保存失败");
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("MQTT 凭证加密保存失败", error);
        }
    }

    @Override
    public synchronized void clear() {
        preferences().edit().clear().commit();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
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
}
