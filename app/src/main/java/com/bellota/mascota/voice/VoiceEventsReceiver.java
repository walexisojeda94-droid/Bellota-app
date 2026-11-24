package com.bellota.mascota.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Adapta los broadcasts de voz a eventos tipados para que MainActivity no
 * tenga que implementar el enrutamiento de mensajes del sistema.
 */
public final class VoiceEventsReceiver extends BroadcastReceiver {
    public interface Listener {
        void onRecognized(String text);
        void onStatus(String status);
        void onStopped();
        void onSessionEvent(Intent intent);
    }

    private final Listener listener;

    public VoiceEventsReceiver(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        this.listener = listener;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String value = intent.getStringExtra(VoiceRecognitionService.EXTRA_TEXT);

        if (VoiceRecognitionService.ACTION_RECOGNIZED.equals(action)) {
            if (value != null && !value.trim().isEmpty()) listener.onRecognized(value);
        } else if (VoiceRecognitionService.ACTION_STATUS.equals(action)) {
            listener.onStatus(value);
        } else if (VoiceRecognitionService.ACTION_STOPPED.equals(action)) {
            listener.onStopped();
        } else if (VoiceSessionService.ACTION_EVENT.equals(action)) {
            listener.onSessionEvent(intent);
        }
    }
}
