package com.bellota.mascota.voice.provider;

/**
 * Contrato independiente del proveedor para los motores conversacionales online.
 *
 * Las implementaciones administran su propio transporte y credenciales. Nunca deben
 * recibir ni conservar claves API permanentes. Los callbacks pueden llegar desde
 * hilos de trabajo; la capa que actualiza la interfaz debe despacharlos al hilo UI.
 */
public interface VoiceProvider {

    /** Identificador estable y no localizado del proveedor, por ejemplo "openai" o "gemini". */
    String getProviderId();

    /**
     * Inicia la conexión de forma asíncrona. Debe ser idempotente: llamadas repetidas
     * mientras conecta o está conectado no deben abrir sesiones duplicadas.
     */
    void connect();

    /** Cierra la sesión y libera audio, red y recursos asociados. Debe ser idempotente. */
    void disconnect();

    /** Envía texto al turno conversacional activo, si el proveedor admite entrada textual. */
    void sendText(String text);

    /** Interrumpe la respuesta de audio actual sin cerrar la sesión. */
    void interrupt();

    /** Indica si el canal conversacional terminó de negociar y está listo para usarse. */
    boolean isConnected();

    interface Listener {
        void onStateChanged(State state, String detail);
        void onUserTranscript(String text);
        void onAssistantTranscript(String text);
        void onAssistantSpeaking(boolean speaking);
        void onError(Failure failure, String detail);
    }

    enum State {
        IDLE,
        REQUESTING_CREDENTIAL,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FALLBACK_LOCAL,
        STOPPED
    }

    enum Failure {
        AUTHENTICATION,
        NETWORK,
        QUOTA,
        RATE_LIMIT,
        PROVIDER,
        AUDIO,
        CONFIGURATION,
        UNKNOWN
    }
}
