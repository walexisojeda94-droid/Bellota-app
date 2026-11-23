package com.bellota.mascota.voice;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class VoiceSessionConfigStoreInstrumentedTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void resumableSessionConfigIsEncryptedAndCanBeRestored() throws Exception {
        VoiceSessionConfigStore store = new VoiceSessionConfigStore(context());
        store.clear();

        String marker = "BELL0TA_PRIVATE_SESSION_CONTEXT_" + UUID.randomUUID();
        VoiceSessionConfigStore.Config expected = new VoiceSessionConfigStore.Config(
                "https://127.0.0.1:1",
                "Instrucciones privadas: " + marker,
                "[{\"name\":\"test_tool\"}]",
                "marin",
                "es-AR");

        store.save(expected);

        SharedPreferences preferences = context().getSharedPreferences(
                "bellota_voice_session_secure", Context.MODE_PRIVATE);
        String encrypted = preferences.getString("encrypted_config", null);
        assertNotNull(encrypted);
        assertFalse(encrypted.contains(marker));

        VoiceSessionConfigStore.Config restored = store.load();
        assertNotNull(restored);
        assertEquals(expected.backendUrl, restored.backendUrl);
        assertEquals(expected.instructions, restored.instructions);
        assertEquals(expected.toolsJson, restored.toolsJson);
        assertEquals(expected.voice, restored.voice);
        assertEquals(expected.locale, restored.locale);

        store.clear();
        assertNull(store.load());
    }
}
