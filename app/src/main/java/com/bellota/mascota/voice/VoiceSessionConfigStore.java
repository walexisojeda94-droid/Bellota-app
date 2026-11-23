package com.bellota.mascota.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the resumable voice configuration because instructions may contain private memory context. */
final class VoiceSessionConfigStore {
    private static final String PREFS = "bellota_voice_session_secure";
    private static final String DATA = "encrypted_config";
    private static final String KEY_ALIAS = "bellota_voice_session_config_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int IV_BYTES = 12;
    private final SharedPreferences preferences;

    static final class Config {
        final String backendUrl, instructions, toolsJson, voice, locale;
        Config(String backendUrl, String instructions, String toolsJson, String voice, String locale) {
            this.backendUrl = backendUrl;
            this.instructions = instructions;
            this.toolsJson = toolsJson;
            this.voice = voice;
            this.locale = locale;
        }
    }

    VoiceSessionConfigStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void save(Config config) throws Exception {
        JSONObject json = new JSONObject();
        json.put("backend", config.backendUrl);
        json.put("instructions", config.instructions);
        json.put("tools", config.toolsJson);
        json.put("voice", config.voice);
        json.put("locale", config.locale);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] encrypted;
        byte[] iv;
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            iv = cipher.getIV();
        } catch (Exception invalidatedKey) {
            deleteKey();
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            iv = cipher.getIV();
        }
        if (iv == null || iv.length != IV_BYTES) throw new IllegalStateException("IV de sesión inválido.");
        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        if (!preferences.edit().putString(DATA, Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("No se pudo guardar la configuración cifrada.");
    }

    synchronized Config load() {
        String encoded = preferences.getString(DATA, null);
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length <= IV_BYTES + 16) throw new IllegalStateException("Configuración cifrada incompleta.");
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            return new Config(json.getString("backend"), json.getString("instructions"),
                    json.getString("tools"), json.getString("voice"), json.getString("locale"));
        } catch (Exception invalid) {
            clear();
            deleteKey();
            return null;
        }
    }

    synchronized void clear() {
        preferences.edit().remove(DATA).commit();
    }

    private void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) throw new IllegalStateException("No existe la clave de configuración.");
        return (SecretKey) key;
    }
}
