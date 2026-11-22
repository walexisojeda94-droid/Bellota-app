package com.bellota.mascota.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.bellota.mascota.core.DebugLog;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Reconocimiento continuo de comandos en español.
 *
 * El idioma es una decisión de la aplicación y nunca se hereda del idioma
 * configurado en Android. Si no hay un modelo español instalado, se informa
 * y se reintenta más tarde sin cambiar silenciosamente a otro idioma.
 */
public class ContinuousVoiceCommands {
    private static final String TAG = "BellotaSpeech";
    private static final int ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT = 12;
    private static final long LANGUAGE_RETRY_DELAY_MS = 60000L;
    private static final String[] SPANISH_LANGUAGES = {"es-AR", "es-ES", "es-US"};

    public interface Listener {
        void onRecognized(String text);
        void onStatus(String status);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> languageCandidates =
            new ArrayList<>(Arrays.asList(SPANISH_LANGUAGES));

    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean restarting;
    private int languageCandidateIndex;
    private int clientErrorCount;
    private int throttleCount;
    private int noMatchCount;
    private int networkErrorCount;
    private long generation;

    public ContinuousVoiceCommands(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status("No hay un servicio de reconocimiento de voz disponible.");
            return;
        }

        running = true;
        languageCandidateIndex = 0;
        clientErrorCount = 0;
        throttleCount = 0;
        noMatchCount = 0;
        networkErrorCount = 0;
        final long session = ++generation;

        try {
            createRecognizer(session);
            status("Reconocimiento español iniciado; preparando el micrófono.");
            listenAgain(session, 250L);
        } catch (Exception error) {
            running = false;
            generation++;
            releaseRecognizer();
            status(android.os.Build.VERSION.SDK_INT >= 31
                    ? "No hay un reconocedor de voz local disponible en este dispositivo."
                    : "No se pudo crear el reconocedor: " + error.getClass().getSimpleName() + ".");
        }
    }

    public void stop() {
        if (!running && !restarting && recognizer == null) return;
        running = false;
        restarting = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        releaseRecognizer();
        status("Reconocimiento local detenido.");
    }

    private void createRecognizer(long session) {
        SpeechRecognizer created;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                throw new IllegalStateException("No hay motor de voz en el dispositivo.");
            created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        } else {
            created = SpeechRecognizer.createSpeechRecognizer(context);
        }
        recognizer = created;
        created.setRecognitionListener(new SessionListener(session, created));
    }

    private void releaseRecognizer() {
        SpeechRecognizer current = recognizer;
        recognizer = null;
        if (current != null) {
            try { current.cancel(); } catch (Exception ignored) { }
            try { current.destroy(); } catch (Exception ignored) { }
        }
    }

    private boolean isCurrent(long session, SpeechRecognizer source) {
        return running && generation == session && recognizer == source;
    }

    private void listenAgain(final long session, long delay) {
        if (!running || generation != session || recognizer == null || restarting) return;
        restarting = true;
        handler.postDelayed(() -> {
            if (!running || generation != session || recognizer == null) return;
            restarting = false;
            SpeechRecognizer current = recognizer;
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

                DebugLog.i(TAG, "startListening: locale=" + languageTag()
                        + "; offlinePreferred=true; generation=" + session + ".");
                current.startListening(intent);
                status("Escucha solicitada en español (" + languageTag() + ", sin conexión preferido).");
            } catch (Exception error) {
                if (!running || generation != session || recognizer != current) return;
                status("No se pudo iniciar la escucha: " + error.getClass().getSimpleName() + ".");
                listenAgain(session, 3000L);
            }
        }, delay);
    }

    private String languageTag() {
        return languageCandidates.get(languageCandidateIndex);
    }

    private void recoverClientError(long session) {
        if (!running || generation != session || restarting) return;
        restarting = true;
        long delay = Math.min(30000L, 3000L * (1L << Math.min(clientErrorCount++, 3)));
        status("El servicio de reconocimiento se reiniciará.");
        handler.postDelayed(() -> {
            if (!running || generation != session) return;
            releaseRecognizer();
            try {
                createRecognizer(session);
                restarting = false;
                listenAgain(session, 500L);
            } catch (Exception error) {
                restarting = false;
                status("Android no pudo reiniciar el reconocimiento; se volverá a intentar.");
                recoverClientError(session);
            }
        }, delay);
    }

    private void moveToNextLanguageMode(long session) {
        if (languageCandidateIndex + 1 < languageCandidates.size()) {
            languageCandidateIndex++;
            status("La variante española anterior no está disponible; probando " + languageTag() + " sin conexión.");
            listenAgain(session, 1200L);
        } else {
            languageCandidateIndex = 0;
            status("No hay un modelo español local compatible. Se reintentará en un minuto; no se cambiará al idioma del teléfono ni al reconocimiento en línea.");
            listenAgain(session, LANGUAGE_RETRY_DELAY_MS);
        }
    }

    private final class SessionListener implements RecognitionListener {
        private final long session;
        private final SpeechRecognizer source;

        SessionListener(long session, SpeechRecognizer source) {
            this.session = session;
            this.source = source;
        }

        @Override public void onReadyForSpeech(Bundle bundle) {
            if (isCurrent(session, source)) status("Android está listo para escuchar en español.");
        }

        @Override public void onBeginningOfSpeech() {
            if (isCurrent(session, source)) DebugLog.i(TAG, "onBeginningOfSpeech.");
        }

        @Override public void onEndOfSpeech() {
            if (isCurrent(session, source)) DebugLog.i(TAG, "onEndOfSpeech.");
        }

        @Override public void onResults(Bundle results) {
            if (!isCurrent(session, source)) return;
            clientErrorCount = 0;
            throttleCount = 0;
            noMatchCount = 0;
            networkErrorCount = 0;
            ArrayList<String> matches = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()
                    && matches.get(0) != null && !matches.get(0).trim().isEmpty()) {
                String recognized = matches.get(0).trim();
                DebugLog.i(TAG, "Resultado de voz recibido; caracteres=" + recognized.length() + ".");
                if (listener != null && isCurrent(session, source)) listener.onRecognized(recognized);
            }
            listenAgain(session, 500L);
        }

        @Override public void onError(int error) {
            if (!isCurrent(session, source)) return;
            DebugLog.w(TAG, "onError=" + error + " (" + errorName(error)
                    + "); locale=" + languageTag() + "; offlinePreferred=true.");
            status("Error de reconocimiento: " + errorName(error) + " (código " + error + ").");

            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                running = false;
                restarting = false;
                generation++;
                releaseRecognizer();
                status("Reconocimiento detenido: Android no concedió permiso de micrófono.");
                return;
            }

            if (error == ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT
                    || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                moveToNextLanguageMode(session);
                return;
            }

            if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                long delay = Math.min(60000L, 5000L * (1L << Math.min(networkErrorCount++, 3)));
                status("El servicio de voz no tiene conexión estable; se ampliará la espera antes de reintentar.");
                listenAgain(session, delay);
                return;
            }

            if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                long delay = Math.min(60000L, 10000L * (1L << Math.min(throttleCount++, 3)));
                status("Android limitó temporalmente el reconocimiento; se aplicará una pausa.");
                listenAgain(session, delay);
                return;
            }

            if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    || error == SpeechRecognizer.ERROR_AUDIO
                    || error == SpeechRecognizer.ERROR_SERVER) {
                recoverClientError(session);
                return;
            }

            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                long delay = Math.min(10000L, 1500L * (1L << Math.min(noMatchCount++, 3)));
                listenAgain(session, delay);
                return;
            }

            listenAgain(session, error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 3000L : 2500L);
        }

        @Override public void onRmsChanged(float value) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onPartialResults(Bundle bundle) { }
        @Override public void onEvent(int eventType, Bundle bundle) { }
    }

    private String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "tiempo de espera de red";
            case SpeechRecognizer.ERROR_NETWORK: return "error de red";
            case SpeechRecognizer.ERROR_AUDIO: return "error de audio";
            case SpeechRecognizer.ERROR_SERVER: return "error del servidor de reconocimiento";
            case SpeechRecognizer.ERROR_CLIENT: return "error del cliente de reconocimiento";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no se detectó voz";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no se pudo reconocer lo dicho";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "reconocedor ocupado";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "permiso de micrófono insuficiente";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "demasiadas solicitudes al servicio";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "servicio de reconocimiento desconectado";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "idioma no disponible";
            case ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT: return "idioma no compatible";
            default: return "error no clasificado";
        }
    }

    private void status(String value) {
        if (listener != null) listener.onStatus(value);
    }
}
