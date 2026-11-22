package com.bellota.mascota.core;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM encrypted private diary, with a non-exportable Android Keystore key. */
public final class PetMemory {
    private static final long MAX_JOURNAL_BYTES = 512L * 1024L;
    private static final String KEY_ALIAS = "bellota_pet_memory_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final byte[] MAGIC = new byte[] { 'B', 'M', 'E', '1' };
    private final File journal;
    private final AtomicFile atomicJournal;

    public PetMemory(Context context) {
        File directory = new File(context.getFilesDir(), "bellota");
        if (!directory.exists()) directory.mkdirs();
        journal = new File(directory, "recuerdos.jsonl");
        atomicJournal = new AtomicFile(journal);
    }

    public synchronized void remember(String type, String detail) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("type", type == null ? "evento" : type);
            String safeDetail = detail == null ? "" : detail;
            entry.put("detail", safeDetail);
            byte[] line = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (line.length > MAX_JOURNAL_BYTES) {
                int low = 0;
                int high = safeDetail.codePointCount(0, safeDetail.length());
                while (low < high) {
                    int mid = low + (high - low + 1) / 2;
                    int end = safeDetail.offsetByCodePoints(0, mid);
                    entry.put("detail", safeDetail.substring(0, end));
                    byte[] candidate = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                    if (candidate.length <= MAX_JOURNAL_BYTES) low = mid;
                    else high = mid - 1;
                }
                int end = safeDetail.offsetByCodePoints(0, low);
                entry.put("detail", safeDetail.substring(0, end));
                line = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            }
            byte[] previous = readPlaintext();
            byte[] combined = new byte[previous.length + line.length];
            System.arraycopy(previous, 0, combined, 0, previous.length);
            System.arraycopy(line, 0, combined, previous.length, line.length);
            writeEncrypted(trimToLimit(combined));
        } catch (Exception ignored) {
            // Memory persistence must never interrupt the pet's runtime.
        }
    }

    private byte[] readPlaintext() throws Exception {
        if (!journal.exists()) return new byte[0];
        byte[] stored;
        try (FileInputStream input = atomicJournal.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            stored = output.toByteArray();
        }
        if (!hasMagic(stored)) {
            // Migrate the previous plaintext JSONL diary on first write.
            byte[] legacy = trimToLimit(stored);
            writeEncrypted(legacy);
            return legacy;
        }
        try {
            if (stored.length <= MAGIC.length + GCM_IV_BYTES + 16)
                throw new GeneralSecurityException("Diario cifrado incompleto.");
            byte[] iv = Arrays.copyOfRange(stored, MAGIC.length, MAGIC.length + GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(stored, MAGIC.length + GCM_IV_BYTES, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception invalidatedOrDamaged) {
            // Preserve ciphertext for diagnosis; never silently replace it with plaintext.
            quarantineUnreadableJournal();
            deleteKey();
            return new byte[0];
        }
    }

    private void writeEncrypted(byte[] plaintext) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES)
            throw new GeneralSecurityException("IV AES-GCM inválido.");
        byte[] payload = new byte[MAGIC.length + iv.length + encrypted.length];
        System.arraycopy(MAGIC, 0, payload, 0, MAGIC.length);
        System.arraycopy(iv, 0, payload, MAGIC.length, iv.length);
        System.arraycopy(encrypted, 0, payload, MAGIC.length + iv.length, encrypted.length);
        FileOutputStream output = null;
        try {
            output = atomicJournal.startWrite();
            output.write(payload);
            output.getFD().sync();
            atomicJournal.finishWrite(output);
        } catch (Exception error) {
            if (output != null) atomicJournal.failWrite(output);
            throw error;
        }
    }

    private static byte[] trimToLimit(byte[] data) {
        if (data.length <= MAX_JOURNAL_BYTES) return data;
        int start = data.length - (int) MAX_JOURNAL_BYTES;
        while (start < data.length && data[start] != '\n') start++;
        if (start < data.length) start++;
        return Arrays.copyOfRange(data, start, data.length);
    }

    private static boolean hasMagic(byte[] data) {
        if (data.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
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
        if (!(key instanceof SecretKey)) throw new GeneralSecurityException("No existe la clave del diario.");
        return (SecretKey) key;
    }

    private void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }

    private void quarantineUnreadableJournal() {
        try {
            if (!journal.exists()) return;
            File quarantined = new File(journal.getParentFile(),
                    "recuerdos.jsonl.locked." + System.currentTimeMillis());
            journal.renameTo(quarantined);
        } catch (Exception ignored) { }
    }

    /** Returns the on-disk path; contents are encrypted binary, not readable JSONL. */
    public File getJournalFile() { return journal; }
}
