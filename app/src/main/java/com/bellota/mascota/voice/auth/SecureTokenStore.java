package com.bellota.mascota.voice.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores only the long-lived device refresh token, encrypted with an Android
 * Keystore AES-GCM key. Access tokens and OpenAI client secrets stay in memory.
 */
public final class SecureTokenStore {
    private static final String PREFS = "bellota_secure_auth";
    private static final String TOKEN_KEY = "refresh_token";
    private static final String KEY_ALIAS = "bellota_device_refresh_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences preferences;
    private final Object lock = new Object();

    public SecureTokenStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context obligatorio.");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveRefreshToken(String token) throws Exception {
        if (token == null || token.trim().length() < 32)
            throw new IllegalArgumentException("El token de renovación no es válido.");
        synchronized (lock) {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            boolean saved = preferences.edit()
                    .putString(TOKEN_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                    .commit();
            if (!saved) throw new IllegalStateException("No se pudo guardar la vinculación.");
        }
    }

    public String readRefreshToken() throws Exception {
        synchronized (lock) {
            String encoded = preferences.getString(TOKEN_KEY, null);
            if (encoded == null || encoded.isEmpty()) return null;
            byte[] payload;
            try {
                payload = Base64.decode(encoded, Base64.NO_WRAP);
            } catch (IllegalArgumentException invalid) {
                clear();
                return null;
            }
            if (payload.length <= 12 + 16) {
                clear();
                return null;
            }
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[payload.length - iv.length];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception invalidatedOrTampered) {
                clear();
                throw new IllegalStateException("La vinculación guardada no se puede descifrar. Es necesario vincular nuevamente el dispositivo.",
                        invalidatedOrTampered);
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            preferences.edit().remove(TOKEN_KEY).commit();
        }
    }

    public boolean hasRefreshToken() {
        synchronized (lock) {
            return preferences.contains(TOKEN_KEY);
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) throw new IllegalStateException("No existe la clave segura del dispositivo.");
        return (SecretKey) key;
    }
}
