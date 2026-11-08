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
    private final TextToSpeech engine;
    private final Listener listener;
    private Runnable onSpeechFinished;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean ready;

    public LocalVoice(Context context, Listener listener) {
        this.listener = listener;
        engine = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false;
            notifyListener(false, "No se pudo iniciar el motor de voz.");
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
                @Override public void onDone(String id) { finishSpeech(); }
                @Override public void onError(String id) { finishSpeech(); }
            });
        }
        notifyListener(ready, ready ? "Voz local lista. Idioma español." : "Falta instalar una voz española en Android.");
    }

    public boolean isReady() { return ready; }
    public void setOnSpeechFinished(Runnable callback) { onSpeechFinished = callback; }

    public void speak(String text) {
        if (!ready || text == null || text.trim().isEmpty()) {
            finishSpeech();
            return;
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bellota-local-voice");
    }

    public void stop() { if (engine != null) engine.stop(); }
    public void release() { ready = false; if (engine != null) engine.shutdown(); }

    private void finishSpeech() {
        mainHandler.post(() -> { if (onSpeechFinished != null) onSpeechFinished.run(); });
    }
    private void notifyListener(boolean available, String detail) {
        if (listener != null) listener.onVoiceReady(available, detail);
    }
}
