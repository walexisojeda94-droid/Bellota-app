package com.bellota.mascota.core;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM encrypted private diary. BME2 stores independently authenticated records,
 * so a normal append encrypts only the new entry instead of rewriting the full diary.
 */
public final class PetMemory {
    private static final long MAX_JOURNAL_BYTES = 512L * 1024L;
    private static final long MAX_ENTRY_AGE_MS = 180L * 24L * 60L * 60L * 1000L;
    private static final long COMPACT_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRY_BYTES = 256 * 1024;
    private static final String KEY_ALIAS = "bellota_pet_memory_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final byte[] MAGIC_V1 = new byte[] { 'B', 'M', 'E', '1' };
    private static final byte[] MAGIC_V2 = new byte[] { 'B', 'M', 'E', '2' };
    private static final int V2_HEADER_BYTES = 12; // magic + last compaction timestamp
    private static final Object JOURNAL_LOCK = new Object();
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
            if (line.length > MAX_ENTRY_BYTES) {
                int low = 0;
                int high = safeDetail.codePointCount(0, safeDetail.length());
                while (low < high) {
                    int mid = low + (high - low + 1) / 2;
                    int end = safeDetail.offsetByCodePoints(0, mid);
                    entry.put("detail", safeDetail.substring(0, end));
                    byte[] candidate = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                    if (candidate.length <= MAX_ENTRY_BYTES) low = mid;
                    else high = mid - 1;
                }
                int end = safeDetail.offsetByCodePoints(0, low);
                entry.put("detail", safeDetail.substring(0, end));
                line = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            }

            synchronized (JOURNAL_LOCK) {
                long now = System.currentTimeMillis();
                if (isV2Journal()) {
                    long lastCompacted = readCompactionTime();
                    if (journal.length() + line.length + 64L > MAX_JOURNAL_BYTES
                            || lastCompacted <= 0L || now - lastCompacted >= COMPACT_INTERVAL_MS) {
                        byte[] current = pruneExpired(readV2Plaintext(), now);
                        byte[] combined = appendLine(current, line);
                        writeEncryptedV2(trimToLimit(combined), now);
                    } else {
                        appendEncryptedRecord(line);
                    }
                } else {
                    // One-time migration from plaintext JSONL or the original BME1 envelope.
                    byte[] previous = pruneExpired(readLegacyPlaintext(), now);
                    writeEncryptedV2(trimToLimit(appendLine(previous, line)), now);
                }
            }
        } catch (Exception ignored) {
            // Memory persistence must never interrupt the pet's runtime.
        }
    }

    private static byte[] appendLine(byte[] previous, byte[] line) {
        byte[] combined = new byte[previous.length + line.length];
        System.arraycopy(previous, 0, combined, 0, previous.length);
        System.arraycopy(line, 0, combined, previous.length, line.length);
        return combined;
    }

    private boolean isV2Journal() {
        if (!journal.exists() || journal.length() < MAGIC_V2.length) return false;
        try (FileInputStream input = atomicJournal.openRead()) {
            byte[] header = new byte[4];
            return input.read(header) == 4 && Arrays.equals(header, MAGIC_V2);
        } catch (Exception ignored) { return false; }
    }

    private long readCompactionTime() {
        try (DataInputStream input = new DataInputStream(atomicJournal.openRead())) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V2)) return 0L;
            return input.readLong();
        } catch (Exception ignored) { return 0L; }
    }

    private byte[] readLegacyPlaintext() throws Exception {
        byte[] stored;
        try (FileInputStream input = atomicJournal.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            stored = output.toByteArray();
        } catch (java.io.FileNotFoundException missing) {
            return new byte[0];
        }
        if (!hasMagic(stored, MAGIC_V1)) {
            // Legacy plaintext is encrypted by the BME2 migration before the next write.
            return trimToLimit(stored);
        }
        try {
            if (stored.length <= MAGIC_V1.length + GCM_IV_BYTES + 16)
                throw new GeneralSecurityException("Diario cifrado incompleto.");
            byte[] iv = Arrays.copyOfRange(stored, MAGIC_V1.length, MAGIC_V1.length + GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(stored, MAGIC_V1.length + GCM_IV_BYTES, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception invalidatedOrDamaged) {
            quarantineUnreadableJournal();
            deleteKey();
            return new byte[0];
        }
    }

    private byte[] readV2Plaintext() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataInputStream input = new DataInputStream(atomicJournal.openRead())) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V2)) return new byte[0];
            input.readLong(); // compaction timestamp
            while (input.available() >= 4) {
                int recordLength = input.readInt();
                if (recordLength < GCM_IV_BYTES + 16 || recordLength > MAX_ENTRY_BYTES + 64
                        || input.available() < recordLength) break;
                byte[] record = new byte[recordLength];
                input.readFully(record);
                byte[] iv = Arrays.copyOfRange(record, 0, GCM_IV_BYTES);
                byte[] ciphertext = Arrays.copyOfRange(record, GCM_IV_BYTES, record.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                output.write(cipher.doFinal(ciphertext));
            }
            return output.toByteArray();
        } catch (Exception damaged) {
            quarantineUnreadableJournal();
            deleteKey();
            return new byte[0];
        }
    }

    private void appendEncryptedRecord(byte[] plaintext) throws Exception {
        byte[] record = encryptRecord(plaintext);
        try (RandomAccessFile output = new RandomAccessFile(journal, "rw")) {
            if (output.length() == 0) {
                output.write(MAGIC_V2);
                output.writeLong(System.currentTimeMillis());
            }
            byte[] magic = new byte[4];
            output.seek(0L);
            output.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V2)) throw new GeneralSecurityException("Formato de diario inesperado.");
            long validEnd = V2_HEADER_BYTES;
            output.seek(V2_HEADER_BYTES);
            while (output.getFilePointer() + 4 <= output.length()) {
                int length = output.readInt();
                if (length < GCM_IV_BYTES + 16 || length > MAX_ENTRY_BYTES + 64
                        || output.getFilePointer() + length > output.length()) break;
                output.seek(output.getFilePointer() + length);
                validEnd = output.getFilePointer();
            }
            output.setLength(validEnd); // discard only an incomplete trailing write
            output.seek(validEnd);
            output.writeInt(record.length);
            output.write(record);
            output.getFD().sync();
        }
    }

    private byte[] encryptRecord(byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES)
            throw new GeneralSecurityException("IV AES-GCM inválido.");
        byte[] record = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, record, 0, iv.length);
        System.arraycopy(encrypted, 0, record, iv.length, encrypted.length);
        return record;
    }

    private void writeEncryptedV2(byte[] plaintext, long compactedAt) throws Exception {
        List<byte[]> records = new ArrayList<>();
        String[] lines = new String(plaintext, StandardCharsets.UTF_8).split("\n");
        long totalBytes = V2_HEADER_BYTES;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isEmpty()) continue;
            byte[] record = encryptRecord((lines[i] + "\n").getBytes(StandardCharsets.UTF_8));
            long required = 4L + record.length;
            if (totalBytes + required > MAX_JOURNAL_BYTES) break;
            records.add(0, record);
            totalBytes += required;
        }
        FileOutputStream output = null;
        try {
            output = atomicJournal.startWrite();
            output.write(MAGIC_V2);
            output.write(new byte[] {
                    (byte) (compactedAt >>> 56), (byte) (compactedAt >>> 48),
                    (byte) (compactedAt >>> 40), (byte) (compactedAt >>> 32),
                    (byte) (compactedAt >>> 24), (byte) (compactedAt >>> 16),
                    (byte) (compactedAt >>> 8), (byte) compactedAt
            });
            for (byte[] record : records) {
                output.write(new byte[] {
                        (byte) (record.length >>> 24), (byte) (record.length >>> 16),
                        (byte) (record.length >>> 8), (byte) record.length
                });
                output.write(record);
            }
            output.getFD().sync();
            atomicJournal.finishWrite(output);
        } catch (Exception error) {
            if (output != null) atomicJournal.failWrite(output);
            throw error;
        }
    }

    private static byte[] pruneExpired(byte[] data, long now) {
        if (data.length == 0) return data;
        long cutoff = now - MAX_ENTRY_AGE_MS;
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\n");
        StringBuilder retained = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            try {
                long timestamp = new JSONObject(line).optLong("timestamp", 0L);
                if (timestamp >= cutoff && timestamp <= now + 24L * 60L * 60L * 1000L)
                    retained.append(line).append('\n');
            } catch (Exception ignored) {
                // Malformed or unrecognized legacy entries are not retained.
            }
        }
        return retained.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] trimToLimit(byte[] data) {
        if (data.length <= MAX_JOURNAL_BYTES) return data;
        int start = data.length - (int) MAX_JOURNAL_BYTES;
        while (start < data.length && data[start] != '\n') start++;
        if (start < data.length) start++;
        return Arrays.copyOfRange(data, start, data.length);
    }

    private static boolean hasMagic(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (data[i] != magic[i]) return false;
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
