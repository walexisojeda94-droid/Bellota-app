package com.bellota.mascota.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Guarda el token de XiaoZhi cifrado con una clave no exportable del Android Keystore. */
public final class XiaoZhiTokenStore {
    private static final String PREFS = "bellota_private_xiaozhi";
    private static final String VALUE = "access_token_ciphertext";
    private static final String KEY_ALIAS = "bellota_xiaozhi_token_key_v1";
    private final SharedPreferences prefs;

    public XiaoZhiTokenStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String token) throws Exception {
        if (token == null || token.isEmpty()) return;
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
        buffer.put(iv).put(encrypted);
        boolean saved = prefs.edit()
                .putString(VALUE, Base64.encodeToString(buffer.array(), Base64.NO_WRAP))
                .commit();
        if (!saved) throw new IllegalStateException("No se pudo guardar el token cifrado.");
    }

    public synchronized String read() throws Exception {
        String encoded = prefs.getString(VALUE, null);
        if (encoded == null) return null;
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length < 29) throw new IllegalStateException("Token cifrado inválido.");
        byte[] iv = new byte[12];
        byte[] encrypted = new byte[packed.length - iv.length];
        System.arraycopy(packed, 0, iv, 0, iv.length);
        System.arraycopy(packed, iv.length, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public synchronized void clear() throws Exception {
        if (!prefs.edit().remove(VALUE).commit())
            throw new IllegalStateException("No se pudo eliminar el token cifrado.");
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
