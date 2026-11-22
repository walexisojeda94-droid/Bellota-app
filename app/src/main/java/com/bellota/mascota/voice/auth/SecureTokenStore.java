package com.bellota.mascota.voice.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
    private static final int GCM_IV_BYTES = 12;

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
            byte[] plain = token.getBytes(StandardCharsets.UTF_8);
            for (int attempt = 0; attempt < 2; attempt++) {
                SecretKey key = getOrCreateKey();
                byte[] payload;
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, key);
                    byte[] encrypted = cipher.doFinal(plain);
                    byte[] iv = cipher.getIV();
                    if (iv == null || iv.length != GCM_IV_BYTES)
                        throw new GeneralSecurityException("IV AES-GCM inválido.");
                    payload = new byte[iv.length + encrypted.length];
                    System.arraycopy(iv, 0, payload, 0, iv.length);
                    System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
                } catch (GeneralSecurityException invalidatedKey) {
                    resetKeyAndToken();
                    if (attempt == 1) throw invalidatedKey;
                    continue;
                }
                boolean saved = preferences.edit()
                        .putString(TOKEN_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                        .commit();
                if (!saved) throw new IllegalStateException("No se pudo guardar la vinculación.");
                return;
            }
            throw new IllegalStateException("No se pudo recuperar el almacenamiento seguro.");
        }
    }

    public String readRefreshToken() throws Exception {
        synchronized (lock) {
            String encoded = preferences.getString(TOKEN_KEY, null);
            if (encoded == null || encoded.isEmpty()) return null;
            final byte[] payload;
            try {
                payload = Base64.decode(encoded, Base64.NO_WRAP);
            } catch (IllegalArgumentException malformed) {
                preferences.edit().remove(TOKEN_KEY).commit();
                throw new IllegalStateException("La credencial local está dañada. Es necesario vincular nuevamente el dispositivo.", malformed);
            }
            if (payload.length <= GCM_IV_BYTES + 16) {
                preferences.edit().remove(TOKEN_KEY).commit();
                throw new IllegalStateException("La credencial local está incompleta. Es necesario vincular nuevamente el dispositivo.");
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] encrypted = new byte[payload.length - iv.length];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                String token = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
                if (token.length() < 32) throw new GeneralSecurityException("Token descifrado inválido.");
                return token;
            } catch (Exception invalidatedOrTampered) {
                resetKeyAndToken();
                throw new IllegalStateException(
                        "Android invalidó la clave segura o la credencial fue alterada. Volvé a vincular el dispositivo.",
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

    private void resetKeyAndToken() {
        preferences.edit().remove(TOKEN_KEY).commit();
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // The next save reports a clear storage error if Android Keystore remains unavailable.
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
        if (!(key instanceof SecretKey)) throw new GeneralSecurityException("No existe la clave segura del dispositivo.");
        return (SecretKey) key;
    }
}
