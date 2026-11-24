package com.bellota.mascota.voice.provider;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.bellota.mascota.voice.auth.BellotaBackendClient;
import com.bellota.mascota.voice.auth.SecureTokenStore;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Gemini Live native-audio transport. The API key never leaves the Cloudflare Worker:
 * Android receives a single-use, model-constrained ephemeral token.
 */
public final class GeminiLiveProvider implements VoiceProvider {
    private static final String PROVIDER_ID = "gemini-live-websocket";
    private static final int INPUT_RATE = 16000;
    private static final int OUTPUT_RATE = 24000;
    private static final int FRAME_SAMPLES = 1600; // 100 ms
    private static final int MAX_RECOVERIES = 2;

    private final Context appContext;
    private final String backendUrl;
    private final SecureTokenStore tokenStore;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final AtomicBoolean closed = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean micRunning = new AtomicBoolean(false);
    private final Object socketLock = new Object();
    private volatile Listener listener;
    private volatile SessionConfig sessionConfig;
    private volatile WebSocket socket;
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private volatile AudioManager audioManager;
    private volatile int previousAudioMode = AudioManager.MODE_NORMAL;
    private volatile boolean previousSpeakerphone;
    private volatile boolean audioModeChanged;
    private volatile long credentialExpiresAt;
    private volatile int recoveryCount;
    private final StringBuilder userTranscript = new StringBuilder();
    private final StringBuilder assistantTranscript = new StringBuilder();
    private final StringBuilder recentContext = new StringBuilder();
    private final Map<String, String> toolNamesByCallId = new ConcurrentHashMap<>();
    private volatile Runnable renewalTask;

    public GeminiLiveProvider(Context context, String backendUrl, SecureTokenStore tokenStore) {
        if (context == null) throw new IllegalArgumentException("Context obligatorio.");
        if (backendUrl == null || !backendUrl.trim().startsWith("https://"))
            throw new IllegalArgumentException("La URL HTTPS del backend no está configurada.");
        if (tokenStore == null) throw new IllegalArgumentException("SecureTokenStore obligatorio.");
        this.appContext = context.getApplicationContext();
        this.backendUrl = backendUrl.trim().replaceAll("/+$", "");
        this.tokenStore = tokenStore;
    }

    @Override public String getProviderId() { return PROVIDER_ID; }
    @Override public void setListener(Listener value) { listener = value; }
    @Override public boolean isConnected() { return connected.get() && !closed.get(); }

    @Override public void connect(SessionConfig config) {
        if (config == null) throw new IllegalArgumentException("La configuración de sesión es obligatoria.");
        if (!closed.compareAndSet(true, false)) return;
        sessionConfig = config;
        recoveryCount = 0;
        synchronized (userTranscript) { userTranscript.setLength(0); assistantTranscript.setLength(0); recentContext.setLength(0); }
        notifyState(State.REQUESTING_CREDENTIAL, "Solicitando token efímero de Gemini Live.");
        openSession(false);
    }

    private void openSession(boolean recovery) {
        if (closed.get()) return;
        notifyState(recovery ? State.RECONNECTING : State.REQUESTING_CREDENTIAL,
                recovery ? "Renovando sesión Gemini Live." : "Solicitando credencial temporal Gemini.");
        try {
            worker.execute(() -> {
                if (closed.get()) return;
                try {
                    BellotaBackendClient backend = new BellotaBackendClient(backendUrl, tokenStore);
                    SessionConfig config = sessionConfig;
                    String instructions = config.instructions;
                    synchronized (recentContext) {
                        if (recentContext.length() > 0)
                            instructions += "\n\nContexto reciente de continuidad (no repetir literalmente):\n" + recentContext;
                    }
                    BellotaBackendClient.GeminiCredential credential =
                            backend.createGeminiLiveToken(instructions, config.voice, config.toolDefinitionsJson);
                    if (closed.get()) return;
                    credentialExpiresAt = credential.expiresAtEpochSeconds;
                    Request request = new Request.Builder()
                            .url(credential.websocketUrl + "?access_token=" +
                                    java.net.URLEncoder.encode(credential.value, "UTF-8").replace("+", "%20"))
                            .build();
                    notifyState(State.CONNECTING, "Abriendo WebSocket de Gemini Live.");
                    WebSocket next = http.newWebSocket(request, new LiveSocketListener());
                    synchronized (socketLock) { socket = next; }
                    scheduleRenewal();
                } catch (BellotaBackendClient.BackendException error) {
                    boolean permanent = error.httpStatus == 401 || error.httpStatus == 402
                            || error.httpStatus == 403 || "provider_not_configured".equals(error.code);
                    fail(mapBackendFailure(error), !permanent, error.getMessage());
                } catch (Exception error) {
                    fail(Failure.NETWORK, true, safeMessage(error));
                }
            });
        } catch (RuntimeException rejected) {
            fail(Failure.NETWORK, true, "No se pudo iniciar la tarea de conexión Gemini.");
        }
    }

    private void scheduleRenewal() {
        cancelRenewal();
        long remaining = Math.max(60000L, credentialExpiresAt * 1000L - System.currentTimeMillis() - 90000L);
        renewalTask = () -> recover("La credencial efímera está próxima a vencer.");
        main.postDelayed(renewalTask, remaining);
    }

    private void cancelRenewal() {
        Runnable task = renewalTask;
        if (task != null) main.removeCallbacks(task);
        renewalTask = null;
    }

    private void recover(String reason) {
        if (closed.get()) return;
        if (recoveryCount >= MAX_RECOVERIES) {
            fail(Failure.SESSION_EXPIRED, true, reason + " Se agotaron los intentos de recuperación.");
            return;
        }
        recoveryCount++;
        connected.set(false);
        stopAudio();
        toolNamesByCallId.clear();
        WebSocket old;
        synchronized (socketLock) { old = socket; socket = null; }
        if (old != null) old.close(1000, "renewing");
        notifyState(State.RECONNECTING, reason);
        main.postDelayed(() -> openSession(true), 350L);
    }

    private final class LiveSocketListener extends WebSocketListener {
        @Override public void onOpen(WebSocket webSocket, Response response) {
            if (closed.get() || !isCurrentSocket(webSocket)) { webSocket.close(1000, "closed"); return; }
            try { webSocket.send(new JSONObject().put("setup", buildSetup()).toString()); }
            catch (Exception error) { fail(Failure.CONFIGURATION, false, "No se pudo preparar Gemini Live: " + safeMessage(error)); }
        }

        @Override public void onMessage(WebSocket webSocket, String text) {
            if (closed.get() || !isCurrentSocket(webSocket)) return;
            try {
                JSONObject event = new JSONObject(text);
                if (event.has("setupComplete")) {
                    connected.set(true);
                    recoveryCount = 0;
                    notifyState(State.CONNECTED, "Gemini Live está listo.");
                    startAudio();
                    return;
                }
                if (event.has("serverContent")) handleServerContent(event.optJSONObject("serverContent"));
                if (event.has("toolCall")) handleToolCall(event.optJSONObject("toolCall"));
                if (event.has("toolCallCancellation")) {
                    JSONObject cancellation = event.optJSONObject("toolCallCancellation");
                    JSONArray ids = cancellation == null ? null : cancellation.optJSONArray("ids");
                    if (ids != null) for (int i = 0; i < ids.length(); i++)
                        toolNamesByCallId.remove(ids.optString(i, ""));
                }
                if (event.has("goAway")) recover("Gemini anunció el cierre próximo de la conexión.");
                if (event.has("error")) {
                    JSONObject error = event.optJSONObject("error");
                    String message = error == null ? "Gemini Live informó un error." : error.optString("message", "Gemini Live informó un error.");
                    fail(Failure.PROVIDER, true, message);
                }
            } catch (Exception error) {
                fail(Failure.PROVIDER, true, "No se pudo interpretar un evento Gemini: " + safeMessage(error));
            }
        }

        @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            if (closed.get() || !isCurrentSocket(webSocket)) return;
            if (connected.get() && recoveryCount < MAX_RECOVERIES) recover("Se interrumpió el WebSocket de Gemini Live.");
            else fail(Failure.NETWORK, true, safeMessage(error));
        }

        @Override public void onClosed(WebSocket webSocket, int code, String reason) {
            if (closed.get() || !isCurrentSocket(webSocket)) return;
            if (connected.get() && recoveryCount < MAX_RECOVERIES)
                recover("Gemini cerró la conexión (" + code + ").");
            else fail(Failure.NETWORK, true, "Gemini cerró la conexión: " + reason);
        }
    }

    private boolean isCurrentSocket(WebSocket candidate) { synchronized (socketLock) { return socket == candidate; } }

    private String currentInstructions() {
        String instructions = sessionConfig == null ? "" : sessionConfig.instructions;
        synchronized (recentContext) {
            if (recentContext.length() > 0)
                instructions += "\\n\\nContexto reciente de continuidad (no repetir literalmente):\\n" + recentContext;
        }
        return instructions;
    }

    private JSONObject buildSetup() throws Exception {
        SessionConfig config = sessionConfig;
        JSONObject setup = new JSONObject();
        setup.put("model", "models/gemini-3.8-live");
        setup.put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("maxOutputTokens", 1024)
                .put("speechConfig", new JSONObject()
                        .put("languageCode", "es-AR")
                        .put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", "Puck")))));
        setup.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", currentInstructions()))));
        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("outputAudioTranscription", new JSONObject());
        setup.put("sessionResumption", new JSONObject());
        JSONArray declarations = convertTools(config.toolDefinitionsJson);
        if (declarations.length() > 0)
            setup.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", declarations)));
        return setup;
    }

    private JSONArray convertTools(String source) throws Exception {
        JSONArray input = new JSONArray(source == null || source.trim().isEmpty() ? "[]" : source);
        JSONArray output = new JSONArray();
        for (int i = 0; i < input.length() && i < 32; i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim();
            if (name.isEmpty()) continue;
            JSONObject declaration = new JSONObject().put("name", name)
                    .put("description", item.optString("description", ""));
            JSONObject parameters = item.optJSONObject("parameters");
            if (parameters == null) parameters = item.optJSONObject("inputSchema");
            declaration.put("parameters", parameters == null
                    ? new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()) : normalizeSchema(parameters));
            output.put(declaration);
        }
        return output;
    }

    private JSONObject normalizeSchema(JSONObject schema) throws Exception {
        JSONObject result = new JSONObject();
        java.util.Iterator<String> keys = schema.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = schema.get(key);
            if ("type".equals(key) && value instanceof String) result.put(key, ((String) value).toUpperCase(java.util.Locale.ROOT));
            else if (value instanceof JSONObject) result.put(key, normalizeSchema((JSONObject) value));
            else if (value instanceof JSONArray) {
                JSONArray array = new JSONArray();
                JSONArray source = (JSONArray) value;
                for (int i = 0; i < source.length(); i++) {
                    Object item = source.get(i);
                    array.put(item instanceof JSONObject ? normalizeSchema((JSONObject) item) : item);
                }
                result.put(key, array);
            } else result.put(key, value);
        }
        return result;
    }

    private void handleServerContent(JSONObject content) throws Exception {
        if (content == null) return;
        if (content.optBoolean("interrupted", false)) {
            AudioTrack track = player;
            if (track != null) { track.pause(); track.flush(); track.play(); }
            notifySpeaking(false);
        }
        JSONObject input = content.optJSONObject("inputTranscription");
        if (input != null) {
            String text = input.optString("text", "");
            if (!text.isEmpty()) {
                synchronized (userTranscript) { userTranscript.append(text); }
                notifyUserTranscript(text, false);
            }
        }
        JSONObject output = content.optJSONObject("outputTranscription");
        if (output != null) {
            String text = output.optString("text", "");
            if (!text.isEmpty()) {
                synchronized (assistantTranscript) { assistantTranscript.append(text); }
                notifyAssistantTranscript(text, false);
            }
        }
        JSONObject turn = content.optJSONObject("modelTurn");
        JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            JSONObject inline = part == null ? null : part.optJSONObject("inlineData");
            if (inline != null && inline.has("data")) playAudio(Base64.decode(inline.optString("data"), Base64.DEFAULT));
            if (part != null && part.has("text")) {
                String text = part.optString("text", "");
                if (!text.isEmpty()) notifyAssistantTranscript(text, false);
            }
        }
        if (content.optBoolean("turnComplete", false) || content.optBoolean("generationComplete", false)) {
            String user;
            String assistant;
            synchronized (userTranscript) { user = userTranscript.toString(); userTranscript.setLength(0); }
            synchronized (assistantTranscript) { assistant = assistantTranscript.toString(); assistantTranscript.setLength(0); }
            if (!user.isEmpty()) {
                remember("Usuario: " + user);
                notifyUserTranscript(user, true);
            }
            if (!assistant.isEmpty()) {
                remember("Bellota: " + assistant);
                notifyAssistantTranscript(assistant, true);
            }
            notifySpeaking(false);
        } else if (turn != null) notifySpeaking(true);
    }

    private void handleToolCall(JSONObject toolCall) {
        JSONArray calls = toolCall == null ? null : toolCall.optJSONArray("functionCalls");
        if (calls == null) return;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) continue;
            String id = call.optString("id", "");
            String name = call.optString("name", "");
            JSONObject args = call.optJSONObject("args");
            if (!id.isEmpty() && !name.isEmpty()) {
                toolNamesByCallId.put(id, name);
                Listener current = listener;
                if (current != null) current.onToolCall(id, name, args == null ? "{}" : args.toString());
            }
        }
    }

    private void startAudio() {
        if (closed.get() || !connected.get()) return;
        try {
            AudioManager manager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            audioManager = manager;
            if (manager != null) {
                previousAudioMode = manager.getMode();
                previousSpeakerphone = manager.isSpeakerphoneOn();
                manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                manager.setSpeakerphoneOn(true);
                audioModeChanged = true;
            }
            int minIn = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minIn <= 0 || minOut <= 0) throw new IllegalStateException("Formato PCM no compatible.");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, FRAME_SAMPLES * 4));
            player = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    Math.max(minOut, OUTPUT_RATE), AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED || player.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("No se pudieron inicializar los dispositivos de audio.");
            player.play();
            recorder.startRecording();
            micRunning.set(true);
            Thread captureThread = new Thread(this::captureLoop, "GeminiLiveMic");
            captureThread.setDaemon(true);
            captureThread.start();
        } catch (Exception error) {
            fail(Failure.AUDIO, true, "No se pudo iniciar el audio Gemini: " + safeMessage(error));
        }
    }

    private void captureLoop() {
        short[] samples = new short[FRAME_SAMPLES];
        while (!closed.get() && connected.get() && micRunning.get()) {
            AudioRecord input = recorder;
            if (input == null) break;
            int count = input.read(samples, 0, samples.length);
            if (count <= 0) {
                if (count < 0) fail(Failure.AUDIO, true, "Falló la captura de audio del micrófono.");
                break;
            }
            ByteBuffer bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) bytes.putShort(samples[i]);
            sendAudioFrame(bytes.array());
        }
    }

    private void sendAudioFrame(byte[] pcm) {
        WebSocket current = socket;
        if (current == null || !connected.get()) return;
        try {
            JSONObject audio = new JSONObject().put("mimeType", "audio/pcm;rate=16000")
                    .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP));
            current.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString());
        } catch (Exception ignored) { }
    }

    private void playAudio(byte[] pcm) {
        AudioTrack track = player;
        if (track == null || pcm == null || pcm.length == 0) return;
        int offset = 0;
        while (offset < pcm.length && !closed.get()) {
            int written = track.write(pcm, offset, pcm.length - offset);
            if (written <= 0) break;
            offset += written;
        }
    }

    @Override public void sendAudio(byte[] pcm16, int offset, int length) {
        // The Gemini adapter owns an AudioRecord configured for 16 kHz mono.
    }

    @Override public void finishAudioInput() {
        WebSocket current = socket;
        if (current != null && connected.get())
            current.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
    }

    @Override public void sendText(String text) {
        if (text == null || text.trim().isEmpty() || !connected.get()) return;
        WebSocket current = socket;
        if (current == null) return;
        try {
            JSONObject part = new JSONObject().put("text", text);
            JSONObject turn = new JSONObject().put("role", "user").put("parts", new JSONArray().put(part));
            current.send(new JSONObject().put("clientContent",
                    new JSONObject().put("turns", new JSONArray().put(turn)).put("turnComplete", true)).toString());
        } catch (Exception error) { fail(Failure.CONFIGURATION, true, safeMessage(error)); }
    }

    @Override public void sendToolResult(String callId, String result, boolean isError) {
        if (callId == null || callId.trim().isEmpty() || !connected.get()) return;
        WebSocket current = socket;
        if (current == null) return;
        try {
            JSONObject response = new JSONObject().put("result", result == null ? "" : result);
            if (isError) response.put("error", true);
            String name = toolNamesByCallId.remove(callId);
            if (name == null) return;
            JSONObject function = new JSONObject().put("id", callId).put("name", name).put("response", response);
            current.send(new JSONObject().put("toolResponse",
                    new JSONObject().put("functionResponses", new JSONArray().put(function))).toString());
        } catch (Exception error) { fail(Failure.PROVIDER, true, safeMessage(error)); }
    }

    @Override public void interrupt() {
        AudioTrack track = player;
        if (track != null) { track.pause(); track.flush(); track.play(); }
    }

    private void remember(String text) {
        synchronized (recentContext) {
            recentContext.append(text).append("\n");
            if (recentContext.length() > 6000) recentContext.delete(0, recentContext.length() - 6000);
        }
    }

    private void stopAudio() {
        micRunning.set(false);
        AudioRecord input = recorder;
        recorder = null;
        if (input != null) {
            try { input.stop(); } catch (RuntimeException ignored) { }
            try { input.release(); } catch (RuntimeException ignored) { }
        }
        AudioTrack output = player;
        player = null;
        if (output != null) {
            try { output.pause(); output.flush(); } catch (RuntimeException ignored) { }
            try { output.release(); } catch (RuntimeException ignored) { }
        }
        AudioManager manager = audioManager;
        if (manager != null && audioModeChanged) {
            try { manager.setSpeakerphoneOn(previousSpeakerphone); manager.setMode(previousAudioMode); }
            catch (RuntimeException ignored) { }
        }
        audioModeChanged = false;
    }

    private void fail(Failure failure, boolean recoverable, String detail) {
        if (closed.get()) return;
        connected.set(false);
        stopAudio();
        cancelRenewal();
        notifyError(failure, recoverable, detail);
    }

    private void notifyError(Failure failure, boolean recoverable, String detail) {
        Listener current = listener;
        if (current != null) current.onError(failure, recoverable, detail);
    }

    private void notifyState(State state, String detail) {
        Listener current = listener;
        if (current != null) current.onStateChanged(state, detail);
    }
    private void notifyUserTranscript(String text, boolean isFinal) {
        Listener current = listener;
        if (current != null) current.onUserTranscript(text, isFinal);
    }
    private void notifyAssistantTranscript(String text, boolean isFinal) {
        Listener current = listener;
        if (current != null) current.onAssistantTranscript(text, isFinal);
    }
    private void notifySpeaking(boolean speaking) {
        Listener current = listener;
        if (current != null) current.onAssistantSpeaking(speaking);
    }

    private static Failure mapBackendFailure(BellotaBackendClient.BackendException error) {
        if (error.httpStatus == 401 || error.httpStatus == 403) return Failure.AUTHENTICATION;
        if (error.httpStatus == 402) return Failure.QUOTA;
        if (error.httpStatus == 429) return Failure.RATE_LIMIT;
        if (error.httpStatus >= 500) return Failure.PROVIDER;
        return Failure.CONFIGURATION;
    }
    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null ? "sin detalle" : message;
    }

    @Override public void disconnect() {
        if (!closed.compareAndSet(false, true)) return;
        connected.set(false);
        cancelRenewal();
        stopAudio();
        WebSocket old;
        synchronized (socketLock) { old = socket; socket = null; }
        if (old != null) old.close(1000, "Bellota session closed");
        toolNamesByCallId.clear();
        worker.shutdownNow();
    }
}
