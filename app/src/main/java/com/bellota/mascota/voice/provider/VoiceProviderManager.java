package com.bellota.mascota.voice.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coordina proveedores online sin acoplar la conversación a una API concreta.
 * Prueba cada proveedor online como máximo una vez por sesión y luego solicita
 * a la aplicación que active su motor local.
 */
public final class VoiceProviderManager {
    public interface Listener {
        void onStateChanged(VoiceProvider.State state, String providerId, String detail);
        void onUserTranscript(String text, boolean isFinal);
        void onAssistantTranscript(String text, boolean isFinal);
        void onAudioOutput(byte[] pcm16, int sampleRateHz, int channels);
        void onAssistantSpeaking(boolean speaking);
        void onToolCall(String callId, String name, String argumentsJson);
        void onLocalFallbackRequested(VoiceProvider.Failure reason, String detail);
    }

    private final Object lock = new Object();
    private final List<VoiceProvider> providers;
    private final Listener listener;
    private VoiceProvider.SessionConfig sessionConfig;
    private VoiceProvider activeProvider;
    private int nextProviderIndex;
    private long sessionGeneration;
    private boolean sessionStarted;
    private boolean localFallbackRequested;

    public VoiceProviderManager(VoiceProvider primary, VoiceProvider secondary, Listener listener) {
        if (primary == null) throw new IllegalArgumentException("Se requiere un proveedor principal.");
        if (primary == secondary) throw new IllegalArgumentException("Los proveedores deben ser instancias distintas.");
        List<VoiceProvider> configured = new ArrayList<>();
        configured.add(primary);
        if (secondary != null) configured.add(secondary);
        this.providers = Collections.unmodifiableList(configured);
        this.listener = listener;
    }

    /** Inicia una sesión nueva desde el proveedor principal. */
    public void connect(VoiceProvider.SessionConfig config) {
        if (config == null) throw new IllegalArgumentException("La configuración de sesión es obligatoria.");
        synchronized (lock) {
            disconnectActiveLocked();
            sessionGeneration++;
            sessionConfig = config;
            nextProviderIndex = 0;
            sessionStarted = true;
            localFallbackRequested = false;
        }
        connectNextProvider(null, null);
    }

    /** Reintenta desde el proveedor principal, por ejemplo tras recuperar conectividad. */
    public void retry() {
        VoiceProvider.SessionConfig config;
        synchronized (lock) {
            config = sessionConfig;
        }
        if (config != null) connect(config);
    }

    public void disconnect() {
        VoiceProvider old;
        synchronized (lock) {
            sessionGeneration++;
            sessionStarted = false;
            old = activeProvider;
            activeProvider = null;
            localFallbackRequested = false;
        }
        if (old != null) {
            old.setListener(null);
            old.disconnect();
        }
        notifyState(VoiceProvider.State.CLOSED, old == null ? null : old.getProviderId(),
                "Sesión de voz cerrada.");
    }

    public boolean isConnected() {
        synchronized (lock) {
            return activeProvider != null && activeProvider.isConnected();
        }
    }

    public String getActiveProviderId() {
        synchronized (lock) {
            return activeProvider == null ? null : activeProvider.getProviderId();
        }
    }

    public void sendAudio(byte[] pcm16, int offset, int length) {
        VoiceProvider provider = activeSnapshot();
        if (provider != null) provider.sendAudio(pcm16, offset, length);
    }

    public void finishAudioInput() {
        VoiceProvider provider = activeSnapshot();
        if (provider != null) provider.finishAudioInput();
    }

    public void sendText(String text) {
        VoiceProvider provider = activeSnapshot();
        if (provider != null) provider.sendText(text);
    }

    public void sendToolResult(String callId, String result, boolean isError) {
        VoiceProvider provider = activeSnapshot();
        if (provider != null) provider.sendToolResult(callId, result, isError);
    }

    public void interrupt() {
        VoiceProvider provider = activeSnapshot();
        if (provider != null) provider.interrupt();
    }

    private VoiceProvider activeSnapshot() {
        synchronized (lock) {
            return activeProvider;
        }
    }

    private void connectNextProvider(VoiceProvider.Failure previousFailure, String previousDetail) {
        final VoiceProvider candidate;
        final long generation;
        final boolean useLocalFallback;
        synchronized (lock) {
            if (!sessionStarted) return;
            if (nextProviderIndex >= providers.size()) {
                sessionStarted = false;
                localFallbackRequested = true;
                candidate = null;
                generation = sessionGeneration;
                useLocalFallback = true;
            } else {
                candidate = providers.get(nextProviderIndex++);
                generation = sessionGeneration;
                activeProvider = candidate;
                useLocalFallback = false;
            }
        }

        if (useLocalFallback) {
            notifyState(VoiceProvider.State.FALLBACK_LOCAL, null,
                    "Los proveedores online no están disponibles. Se solicita la voz local.");
            if (listener != null) {
                listener.onLocalFallbackRequested(
                        previousFailure == null ? VoiceProvider.Failure.UNKNOWN : previousFailure,
                        previousDetail == null ? "No hay proveedores online disponibles." : previousDetail);
            }
            return;
        }

        candidate.setListener(createProviderListener(candidate, generation));
        notifyState(VoiceProvider.State.CONNECTING, candidate.getProviderId(),
                "Conectando proveedor de voz.");
        try {
            candidate.connect(sessionConfig);
        } catch (RuntimeException error) {
            handleProviderFailure(candidate, generation, VoiceProvider.Failure.PROVIDER,
                    true, error.getClass().getSimpleName() + ": " + safeMessage(error));
        }
    }

    private VoiceProvider.Listener createProviderListener(VoiceProvider provider, long generation) {
        return new VoiceProvider.Listener() {
            @Override public void onStateChanged(VoiceProvider.State state, String detail) {
                if (!isCurrent(provider, generation)) return;
                notifyState(state, provider.getProviderId(), detail);
            }
            @Override public void onUserTranscript(String text, boolean isFinal) {
                if (isCurrent(provider, generation) && listener != null)
                    listener.onUserTranscript(text, isFinal);
            }
            @Override public void onAssistantTranscript(String text, boolean isFinal) {
                if (isCurrent(provider, generation) && listener != null)
                    listener.onAssistantTranscript(text, isFinal);
            }
            @Override public void onAudioOutput(byte[] audio, int sampleRateHz, int channels) {
                if (isCurrent(provider, generation) && listener != null)
                    listener.onAudioOutput(audio, sampleRateHz, channels);
            }
            @Override public void onAssistantSpeaking(boolean speaking) {
                if (isCurrent(provider, generation) && listener != null)
                    listener.onAssistantSpeaking(speaking);
            }
            @Override public void onToolCall(String callId, String name, String argumentsJson) {
                if (isCurrent(provider, generation) && listener != null)
                    listener.onToolCall(callId, name, argumentsJson);
            }
            @Override public void onError(VoiceProvider.Failure failure, boolean recoverable, String detail) {
                handleProviderFailure(provider, generation,
                        failure == null ? VoiceProvider.Failure.UNKNOWN : failure,
                        recoverable, detail);
            }
        };
    }

    private boolean isCurrent(VoiceProvider provider, long generation) {
        synchronized (lock) {
            return sessionStarted && sessionGeneration == generation && activeProvider == provider;
        }
    }

    private void handleProviderFailure(VoiceProvider provider, long generation,
                                       VoiceProvider.Failure failure, boolean recoverable, String detail) {
        synchronized (lock) {
            if (!sessionStarted || sessionGeneration != generation || activeProvider != provider) return;
            activeProvider = null;
        }
        provider.setListener(null);
        provider.disconnect();
        notifyState(VoiceProvider.State.RECONNECTING, provider.getProviderId(),
                "El proveedor no pudo continuar; se evaluará el siguiente motor.");
        // No se reintenta el mismo proveedor en bucle. El siguiente se prueba una sola vez.
        connectNextProvider(failure, detail == null ? "Fallo del proveedor." : detail);
    }

    private void requestLocalFallbackLocked(VoiceProvider.Failure failure, String detail) {
        if (!sessionStarted || localFallbackRequested) return;
        sessionStarted = false;
        localFallbackRequested = true;
        notifyState(VoiceProvider.State.FALLBACK_LOCAL, null,
                "Los proveedores online no están disponibles. Se solicita la voz local.");
        if (listener != null) {
            listener.onLocalFallbackRequested(
                    failure == null ? VoiceProvider.Failure.UNKNOWN : failure,
                    detail == null ? "No hay proveedores online disponibles." : detail);
        }
    }

    private void disconnectActiveLocked() {
        VoiceProvider old = activeProvider;
        activeProvider = null;
        if (old != null) {
            old.setListener(null);
            old.disconnect();
        }
    }

    private void notifyState(VoiceProvider.State state, String providerId, String detail) {
        if (listener != null) listener.onStateChanged(state, providerId, detail);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "sin detalle" : message;
    }
}
