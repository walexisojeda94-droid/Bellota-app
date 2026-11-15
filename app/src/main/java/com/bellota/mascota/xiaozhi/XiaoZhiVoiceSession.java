package com.bellota.mascota.xiaozhi;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import com.bellota.mascota.core.DebugLog;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.json.JSONObject;

import org.json.JSONArray;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Cliente de voz XiaoZhi WebSocket:
 * protocolo v1 (Opus crudo), Opus mono a 16 kHz y paquetes de 60 ms.
 */
public final class XiaoZhiVoiceSession {
    private static final String TAG = "BellotaXiaoZhiVoice";
    private static final int PROTOCOL_VERSION = 1;
    private static final int UPLINK_RATE = 16000;
    private static final int FRAME_MS = 60;
    private static final long HELLO_TIMEOUT_MS = 30000L;
    private static final int UPLINK_FRAME = UPLINK_RATE * FRAME_MS / 1000;

    public interface Listener {
        void onStatus(String status);
        void onTranscript(String text);
        void onSpeaking(boolean speaking);
        void onError(String message);
    }

    /** Puente hacia las herramientas MCP reales de Bellota. */
    public interface McpHandler {
        JSONArray getTools() throws Exception;
        String executeTool(String name, JSONObject arguments) throws Exception;
    }

    private final XiaoZhiConnection identity;
    private final Listener listener;
    private final McpHandler mcpHandler;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true).pingInterval(30, TimeUnit.SECONDS).build();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    private final AtomicBoolean firstDownlinkPacket = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable helloTimeout = this::checkHelloTimeout;
    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile boolean serverSpeaking;
    private volatile boolean audioNegotiated;
    private volatile boolean mcpReady;
    private volatile int negotiatedProtocolVersion;
    private volatile int downlinkRate = 24000;
    private volatile boolean muted;
    private AudioRecord recorder;
    private AudioTrack player;
    private OpusEncoder encoder;
    private OpusDecoder decoder;

    private void checkHelloTimeout() {
        if (connecting.get() && socket != null && !audioNegotiated) {
            // El timeout es diagnóstico, no una orden de desconexión: algunos servidores
            // pueden tardar en responder al hello. Se conserva el WebSocket para aceptar
            // una respuesta tardía y evitar cancelar una sesión que sigue abierta.
            DebugLog.w(TAG, "XiaoZhi todavía no respondió al hello después de "
                    + (HELLO_TIMEOUT_MS / 1000L) + " segundos; se mantiene el WebSocket abierto.");
            reportStatus("Esperando respuesta de XiaoZhi… la conexión sigue abierta.");
        }
    }

    public XiaoZhiVoiceSession(XiaoZhiConnection identity, Listener listener) {
        this(identity, listener, null);
    }

    public XiaoZhiVoiceSession(XiaoZhiConnection identity, Listener listener, McpHandler mcpHandler) {
        this.identity = identity;
        this.listener = listener;
        this.mcpHandler = mcpHandler;
    }

    public boolean isRunning() { return running.get(); }

    public void connect() {
        if (terminated.get() || !connecting.compareAndSet(false, true)) return;
        intentionalClose.set(false);
        audioNegotiated = false;
        mcpReady = false;
        firstDownlinkPacket.set(false);
        JSONObject cfg = identity.getWebSocketConfig();
        if (cfg == null) {
            fail("XiaoZhi no entregó configuración WebSocket para voz.");
            return;
        }
        String url = cfg.optString("url", "");
        if (url.isEmpty()) {
            fail("La configuración OTA no contiene una URL WebSocket.");
            return;
        }
        String token = cfg.optString("token", "").trim();
        if (token.isEmpty()) token = identity.getAccessToken().trim();
        if (token.isEmpty()) {
            fail("XiaoZhi no entregó un token de voz.");
            return;
        }

        try {
            // OTA no informa una versión WebSocket válida (version=-1 en esta cuenta).
            // XiaoZhi documenta v1 como versión predeterminada: Opus crudo en tramas
            // binarias, sin cabecera BinaryProtocol2.
            final int protocolVersion = PROTOCOL_VERSION;
            negotiatedProtocolVersion = PROTOCOL_VERSION;
            encoder = new OpusEncoder(UPLINK_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            int rate = cfg.optInt("sample_rate", 24000);
            if (rate != 8000 && rate != 12000 && rate != 16000 && rate != 24000 && rate != 48000) rate = 24000;
            downlinkRate = rate;
            decoder = new OpusDecoder(downlinkRate, 1);

            Request request = new Request.Builder().url(url)
                    .header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token)
                    .header("Protocol-Version", String.valueOf(PROTOCOL_VERSION))
                    .header("Device-Id", identity.getDeviceId())
                    .header("Client-Id", identity.getClientId())
                    .build();
            reportStatus("Conectando con XiaoZhi…");
            DebugLog.i(TAG, "Abriendo WebSocket " + url.split("\\?")[0] + " (protocolo v" + protocolVersion + ", bajada " + downlinkRate + " Hz).");
            socket = http.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    if (!isCurrentSocket(webSocket)) {
                        DebugLog.w(TAG, "onOpen ignorado: el socket ya no es el actual.");
                        return;
                    }
                    DebugLog.i(TAG, "Handshake WebSocket HTTP " + response.code() + " (" + response.message() + "); Protocol-Version respuesta=" + response.header("Protocol-Version", "no informado") + "; enviando hello.");
                    mainHandler.postDelayed(helloTimeout, HELLO_TIMEOUT_MS);
                    try {
                        JSONObject hello = new JSONObject();
                        hello.put("type", "hello");
                        hello.put("version", protocolVersion);
                        hello.put("features", new JSONObject().put("aec", true).put("mcp", mcpHandler != null));
                        hello.put("transport", "websocket");
                        hello.put("audio_params", new JSONObject().put("format", "opus")
                                .put("sample_rate", UPLINK_RATE).put("channels", 1)
                                .put("frame_duration", FRAME_MS));
                        boolean queued = webSocket.send(hello.toString());
                        DebugLog.i(TAG, "Hello enviado=" + queued + "; payload=" + hello.toString() + ".");
                        if (!queued) fail("OkHttp rechazó el envío del mensaje hello de XiaoZhi.");
                    } catch (Exception e) { fail("No pude iniciar el protocolo de XiaoZhi."); }
                }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    if (!isCurrentSocket(webSocket)) return;
                    try {
                        JSONObject incoming = new JSONObject(text);
                        JSONObject params = incoming.optJSONObject("audio_params");
                        String type = incoming.optString("type", "(sin type)");
                        DebugLog.i(TAG, "Texto servidor: type=" + type + "; chars=" + text.length()
                                + (incoming.has("transport") ? "; transport=" + incoming.optString("transport") : "")
                                + (incoming.has("version") ? "; version=" + incoming.optInt("version", -999) : "")
                                + (params != null ? "; audio_format=" + params.optString("format", "omitido")
                                + "; audio_rate=" + params.optInt("sample_rate", -1)
                                + "; audio_channels=" + params.optInt("channels", -1)
                                + "; frame_ms=" + params.optInt("frame_duration", -1) : ""));
                    } catch (Exception ignored) {
                        DebugLog.w(TAG, "Texto servidor no JSON; chars=" + text.length());
                    }
                    handleJson(webSocket, text);
                }

                @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                    if (!isCurrentSocket(webSocket)) return;
                    DebugLog.i(TAG, "Binario servidor: " + bytes.size() + " bytes.");
                    handleBinaryFrame(webSocket, bytes.toByteArray());
                }

                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    if (isCurrentSocket(webSocket)) {
                        DebugLog.e(TAG, "WebSocket onFailure: " + t.getClass().getSimpleName()
                                + "; HTTP=" + (response == null ? "sin respuesta HTTP" : response.code() + " " + response.message()));
                        fail("Se perdió la conexión de voz con XiaoZhi: " +
                                (response != null ? "HTTP " + response.code() : t.getClass().getSimpleName()));
                    }
                }

                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    if (webSocket != socket || !terminated.compareAndSet(false, true)) return;
                    boolean unexpected = !intentionalClose.get();
                    DebugLog.w(TAG, "WebSocket cerrado: code=" + code + "; reason="
                            + (reason == null || reason.isEmpty() ? "(sin motivo)" : reason)
                            + "; inesperado=" + unexpected + ".");
                    socket = null;
                    connecting.set(false);
                    mainHandler.removeCallbacks(helloTimeout);
                    stopAudio();
                    audioExecutor.shutdownNow();
                    running.set(false);
                    if (unexpected) {
                        reportError("XiaoZhi cerró la sesión de voz ("
                                + code + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ").");
                    } else {
                        reportStatus("Conexión XiaoZhi cerrada.");
                    }
                }
            });
        } catch (Exception e) {
            fail("No se pudo preparar el audio Opus: " + e.getMessage());
        }
    }

    private boolean isCurrentSocket(WebSocket candidate) {
        // newWebSocket() puede devolver después de que dispare onOpen u onFailure: sin esta
        // asignación el hello nunca se enviaba (o el fallo se ignoraba) y la sesión se colgaba.
        if (candidate != null && socket == null && !terminated.get() && !intentionalClose.get()) socket = candidate;
        return candidate != null && candidate == socket && !intentionalClose.get() && !terminated.get();
    }

    private void handleJson(WebSocket ws, String raw) {
        if (!isCurrentSocket(ws)) return;
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.optString("type", "");
            DebugLog.i(TAG, "<- JSON type=" + type
                    + ("hello".equals(type) ? "; transport=" + msg.optString("transport", "(omitido)")
                    + "; version=" + (msg.has("version") ? msg.optInt("version", -999) : "omitida")
                    + "; session_id_present=" + msg.has("session_id")
                    + "; audio_params_present=" + (msg.optJSONObject("audio_params") != null) : "")
                    + ("tts".equals(type) ? "; state=" + msg.optString("state", "") : ""));
            if ("hello".equals(type)) {
                if (!"websocket".equals(msg.optString("transport", ""))) {
                    fail("XiaoZhi respondió con un transporte distinto de WebSocket. Bellota no enviará audio.");
                    return;
                }
                // La respuesta hello del servidor no está obligada a repetir version ni
                // todos los campos de audio_params. Si los omite, se conservan los
                // valores que Bellota anunció en su propio hello.
                int serverVersion = msg.has("version")
                        ? msg.optInt("version", -1) : negotiatedProtocolVersion;
                if (serverVersion != negotiatedProtocolVersion) {
                    fail("XiaoZhi negoció el protocolo " + serverVersion
                            + ", pero Bellota inició con v" + negotiatedProtocolVersion + ".");
                    return;
                }
                mainHandler.removeCallbacks(helloTimeout);
                JSONObject params = msg.optJSONObject("audio_params");
                String format = params == null ? "opus" : params.optString("format", "opus");
                if (!"opus".equalsIgnoreCase(format)) {
                    fail("XiaoZhi solicitó el formato de audio " + format + ". Bellota solo tiene implementado Opus.");
                    return;
                }
                int channels = params == null ? 1 : params.optInt("channels", 1);
                if (channels != 1) {
                    fail("XiaoZhi solicitó audio de " + channels + " canales. Bellota solo tiene implementado Opus mono.");
                    return;
                }
                int rate = params == null ? downlinkRate : params.optInt("sample_rate", downlinkRate);
                if (rate != 8000 && rate != 12000 && rate != 16000 && rate != 24000 && rate != 48000) {
                    fail("XiaoZhi solicitó una frecuencia de audio no compatible: " + rate + " Hz.");
                    return;
                }
                int frameDuration = params == null ? FRAME_MS : params.optInt("frame_duration", FRAME_MS);
                if (frameDuration != FRAME_MS) {
                    fail("XiaoZhi solicitó paquetes de " + frameDuration + " ms. Bellota solo tiene implementados paquetes de " + FRAME_MS + " ms.");
                    return;
                }
                String negotiatedSessionId = msg.optString("session_id", "").trim();
                if (negotiatedSessionId.isEmpty()) {
                    fail("La respuesta hello de XiaoZhi no contiene session_id; no se puede continuar la sesión.");
                    return;
                }
                sessionId = negotiatedSessionId;
                negotiatedProtocolVersion = serverVersion;
                connecting.set(false);
                downlinkRate = rate;
                decoder = new OpusDecoder(downlinkRate, 1);
                recreatePlayer();
                audioNegotiated = true;
                running.set(true);
                DebugLog.i(TAG, "Hello aceptado. Opus mono; subida 16000 Hz, bajada " + downlinkRate + " Hz.");
                reportStatus("Bellota está conectada a XiaoZhi.");
                startListening();
            } else if ("mcp".equals(type)) {
                handleMcp(ws, msg);
            } else if ("stt".equals(type)) {
                String transcript = msg.optString("text", "");
                if (!transcript.isEmpty() && listener != null) listener.onTranscript(transcript);
            } else if ("tts".equals(type)) {
                String state = msg.optString("state", "");
                if ("start".equals(state)) {
                    DebugLog.i(TAG, "XiaoZhi inició TTS.");
                    serverSpeaking = true;
                    stopCapture();
                    if (listener != null) listener.onSpeaking(true);
                } else if ("stop".equals(state)) {
                    serverSpeaking = false;
                    if (listener != null) listener.onSpeaking(false);
                    startListening();
                }
            } else if ("llm".equals(type)) {
                String emotion = msg.optString("emotion", "");
                reportStatus(emotion.isEmpty() ? "XiaoZhi está pensando…" : "Bellota: " + emotion);
            } else if ("goodbye".equals(type)) {
                DebugLog.i(TAG, "XiaoZhi envió goodbye; cerrando la sesión de forma ordenada.");
                intentionalClose.set(true);
                mainHandler.removeCallbacks(helloTimeout);
                connecting.set(false);
                running.set(false);
                serverSpeaking = false;
                stopAudio();
                WebSocket current = socket;
                if (current != null) current.close(1000, "XiaoZhi goodbye");
                reportStatus("XiaoZhi finalizó la sesión de voz.");
            }
        } catch (Exception e) {
            DebugLog.w(TAG, "Mensaje JSON de XiaoZhi no válido", e);
        }
    }

    /** Procesa MCP encapsulado en el sobre de sesión WebSocket de XiaoZhi. */
    private void handleMcp(WebSocket ws, JSONObject envelope) {
        if (mcpHandler == null) {
            DebugLog.w(TAG, "El servidor envió MCP, pero Bellota no registró un manejador.");
            return;
        }
        JSONObject request = envelope.optJSONObject("payload");
        if (request == null) {
            DebugLog.w(TAG, "Mensaje MCP sin payload JSON-RPC.");
            return;
        }
        if (!request.has("id") || request.isNull("id")) {
            // notifications/initialized y demás notificaciones no llevan respuesta.
            DebugLog.i(TAG, "Notificación MCP recibida: " + request.optString("method", "(sin método)") + ".");
            return;
        }
        Object id = request.opt("id");
        String method = request.optString("method", "");
        try {
            JSONObject response;
            if ("initialize".equals(method)) {
                response = new JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("result", new JSONObject()
                                .put("protocolVersion", "2024-11-05")
                                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                                .put("serverInfo", new JSONObject()
                                        .put("name", "Bellota").put("version", "0.4.4")));
            } else if ("tools/list".equals(method)) {
                JSONArray tools = mcpHandler.getTools();
                response = new JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("result", new JSONObject().put("tools", tools == null ? new JSONArray() : tools));
            } else if ("tools/call".equals(method)) {
                JSONObject params = request.optJSONObject("params");
                String name = params == null ? "" : params.optString("name", "");
                JSONObject arguments = params == null ? null : params.optJSONObject("arguments");
                if (arguments == null) arguments = new JSONObject();
                boolean isError = false;
                String resultText;
                try {
                    resultText = mcpHandler.executeTool(name, arguments);
                    if (resultText == null) resultText = "";
                } catch (Exception toolError) {
                    isError = true;
                    resultText = toolError.getMessage() == null
                            ? toolError.getClass().getSimpleName() : toolError.getMessage();
                }
                response = new JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("result", new JSONObject()
                                .put("content", new JSONArray().put(new JSONObject()
                                        .put("type", "text").put("text", resultText)))
                                .put("isError", isError));
            } else if ("ping".equals(method)) {
                response = new JSONObject().put("jsonrpc", "2.0")
                        .put("id", id).put("result", new JSONObject());
            } else {
                response = new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("error", new JSONObject().put("code", -32601)
                                .put("message", "Method not found: " + method));
            }
            JSONObject outbound = new JSONObject()
                    .put("session_id", sessionId)
                    .put("type", "mcp")
                    .put("payload", response);
            if (!ws.send(outbound.toString())) {
                DebugLog.w(TAG, "OkHttp rechazó la respuesta MCP para método " + method + ".");
            } else {
                DebugLog.i(TAG, "Respuesta MCP enviada: " + method + ".");
                if ("tools/list".equals(method)) {
                    // El flujo oficial completa initialize + tools/list antes de comenzar
                    // a transmitir audio de la conversación.
                    mcpReady = true;
                    if (running.get() && !serverSpeaking && !capturing.get()) {
                        try { startCapture(); }
                        catch (Exception e) { fail("No pude iniciar el micrófono después de inicializar MCP."); }
                    }
                }
            }
        } catch (Exception error) {
            DebugLog.e(TAG, "No se pudo responder al mensaje MCP " + method + ".", error);
        }
    }

    private synchronized void startListening() {
        if (terminated.get() || !running.get() || serverSpeaking || capturing.get()) return;
        try {
            JSONObject listen = new JSONObject();
            listen.put("session_id", sessionId);
            listen.put("type", "listen");
            listen.put("state", "start");
            listen.put("mode", "realtime");
            WebSocket ws = socket;
            if (ws == null || !ws.send(listen.toString())) {
                fail("No pude iniciar la escucha de XiaoZhi.");
                return;
            }
            if (mcpHandler == null || mcpReady) startCapture();
        } catch (Exception e) { fail("No pude iniciar el micrófono."); }
    }

    private void startCapture() throws Exception {
        if (capturing.get()) return;
        if (identity.getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Bellota necesita permiso de micrófono para iniciar el audio de XiaoZhi.");
        }
        int min = AudioRecord.getMinBufferSize(UPLINK_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min, UPLINK_FRAME * 2 * 4);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                UPLINK_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); recorder = null;
            throw new IllegalStateException("Android no pudo inicializar el micrófono.");
        }
        recorder.startRecording();
        final AudioRecord activeRecorder = recorder;
        capturing.set(true);
        audioExecutor.execute(() -> {
            short[] frame = new short[UPLINK_FRAME];
            short[] readBuffer = new short[UPLINK_FRAME];
            byte[] opus = new byte[4000];
            int filled = 0;
            while (capturing.get() && running.get() && !serverSpeaking && recorder == activeRecorder) {
                int count = activeRecorder.read(readBuffer, 0, readBuffer.length);
                // FIX: al llegar tts/start se libera el recorder a propósito; ese error no es fatal.
                if (!capturing.get() || recorder != activeRecorder) break;
                if (count < 0) {
                    if (running.get() && !serverSpeaking && recorder == activeRecorder)
                        fail("Android interrumpió la captura del micrófono (código " + count + ").");
                    break;
                }
                if (count == 0) {
                    try { Thread.sleep(8L); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                int offset = 0;
                while (offset < count && capturing.get() && running.get() && !serverSpeaking) {
                    int copy = Math.min(UPLINK_FRAME - filled, count - offset);
                    System.arraycopy(readBuffer, offset, frame, filled, copy);
                    filled += copy;
                    offset += copy;
                    if (filled == UPLINK_FRAME) {
                        try {
                            int length = encoder.encode(frame, 0, UPLINK_FRAME, opus, 0, opus.length);
                            WebSocket ws = socket;
                            if (length > 0 && ws != null && capturing.get() && !muted) {
                                if (!ws.send(ByteString.of(wrapUplink(java.util.Arrays.copyOf(opus, length))))) {
                                    fail("No se pudo enviar el audio a XiaoZhi.");
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            DebugLog.e(TAG, "Error codificando audio", e);
                            fail("Falló la codificación de audio Opus.");
                            return;
                        }
                        filled = 0;
                    }
                }
            }
        });
    }

    private synchronized void stopCapture() {
        capturing.set(false);
        AudioRecord current = recorder;
        recorder = null;
        if (current != null) {
            try { current.stop(); } catch (Exception ignored) { }
            try { current.release(); } catch (Exception ignored) { }
        }
    }

    /** Con muted=true se sigue leyendo el micrófono pero no se envía audio (p. ej. mientras Bellota habla). */
    public void setMuted(boolean value) { muted = value; }

    /** Protocolo v1: cada trama binaria contiene únicamente un paquete Opus. */
    private byte[] wrapUplink(byte[] opus) {
        return opus;
    }

    /** En v1, WebSocket ya distingue JSON (texto) de audio Opus (binario). */
    private void handleBinaryFrame(WebSocket ws, byte[] frame) {
        if (negotiatedProtocolVersion != PROTOCOL_VERSION || frame == null || frame.length == 0) {
            DebugLog.w(TAG, "Trama Opus vacía o protocolo v1 no negociado.");
            return;
        }
        handleAudio(frame);
    }

    private void handleAudio(byte[] packet) {
        if (!running.get() || !audioNegotiated || negotiatedProtocolVersion != PROTOCOL_VERSION
                || !serverSpeaking || packet == null || packet.length == 0) return;
        if (firstDownlinkPacket.compareAndSet(false, true)) {
            DebugLog.i(TAG, "Primer paquete Opus recibido desde XiaoZhi: " + packet.length + " bytes.");
        }
        try {
            if (player == null) createPlayer();
            short[] pcm = new short[5760];
            int samples = decoder.decode(packet, 0, packet.length, pcm, 0, pcm.length, false);
            if (samples > 0 && player != null) {
                if (player.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) player.play();
                player.write(pcm, 0, samples);
            }
        } catch (Exception e) {
            DebugLog.w(TAG, "No se pudo decodificar audio Opus", e);
        }
    }

    private synchronized void createPlayer() {
        int min = AudioTrack.getMinBufferSize(downlinkRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        player = new AudioTrack(AudioManager.STREAM_MUSIC, downlinkRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, downlinkRate / 2), AudioTrack.MODE_STREAM);
    }

    private synchronized void recreatePlayer() {
        releasePlayer();
        createPlayer();
    }

    private synchronized void releasePlayer() {
        if (player != null) {
            try { player.pause(); player.flush(); player.stop(); } catch (Exception ignored) { }
            player.release(); player = null;
        }
    }

    private void stopAudio() {
        audioNegotiated = false;
        stopCapture();
        releasePlayer();
    }

    public void disconnect() {
        if (!terminated.compareAndSet(false, true)) return;
        intentionalClose.set(true);
        connecting.set(false);
        mainHandler.removeCallbacks(helloTimeout);
        running.set(false);
        serverSpeaking = false;
        stopAudio();
        audioExecutor.shutdownNow();
        WebSocket ws = socket;
        if (ws != null && sessionId != null && !sessionId.isEmpty()) {
            try {
                ws.send(new JSONObject().put("session_id", sessionId)
                        .put("type", "goodbye").toString());
            } catch (Exception e) {
                DebugLog.w(TAG, "No se pudo enviar goodbye antes del cierre.", e);
            }
        }
        socket = null;
        if (ws != null) ws.close(1000, "Bellota cerrando sesión");
    }

    private void fail(String message) {
        if (!terminated.compareAndSet(false, true)) return;
        intentionalClose.set(true);
        connecting.set(false);
        mainHandler.removeCallbacks(helloTimeout);
        running.set(false);
        stopAudio();
        audioExecutor.shutdownNow();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.cancel();
        reportError(message);
    }

    private void reportStatus(String value) { if (listener != null) listener.onStatus(value); }
    private void reportError(String value) { if (listener != null) listener.onError(value); }
}
