package com.bellota.mascota.voice;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;

import com.bellota.mascota.core.DebugLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private static final String[] SPANISH_LANGUAGES = {"es-US", "es-AR", "es-ES", "es"};

    public interface Listener {
        void onRecognized(String text);
        void onStatus(String status);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> languageCandidates =
            new ArrayList<>(Arrays.asList(SPANISH_LANGUAGES));
    // Android puede tener el paquete de voz de Google y su modelo descargado,
    // aunque el motor dedicado on-device no exponga ese mismo modelo.
    private boolean usingDefaultProvider;

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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            // Si el motor dedicado no existe, probar el proveedor configurado en Android.
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                status("No hay un servicio de reconocimiento de voz disponible en Android.");
                return;
            }
            usingDefaultProvider = true;
        } else if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status("No hay un servicio de reconocimiento de voz disponible en Android.");
            return;
        } else {
            usingDefaultProvider = false;
        }

        running = true;
        languageCandidateIndex = 0;
        clientErrorCount = 0;
        throttleCount = 0;
        noMatchCount = 0;
        networkErrorCount = 0;
        final long session = ++generation;

        try {
            createRecognizer(session, usingDefaultProvider);
            status(usingDefaultProvider
                    ? "Usando el servicio de reconocimiento configurado en Android; se prioriza el modelo descargado y se solicita funcionamiento sin conexión. Idioma inicial: " + languageTag() + "."
                    : VoiceRecognitionPolicy.descriptionForApi(android.os.Build.VERSION.SDK_INT)
                            + " Idioma inicial: " + languageTag() + ".");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                discoverRecognitionSupport(session);
            } else if (android.os.Build.VERSION.SDK_INT < 31) {
                queryLegacySupportedLanguages(session);
            } else {
                listenAgain(session, 250L);
            }
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

    private void createRecognizer(long session, boolean useDefaultProvider) {
        SpeechRecognizer created;
        if (!useDefaultProvider && android.os.Build.VERSION.SDK_INT >= 31) {
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

    /**
     * Android 13+ exposes the locales installed for the selected recognition service.
     * Select an installed Spanish model instead of guessing locale tags.
     */
    private void discoverRecognitionSupport(final long session) {
        if (android.os.Build.VERSION.SDK_INT < 33 || !running
                || generation != session || recognizer == null) return;
        Intent supportIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        supportIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        supportIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try {
            recognizer.checkRecognitionSupport(supportIntent, context.getMainExecutor(),
                    new RecognitionSupportCallback() {
                        @Override public void onSupportResult(RecognitionSupport support) {
                            if (!running || generation != session || recognizer == null) return;
                            List<String> installed = support == null ? null
                                    : support.getInstalledOnDeviceLanguages();
                            List<String> selected = VoiceRecognitionPolicy.orderedSpanishLanguages(installed);
                            if (!selected.isEmpty()) {
                                languageCandidates.clear();
                                languageCandidates.addAll(selected);
                                languageCandidateIndex = 0;
                                status("Modelos españoles locales detectados en el motor de Android: "
                                        + selected.size() + ". Primera variante: " + languageTag() + ".");
                                listenAgain(session, 250L);
                                return;
                            }

                            if (!usingDefaultProvider && SpeechRecognizer.isRecognitionAvailable(context)) {
                                switchToDefaultProvider(session);
                                return;
                            }

                            List<String> downloadable = support == null ? null
                                    : support.getSupportedOnDeviceLanguages();
                            String available = VoiceRecognitionPolicy.selectSpanishLanguage(downloadable);
                            if (available != null) {
                                Intent downloadIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                                downloadIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                                downloadIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, available);
                                downloadIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                                try {
                                    recognizer.triggerModelDownload(downloadIntent);
                                    status("Android admite español (" + available + "), pero el modelo local no figura instalado. Se solicitó su descarga; Bellota volverá a comprobarlo.");
                                    handler.postDelayed(() -> discoverRecognitionSupport(session),
                                            LANGUAGE_RETRY_DELAY_MS);
                                    return;
                                } catch (RuntimeException downloadError) {
                                    DebugLog.w(TAG, "No se pudo solicitar descarga del modelo español: "
                                            + downloadError.getClass().getSimpleName());
                                }
                            }
                            status("El servicio de reconocimiento instalado no informa un modelo español local disponible. Revisá los idiomas sin conexión del servicio de voz de Android.");
                            handler.postDelayed(() -> discoverRecognitionSupport(session),
                                    LANGUAGE_RETRY_DELAY_MS);
                        }

                        @Override public void onError(int error) {
                            if (!running || generation != session) return;
                            DebugLog.w(TAG, "checkRecognitionSupport falló: " + error
                                    + "; se probará el proveedor alternativo si está disponible.");
                            if (!usingDefaultProvider && SpeechRecognizer.isRecognitionAvailable(context)) {
                                switchToDefaultProvider(session);
                            } else {
                                listenAgain(session, LANGUAGE_RETRY_DELAY_MS);
                            }
                        }
                    });
        } catch (RuntimeException error) {
            DebugLog.w(TAG, "No se pudo consultar compatibilidad de idiomas: "
                    + error.getClass().getSimpleName());
            if (!usingDefaultProvider && SpeechRecognizer.isRecognitionAvailable(context))
                switchToDefaultProvider(session);
            else
                listenAgain(session, LANGUAGE_RETRY_DELAY_MS);
        }
    }

    private void switchToDefaultProvider(final long session) {
        if (!running || generation != session || usingDefaultProvider || restarting) return;
        restarting = true;
        status("El motor local dedicado no anuncia español. Consultando los idiomas instalados en el servicio de voz de Android.");
        handler.postDelayed(() -> {
            if (!running || generation != session) return;
            releaseRecognizer();
            try {
                usingDefaultProvider = true;
                createRecognizer(session, true);
                restarting = false;
                discoverRecognitionSupport(session);
            } catch (RuntimeException error) {
                restarting = false;
                status("No se pudo iniciar el servicio de voz configurado en Android.");
                handler.postDelayed(() -> discoverRecognitionSupport(session),
                        LANGUAGE_RETRY_DELAY_MS);
            }
        }, 500L);
    }

    /**
     * Android 7-11 has no on-device support query. Ask the installed provider
     * which locales it recognizes and choose a Spanish locale from that list.
     */
    private void queryLegacySupportedLanguages(final long session) {
        Intent detailsIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
        try {
            context.sendOrderedBroadcast(detailsIntent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (!running || generation != session) return;
                    Bundle extras = getResultExtras(true);
                    ArrayList<String> supported = extras == null ? null
                            : extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES);
                    List<String> selected = VoiceRecognitionPolicy.orderedSpanishLanguages(supported);
                    if (!selected.isEmpty()) {
                        languageCandidates.clear();
                        languageCandidates.addAll(selected);
                        languageCandidateIndex = 0;
                        status("El servicio de Android anuncia " + selected.size()
                                + " variantes españolas. Primera opción: " + languageTag()
                                + ". Se solicitará reconocimiento sin conexión cuando el proveedor lo permita.");
                    } else {
                        status("Android no informó sus idiomas compatibles. Se probarán las variantes españolas habituales sin usar el idioma del teléfono.");
                    }
                    listenAgain(session, 250L);
                }
            }, null, Activity.RESULT_OK, null, null);
        } catch (RuntimeException error) {
            status("No se pudieron consultar los idiomas del servicio Android. Se probarán las variantes españolas habituales.");
            listenAgain(session, 250L);
        }
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
                createRecognizer(session, usingDefaultProvider);
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
            status("La variante española anterior no está disponible; probando " + languageTag() + ".");
            listenAgain(session, 1200L);
            return;
        }

        languageCandidateIndex = 0;
        if (!usingDefaultProvider && SpeechRecognizer.isRecognitionAvailable(context)) {
            // Segundo intento: proveedor elegido por Android (por ejemplo, Servicios de voz de Google).
            // EXTRA_PREFER_OFFLINE sigue activo, pero Android puede requerir red según el proveedor.
            restarting = true;
            status("El motor local dedicado no acepta las variantes españolas. Probando el servicio de voz configurado en Android con el idioma descargado " + languageTag() + ".");
            handler.postDelayed(() -> {
                if (!running || generation != session) return;
                releaseRecognizer();
                try {
                    usingDefaultProvider = true;
                    createRecognizer(session, true);
                    restarting = false;
                    if (android.os.Build.VERSION.SDK_INT >= 33) discoverRecognitionSupport(session);
                    else listenAgain(session, 500L);
                } catch (Exception error) {
                    restarting = false;
                    status("No se pudo iniciar el servicio de voz configurado en Android.");
                    listenAgain(session, LANGUAGE_RETRY_DELAY_MS);
                }
            }, 1200L);
            return;
        }

        status("Ningún motor aceptó los modelos españoles disponibles. Se reintentará en un minuto sin desconectar la voz online.");
        listenAgain(session, LANGUAGE_RETRY_DELAY_MS);
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
                status(android.os.Build.VERSION.SDK_INT >= 31
                    ? "El reconocedor on-device informó un error de red. Se reintentará sin cambiar al idioma del teléfono."
                    : "El proveedor de voz de Android requiere conexión. En Android 7-11 la preferencia sin conexión no garantiza un modelo offline; se reintentará y se informará si no está disponible.");
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
