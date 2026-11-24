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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM encrypted private diary. BME3 stores independently authenticated records
 * and a key version, so appends encrypt only the new entry and rotation is atomic.
 */
public final class PetMemory {
    private static final long MAX_JOURNAL_BYTES = 512L * 1024L;
    private static final long MAX_ENTRY_AGE_MS = 180L * 24L * 60L * 60L * 1000L;
    private static final long COMPACT_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRY_BYTES = 256 * 1024;
    private static final String KEY_ALIAS_PREFIX = "bellota_pet_memory_v";
    private static final int CURRENT_KEY_VERSION = 2;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512), runnable -> {
                Thread thread = new Thread(runnable, "Bellota-MemoryWriter");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicInteger PENDING_WRITES = new AtomicInteger();
    private static final AtomicInteger FAILED_WRITES = new AtomicInteger();
    private static final AtomicInteger REJECTED_WRITES = new AtomicInteger();
    private static final AtomicBoolean CLEARING = new AtomicBoolean(false);
    private static final AtomicBoolean LAST_CLEAR_SUCCESS = new AtomicBoolean(false);
    private static final AtomicInteger CLEAR_GENERATION = new AtomicInteger();
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final byte[] MAGIC_V1 = new byte[] { 'B', 'M', 'E', '1' };
    private static final byte[] MAGIC_V2 = new byte[] { 'B', 'M', 'E', '2' };
    private static final byte[] MAGIC_V3 = new byte[] { 'B', 'M', 'E', '3' };
    private static final int V2_HEADER_BYTES = 12; // magic + last compaction timestamp
    private static final int V3_HEADER_BYTES = 16; // magic + compaction timestamp + key version
    private static final Object JOURNAL_LOCK = new Object();
    private final File journal;
    private final AtomicFile atomicJournal;

    public PetMemory(Context context) {
        File directory = new File(context.getFilesDir(), "bellota");
        if (!directory.exists()) directory.mkdirs();
        journal = new File(directory, "recuerdos.jsonl");
        atomicJournal = new AtomicFile(journal);
    }

    /**
     * Enqueues a memory event without touching disk on the caller thread.
     * A single bounded writer preserves ordering and never falls back to the UI thread.
     */
    public void remember(String type, String detail) {
        if (CLEARING.get()) return;
        int generation = CLEAR_GENERATION.get();
        if (CLEARING.get()) return;
        enqueue(() -> {
            if (!CLEARING.get() && generation == CLEAR_GENERATION.get())
                rememberNow(type, detail);
        });
    }

    private synchronized void rememberNow(String type, String detail) {
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
                if (isV3Journal()) {
                    int version = readKeyVersion();
                    long lastCompacted = readCompactionTime();
                    if (journal.length() + line.length + 64L > MAX_JOURNAL_BYTES
                            || lastCompacted <= 0L || now - lastCompacted >= COMPACT_INTERVAL_MS) {
                        byte[] current = pruneExpired(readV3Plaintext(), now);
                        int writeVersion = journal.exists() ? version : Math.max(CURRENT_KEY_VERSION, version + 1);
                        writeEncryptedV3(trimToLimit(appendLine(current, line)), now, writeVersion);
                    } else {
                        appendEncryptedRecord(line, version);
                    }
                } else if (isV2Journal()) {
                    byte[] current = pruneExpired(readV2Plaintext(), now);
                    boolean legacyKeyWasUsable = journal.exists();
                    writeEncryptedV3(trimToLimit(appendLine(current, line)), now, CURRENT_KEY_VERSION);
                    if (legacyKeyWasUsable) deleteKey(1);
                } else {
                    // One-time migration from plaintext JSONL or the original BME1 envelope.
                    boolean encryptedLegacy = hasMagicAtStart(MAGIC_V1);
                    byte[] previous = pruneExpired(readLegacyPlaintext(), now);
                    boolean legacyKeyWasUsable = encryptedLegacy && journal.exists();
                    writeEncryptedV3(trimToLimit(appendLine(previous, line)), now, CURRENT_KEY_VERSION);
                    if (legacyKeyWasUsable) deleteKey(1);
                }
            }
        } catch (Exception error) {
            FAILED_WRITES.incrementAndGet();
            // Persistence errors are observable through counters and never interrupt the pet runtime.
        }
    }

    private static boolean enqueue(Runnable operation) {
        PENDING_WRITES.incrementAndGet();
        try {
            WRITER.execute(() -> {
                try { operation.run(); }
                catch (RuntimeException ignored) { FAILED_WRITES.incrementAndGet(); }
                finally { PENDING_WRITES.decrementAndGet(); }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            PENDING_WRITES.decrementAndGet();
            REJECTED_WRITES.incrementAndGet();
            return false;
        }
    }

    /** Test/diagnostic hook. Waiting is explicit and must never be called from the UI thread. */
    public static boolean awaitPendingWrites(long timeoutMs) throws InterruptedException {
        CountDownLatch barrier = new CountDownLatch(1);
        try { WRITER.execute(barrier::countDown); }
        catch (java.util.concurrent.RejectedExecutionException full) { return false; }
        return barrier.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public static int getPendingWriteCount() { return PENDING_WRITES.get(); }
    public static int getFailedWriteCount() { return FAILED_WRITES.get(); }
    public static int getRejectedWriteCount() { return REJECTED_WRITES.get(); }
    public static boolean wasLastClearSuccessful() { return LAST_CLEAR_SUCCESS.get(); }

    /** Deletes active and quarantined diary files before removing its encryption keys. */
    public void clear() {
        if (!CLEARING.compareAndSet(false, true)) return;
        CLEAR_GENERATION.incrementAndGet();
        LAST_CLEAR_SUCCESS.set(false);
        boolean queued = enqueue(() -> {
            try {
                synchronized (JOURNAL_LOCK) {
                    File[] files = journal.getParentFile().listFiles((dir, name) ->
                            name.equals(journal.getName()) || name.equals(journal.getName() + ".bak")
                                    || name.startsWith(journal.getName() + ".locked."));
                    if (files == null) {
                        FAILED_WRITES.incrementAndGet();
                        return;
                    }
                    for (File file : files) {
                        if (file.exists() && !file.delete()) {
                            FAILED_WRITES.incrementAndGet();
                            return;
                        }
                    }
                    if (!deleteAllKeys()) {
                        FAILED_WRITES.incrementAndGet();
                        return;
                    }
                    LAST_CLEAR_SUCCESS.set(true);
                }
            } finally {
                CLEARING.set(false);
            }
        });
        if (!queued) CLEARING.set(false);
    }

    /** Re-encrypts the complete diary with a fresh versioned Keystore key. */
    public void rotateKey() {
        enqueue(() -> {
            synchronized (JOURNAL_LOCK) {
                try {
                    if (!journal.exists() || journal.length() == 0L) return;
                    int oldVersion = isV3Journal() ? readKeyVersion() : 1;
                    byte[] plaintext = isV3Journal() ? readV3Plaintext()
                            : isV2Journal() ? readV2Plaintext() : readLegacyPlaintext();
                    if (!journal.exists()) return; // key loss/corruption was quarantined; preserve its ciphertext
                    if (oldVersion >= 16) return;
                    int nextVersion = Math.max(CURRENT_KEY_VERSION, oldVersion + 1);
                    getOrCreateKey(nextVersion);
                    writeEncryptedV3(plaintext, System.currentTimeMillis(), nextVersion);
                    deleteKey(oldVersion);
                } catch (Exception error) {
                    FAILED_WRITES.incrementAndGet();
                }
            }
        });
    }

    private static boolean deleteAllKeys() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            for (int version = 1; version <= 16; version++) {
                String alias = KEY_ALIAS_PREFIX + version;
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static byte[] appendLine(byte[] previous, byte[] line) {
        byte[] combined = new byte[previous.length + line.length];
        System.arraycopy(previous, 0, combined, 0, previous.length);
        System.arraycopy(line, 0, combined, previous.length, line.length);
        return combined;
    }

    private boolean hasMagicAtStart(byte[] expected) {
        if (!journal.exists() || journal.length() < expected.length) return false;
        try (FileInputStream input = atomicJournal.openRead()) {
            byte[] header = new byte[expected.length];
            return input.read(header) == expected.length && Arrays.equals(header, expected);
        } catch (Exception ignored) { return false; }
    }

    private boolean isV2Journal() { return hasMagicAtStart(MAGIC_V2); }
    private boolean isV3Journal() { return hasMagicAtStart(MAGIC_V3); }

    private int readKeyVersion() throws Exception {
        try (DataInputStream input = new DataInputStream(atomicJournal.openRead())) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V3)) return 1;
            input.readLong();
            int version = input.readInt();
            if (version < 1 || version > 16) throw new GeneralSecurityException("Versión de clave inválida.");
            return version;
        }
    }

    private long readCompactionTime() {
        try (DataInputStream input = new DataInputStream(atomicJournal.openRead())) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V3)) return 0L;
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
            // Accept only the known legacy JSONL format. Unknown binary headers are quarantined.
            String legacyText = new String(stored, StandardCharsets.UTF_8).trim();
            if (legacyText.isEmpty()) return new byte[0];
            if (legacyText.startsWith("{") && legacyText.contains("\"timestamp\""))
                return trimToLimit(stored);
            if (!quarantineUnreadableJournal())
                throw new GeneralSecurityException("No se pudo preservar un diario con formato desconocido.");
            return new byte[0];
        }
        try {
            if (stored.length <= MAGIC_V1.length + GCM_IV_BYTES + 16)
                throw new GeneralSecurityException("Diario cifrado incompleto.");
            byte[] iv = Arrays.copyOfRange(stored, MAGIC_V1.length, MAGIC_V1.length + GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(stored, MAGIC_V1.length + GCM_IV_BYTES, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(1), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception invalidatedOrDamaged) {
            if (!quarantineUnreadableJournal()) throw new GeneralSecurityException("No se pudo preservar el diario bloqueado.");
            return new byte[0];
        }
    }

    private byte[] readV2Plaintext() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataInputStream input = new DataInputStream(atomicJournal.openRead())) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V2)) throw new GeneralSecurityException("Formato BME2 inválido.");
            input.readLong();
            while (input.available() > 0) {
                if (input.available() < 4) break; // incomplete trailing length from an interrupted append
                int recordLength = input.readInt();
                if (recordLength < GCM_IV_BYTES + 16 || recordLength > MAX_ENTRY_BYTES + 64)
                    throw new GeneralSecurityException("Longitud de registro BME2 inválida.");
                if (input.available() < recordLength) break;
                byte[] record = new byte[recordLength];
                input.readFully(record);
                byte[] iv = Arrays.copyOfRange(record, 0, GCM_IV_BYTES);
                byte[] ciphertext = Arrays.copyOfRange(record, GCM_IV_BYTES, record.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(1), new GCMParameterSpec(GCM_TAG_BITS, iv));
                output.write(cipher.doFinal(ciphertext));
            }
            return output.toByteArray();
        } catch (Exception damagedOrMissingKey) {
            if (!quarantineUnreadableJournal()) throw new IllegalStateException("No se pudo preservar el diario BME2.", damagedOrMissingKey);
            return new byte[0];
        }
    }

    private byte[] readV3Plaintext() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataInputStream input = new DataInputStream(atomicJournal.openRead())) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V3)) throw new GeneralSecurityException("Formato BME3 inválido.");
            input.readLong();
            int version = input.readInt();
            if (version < 1 || version > 16) throw new GeneralSecurityException("Versión de clave inválida.");
            while (input.available() > 0) {
                if (input.available() < 4) break;
                int recordLength = input.readInt();
                if (recordLength < GCM_IV_BYTES + 16 || recordLength > MAX_ENTRY_BYTES + 64)
                    throw new GeneralSecurityException("Longitud de registro BME3 inválida.");
                if (input.available() < recordLength) break;
                byte[] record = new byte[recordLength];
                input.readFully(record);
                byte[] iv = Arrays.copyOfRange(record, 0, GCM_IV_BYTES);
                byte[] ciphertext = Arrays.copyOfRange(record, GCM_IV_BYTES, record.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(version), new GCMParameterSpec(GCM_TAG_BITS, iv));
                output.write(cipher.doFinal(ciphertext));
            }
            return output.toByteArray();
        } catch (Exception damagedOrMissingKey) {
            if (!quarantineUnreadableJournal()) throw new IllegalStateException("No se pudo preservar el diario BME3.", damagedOrMissingKey);
            return new byte[0];
        }
    }

    private void appendEncryptedRecord(byte[] plaintext, int version) throws Exception {
        try {
            getExistingKey(version);
        } catch (Exception missingOrInvalidatedKey) {
            if (!quarantineUnreadableJournal())
                throw new GeneralSecurityException("No se pudo preservar el diario cuya clave falta.", missingOrInvalidatedKey);
            writeEncryptedV3(plaintext, System.currentTimeMillis(), Math.max(CURRENT_KEY_VERSION, version + 1));
            return;
        }
        byte[] record = encryptRecord(plaintext, version, false);
        try (RandomAccessFile output = new RandomAccessFile(journal, "rw")) {
            if (output.length() == 0) throw new GeneralSecurityException("No existe un diario BME3 para anexar.");
            byte[] magic = new byte[4];
            output.seek(0L);
            output.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_V3)) throw new GeneralSecurityException("Formato de diario inesperado.");
            int versionInFile = readKeyVersion();
            if (versionInFile != version) throw new GeneralSecurityException("La versión de clave cambió durante la escritura.");
            long validEnd = V3_HEADER_BYTES;
            output.seek(V3_HEADER_BYTES);
            while (output.getFilePointer() + 4 <= output.length()) {
                int length = output.readInt();
                if (length < GCM_IV_BYTES + 16 || length > MAX_ENTRY_BYTES + 64)
                    throw new GeneralSecurityException("Longitud de registro BME3 inválida.");
                if (output.getFilePointer() + length > output.length()) break;
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

    private byte[] encryptRecord(byte[] plaintext, int version, boolean allowCreate) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, allowCreate ? getOrCreateKey(version) : getExistingKey(version));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES)
            throw new GeneralSecurityException("IV AES-GCM inválido.");
        byte[] record = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, record, 0, iv.length);
        System.arraycopy(encrypted, 0, record, iv.length, encrypted.length);
        return record;
    }

    private void writeEncryptedV3(byte[] plaintext, long compactedAt, int version) throws Exception {
        getOrCreateKey(version);
        List<byte[]> records = new ArrayList<>();
        String[] lines = new String(plaintext, StandardCharsets.UTF_8).split("\n");
        long totalBytes = V3_HEADER_BYTES;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isEmpty()) continue;
            byte[] record = encryptRecord((lines[i] + "\n").getBytes(StandardCharsets.UTF_8), version, false);
            long required = 4L + record.length;
            if (totalBytes + required > MAX_JOURNAL_BYTES) break;
            records.add(0, record);
            totalBytes += required;
        }
        FileOutputStream output = null;
        try {
            output = atomicJournal.startWrite();
            output.write(MAGIC_V3);
            output.write(new byte[] {
                    (byte) (compactedAt >>> 56), (byte) (compactedAt >>> 48),
                    (byte) (compactedAt >>> 40), (byte) (compactedAt >>> 32),
                    (byte) (compactedAt >>> 24), (byte) (compactedAt >>> 16),
                    (byte) (compactedAt >>> 8), (byte) compactedAt
            });
            output.write(new byte[] {
                    (byte) (version >>> 24), (byte) (version >>> 16),
                    (byte) (version >>> 8), (byte) version
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

    private SecretKey getOrCreateKey(int version) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        String alias = KEY_ALIAS_PREFIX + version;
        java.security.Key existing = keyStore.getKey(alias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private SecretKey getExistingKey(int version) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        java.security.Key key = keyStore.getKey(KEY_ALIAS_PREFIX + version, null);
        if (!(key instanceof SecretKey)) throw new GeneralSecurityException("No existe la clave v" + version + " del diario.");
        return (SecretKey) key;
    }

    private void deleteKey(int version) {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            String alias = KEY_ALIAS_PREFIX + version;
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
        } catch (Exception ignored) { }
    }

    private boolean quarantineUnreadableJournal() {
        try {
            if (!journal.exists()) return true;
            File quarantined = new File(journal.getParentFile(),
                    "recuerdos.jsonl.locked." + System.currentTimeMillis());
            return journal.renameTo(quarantined);
        } catch (Exception ignored) { return false; }
    }

    /** Returns the on-disk path; contents are encrypted binary, not readable JSONL. */

    public File getJournalFile() { return journal; }
}
