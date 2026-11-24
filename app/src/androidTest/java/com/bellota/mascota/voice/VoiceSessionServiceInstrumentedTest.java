package com.bellota.mascota.voice;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;


import com.bellota.mascota.voice.auth.SecureTokenStore;

import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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

    @Test(timeout = 30000L)
    public void test00LocalVoiceServiceCanRestartImmediatelyAfterStop() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            VoiceRecognitionService.pause(context);
            assertTrue("El servicio local no llegó a foreground antes de detenerlo.",
                    waitFor(VoiceRecognitionService::isForegroundService, 10000L));

            // Reissue a foreground start immediately after stopService(). Android may
            // deliver it to the same Service instance before onDestroy has completed.
            VoiceRecognitionService.stop(context);
            VoiceRecognitionService.pause(context);
            assertTrue("El servicio local reutilizado no volvió a foreground.",
                    waitFor(VoiceRecognitionService::isForegroundService, 10000L));
        } finally {
            VoiceRecognitionService.stop(context);
            waitFor(() -> !VoiceRecognitionService.isForegroundService(), 5000L);
        }
    }

    @Test(timeout = 90000L) public void test01ForegroundOwnerRestoresAfterUnexpectedServiceTeardown() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
    @Test(timeout = 90000L)
    public void test99CanRecoverPersistedSessionAfterServiceTeardown() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecureTokenStore tokenStore = new SecureTokenStore(context);
        VoiceSessionConfigStore configStore = new VoiceSessionConfigStore(context);
        try {
            VoiceRecognitionService.stop(context);
            tokenStore.saveRefreshToken("instrumented-recovery-token-01234567890123456789");
            VoiceSessionService.start(context, "https://127.0.0.1:1",
                    "Sos Bellota. Prueba de recuperación del proceso.", "[]", "marin", "es-AR");
            assertTrue("El servicio foreground no llegó a iniciarse para la prueba de recuperación.",
                    waitFor(VoiceSessionService::isServiceRunning, 10000L));
            assertTrue("El servicio online no se promovió a foreground durante su creación.",
                    waitFor(VoiceSessionService::isForegroundService, 5000L));
            assertTrue("La sesión no quedó persistida para recuperación tras reiniciar el servicio.",
                    VoiceSessionService.isActive(context));

            // Simulate OS teardown without the user's STOP action.
            context.stopService(new Intent(context, VoiceSessionService.class));
            assertTrue("El servicio no terminó durante la simulación de cierre del proceso.",
                    waitFor(() -> !VoiceSessionService.isServiceRunning(), 10000L));
            assertTrue("El estado recuperable se perdió durante el cierre inesperado.",
                    VoiceSessionService.isActive(context));

            VoiceSessionService.restoreIfActive(context);
            assertTrue("No se pudo recuperar la sesión persistida después del cierre.",
                    waitFor(VoiceSessionService::isServiceRunning, 10000L));
            assertTrue("La sesión recuperada no volvió a foreground durante la creación.",
                    waitFor(VoiceSessionService::isForegroundService, 5000L));
        } finally {
            VoiceSessionService.stop(context);
            waitFor(() -> !VoiceSessionService.isActive(context), 5000L);
            VoiceRecognitionService.stop(context);
            tokenStore.clearRefreshToken();
            configStore.clear();
        }
    }

}