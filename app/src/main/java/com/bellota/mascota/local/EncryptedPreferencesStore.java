package com.bellota.mascota.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM encrypted SharedPreferences adapter for persistent conversation memory. */
public final class EncryptedPreferencesStore implements ConversationMemory.Store {
    private static final String PREFS = "bellota_conversation";
    private static final String KEY_ALIAS = "bellota_conversation_memory_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SharedPreferences preferences;
    private final Object lock = new Object();

    public EncryptedPreferencesStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migratePlaintextValues();
    }

    @Override public String get(String key) {
        synchronized (lock) {
            String stored = preferences.getString(key, null);
            if (stored == null) return null;
            if (!stored.startsWith(PREFIX)) {
                if (!writeEncryptedLocked(key, stored)) {
                    resetAllMemory();
                    return null;
                }
                return stored;
            }
            try {
                byte[] payload = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
                if (payload.length <= IV_BYTES + 16) throw new GeneralSecurityException("Valor cifrado incompleto.");
                byte[] iv = new byte[IV_BYTES];
                byte[] ciphertext = new byte[payload.length - IV_BYTES];
                System.arraycopy(payload, 0, iv, 0, IV_BYTES);
                System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(TAG_BITS, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (Exception invalidatedOrDamaged) {
                resetAllMemory();
                return null;
            }
        }
    }

    @Override public void put(String key, String value) {
        synchronized (lock) {
            if (value == null) {
                preferences.edit().remove(key).apply();
                return;
            }
            writeEncryptedLocked(key, value);
        }
    }

    private void migratePlaintextValues() {
        synchronized (lock) {
            Map<String, ?> values = preferences.getAll();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (entry.getValue() instanceof String) {
                    String value = (String) entry.getValue();
                    if (!value.startsWith(PREFIX) && !writeEncryptedLocked(entry.getKey(), value)) {
                        resetAllMemory();
                        return;
                    }
                }
            }
        }
    }

    private boolean writeEncryptedLocked(String key, String value) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != IV_BYTES)
                    throw new GeneralSecurityException("IV AES-GCM inválido.");
                byte[] payload = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, payload, 0, iv.length);
                System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
                preferences.edit()
                        .putString(key, PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP))
                        .apply();
                return true;
            } catch (GeneralSecurityException invalidatedKey) {
                resetAllMemory();
                if (attempt == 1) return false;
            } catch (Exception storageFailure) {
                return false;
            }
        }
        return false;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        try {
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
        } catch (Exception invalidated) {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        }
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
        if (!(key instanceof SecretKey)) throw new GeneralSecurityException("No existe la clave de memoria.");
        return (SecretKey) key;
    }

    private void resetAllMemory() {
        preferences.edit().clear().apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }
}
