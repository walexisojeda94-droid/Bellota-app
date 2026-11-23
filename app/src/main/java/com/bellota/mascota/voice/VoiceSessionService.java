package com.bellota.mascota.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ConnectivityManager.NetworkCallback;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.bellota.mascota.R;
import com.bellota.mascota.voice.auth.SecureTokenStore;
import com.bellota.mascota.voice.provider.OpenAIRealtimeWebRtcProvider;
import com.bellota.mascota.voice.provider.VoiceProvider;
import com.bellota.mascota.voice.provider.VoiceProviderManager;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Owns the online voice session independently of MainActivity.
 * The foreground notification makes microphone use visible and provides an explicit stop action.
 */
public final class VoiceSessionService extends Service {
    public static final String ACTION_START = "com.bellota.mascota.voice.session.START";
    public static final String ACTION_STOP = "com.bellota.mascota.voice.session.STOP";
    public static final String ACTION_TOOL_RESULT = "com.bellota.mascota.voice.session.TOOL_RESULT";
    public static final String ACTION_EVENT = "com.bellota.mascota.voice.session.EVENT";
    public static final String EXTRA_BACKEND_URL = "backend_url";
    public static final String EXTRA_INSTRUCTIONS = "instructions";
    public static final String EXTRA_TOOLS = "tools";
    public static final String EXTRA_VOICE = "voice";
    public static final String EXTRA_LOCALE = "locale";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_FINAL = "final";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_TOOL_NAME = "tool_name";
    public static final String EXTRA_ARGUMENTS = "arguments";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_IS_ERROR = "is_error";

    private static final String CHANNEL_ID = "bellota_online_voice";
    private static final int NOTIFICATION_ID = 2402;
    private static final String PREFS = "bellota_voice_session";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_PENDING_CALLS = "pending_tool_calls";
    private static final long[] RETRY_DELAYS_MS = {1500L, 3000L, 6000L, 12000L, 20000L, 30000L, 60000L};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> pendingToolCalls = new HashSet<>();
    private volatile VoiceProviderManager manager;
    private ConnectivityManager connectivityManager;
    private NetworkCallback networkCallback;
    private String backendUrl;
    private String instructions;
    private String toolsJson;
    private String voice;
    private String locale;
    private int retryIndex;
    private volatile boolean started;
    private boolean connecting;
    private boolean foreground;

    private final Runnable retryRunnable = () -> {
        if (started && hasInternet()) connectFreshManager();
    };

    public static void start(Context context, String backendUrl, String instructions,
                             String toolsJson, String voice, String locale) {
        Intent intent = new Intent(context, VoiceSessionService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_BACKEND_URL, backendUrl);
        intent.putExtra(EXTRA_INSTRUCTIONS, instructions);
        intent.putExtra(EXTRA_TOOLS, toolsJson);
        intent.putExtra(EXTRA_VOICE, voice);
        intent.putExtra(EXTRA_LOCALE, locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void restoreIfActive(Context context) {
        if (!isActive(context)) return;
        Intent intent = new Intent(context, VoiceSessionService.class);
        intent.setAction("com.bellota.mascota.voice.session.RESTORE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, VoiceSessionService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static boolean isActive(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
    }

    public static boolean isToolCallCurrent(Context context, String callId) {
        if (callId == null || callId.trim().isEmpty() || !isActive(context)) return false;
        Set<String> pending = context.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_PENDING_CALLS, null);
        return pending != null && pending.contains(callId);
    }

    public static void sendToolResult(Context context, String callId, String result, boolean isError) {
        Intent intent = new Intent(context, VoiceSessionService.class);
        intent.setAction(ACTION_TOOL_RESULT);
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_RESULT, result);
        intent.putExtra(EXTRA_IS_ERROR, isError);
        context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOOL_RESULT.equals(action)) {
            String callId = intent == null ? null : intent.getStringExtra(EXTRA_CALL_ID);
            boolean pending;
            synchronized (pendingToolCalls) { pending = callId != null && pendingToolCalls.remove(callId); }
            removePersistedPendingCall(callId);
            if (pending && manager != null
                    && manager.isToolCallCurrent(callId)) {
                manager.sendToolResult(callId, intent.getStringExtra(EXTRA_RESULT),
                        intent.getBooleanExtra(EXTRA_IS_ERROR, false));
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_START.equals(action)) {
            backendUrl = intent.getStringExtra(EXTRA_BACKEND_URL);
            instructions = intent.getStringExtra(EXTRA_INSTRUCTIONS);
            toolsJson = intent.getStringExtra(EXTRA_TOOLS);
            voice = intent.getStringExtra(EXTRA_VOICE);
            locale = intent.getStringExtra(EXTRA_LOCALE);
            try { persistConfig(); }
            catch (Exception storageError) {
                emit("state", VoiceProvider.State.CLOSED.name(),
                        "No se pudo proteger la configuración de recuperación. La sesión online no se inició.", null);
                stopSelf();
                return START_NOT_STICKY;
            }
        } else if (backendUrl == null) {
            restoreConfig();
        }
        if (!hasConfig()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        started = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
        ensureForeground();
        VoiceRecognitionService.stop(this);
        if (manager == null) connectFreshManager();
        return START_STICKY;
    }

    private boolean hasConfig() {
        return backendUrl != null && backendUrl.startsWith("https://")
                && instructions != null && toolsJson != null && voice != null && locale != null;
    }

    private void persistConfig() throws Exception {
        new VoiceSessionConfigStore(this).save(new VoiceSessionConfigStore.Config(
                backendUrl, instructions, toolsJson, voice, locale));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
    }

    private void restoreConfig() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)) return;
        VoiceSessionConfigStore.Config config = new VoiceSessionConfigStore(this).load();
        if (config == null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
            return;
        }
        backendUrl = config.backendUrl;
        instructions = config.instructions;
        toolsJson = config.toolsJson;
        voice = config.voice;
        locale = config.locale;
    }

    private void connectFreshManager() {
        if (!started || !hasInternet()) return;
        handler.removeCallbacks(retryRunnable);
        VoiceProviderManager old = manager;
        manager = null;
        if (old != null) old.disconnect();
        synchronized (pendingToolCalls) { pendingToolCalls.clear(); }
        clearPersistedPendingCalls();

        connecting = true;
        OpenAIRealtimeWebRtcProvider provider = new OpenAIRealtimeWebRtcProvider(
                getApplicationContext(), backendUrl, new SecureTokenStore(getApplicationContext()));
        final VoiceProviderManager[] holder = new VoiceProviderManager[1];
        VoiceProviderManager next = new VoiceProviderManager(provider, null,
                new VoiceProviderManager.Listener() {
                    @Override public void onStateChanged(VoiceProvider.State state, String providerId, String detail) {
                        if (manager != holder[0]) return;
                        emit("state", state.name(), detail, providerId);
                        if (state == VoiceProvider.State.RECONNECTING) {
                            synchronized (pendingToolCalls) { pendingToolCalls.clear(); }
                            clearPersistedPendingCalls();
                        }
                        updateNotification(detail == null ? state.name() : detail);
                        if (state == VoiceProvider.State.CONNECTED) { retryIndex = 0; connecting = false; }
                        else if (state == VoiceProvider.State.FALLBACK_LOCAL) connecting = false;
                    }
                    @Override public void onUserTranscript(String text, boolean isFinal) {
                        emitText("user_transcript", text, isFinal);
                    }
                    @Override public void onAssistantTranscript(String text, boolean isFinal) {
                        emitText("assistant_transcript", text, isFinal);
                    }
                    @Override public void onAudioOutput(byte[] audio, int sampleRateHz, int channels) { }
                    @Override public void onAssistantSpeaking(boolean speaking) {
                        emit("speaking", Boolean.toString(speaking), null, null);
                    }
                    @Override public void onToolCall(String callId, String name, String argumentsJson) {
                        if (manager != holder[0] || !manager.isToolCallCurrent(callId)) return;
                        synchronized (pendingToolCalls) { pendingToolCalls.add(callId); }
                        persistPendingCall(callId);
                        Intent event = baseEvent("tool_call");
                        event.putExtra(EXTRA_CALL_ID, callId);
                        event.putExtra(EXTRA_TOOL_NAME, name);
                        event.putExtra(EXTRA_ARGUMENTS, argumentsJson);
                        sendBroadcast(event);
                        handler.postDelayed(() -> {
                            boolean stillPending;
                            synchronized (pendingToolCalls) { stillPending = pendingToolCalls.remove(callId); }
                            removePersistedPendingCall(callId);
                            VoiceProviderManager current = manager;
                            if (stillPending && current != null && current.isToolCallCurrent(callId))
                                current.sendToolResult(callId,
                                        "La interfaz de Bellota no está disponible para ejecutar esta acción en este momento.", true);
                        }, 10000L);
                    }
                    @Override public void onLocalFallbackRequested(VoiceProvider.Failure reason, String detail) {
                        emit("state", VoiceProvider.State.RECONNECTING.name(),
                                "Conexión interrumpida. Se reintentará automáticamente.", provider.getProviderId());
                        if (reason == VoiceProvider.Failure.AUTHENTICATION
                                || reason == VoiceProvider.Failure.QUOTA
                                || reason == VoiceProvider.Failure.CONFIGURATION) {
                            handler.post(() -> { emit("state", VoiceProvider.State.CLOSED.name(),
                                    "La sesión online requiere revisar la vinculación o el límite de consumo. Se reanudó la voz local.", null);
                                stopSession(true); stopSelf(); });
                        } else scheduleRetry();
                    }
                });
        holder[0] = next;
        manager = next;
        VoiceProvider.SessionConfig config = new VoiceProvider.SessionConfig(
                locale, voice, instructions, toolsJson, 48000);
        next.connect(config);
    }

    private void scheduleRetry() {
        if (!started) return;
        handler.removeCallbacks(retryRunnable);
        long delay = RETRY_DELAYS_MS[Math.min(retryIndex, RETRY_DELAYS_MS.length - 1)];
        retryIndex = Math.min(retryIndex + 1, RETRY_DELAYS_MS.length - 1);
        handler.postDelayed(retryRunnable, delay);
    }

    private boolean hasInternet() {
        if (connectivityManager == null) return true;
        if (Build.VERSION.SDK_INT >= 23) {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null
                    : connectivityManager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || Build.VERSION.SDK_INT < 24) return;
        networkCallback = new NetworkCallback() {
            @Override public void onAvailable(Network network) {
                handler.post(() -> {
                    if (started && (manager == null || (!manager.isConnected() && !connecting))) {
                        retryIndex = 0;
                        handler.removeCallbacks(retryRunnable);
                        handler.postDelayed(retryRunnable, 500L);
                    }
                });
            }
            @Override public void onLost(Network network) {
                emit("state", VoiceProvider.State.RECONNECTING.name(),
                        "Se perdió Internet. Bellota reintentará al recuperar la conexión.", null);
            }
        };
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback); }
        catch (RuntimeException ignored) { networkCallback = null; }
    }

    private void stopSession(boolean resumeLocal) {
        started = false;
        connecting = false;
        handler.removeCallbacksAndMessages(null);
        if (manager != null) manager.disconnect();
        manager = null;
        synchronized (pendingToolCalls) { pendingToolCalls.clear(); }
        clearPersistedPendingCalls();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        if (resumeLocal) {
            new VoiceSessionConfigStore(this).clear();
            VoiceRecognitionService.resume(this);
        }
        if (foreground) stopForeground(true);
        foreground = false;
    }

    private void persistPendingCall(String callId) {
        if (callId == null || callId.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> copy = new HashSet<>(prefs.getStringSet(KEY_PENDING_CALLS, new HashSet<>()));
        copy.add(callId);
        prefs.edit().putStringSet(KEY_PENDING_CALLS, copy).apply();
    }

    private void removePersistedPendingCall(String callId) {
        if (callId == null || callId.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> copy = new HashSet<>(prefs.getStringSet(KEY_PENDING_CALLS, new HashSet<>()));
        copy.remove(callId);
        prefs.edit().putStringSet(KEY_PENDING_CALLS, copy).apply();
    }

    private void clearPersistedPendingCalls() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_CALLS).apply();
    }

    private void ensureForeground() {
        if (foreground) return;
        Intent stop = new Intent(this, VoiceSessionService.class);
        stop.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getService(this, 1, stop, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Bellota mantiene la voz online")
                .setContentText("Micrófono activo. Usá Detener para cerrar la sesión.")
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Detener", pending).build();
        startForeground(NOTIFICATION_ID, notification);
        foreground = true;
    }

    private void updateNotification(String detail) {
        if (!foreground) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent stop = new Intent(this, VoiceSessionService.class);
        stop.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getService(this, 1, stop, flags);
        manager.notify(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Bellota mantiene la voz online")
                .setContentText(detail == null ? "Micrófono activo." : detail)
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Detener", pending).build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Voz online de Bellota", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Indica cuándo Bellota mantiene activa una conversación online.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Intent baseEvent(String event) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, event);
        return intent;
    }

    private void emit(String event, String state, String detail, String provider) {
        Intent intent = baseEvent(event);
        if (state != null) intent.putExtra(EXTRA_STATE, state);
        if (detail != null) intent.putExtra(EXTRA_DETAIL, detail);
        if (provider != null) intent.putExtra(EXTRA_PROVIDER, provider);
        sendBroadcast(intent);
    }

    private void emitText(String event, String text, boolean isFinal) {
        if (text == null || text.trim().isEmpty()) return;
        Intent intent = baseEvent(event);
        intent.putExtra(EXTRA_TEXT, text);
        intent.putExtra(EXTRA_FINAL, isFinal);
        sendBroadcast(intent);
    }

    @Override public void onDestroy() {
        // Unexpected service teardown must not erase the encrypted restart configuration.
        // START_STICKY or the next Activity resume can recreate the owner.
        if (started) {
            started = false;
            connecting = false;
            handler.removeCallbacksAndMessages(null);
            if (manager != null) manager.disconnect();
            manager = null;
            synchronized (pendingToolCalls) { pendingToolCalls.clear(); }
            clearPersistedPendingCalls();
        }
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
