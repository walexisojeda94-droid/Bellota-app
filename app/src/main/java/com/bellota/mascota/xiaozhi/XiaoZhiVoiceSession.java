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
import android.util.Log;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
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
 * Cliente de voz XiaoZhi WebSocket, protocolo binario v1 (Opus crudo).
 * Captura PCM mono a 16 kHz, lo codifica en paquetes Opus de 60 ms
 * y reproduce los paquetes Opus recibidos del servidor.
 */
public final class XiaoZhiVoiceSession {
    private static final String TAG = "BellotaXiaoZhiVoice";
    private static final int UPLINK_RATE = 16000;
    private static final int FRAME_MS = 60;
    private static final int UPLINK_FRAME = UPLINK_RATE * FRAME_MS / 1000;

    public interface Listener {
        void onStatus(String status);
        void onTranscript(String text);
        void onSpeaking(boolean speaking);
        void onError(String message);
    }

    private final XiaoZhiConnection identity;
    private final Listener listener;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true).pingInterval(30, TimeUnit.SECONDS).build();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    private final AtomicBoolean firstDownlinkPacket = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable helloTimeout = this::checkHelloTimeout;
    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile boolean serverSpeaking;
    private volatile boolean audioNegotiated;
    private volatile int negotiatedProtocolVersion;
    private volatile int downlinkRate = 24000;
    private volatile long sessionStartMs;
    private volatile boolean muted;
    private AudioRecord recorder;
    private AudioTrack player;
    private OpusEncoder encoder;
    private OpusDecoder decoder;

    private void checkHelloTimeout() {
        if (!running.get() && socket != null && !audioNegotiated) {
            fail("XiaoZhi abrió WebSocket pero no completó la negociación hello en 10 segundos.");
        }
    }

    public XiaoZhiVoiceSession(XiaoZhiConnection identity, Listener listener) {
        this.identity = identity;
        this.listener = listener;
    }

    public boolean isRunning() { return running.get(); }

    public void connect() {
        if (running.get()) return;
        intentionalClose.set(false);
        firstDownlinkPacket.set(false);
        JSONObject cfg = identity.getWebSocketConfig();
        if (cfg == null) {
            reportError("XiaoZhi no entregó configuración WebSocket para voz.");
            return;
        }
        String url = cfg.optString("url", "");
        if (url.isEmpty()) {
            reportError("La configuración OTA no contiene una URL WebSocket.");
            return;
        }
        String token = cfg.optString("token", "").trim();
        if (token.isEmpty()) token = identity.getAccessToken().trim();
        if (token.isEmpty()) {
            reportError("XiaoZhi no entregó un token de voz.");
            return;
        }

        try {
            // El OTA oficial suele no mandar "version": el protocolo por defecto es 1.
            int protocolVersion = cfg.optInt("version", 1);
            if (protocolVersion < 1 || protocolVersion > 3) {
                reportError("XiaoZhi solicitó el protocolo binario " + protocolVersion + ", que Bellota no implementa (soporta 1, 2 y 3).");
                return;
            }
            sessionStartMs = System.currentTimeMillis();
            negotiatedProtocolVersion = protocolVersion; // FIX: antes quedaba en 0 y rechazaba el hello / descartaba todo el audio
            encoder = new OpusEncoder(UPLINK_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            int rate = cfg.optInt("sample_rate", 24000);
            if (rate != 8000 && rate != 12000 && rate != 16000 && rate != 24000 && rate != 48000) rate = 24000;
            downlinkRate = rate;
            decoder = new OpusDecoder(downlinkRate, 1);

            Request request = new Request.Builder().url(url)
                    .header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token)
                    .header("Protocol-Version", String.valueOf(protocolVersion))
                    .header("Device-Id", identity.getDeviceId())
                    .header("Client-Id", identity.getClientId())
                    .build();
            reportStatus("Conectando con XiaoZhi…");
            socket = http.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    Log.i(TAG, "Handshake WebSocket HTTP " + response.code() + "; enviando hello.");
                    mainHandler.postDelayed(helloTimeout, 10000L);
                    try {
                        JSONObject hello = new JSONObject();
                        hello.put("type", "hello");
                        hello.put("version", protocolVersion);
                        hello.put("features", new JSONObject().put("mcp", false));
                        hello.put("transport", "websocket");
                        hello.put("audio_params", new JSONObject().put("format", "opus")
                                .put("sample_rate", UPLINK_RATE).put("channels", 1)
                                .put("frame_duration", FRAME_MS));
                        webSocket.send(hello.toString());
                    } catch (Exception e) { fail("No pude iniciar el protocolo de XiaoZhi."); }
                }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    handleJson(webSocket, text);
                }

                @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                    handleAudio(bytes.toByteArray());
                }

                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    if (!intentionalClose.get()) {
                        fail("Se perdió la conexión de voz con XiaoZhi: " +
                                (response != null ? "HTTP " + response.code() : t.getMessage()));
                    }
                }

                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    boolean unexpected = !intentionalClose.get();
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
            reportError("No se pudo preparar el audio Opus: " + e.getMessage());
        }
    }

    private void handleJson(WebSocket ws, String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            String type = msg.optString("type", "");
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
                // El protocolo permite que el hello omita session_id; en ese caso
                // los mensajes posteriores llevan el campo vacío.
                sessionId = msg.optString("session_id", "");
                negotiatedProtocolVersion = serverVersion;
                downlinkRate = rate;
                decoder = new OpusDecoder(downlinkRate, 1);
                recreatePlayer();
                audioNegotiated = true;
                running.set(true);
                Log.i(TAG, "Hello aceptado. Opus mono; subida 16000 Hz, bajada " + downlinkRate + " Hz.");
                reportStatus("Bellota está conectada a XiaoZhi.");
                startListening();
            } else if ("stt".equals(type)) {
                String transcript = msg.optString("text", "");
                if (!transcript.isEmpty() && listener != null) listener.onTranscript(transcript);
            } else if ("tts".equals(type)) {
                String state = msg.optString("state", "");
                if ("start".equals(state)) {
                    Log.i(TAG, "XiaoZhi inició TTS.");
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
            }
        } catch (Exception e) {
            Log.w(TAG, "Mensaje JSON de XiaoZhi no válido", e);
        }
    }

    private synchronized void startListening() {
        if (!running.get() || serverSpeaking || capturing.get()) return;
        try {
            JSONObject listen = new JSONObject();
            listen.put("session_id", sessionId);
            listen.put("type", "listen");
            listen.put("state", "start");
            listen.put("mode", "auto");
            WebSocket ws = socket;
            if (ws == null || !ws.send(listen.toString())) {
                fail("No pude iniciar la escucha de XiaoZhi.");
                return;
            }
            startCapture();
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
                if (count == AudioRecord.ERROR_INVALID_OPERATION || count == AudioRecord.ERROR_BAD_VALUE
                        || count == AudioRecord.ERROR_DEAD_OBJECT) {
                    fail("Android interrumpió la captura del micrófono.");
                    break;
                }
                if (count <= 0) continue;
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
                            Log.e(TAG, "Error codificando audio", e);
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
            current.release();
        }
    }

    /** v1: Opus crudo. v2: cabecera de 16 B. v3: cabecera de 4 B (todo en orden de red). */
    /** Con muted=true se sigue leyendo el micrófono pero no se envía audio (p. ej. mientras Bellota habla). */
    public void setMuted(boolean value) { muted = value; }

    private byte[] wrapUplink(byte[] opus) {
        int v = negotiatedProtocolVersion;
        if (v == 2) {
            ByteBuffer b = ByteBuffer.allocate(16 + opus.length);
            b.putShort((short) 2).putShort((short) 0).putInt(0)
                    .putInt((int) (System.currentTimeMillis() - sessionStartMs)).putInt(opus.length).put(opus);
            return b.array();
        }
        if (v == 3) {
            ByteBuffer b = ByteBuffer.allocate(4 + opus.length);
            b.put((byte) 0).put((byte) 0).putShort((short) opus.length).put(opus);
            return b.array();
        }
        return opus;
    }

    private byte[] unwrapDownlink(byte[] p) {
        int v = negotiatedProtocolVersion;
        if (v == 2) {
            if (p.length < 16) return null;
            ByteBuffer b = ByteBuffer.wrap(p);
            b.getShort(); int type = b.getShort() & 0xFFFF; b.getInt(); b.getInt();
            long size = b.getInt() & 0xFFFFFFFFL;
            if (type != 0 || size != p.length - 16) return null;
            return java.util.Arrays.copyOfRange(p, 16, p.length);
        }
        if (v == 3) {
            if (p.length < 4) return null;
            int type = p[0] & 0xFF, size = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
            if (type != 0 || size != p.length - 4) return null;
            return java.util.Arrays.copyOfRange(p, 4, p.length);
        }
        return p;
    }

    private void handleAudio(byte[] packet) {
        if (!running.get() || !audioNegotiated || negotiatedProtocolVersion < 1
                || !serverSpeaking || packet.length == 0) return;
        packet = unwrapDownlink(packet);
        if (packet == null || packet.length == 0) return;
        if (firstDownlinkPacket.compareAndSet(false, true)) {
            Log.i(TAG, "Primer paquete Opus recibido desde XiaoZhi: " + packet.length + " bytes.");
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
            Log.w(TAG, "No se pudo decodificar audio Opus", e);
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
        intentionalClose.set(true);
        mainHandler.removeCallbacks(helloTimeout);
        running.set(false);
        serverSpeaking = false;
        stopAudio();
        audioExecutor.shutdownNow();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.close(1000, "Bellota cerrando sesión");
    }

    private void fail(String message) {
        intentionalClose.set(true);
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
