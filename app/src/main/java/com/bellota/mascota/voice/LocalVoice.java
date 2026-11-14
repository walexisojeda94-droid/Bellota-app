package com.bellota.mascota.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

/** Voz local de Bellota usando el motor TTS instalado en Android. */
public class LocalVoice implements TextToSpeech.OnInitListener {
    public interface Listener { void onVoiceReady(boolean ready, String detail); }

    private static final long PENDING_SPEECH_TIMEOUT_MS = 6000L;
    private final TextToSpeech engine;
    private final Listener listener;
    private Runnable onSpeechFinished;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean ready;
    private boolean released;
    private String pendingText;
    private String activeUtteranceId;
    private Runnable pendingSpeechTimeout;
    private long utteranceSequence;

    public LocalVoice(Context context, Listener listener) {
        this.listener = listener;
        engine = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override public void onInit(int status) {
        if (released) return;
        if (status != TextToSpeech.SUCCESS) {
            ready = false;
            clearPendingSpeech();
            notifyListener(false, "No se pudo iniciar el motor de voz.");
            finishSpeech();
            return;
        }

        int result = engine.setLanguage(new Locale("es", "AR"));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
            result = engine.setLanguage(new Locale("es"));

        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
        if (ready) {
            engine.setSpeechRate(0.95f);
            engine.setPitch(1.05f);
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { finishSpeech(id); }
                @Override public void onError(String id) { finishSpeech(id); }
            });
        }

        notifyListener(ready, ready
                ? "Voz local lista. Idioma español."
                : "Falta instalar una voz española en Android.");

        if (!ready) {
            clearPendingSpeech();
            finishSpeech();
            return;
        }

        if (pendingText != null) {
            String queued = pendingText;
            clearPendingSpeech();
            speak(queued);
        } else {
            cancelPendingTimeout();
        }
    }

    public boolean isReady() { return ready; }
    public void setOnSpeechFinished(Runnable callback) { onSpeechFinished = callback; }

    public void speak(String text) {
        if (released) return;
        if (text == null || text.trim().isEmpty()) {
            clearPendingSpeech();
            finishSpeech();
            return;
        }
        if (!ready) {
            pendingText = text;
            cancelPendingTimeout();
            pendingSpeechTimeout = () -> {
                pendingSpeechTimeout = null;
                if (!ready && pendingText != null && !released) {
                    pendingText = null;
                    finishSpeech();
                }
            };
            mainHandler.postDelayed(pendingSpeechTimeout, PENDING_SPEECH_TIMEOUT_MS);
            return;
        }

        cancelPendingTimeout();
        pendingText = null;
        String utteranceId = "bellota-local-voice-" + (++utteranceSequence);
        activeUtteranceId = utteranceId;
        int result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) finishSpeech(utteranceId);
    }

    public void stop() {
        clearPendingSpeech();
        activeUtteranceId = null;
        if (engine != null) engine.stop();
    }

    public void release() {
        released = true;
        ready = false;
        stop();
        if (engine != null) engine.shutdown();
    }

    private void clearPendingSpeech() {
        pendingText = null;
        cancelPendingTimeout();
    }

    private void cancelPendingTimeout() {
        if (pendingSpeechTimeout != null) {
            mainHandler.removeCallbacks(pendingSpeechTimeout);
            pendingSpeechTimeout = null;
        }
    }

    private void finishSpeech() {
        finishSpeech(activeUtteranceId);
    }

    private void finishSpeech(String utteranceId) {
        mainHandler.post(() -> {
            // Ignorar onDone/onError tardíos de una frase reemplazada o cancelada.
            if (utteranceId != null && !utteranceId.equals(activeUtteranceId)) return;
            activeUtteranceId = null;
            if (onSpeechFinished != null) onSpeechFinished.run();
        });
    }

    private void notifyListener(boolean available, String detail) {
        if (listener != null) listener.onVoiceReady(available, detail);
    }
}
