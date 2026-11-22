package com.bellota.mascota.voice.provider;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.bellota.mascota.voice.auth.BellotaBackendClient;
import com.bellota.mascota.voice.auth.SecureTokenStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI Realtime audio-only transport over native Android WebRTC.
 *
 * The WebRTC audio device module captures microphone audio and renders remote audio.
 * sendAudio() is intentionally a no-op because the native audio track owns capture;
 * server VAD detects turn boundaries. No permanent OpenAI key is stored here.
 */
public final class OpenAIRealtimeWebRtcProvider implements VoiceProvider {
    private static final String PROVIDER_ID = "openai-realtime-webrtc";
    private static final String OPENAI_CALLS_URL = "https://api.openai.com/v1/realtime/calls";
    private static final int HTTP_TIMEOUT_MS = 15000;
    private static final int MAX_RECOVERY_ATTEMPTS = 1;
    private static final int MAX_SDP_BYTES = 262144;
    private static final long OFFER_GATHER_TIMEOUT_MS = 4000L;
    private static final Object FACTORY_LOCK = new Object();
    private static boolean factoryInitialized;

    private final Context appContext;
    private final String backendUrl;
    private final SecureTokenStore tokenStore;
    private volatile ExecutorService worker = newWorker();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean(true);
    private final AtomicBoolean offerSubmitted = new AtomicBoolean(false);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final AtomicLong attemptSequence = new AtomicLong(0L);
    private volatile long activeAttemptId;
    private final AtomicBoolean retryScheduled = new AtomicBoolean(false);
    private final RealtimeSessionBudget sessionBudget = new RealtimeSessionBudget();
    private volatile int maxOutputTokens = RealtimeSessionBudget.MAX_OUTPUT_TOKENS_PER_RESPONSE;
    private volatile List<BellotaBackendClient.IceServerConfig> configuredIceServers = Collections.emptyList();
    private volatile HttpURLConnection activeHttpConnection;
    private volatile long sessionMaxDurationMs = 2700000L;
    private final Runnable negotiationTimeout = () -> fail(Failure.NETWORK, true, "La negociación WebRTC superó el tiempo máximo.");
    private volatile Runnable iceDisconnectTimeout;
    private volatile Runnable sessionTimeout;
    private volatile Runnable offerFallbackTimeout;
    private volatile Listener listener;
    private volatile boolean connected;
    private volatile SessionConfig sessionConfig;
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audioDeviceModule;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private DataChannel dataChannel;
    private AudioManager audioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean previousSpeakerphone;
    private boolean audioModeChanged;

    public OpenAIRealtimeWebRtcProvider(Context context, String backendUrl, SecureTokenStore tokenStore) {
        if (context == null) throw new IllegalArgumentException("Context obligatorio.");
        if (backendUrl == null || !backendUrl.trim().startsWith("https://"))
            throw new IllegalArgumentException("La URL HTTPS del backend no está configurada.");
        if (tokenStore == null) throw new IllegalArgumentException("SecureTokenStore obligatorio.");
        this.appContext = context.getApplicationContext();
        this.backendUrl = backendUrl.trim().replaceAll("/+$", "");
        this.tokenStore = tokenStore;
    }

    @Override public String getProviderId() { return PROVIDER_ID; }
    @Override public void setListener(Listener listener) { this.listener = listener; }
    @Override public boolean isConnected() { return connected && !closed.get(); }

    @Override public void connect(SessionConfig config) {
        if (config == null) throw new IllegalArgumentException("La configuración de sesión es obligatoria.");
        if (!closed.compareAndSet(true, false)) return;
        ensureWorker();
        sessionConfig = config;
        offerSubmitted.set(false);
        retryCount.set(0);
        retryScheduled.set(false);
        sessionBudget.reset();
        mainHandler.removeCallbacks(negotiationTimeout);
        mainHandler.postDelayed(negotiationTimeout, 60000L);
        notifyState(State.REQUESTING_CREDENTIAL, "Solicitando credencial temporal de OpenAI.");
        executeWorker(() -> establishSession(config));
    }

    private void establishSession(SessionConfig config) {
        final long attempt = attemptSequence.incrementAndGet();
        activeAttemptId = attempt;
        try {
            BellotaBackendClient backend = new BellotaBackendClient(backendUrl, tokenStore);
            BellotaBackendClient.RealtimeCredential credential =
                    backend.createRealtimeClientSecret(config.instructions, config.voice);
            if (closed.get()) return;
            configuredIceServers = credential.iceServers == null ? Collections.emptyList() : credential.iceServers;
            maxOutputTokens = Math.max(1, Math.min(4096, credential.maxOutputTokens));
            sessionMaxDurationMs = Math.max(60000L, Math.min(RealtimeSessionBudget.MAX_DURATION_SECONDS * 1000L, credential.sessionMaxDurationSeconds * 1000L));
            notifyState(State.CONNECTING, "Preparando conexión WebRTC.");
            initializeWebRtc();
            createPeerConnection(attempt);
            createAudioAndDataTracks(attempt);
            // Set the ephemeral secret before SDP gathering can complete asynchronously.
            exchangeSecret = credential.value;
            createOffer(attempt);
        } catch (BellotaBackendClient.BackendException error) {
            fail(mapBackendFailure(error), error.httpStatus == 429 || error.httpStatus >= 500, error.getMessage());
        } catch (Exception error) {
            fail(error instanceof IOException ? Failure.NETWORK : Failure.AUDIO, true,
                    safeMessage(error));
        }
    }

    private volatile String exchangeSecret;

    private void initializeWebRtc() {
        synchronized (FACTORY_LOCK) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions());
                factoryInitialized = true;
            }
        }
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();
        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            previousAudioMode = audioManager.getMode();
            previousSpeakerphone = audioManager.isSpeakerphoneOn();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            audioModeChanged = true;
        }
    }

    private void createPeerConnection(final long attempt) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        for (BellotaBackendClient.IceServerConfig configured : configuredIceServers) {
            for (String url : configured.urls) {
                if (configured.username.isEmpty() && configured.credential.isEmpty()) {
                    iceServers.add(IceServer.builder(url).createIceServer());
                } else {
                    iceServers.add(IceServer.builder(url).setUsername(configured.username)
                            .setPassword(configured.credential).createIceServer());
                }
            }
        }
        PeerConnection.RTCConfiguration rtc = new PeerConnection.RTCConfiguration(iceServers);
        rtc.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // GATHER_ONCE permite que la recolección ICE llegue a COMPLETE; con GATHER_CONTINUALLY
        // nunca termina y la oferta SDP quedaría sin enviarse.
        rtc.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        peerConnection = factory.createPeerConnection(rtc, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (closed.get() || attempt != activeAttemptId) return;
                if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                    connected = true;
                    mainHandler.removeCallbacks(negotiationTimeout);
                    cancelIceDisconnectTimeout();
                    scheduleSessionTimeout();
                    notifyState(State.CONNECTED, "OpenAI Realtime conectado por WebRTC.");
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    connected = false;
                    notifyState(State.RECONNECTING, "Se interrumpió temporalmente la conexión WebRTC.");
                    scheduleIceDisconnectTimeout();
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    fail(Failure.NETWORK, true, "Falló la conexión ICE de WebRTC.");
                }
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                if (attempt == activeAttemptId && state == PeerConnection.IceGatheringState.COMPLETE && offerSubmitted.compareAndSet(false, true)) {
                    executeWorker(() -> exchangeOffer(attempt));
                }
            }
            @Override public void onIceCandidate(IceCandidate candidate) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] streams) { }
            @Override public void onTrack(org.webrtc.RtpTransceiver transceiver) { }
        });
        if (peerConnection == null) throw new IllegalStateException("No se pudo crear PeerConnection.");
    }

    private void createAudioAndDataTracks(final long attempt) {
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("bellota-microphone", audioSource);
        localAudioTrack.setEnabled(true);
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("bellota-audio"));
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        dataChannel = peerConnection.createDataChannel("oai-events", init);
        if (dataChannel == null) throw new IllegalStateException("No se pudo crear el canal de eventos.");
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) { }
            @Override public void onStateChange() {
                if (attempt == activeAttemptId && dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) sendSessionUpdate();
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                if (attempt != activeAttemptId || closed.get()) return;
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                handleEvent(new String(bytes, StandardCharsets.UTF_8));
            }
        });
    }

    private void createOffer(final long attempt) {
        final PeerConnection currentPeer = peerConnection;
        if (currentPeer == null) { fail(Failure.NETWORK, true, "No hay PeerConnection para crear la oferta SDP."); return; }
        currentPeer.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                if (closed.get() || attempt != activeAttemptId || currentPeer != peerConnection) return;
                currentPeer.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() { scheduleOfferFallback(attempt); }
                    @Override public void onSetFailure(String error) {
                        if (attempt == activeAttemptId) fail(Failure.NETWORK, true, "No se pudo establecer la oferta SDP: " + error);
                    }
                    @Override public void onCreateSuccess(SessionDescription ignored) { }
                    @Override public void onCreateFailure(String error) { }
                }, offer);
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) {
                if (attempt == activeAttemptId) fail(Failure.NETWORK, true, "No se pudo crear la oferta SDP: " + error);
            }
            @Override public void onSetFailure(String error) { }
        }, new MediaConstraints());
    }

    /** Si la recolección ICE no informa COMPLETE a tiempo, se envía la oferta con lo reunido hasta ahora. */
    private void scheduleOfferFallback(final long attempt) {
        Runnable old = offerFallbackTimeout;
        if (old != null) mainHandler.removeCallbacks(old);
        Runnable timeout = () -> {
            if (!closed.get() && attempt == activeAttemptId && offerSubmitted.compareAndSet(false, true)) {
                executeWorker(() -> exchangeOffer(attempt));
            }
        };
        offerFallbackTimeout = timeout;
        mainHandler.postDelayed(timeout, OFFER_GATHER_TIMEOUT_MS);
    }

    private void exchangeOffer(long attempt) {
        final PeerConnection currentPeer = peerConnection;
        if (closed.get() || attempt != activeAttemptId || currentPeer == null) return;
        try {
            SessionDescription local = currentPeer.getLocalDescription();
            if (local == null || local.description == null || local.description.isEmpty())
                throw new IOException("La oferta SDP local está vacía.");
            HttpURLConnection connection = (HttpURLConnection) new URL(OPENAI_CALLS_URL).openConnection();
            activeHttpConnection = connection;
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(HTTP_TIMEOUT_MS);
                connection.setReadTimeout(HTTP_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + exchangeSecret);
                connection.setRequestProperty("Content-Type", "application/sdp");
                connection.setRequestProperty("Accept", "application/sdp");
                byte[] offer = local.description.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(offer.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(offer); }
                int status = connection.getResponseCode();
                InputStream response = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String answer = response == null ? "" : readLimited(response);
                if (status < 200 || status >= 300) {
                    Failure failure = status == 401 || status == 403 ? Failure.AUTHENTICATION
                            : status == 429 ? Failure.RATE_LIMIT : status == 402 ? Failure.QUOTA : Failure.PROVIDER;
                    throw new ProviderHttpException(failure, status,
                            "OpenAI rechazó la negociación WebRTC (HTTP " + status + ").");
                }
                if (answer.trim().isEmpty()) throw new IOException("OpenAI devolvió una respuesta SDP vacía.");
                if (closed.get() || attempt != activeAttemptId || currentPeer != peerConnection) return;
                currentPeer.setRemoteDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        if (attempt == activeAttemptId) exchangeSecret = null;
                    }
                    @Override public void onSetFailure(String error) {
                        if (attempt == activeAttemptId) fail(Failure.NETWORK, true, "No se pudo aplicar la respuesta SDP: " + error);
                    }
                    @Override public void onCreateSuccess(SessionDescription ignored) { }
                    @Override public void onCreateFailure(String error) { }
                }, new SessionDescription(SessionDescription.Type.ANSWER, answer));
            } finally {
                connection.disconnect();
                if (activeHttpConnection == connection) activeHttpConnection = null;
            }
        } catch (ProviderHttpException error) {
            fail(error.failure, error.status == 429 || error.status >= 500, error.getMessage());
        } catch (Exception error) {
            fail(error instanceof IOException ? Failure.NETWORK : Failure.PROVIDER, true, safeMessage(error));
        }
    }

    private void sendSessionUpdate() {
        try {
            JSONObject session = new JSONObject();
            session.put("type", "realtime");
            session.put("instructions", sessionConfig.instructions.isEmpty()
                    ? "Sos Bellota, una compañera virtual conversacional. Hablá en español rioplatense natural."
                    : sessionConfig.instructions);
            session.put("output_modalities", new JSONArray().put("audio"));
            session.put("max_output_tokens", maxOutputTokens);
            JSONObject audio = new JSONObject();
            audio.put("input", new JSONObject().put("turn_detection",
                    new JSONObject().put("type", "server_vad")));
            audio.put("output", new JSONObject().put("voice",
                    sessionConfig.voice.isEmpty() ? "marin" : sessionConfig.voice));
            session.put("audio", audio);
            JSONArray tools = convertTools(sessionConfig.toolDefinitionsJson);
            if (tools.length() > 0) {
                session.put("tools", tools);
                session.put("tool_choice", "auto");
            }
            sendEvent(new JSONObject().put("type", "session.update").put("session", session));
        } catch (Exception error) {
            fail(Failure.CONFIGURATION, false, "No se pudo configurar la sesión Realtime: " + safeMessage(error));
        }
    }

    private JSONArray convertTools(String source) throws JSONException {
        JSONArray input = new JSONArray(source);
        JSONArray output = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject tool = input.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name", "").trim();
            if (name.isEmpty()) continue;
            JSONObject function = new JSONObject();
            function.put("type", "function");
            function.put("name", name);
            function.put("description", tool.optString("description", ""));
            JSONObject parameters = tool.optJSONObject("parameters");
            if (parameters == null) parameters = tool.optJSONObject("inputSchema");
            if (parameters == null) parameters = new JSONObject().put("type", "object").put("properties", new JSONObject());
            function.put("parameters", parameters);
            output.put(function);
        }
        return output;
    }

    private void handleEvent(String raw) {
        try {
            JSONObject event = new JSONObject(raw);
            String type = event.optString("type", "");
            switch (type) {
                case "input_audio_transcription.delta":
                case "conversation.item.input_audio_transcription.delta":
                    notifyUserTranscript(event.optString("delta", ""), false);
                    break;
                case "input_audio_transcription.completed":
                case "conversation.item.input_audio_transcription.completed":
                    notifyUserTranscript(event.optString("transcript", ""), true);
                    break;
                case "response.output_audio_transcript.delta":
                case "response.output_text.delta":
                    notifyAssistantTranscript(event.optString("delta", ""), false);
                    break;
                case "response.output_audio_transcript.done":
                case "response.output_text.done":
                    notifyAssistantTranscript(event.optString("transcript",
                            event.optString("text", "")), true);
                    break;
                case "response.created":
                    notifySpeaking(true);
                    break;
                case "response.done":
                    notifySpeaking(false);
                    JSONObject response = event.optJSONObject("response");
                    JSONObject usage = response == null ? null : response.optJSONObject("usage");
                    long used = usage == null ? 0L : usage.optLong("total_tokens", 0L);
                    if (used > 0L && sessionBudget.recordResponseUsage(used)) {
                        fail(Failure.QUOTA, false, "Se alcanzó el límite de consumo configurado para esta sesión.");
                        return;
                    }
                    break;
                case "response.function_call_arguments.done":
                    notifyToolCall(event.optString("call_id", ""), event.optString("name", ""),
                            event.optString("arguments", "{}"));
                    break;
                case "error":
                    JSONObject error = event.optJSONObject("error");
                    String message = error == null ? "Error de OpenAI Realtime." : error.optString("message", "Error de OpenAI Realtime.");
                    fail(Failure.PROVIDER, true, message);
                    break;
                default:
                    break;
            }
        } catch (JSONException ignored) {
            // Ignore malformed or unknown provider events without breaking the media session.
        }
    }

    private void sendEvent(JSONObject event) {
        DataChannel channel = dataChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN) return;
        byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    @Override public void sendAudio(byte[] pcm16, int offset, int length) {
        // Native WebRTC audio track captures the microphone directly. Audio is not duplicated here.
    }

    @Override public void finishAudioInput() {
        // Server VAD detects the end of each utterance from the native audio track.
    }

    @Override public void sendText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            JSONObject content = new JSONObject().put("type", "input_text").put("text", text.trim());
            JSONObject item = new JSONObject().put("type", "message").put("role", "user")
                    .put("content", new JSONArray().put(content));
            sendEvent(new JSONObject().put("type", "conversation.item.create").put("item", item));
            sendEvent(new JSONObject().put("type", "response.create"));
        } catch (JSONException ignored) { }
    }

    @Override public void sendToolResult(String callId, String result, boolean isError) {
        if (callId == null || callId.trim().isEmpty()) return;
        try {
            JSONObject item = new JSONObject().put("type", "function_call_output")
                    .put("call_id", callId).put("output", result == null ? "" : result);
            sendEvent(new JSONObject().put("type", "conversation.item.create").put("item", item));
            sendEvent(new JSONObject().put("type", "response.create"));
        } catch (JSONException ignored) { }
    }

    @Override public void interrupt() {
        try { sendEvent(new JSONObject().put("type", "response.cancel")); }
        catch (JSONException ignored) { }
    }

    @Override public void disconnect() {
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        notifyState(State.CLOSING, "Cerrando sesión WebRTC.");
        cancelPendingCallbacks();
        HttpURLConnection connection = activeHttpConnection;
        if (connection != null) connection.disconnect();
        releaseResources();
        notifyState(State.CLOSED, "Sesión WebRTC cerrada.");
        shutdownWorker();
    }

    private synchronized void releaseResources() {
        exchangeSecret = null;
        try { if (dataChannel != null) { dataChannel.unregisterObserver(); dataChannel.close(); dataChannel.dispose(); } }
        catch (RuntimeException ignored) { }
        dataChannel = null;
        try { if (peerConnection != null) { peerConnection.close(); peerConnection.dispose(); } }
        catch (RuntimeException ignored) { }
        peerConnection = null;
        try { if (localAudioTrack != null) localAudioTrack.dispose(); } catch (RuntimeException ignored) { }
        localAudioTrack = null;
        try { if (audioSource != null) audioSource.dispose(); } catch (RuntimeException ignored) { }
        audioSource = null;
        try { if (factory != null) factory.dispose(); } catch (RuntimeException ignored) { }
        factory = null;
        try { if (audioDeviceModule != null) audioDeviceModule.release(); } catch (RuntimeException ignored) { }
        audioDeviceModule = null;
        if (audioModeChanged && audioManager != null) {
            try {
                audioManager.setSpeakerphoneOn(previousSpeakerphone);
                audioManager.setMode(previousAudioMode);
            } catch (RuntimeException ignored) { }
        }
        audioModeChanged = false;
    }

    private synchronized void fail(Failure failure, boolean recoverable, String detail) {
        if (closed.get() || retryScheduled.get()) return;
        if (recoverable && retryCount.getAndIncrement() < MAX_RECOVERY_ATTEMPTS) {
            retryScheduled.set(true);
            connected = false;
            cancelIceDisconnectTimeout();
            HttpURLConnection connection = activeHttpConnection;
            if (connection != null) connection.disconnect();
            releaseResources();
            offerSubmitted.set(false);
            notifyState(State.RECONNECTING, "Fallo transitorio. Se realizará un único intento de recuperación.");
            long delay = failure == Failure.RATE_LIMIT ? 5000L : 1200L;
            mainHandler.removeCallbacks(negotiationTimeout);
            mainHandler.postDelayed(negotiationTimeout, 60000L);
            mainHandler.postDelayed(() -> {
                retryScheduled.set(false);
                if (!closed.get()) executeWorker(() -> establishSession(sessionConfig));
            }, delay);
            return;
        }
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        cancelPendingCallbacks();
        HttpURLConnection connection = activeHttpConnection;
        if (connection != null) connection.disconnect();
        releaseResources();
        shutdownWorker();
        Listener current = listener;
        if (current != null) {
            try { current.onError(failure, recoverable, detail); } catch (RuntimeException ignored) { }
        }
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Bellota-Realtime-WebRTC");
            thread.setDaemon(true);
            return thread;
        });
    }

    private synchronized void ensureWorker() {
        if (worker == null || worker.isShutdown()) worker = newWorker();
    }

    private void executeWorker(Runnable task) {
        ExecutorService executor = worker;
        if (executor == null || executor.isShutdown() || closed.get()) return;
        try { executor.execute(task); } catch (RejectedExecutionException ignored) { }
    }

    private void shutdownWorker() {
        ExecutorService executor = worker;
        if (executor != null) executor.shutdownNow();
    }

    private void scheduleIceDisconnectTimeout() {
        cancelIceDisconnectTimeout();
        Runnable timeout = () -> {
            if (!closed.get() && !connected)
                fail(Failure.NETWORK, true, "La conexión ICE no se recuperó dentro del período de gracia.");
        };
        iceDisconnectTimeout = timeout;
        mainHandler.postDelayed(timeout, 10000L);
    }

    private void cancelIceDisconnectTimeout() {
        Runnable timeout = iceDisconnectTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        iceDisconnectTimeout = null;
    }

    private void scheduleSessionTimeout() {
        Runnable old = sessionTimeout;
        if (old != null) mainHandler.removeCallbacks(old);
        Runnable timeout = () -> {
            if (!closed.get()) fail(Failure.SESSION_EXPIRED, false,
                    "La sesión alcanzó su duración máxima y se cerrará para controlar el consumo.");
        };
        sessionTimeout = timeout;
        mainHandler.postDelayed(timeout, sessionMaxDurationMs);
    }

    private void cancelPendingCallbacks() {
        mainHandler.removeCallbacks(negotiationTimeout);
        cancelIceDisconnectTimeout();
        Runnable timeout = sessionTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        Runnable offerTimeout = offerFallbackTimeout;
        if (offerTimeout != null) mainHandler.removeCallbacks(offerTimeout);
        offerFallbackTimeout = null;
        sessionTimeout = null;
    }

    private void notifyState(State state, String detail) {
        Listener current = listener;
        if (current != null) { try { current.onStateChanged(state, detail); } catch (RuntimeException ignored) { } }
    }
    private void notifyUserTranscript(String text, boolean isFinal) {
        Listener current = listener;
        if (current != null && text != null && !text.isEmpty()) { try { current.onUserTranscript(text, isFinal); } catch (RuntimeException ignored) { } }
    }
    private void notifyAssistantTranscript(String text, boolean isFinal) {
        Listener current = listener;
        if (current != null && text != null && !text.isEmpty()) { try { current.onAssistantTranscript(text, isFinal); } catch (RuntimeException ignored) { } }
    }
    private void notifySpeaking(boolean speaking) {
        Listener current = listener;
        if (current != null) { try { current.onAssistantSpeaking(speaking); } catch (RuntimeException ignored) { } }
    }
    private void notifyToolCall(String callId, String name, String args) {
        Listener current = listener;
        if (current != null && !callId.isEmpty() && !name.isEmpty()) { try { current.onToolCall(callId, name, args); } catch (RuntimeException ignored) { } }
    }

    private static Failure mapBackendFailure(BellotaBackendClient.BackendException error) {
        if (error.httpStatus == 401 || error.httpStatus == 403) return Failure.AUTHENTICATION;
        if (error.httpStatus == 402 || "provider_quota".equals(error.code)) return Failure.QUOTA;
        if (error.httpStatus == 429) return Failure.RATE_LIMIT;
        if (error.httpStatus >= 500) return Failure.PROVIDER;
        return Failure.CONFIGURATION;
    }
    private static String readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0, read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SDP_BYTES) throw new IOException("Respuesta SDP demasiado grande.");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class ProviderHttpException extends Exception {
        final Failure failure;
        final int status;
        ProviderHttpException(Failure failure, int status, String message) {
            super(message); this.failure = failure; this.status = status;
        }
    }
}
