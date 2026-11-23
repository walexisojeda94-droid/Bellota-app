package com.bellota.mascota.security;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bellota.mascota.core.PetMemory;
import com.bellota.mascota.local.EncryptedPreferencesStore;
import com.bellota.mascota.voice.auth.SecureTokenStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SecureStorageInstrumentedTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void refreshTokenIsEncryptedAndCanBeRecovered() throws Exception {
        SecureTokenStore store = new SecureTokenStore(context());
        assertTrue(store.clear());
        String token = "bellota-test-" + UUID.randomUUID().toString().replace("-", "") + "-refresh-token";
        store.saveRefreshToken(token);
        assertEquals(token, store.readRefreshToken());
        String encrypted = context().getSharedPreferences("bellota_secure_auth", Context.MODE_PRIVATE)
                .getString("refresh_token", null);
        assertTrue(encrypted != null && !encrypted.contains(token));
        assertTrue(store.clear());
        assertFalse(store.hasRefreshToken());
    }

    @Test public void diaryAndConversationPreferencesAreEncrypted() throws Exception {
        String marker = "BELL0TA_PRIVATE_SENTINEL_" + UUID.randomUUID();
        PetMemory memory = new PetMemory(context());
        memory.remember("security_test", marker);

        byte[] journal;
        try (FileInputStream input = new FileInputStream(memory.getJournalFile());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            journal = output.toByteArray();
        }
        assertTrue(journal.length > 16);
        assertArrayEquals(new byte[] { 'B', 'M', 'E', '2' },
                new byte[] { journal[0], journal[1], journal[2], journal[3] });
        assertFalse(new String(journal, StandardCharsets.UTF_8).contains(marker));

        SharedPreferences rawPreferences = context().getSharedPreferences(
                "bellota_conversation", Context.MODE_PRIVATE);
        String key = "instrumentation_" + UUID.randomUUID().toString();
        assertTrue(rawPreferences.edit().putString(key, marker).commit());

        EncryptedPreferencesStore encrypted = new EncryptedPreferencesStore(context());
        assertEquals(marker, encrypted.get(key));
        String stored = rawPreferences.getString(key, null);
        assertNotNull(stored);
        assertTrue(stored.startsWith("v1:"));
        assertFalse(stored.contains(marker));
        rawPreferences.edit().remove(key).apply();
    }
}
