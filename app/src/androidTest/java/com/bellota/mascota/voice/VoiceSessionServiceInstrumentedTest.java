package com.bellota.mascota.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.InputStream;

import com.bellota.mascota.voice.auth.SecureTokenStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class VoiceSessionServiceInstrumentedTest {
    private interface Condition { boolean evaluate(); }

    private boolean waitFor(Condition condition, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.evaluate()) return true;
            SystemClock.sleep(100L);
        }
        return condition.evaluate();
    }

    @Test public void foregroundOwnerRestoresAfterUnexpectedServiceTeardown() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ParcelFileDescriptor permissionCommand = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand("pm grant " + context.getPackageName()
                        + " " + Manifest.permission.RECORD_AUDIO);
        try (InputStream commandOutput = new ParcelFileDescriptor.AutoCloseInputStream(permissionCommand)) {
            while (commandOutput.read() != -1) { }
        }

        SecureTokenStore tokenStore = new SecureTokenStore(context);
        VoiceSessionConfigStore configStore = new VoiceSessionConfigStore(context);
        try {
            VoiceRecognitionService.stop(context);
            tokenStore.saveRefreshToken("instrumented-session-token-01234567890123456789");

            VoiceSessionService.start(context, "https://127.0.0.1:1",
                    "Sos Bellota. Prueba de ciclo de vida.", "[]", "marin", "es-AR");
            assertTrue("El servicio foreground no llegó a iniciarse.",
                    waitFor(VoiceSessionService::isServiceRunning, 10000L));
            assertTrue("La sesión no quedó marcada como activa.", VoiceSessionService.isActive(context));

            context.stopService(new Intent(context, VoiceSessionService.class));
            assertTrue("El servicio no terminó después de stopService.",
                    waitFor(() -> !VoiceSessionService.isServiceRunning(), 10000L));
            assertTrue("La configuración cifrada no quedó disponible para recuperación.",
                    VoiceSessionService.isActive(context));

            VoiceSessionService.restoreIfActive(context);
            assertTrue("El servicio no pudo reconstruirse desde la configuración cifrada.",
                    waitFor(VoiceSessionService::isServiceRunning, 10000L));

            VoiceSessionService.stop(context);
            assertTrue("El cierre explícito no limpió el estado activo.",
                    waitFor(() -> !VoiceSessionService.isActive(context), 10000L));
        } finally {
            VoiceSessionService.stop(context);
            waitFor(() -> !VoiceSessionService.isActive(context), 5000L);
            VoiceRecognitionService.stop(context);
            tokenStore.clearRefreshToken();
            configStore.clear();
        }
    }
}
