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
        byte[] first = readAll(journal);
        assertTrue(first.length > 20);
        assertEquals("BME2", new String(first, 0, 4, StandardCharsets.US_ASCII));
        String raw = new String(first, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("legacy private text"));
        assertFalse(raw.contains("new private text"));

        memory.remember("test", "another private entry");
        byte[] second = readAll(journal);
        assertTrue(second.length > first.length);
        assertTrue("A normal append must not rewrite the complete journal",
                second.length - first.length < first.length);
        raw = new String(second, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("another private entry"));
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
