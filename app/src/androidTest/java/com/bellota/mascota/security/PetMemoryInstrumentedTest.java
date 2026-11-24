package com.bellota.mascota.security;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.bellota.mascota.core.PetMemory;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PetMemoryInstrumentedTest {
    @Test public void migratesLegacyJournalAndAppendsOnlyEncryptedRecords() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "bellota");
        if (!directory.exists()) assertTrue(directory.mkdirs());
        File journal = new File(directory, "recuerdos.jsonl");
        File backup = new File(journal.getPath() + ".bak");
        journal.delete();
        backup.delete();

        String legacy = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"type\":\"legacy\",\"detail\":\"legacy private text\"}\n";
        try (FileOutputStream output = new FileOutputStream(journal)) {
            output.write(legacy.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }

        PetMemory memory = new PetMemory(context);
        memory.remember("test", "new private text");
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        byte[] first = readAll(journal);
        assertTrue(first.length > 20);
        assertEquals("BME3", new String(first, 0, 4, StandardCharsets.US_ASCII));
        String raw = new String(first, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("legacy private text"));
        assertFalse(raw.contains("new private text"));

        memory.remember("test", "another private entry");
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        byte[] second = readAll(journal);
        assertTrue(second.length > first.length);
        assertTrue("A normal append must not rewrite the complete journal",
                second.length - first.length < first.length);
        raw = new String(second, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("another private entry"));
    }

    @Test public void rotatesVersionedKeyWithoutOverwritingTheDiary() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PetMemory memory = new PetMemory(context);
        memory.clear();
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        memory.remember("rotation", "preserve this private entry");
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        File journal = memory.getJournalFile();
        byte[] before = readAll(journal);
        assertEquals("BME3", new String(before, 0, 4, StandardCharsets.US_ASCII));
        int oldVersion = readInt(before, 12);
        memory.rotateKey();
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        byte[] after = readAll(journal);
        assertEquals("BME3", new String(after, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(oldVersion + 1, readInt(after, 12));
        memory.remember("rotation", "append after key rotation");
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        assertTrue(journal.length() > after.length);
    }

    @Test public void authenticationFailureQuarantinesDamagedDiaryAndStartsFresh() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PetMemory memory = new PetMemory(context);
        memory.clear();
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        memory.remember("integrity", "authenticated record before corruption");
        assertTrue(PetMemory.awaitPendingWrites(10000L));

        File journal = memory.getJournalFile();
        byte[] damaged = readAll(journal);
        assertTrue(damaged.length > 32);
        // Force the next write through the full authenticated-read/compaction path.
        long oldTimestamp = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L;
        for (int i = 0; i < 8; i++) damaged[4 + i] = (byte) (oldTimestamp >>> (56 - i * 8));
        damaged[damaged.length - 1] ^= 0x01;
        try (FileOutputStream output = new FileOutputStream(journal)) {
            output.write(damaged);
            output.getFD().sync();
        }

        memory.remember("integrity", "record after recovery");
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        File[] quarantined = journal.getParentFile().listFiles(
                (dir, name) -> name.startsWith("recuerdos.jsonl.locked."));
        assertNotNull(quarantined);
        assertTrue(quarantined.length > 0);
        assertTrue(journal.exists());
        assertEquals("BME3", new String(readAll(journal), 0, 4, StandardCharsets.US_ASCII));
    }

    @Test public void lostKeystoreKeyQuarantinesCiphertextInsteadOfOverwritingIt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PetMemory memory = new PetMemory(context);
        memory.clear();
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        memory.remember("key_loss", "private entry before key loss");
        assertTrue(PetMemory.awaitPendingWrites(10000L));

        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias("bellota_pet_memory_v2"))
            keyStore.deleteEntry("bellota_pet_memory_v2");

        memory.remember("key_loss", "new entry after key loss");
        assertTrue(PetMemory.awaitPendingWrites(10000L));
        File directory = memory.getJournalFile().getParentFile();
        File[] quarantined = directory.listFiles((dir, name) -> name.startsWith("recuerdos.jsonl.locked."));
        assertNotNull(quarantined);
        assertTrue("The old encrypted journal must remain recoverable if its original key is restored.",
                quarantined.length > 0);
        assertTrue(memory.getJournalFile().exists());
        assertEquals("BME3", new String(readAll(memory.getJournalFile()), 0, 4, StandardCharsets.US_ASCII));
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return data;
        }
    }
}
