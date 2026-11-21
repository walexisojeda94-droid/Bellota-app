package com.bellota.mascota.voice.provider;

/**
 * Contrato de una sesión de conversación de voz, independiente del proveedor.
 *
 * Las implementaciones gestionan su transporte y solicitan credenciales efímeras
 * al backend. Nunca deben incluir ni conservar claves API permanentes.
 *
 * Todos los callbacks del Listener pueden llegar desde hilos de trabajo. La capa
 * de aplicación debe despachar al hilo principal cualquier cambio de interfaz.
 */
public interface VoiceProvider {

    String getProviderId();

    /** Registra el receptor de eventos antes de abrir una sesión. Puede recibir null al liberar. */
    void setListener(Listener listener);

    /** Abre una sesión nueva de forma asíncrona. No debe crear sesiones duplicadas. */
    void connect(SessionConfig config);

    /** Envía un bloque PCM16 little-endian de audio mono capturado desde el micrófono. */
    void sendAudio(byte[] pcm16, int offset, int length);

    /** Indica que terminó el bloque actual de entrada de voz, sin cerrar la sesión. */
    void finishAudioInput();

    /** Envía texto como entrada conversacional. */
    void sendText(String text);

    /** Devuelve el resultado de una herramienta solicitada por el modelo. */
    void sendToolResult(String callId, String result, boolean isError);

    /** Interrumpe la respuesta actual sin cerrar la sesión. */
    void interrupt();

    /** Cierra la sesión y libera transporte y recursos de audio. Debe ser idempotente. */
    void disconnect();

    boolean isConnected();

    interface Listener {
        void onStateChanged(State state, String detail);
        void onUserTranscript(String text, boolean isFinal);
        void onAssistantTranscript(String text, boolean isFinal);
        /** Audio PCM16 little-endian; el consumidor debe reproducirlo en el formato indicado. */
        void onAudioOutput(byte[] pcm16, int sampleRateHz, int channels);
        void onAssistantSpeaking(boolean speaking);
        void onToolCall(String callId, String name, String argumentsJson);
        void onError(Failure failure, boolean recoverable, String detail);
    }

    /** Configuración común mínima; los adaptadores traducen estos valores a cada API. */
    final class SessionConfig {
        public final String locale;
        public final String voice;
        public final String instructions;
        public final String toolDefinitionsJson;
        public final int inputSampleRateHz;

        public SessionConfig(String locale, String voice, String instructions,
                             String toolDefinitionsJson, int inputSampleRateHz) {
            if (inputSampleRateHz <= 0) {
                throw new IllegalArgumentException("inputSampleRateHz debe ser mayor que cero.");
            }
            this.locale = locale == null ? "es-AR" : locale;
            this.voice = voice == null ? "" : voice;
            this.instructions = instructions == null ? "" : instructions;
            this.toolDefinitionsJson = toolDefinitionsJson == null ? "[]" : toolDefinitionsJson;
            this.inputSampleRateHz = inputSampleRateHz;
        }
    }

    enum State {
        IDLE,
        REQUESTING_CREDENTIAL,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FALLBACK_LOCAL,
        CLOSING,
        CLOSED
    }

    enum Failure {
        AUTHENTICATION,
        NETWORK,
        QUOTA,
        RATE_LIMIT,
        PROVIDER,
        AUDIO,
        CONFIGURATION,
        SESSION_EXPIRED,
        UNKNOWN
    }
}
