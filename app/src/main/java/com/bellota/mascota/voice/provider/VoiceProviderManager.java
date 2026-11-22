package com.bellota.mascota.voice.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Coordina proveedores online y solicita el respaldo local cuando todos fallan.
 * Las operaciones de ciclo de vida se serializan para evitar conexiones y cierres
 * concurrentes sobre una misma instancia de proveedor.
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
    private final Object lifecycleLock = new Object();
    private final List<VoiceProvider> providers;
    private final Listener listener;
    private VoiceProvider.SessionConfig sessionConfig;
    private VoiceProvider activeProvider;
    private int nextProviderIndex;
    private long sessionGeneration;
    private boolean sessionStarted;
    private final Map<String, ToolCallRoute> pendingToolCalls = new LinkedHashMap<>();
    private static final int MAX_PENDING_TOOL_CALLS = 64;
    private static final int MAX_RETIRED_TOOL_CALL_IDS = 256;
    private final Set<String> retiredToolCallIds = new LinkedHashSet<>();

    private static final class ToolCallRoute {
        final VoiceProvider provider;
        final long generation;
        ToolCallRoute(VoiceProvider provider, long generation) {
            this.provider = provider;
            this.generation = generation;
        }
    }

    public VoiceProviderManager(VoiceProvider primary, VoiceProvider secondary, Listener listener) {
        if (primary == null) throw new IllegalArgumentException("Se requiere un proveedor principal.");
        if (primary == secondary) throw new IllegalArgumentException("Los proveedores deben ser instancias distintas.");
        List<VoiceProvider> configured = new ArrayList<>();
        configured.add(primary);
        if (secondary != null) configured.add(secondary);
        providers = Collections.unmodifiableList(configured);
        this.listener = listener;
    }

    public void connect(VoiceProvider.SessionConfig config) {
        if (config == null) throw new IllegalArgumentException("La configuración de sesión es obligatoria.");
        synchronized (lifecycleLock) {
            VoiceProvider previous;
            synchronized (lock) {
                previous = activeProvider;
                activeProvider = null;
                sessionGeneration++;
                sessionConfig = config;
                nextProviderIndex = 0;
                sessionStarted = true;
                retirePendingToolCallsLocked();
            }
            dispose(previous);
            connectNextProvider(null, null, currentGeneration());
        }
    }

    /** Reintenta desde el proveedor principal, por ejemplo tras recuperar conectividad. */
    public void retry() {
        VoiceProvider.SessionConfig config;
        synchronized (lock) { config = sessionConfig; }
        if (config != null) connect(config);
    }

    public void disconnect() {
        synchronized (lifecycleLock) {
            VoiceProvider old;
            synchronized (lock) {
                sessionGeneration++;
                sessionStarted = false;
                old = activeProvider;
                activeProvider = null;
                pendingToolCalls.clear();
            }
            dispose(old);
            safeNotifyState(VoiceProvider.State.CLOSED,
                    old == null ? null : safeProviderId(old), "Sesión de voz cerrada.");
        }
    }

    public boolean isConnected() {
        VoiceProvider current;
        synchronized (lock) { current = activeProvider; }
        try { return current != null && current.isConnected(); }
        catch (RuntimeException ignored) { return false; }
    }

    public String getActiveProviderId() {
        VoiceProvider current;
        synchronized (lock) { current = activeProvider; }
        return current == null ? null : safeProviderId(current);
    }

    public void sendAudio(byte[] pcm16, int offset, int length) {
        if (pcm16 == null) throw new IllegalArgumentException("El audio no puede ser null.");
        if (offset < 0 || length < 0 || offset > pcm16.length - length)
            throw new IllegalArgumentException("El rango de audio está fuera del buffer.");
        if ((length & 1) != 0) throw new IllegalArgumentException("PCM16 requiere una cantidad par de bytes.");
        VoiceProvider current = activeSnapshot();
        if (current != null) safely(() -> current.sendAudio(pcm16, offset, length));
    }

    public void finishAudioInput() {
        VoiceProvider current = activeSnapshot();
        if (current != null) safely(current::finishAudioInput);
    }

    public void sendText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        VoiceProvider current = activeSnapshot();
        if (current != null) safely(() -> current.sendText(text));
    }

    /** True only while the originating provider session still owns this tool call. */
    public boolean isToolCallCurrent(String callId) {
        if (callId == null || callId.trim().isEmpty()) return false;
        synchronized (lock) {
            ToolCallRoute route = pendingToolCalls.get(callId);
            return route != null && sessionStarted && route.generation == sessionGeneration
                    && route.provider == activeProvider;
        }
    }

    public void sendToolResult(String callId, String result, boolean isError) {
        if (callId == null || callId.trim().isEmpty()) return;
        final ToolCallRoute route;
        synchronized (lock) {
            route = pendingToolCalls.remove(callId);
            if (route != null) retireToolCallIdLocked(callId);
            if (route == null || !sessionStarted || route.generation != sessionGeneration
                    || route.provider != activeProvider) return;
        }
        safely(() -> route.provider.sendToolResult(callId, result, isError));
    }

    public void interrupt() {
        VoiceProvider current = activeSnapshot();
        if (current != null) safely(current::interrupt);
    }

    private long currentGeneration() { synchronized (lock) { return sessionGeneration; } }

    private VoiceProvider activeSnapshot() {
        synchronized (lock) { return activeProvider; }
    }

    private void connectNextProvider(VoiceProvider.Failure previousFailure, String previousDetail, long expectedGeneration) {
        final VoiceProvider candidate;
        final VoiceProvider.SessionConfig config;
        final long generation;
        synchronized (lock) {
            if (!sessionStarted || sessionGeneration != expectedGeneration) return;
            if (nextProviderIndex >= providers.size()) {
                sessionStarted = false;
                candidate = null;
                config = null;
                generation = sessionGeneration;
            } else {
                candidate = providers.get(nextProviderIndex++);
                config = sessionConfig;
                generation = sessionGeneration;
                activeProvider = candidate;
            }
        }

        if (candidate == null) {
            safeNotifyState(VoiceProvider.State.FALLBACK_LOCAL, null,
                    "Los proveedores online no están disponibles. Se solicita la voz local.");
            synchronized (lock) {
                if (sessionGeneration != generation || sessionStarted) return;
            }
            safely(() -> {
                if (listener != null) listener.onLocalFallbackRequested(
                        previousFailure == null ? VoiceProvider.Failure.UNKNOWN : previousFailure,
                        previousDetail == null ? "No hay proveedores online disponibles." : previousDetail);
            });
            return;
        }

        try {
            candidate.setListener(createProviderListener(candidate, generation));
            safeNotifyState(VoiceProvider.State.CONNECTING, safeProviderId(candidate),
                    "Conectando proveedor de voz.");
            if (!isCurrent(candidate, generation)) return;
            candidate.connect(config);
        } catch (RuntimeException error) {
            handleProviderFailure(candidate, generation, VoiceProvider.Failure.PROVIDER,
                    error.getClass().getSimpleName() + ": " + safeMessage(error));
        }
    }

    private VoiceProvider.Listener createProviderListener(VoiceProvider provider, long generation) {
        return new VoiceProvider.Listener() {
            @Override public void onStateChanged(VoiceProvider.State state, String detail) {
                if (isCurrent(provider, generation))
                    safeNotifyState(state, safeProviderId(provider), detail);
            }
            @Override public void onUserTranscript(String text, boolean isFinal) {
                if (isCurrent(provider, generation))
                    safely(() -> { if (listener != null) listener.onUserTranscript(text, isFinal); });
            }
            @Override public void onAssistantTranscript(String text, boolean isFinal) {
                if (isCurrent(provider, generation))
                    safely(() -> { if (listener != null) listener.onAssistantTranscript(text, isFinal); });
            }
            @Override public void onAudioOutput(byte[] audio, int sampleRateHz, int channels) {
                if (isCurrent(provider, generation) && audio != null
                        && sampleRateHz > 0 && channels > 0)
                    safely(() -> { if (listener != null) listener.onAudioOutput(audio, sampleRateHz, channels); });
            }
            @Override public void onAssistantSpeaking(boolean speaking) {
                if (isCurrent(provider, generation))
                    safely(() -> { if (listener != null) listener.onAssistantSpeaking(speaking); });
            }
            @Override public void onToolCall(String callId, String name, String argumentsJson) {
                if (callId == null || callId.trim().isEmpty() || name == null || name.trim().isEmpty()) return;
                synchronized (lock) {
                    if (!sessionStarted || sessionGeneration != generation || activeProvider != provider
                            || retiredToolCallIds.contains(callId)) return;
                    if (pendingToolCalls.size() >= MAX_PENDING_TOOL_CALLS) {
                        String oldest = pendingToolCalls.keySet().iterator().next();
                        pendingToolCalls.remove(oldest);
                        retireToolCallIdLocked(oldest);
                    }
                    pendingToolCalls.put(callId, new ToolCallRoute(provider, generation));
                }
                safely(() -> {
                    if (listener != null && isCurrent(provider, generation))
                        listener.onToolCall(callId, name, argumentsJson);
                });
            }
            @Override public void onError(VoiceProvider.Failure failure, boolean recoverable, String detail) {
                handleProviderFailure(provider, generation,
                        failure == null ? VoiceProvider.Failure.UNKNOWN : failure, detail);
            }
        };
    }

    private boolean isCurrent(VoiceProvider provider, long generation) {
        synchronized (lock) {
            return sessionStarted && sessionGeneration == generation && activeProvider == provider;
        }
    }

    private void handleProviderFailure(VoiceProvider provider, long generation,
                                       VoiceProvider.Failure failure, String detail) {
        synchronized (lifecycleLock) {
            synchronized (lock) {
                if (!sessionStarted || sessionGeneration != generation || activeProvider != provider) return;
                activeProvider = null;
                pendingToolCalls.clear();
            }
            dispose(provider);
            safeNotifyState(VoiceProvider.State.RECONNECTING, safeProviderId(provider),
                    "El proveedor no pudo continuar; se evaluará el siguiente motor.");
            connectNextProvider(failure, detail == null ? "Fallo del proveedor." : detail, generation);
        }
    }

    private void retirePendingToolCallsLocked() {
        for (String callId : pendingToolCalls.keySet()) retireToolCallIdLocked(callId);
        pendingToolCalls.clear();
    }

    private void retireToolCallIdLocked(String callId) {
        if (callId == null || callId.isEmpty()) return;
        retiredToolCallIds.add(callId);
        while (retiredToolCallIds.size() > MAX_RETIRED_TOOL_CALL_IDS) {
            String oldest = retiredToolCallIds.iterator().next();
            retiredToolCallIds.remove(oldest);
        }
    }

    private void dispose(VoiceProvider provider) {
        if (provider == null) return;
        try { provider.setListener(null); } catch (RuntimeException ignored) { }
        try { provider.disconnect(); } catch (RuntimeException ignored) { }
    }

    private String safeProviderId(VoiceProvider provider) {
        try { return provider.getProviderId(); }
        catch (RuntimeException ignored) { return null; }
    }

    private void safeNotifyState(VoiceProvider.State state, String providerId, String detail) {
        safely(() -> { if (listener != null) listener.onStateChanged(state, providerId, detail); });
    }

    private void safely(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) {
            // Un consumidor defectuoso no debe bloquear el ciclo de vida del proveedor.
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "sin detalle" : message;
    }
}
