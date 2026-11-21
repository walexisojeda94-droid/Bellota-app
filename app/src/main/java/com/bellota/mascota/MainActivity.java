package com.bellota.mascota;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.bellota.mascota.core.DebugLog;
import com.bellota.mascota.core.PetLife;
import com.bellota.mascota.core.PetMemory;
import com.bellota.mascota.core.AutonomousBehavior;
import com.bellota.mascota.local.OfflineReplies;
import com.bellota.mascota.local.LocalConversationEngine;
import com.bellota.mascota.local.Reply;
import com.bellota.mascota.ui.EyePetView;
import com.bellota.mascota.voice.ContinuousVoiceCommands;
import com.bellota.mascota.voice.LocalVoice;
import com.bellota.mascota.voice.provider.VoiceProvider;
import com.bellota.mascota.voice.provider.VoiceProviderManager;
import com.bellota.mascota.voice.provider.OpenAIRealtimeWebRtcProvider;
import com.bellota.mascota.voice.auth.SecureTokenStore;
import com.bellota.mascota.weather.WeatherService;
import org.json.JSONArray;
import org.json.JSONObject;
import com.bellota.mascota.local.ConversationMemory;
import com.bellota.mascota.local.PetVoiceLines;
import com.bellota.mascota.ui.PlayfieldView;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int MICROPHONE_PERMISSION = 41;
    private static final int LOCATION_PERMISSION = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OfflineReplies offlineReplies = new OfflineReplies();
    private LocalConversationEngine conversationEngine;
    private EyePetView eyes;
    private FrameLayout petRoot;
    private LocalVoice localVoice;
    private ContinuousVoiceCommands voiceCommands;
    private VoiceProviderManager onlineVoiceManager;
    private boolean onlineVoiceConnecting;
    private PetLife petLife;
    private AutonomousBehavior autonomy;
    private PetMemory petMemory;
    private long touchStartedAt;
    private float touchStartX, touchStartY, touchPressure;
    private boolean paused;
    private long lastVoiceDiagnosticToastAt;
    private boolean soundEnabled = true;
    private boolean waitingForWeatherPermission;
    private PlayfieldView playfield;
    private final PetVoiceLines lines = new PetVoiceLines();
    private final java.util.Random rnd = new java.util.Random();
    private long busyUntil;
    private Runnable pendingSleepTask;
    private String pendingSleepReason;
    private boolean hideSeekActive;
    private int hideSeekToken;
    private long lastMumbleAt;
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private boolean lightTheme;
    private long lastThemeChange;
    private final Runnable autonomousRoutine = new Runnable() {
        @Override public void run() {
            long delay = 14000L + (long) (Math.random() * 12000L);
            if (eyes != null && autonomy != null && !isFinishing() && !paused) {
                if (autonomy.isSleeping()) delay = 60000L; // dormida solo revisa si ya recuperó fuerzas
                runAutonomousTick();
            }
            handler.postDelayed(this, delay);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        petLife = new PetLife(this);
        autonomy = new AutonomousBehavior(this);
        petMemory = new PetMemory(this);
        conversationEngine = new LocalConversationEngine(this);
        petMemory.remember("inicio", "Bellota inició una nueva sesión.");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildPetScreen();
        applySleepVisuals();
        localVoice = new LocalVoice(this, (ready, detail) -> { });
        localVoice.setOnSpeechFinished(() -> {
            if (!paused && !isFinishing() && voiceCommands != null) voiceCommands.start();
        });
        setupAmbientTheme();
        requestMicrophonePermission();
        handler.postDelayed(autonomousRoutine, 1800L);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.Drawable makeAuthButtonBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(0xCC173B3B);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), 0xFF00BFA6);
        return drawable;
    }

    /** Muestra el diálogo de vinculación. La voz local continúa funcionando sin cambios. */
    private void showOnlinePairingDialog() {
        final String backendUrl = getString(R.string.backend_base_url).trim();
        final boolean configured = backendUrl.startsWith("https://");
        final SecureTokenStore tokenStore = new SecureTokenStore(getApplicationContext());
        final boolean paired = tokenStore.hasRefreshToken();

        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(12), dp(22), dp(4));

        android.widget.TextView explanation = new android.widget.TextView(this);
        explanation.setText(paired
                ? "Este dispositivo ya tiene una vinculación segura. Podés iniciar o detener la conversación online."
                : "Vinculá Bellota con un código de un solo uso. El código vence a los 10 minutos.");
        explanation.setTextColor(0xFFE7EEEE);
        explanation.setTextSize(14f);
        panel.addView(explanation);

        android.widget.EditText codeInput = new android.widget.EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setHint("Código de vinculación");
        codeInput.setTextColor(0xFFFFFFFF);
        codeInput.setHintTextColor(0xFF9BAEAE);
        codeInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setLetterSpacing(0.12f);
        codeInput.setVisibility(paired ? View.GONE : View.VISIBLE);
        android.widget.LinearLayout.LayoutParams codeParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        codeParams.topMargin = dp(14);
        panel.addView(codeInput, codeParams);

        android.widget.TextView status = new android.widget.TextView(this);
        status.setText(!configured
                ? "El backend todavía no está desplegado/configurado. La voz local sigue disponible."
                : paired ? "Dispositivo vinculado. La conexión online todavía no está iniciada."
                : "Servicio configurado. La vinculación no modifica la voz local.");
        status.setTextColor(configured ? 0xFF9ED8C8 : 0xFFFFD18A);
        status.setTextSize(12f);
        android.widget.LinearLayout.LayoutParams statusParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        panel.addView(status, statusParams);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Voz online de Bellota")
                .setView(panel)
                .setNegativeButton("Cerrar", null)
                .setPositiveButton(paired ? "Desvincular" : "Vincular", null)
                .setNeutralButton((onlineVoiceConnecting || (onlineVoiceManager != null && onlineVoiceManager.isConnected())) ? "Desconectar" : "Conectar", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.widget.Button primary = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button online = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
            primary.setEnabled(configured);
            online.setEnabled(configured && paired);

            primary.setOnClickListener(v -> {
                if (paired) {
                    if (onlineVoiceManager != null) disconnectOnlineVoice(true);
                    new Thread(tokenStore::clear, "Bellota-Unpair").start();
                    status.setText("Vinculación local eliminada. Para revocar el dispositivo en el servidor se requiere la función de revocación del backend.");
                    status.setTextColor(0xFFFFD18A);
                    primary.setEnabled(false);
                    online.setEnabled(false);
                    return;
                }
                String code = codeInput.getText().toString().trim();
                if (!code.matches("[A-Za-z0-9_-]{6,32}")) {
                    codeInput.setError("Ingresá el código recibido.");
                    return;
                }
                primary.setEnabled(false);
                status.setText("Vinculando dispositivo...");
                status.setTextColor(0xFF9ED8C8);
                new Thread(() -> {
                    String result;
                    boolean success = false;
                    try {
                        com.bellota.mascota.voice.auth.BellotaBackendClient client =
                                new com.bellota.mascota.voice.auth.BellotaBackendClient(backendUrl, tokenStore);
                        client.pair(code, android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                        result = "Dispositivo vinculado. Ya podés iniciar la voz online.";
                        success = true;
                    } catch (com.bellota.mascota.voice.auth.BellotaBackendClient.BackendException error) {
                        result = error.getMessage();
                    } catch (Exception error) {
                        result = "No se pudo completar la vinculación. Revisá la conexión e intentá nuevamente.";
                    }
                    final String message = result;
                    final boolean successResult = success;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        status.setText(message);
                        status.setTextColor(successResult ? 0xFF9ED8C8 : 0xFFFFA6A6);
                        if (successResult) {
                            codeInput.setVisibility(View.GONE);
                            explanation.setText("Este dispositivo ya tiene una vinculación segura.");
                            primary.setText("Vinculado");
                            primary.setEnabled(false);
                            online.setEnabled(true);
                        } else {
                            primary.setEnabled(true);
                        }
                    });
                }, "Bellota-Pairing").start();
            });

            online.setOnClickListener(v -> {
                if (onlineVoiceConnecting || (onlineVoiceManager != null && onlineVoiceManager.isConnected())) {
                    disconnectOnlineVoice(true);
                    status.setText("Voz online desconectada. Se reanudó la escucha local.");
                    online.setText("Conectar");
                    return;
                }
                online.setEnabled(false);
                status.setText("Iniciando OpenAI Realtime por WebRTC...");
                status.setTextColor(0xFF9ED8C8);
                connectOnlineVoice(backendUrl, status, online);
            });
        });
        dialog.show();
    }

    private void connectOnlineVoice(String backendUrl, android.widget.TextView status,
                                    android.widget.Button onlineButton) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Falta el permiso de micrófono.");
            onlineButton.setEnabled(true);
            return;
        }
        if (isAsleep() || !soundEnabled) {
            status.setText("Bellota debe estar despierta y con el sonido habilitado.");
            onlineButton.setEnabled(true);
            return;
        }
        if (voiceCommands != null) voiceCommands.stop();
        if (localVoice != null) localVoice.stop();
        try {
            SecureTokenStore store = new SecureTokenStore(getApplicationContext());
            OpenAIRealtimeWebRtcProvider provider =
                    new OpenAIRealtimeWebRtcProvider(getApplicationContext(), backendUrl, store);
            onlineVoiceManager = new VoiceProviderManager(provider, null, new VoiceProviderManager.Listener() {
                @Override public void onStateChanged(VoiceProvider.State state, String providerId, String detail) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        status.setText(detail == null ? state.name() : detail);
                        if (state == VoiceProvider.State.CONNECTED) {
                            onlineVoiceConnecting = false;
                            onlineButton.setText("Desconectar");
                            onlineButton.setEnabled(true);
                        } else if (state == VoiceProvider.State.FALLBACK_LOCAL
                                || state == VoiceProvider.State.CLOSED) {
                            onlineVoiceConnecting = false;
                            onlineButton.setText("Conectar");
                            onlineButton.setEnabled(true);
                        }
                    });
                }
                @Override public void onUserTranscript(String text, boolean isFinal) {
                    if (!isFinal || text == null || text.trim().isEmpty()) return;
                    runOnUiThread(() -> {
                        if (petMemory != null) petMemory.remember("voz_usuario", text);
                        if (!isAsleep()) {
                            userInteracted("attention", true);
                            if (eyes != null) eyes.reactToAttention();
                        }
                    });
                }
                @Override public void onAssistantTranscript(String text, boolean isFinal) {
                    if (isFinal && petMemory != null && text != null && !text.trim().isEmpty())
                        petMemory.remember("voz_bellota", text);
                }
                @Override public void onAudioOutput(byte[] pcm16, int sampleRateHz, int channels) {
                    // WebRTC reproduce el audio remoto directamente mediante su dispositivo de audio nativo.
                }
                @Override public void onAssistantSpeaking(boolean speaking) {
                    runOnUiThread(() -> {
                        if (eyes != null && !isAsleep())
                            eyes.setMood(speaking ? EyePetView.Mood.THINKING : EyePetView.Mood.CALM);
                    });
                }
                @Override public void onToolCall(String callId, String name, String argumentsJson) {
                    new Thread(() -> {
                        try {
                            String result = executeBellotaTool(name, new JSONObject(argumentsJson));
                            VoiceProviderManager manager = onlineVoiceManager;
                            if (manager != null) manager.sendToolResult(callId, result, false);
                        } catch (Exception error) {
                            VoiceProviderManager manager = onlineVoiceManager;
                            if (manager != null) manager.sendToolResult(callId,
                                    error.getMessage() == null ? "No se pudo ejecutar la herramienta." : error.getMessage(), true);
                        }
                    }, "Bellota-ToolCall").start();
                }
                @Override public void onLocalFallbackRequested(VoiceProvider.Failure reason, String detail) {
                    runOnUiThread(() -> {
                        onlineVoiceConnecting = false;
                        onlineVoiceManager = null;
                        if (!paused && !isFinishing()) startLocalRecognizer();
                        status.setText("OpenAI no está disponible. Bellota volvió a la voz local: " + detail);
                        onlineButton.setText("Conectar");
                        onlineButton.setEnabled(true);
                    });
                }
            });
            onlineVoiceConnecting = true;
            JSONArray tools = buildBellotaTools();
            VoiceProvider.SessionConfig config = new VoiceProvider.SessionConfig(
                    "es-AR", "marin",
                    "Sos Bellota, una compañera virtual con personalidad propia. Hablá en español rioplatense natural, con respuestas conversacionales y concisas. Conservá continuidad y contexto. No describas acciones que no realizaste.",
                    tools.toString(), 48000);
            onlineVoiceManager.connect(config);
        } catch (Exception error) {
            onlineVoiceConnecting = false;
            onlineVoiceManager = null;
            startLocalRecognizer();
            status.setText("No se pudo iniciar OpenAI Realtime: " + error.getMessage());
            onlineButton.setEnabled(true);
        }
    }

    private void disconnectOnlineVoice(boolean resumeLocal) {
        VoiceProviderManager manager = onlineVoiceManager;
        onlineVoiceManager = null;
        onlineVoiceConnecting = false;
        if (manager != null) manager.disconnect();
        if (resumeLocal && !paused && !isFinishing()) startLocalRecognizer();
    }

    private void buildPetScreen() {
        FrameLayout root = new FrameLayout(this);
        petRoot = root;
        root.setBackgroundColor(0xFF080D10);
        eyes = new EyePetView(this);
        eyes.setLightTheme(false);
        eyes.setOnClickListener(v -> { }); // la reacción al toque se resuelve en ACTION_UP (onPetTouched)
        eyes.setOnTouchListener((view, event) -> {
            if (event.getPointerCount() > 1) {
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && petMemory != null)
                    petMemory.remember("contacto_multiple", "Bellota recibió un toque con varios dedos.");
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchStartedAt = System.currentTimeMillis();
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchPressure = event.getPressure();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                long duration = System.currentTimeMillis() - touchStartedAt;
                float distance = (float) Math.hypot(event.getX() - touchStartX, event.getY() - touchStartY);
                if (duration >= android.view.ViewConfiguration.getLongPressTimeout()) {
                    // La pulsación prolongada no ejecuta acciones ocultas.
                } else {
                    boolean stroke = distance > 24f || duration > 420L;
                    boolean firm = !stroke && touchPressure >= 0.72f && duration < 190L;
                    onPetTouched(firm ? "rough_touch" : "caress", firm);
                    view.performClick();
                }
                return true;
            }
            return true;
        });
        int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.42f);
        int height = (int)(getResources().getDisplayMetrics().heightPixels * 0.34f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = (getResources().getDisplayMetrics().widthPixels - width) / 2;
        params.topMargin = (getResources().getDisplayMetrics().heightPixels - height) / 2;
        root.addView(eyes, params);
        playfield = new PlayfieldView(this);
        playfield.setListener(new PlayfieldView.Listener() {
            @Override public void onAttention(float x, float y) { if (eyes != null) eyes.lookAtParentPoint(x, y); }
            @Override public void onEvent(PlayfieldView.Game game, String event) { handleGameEvent(game, event); }
            @Override public void onFinished(PlayfieldView.Game game) { finishGame(); }
        });
        root.addView(playfield, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Acceso discreto a la vinculación online. No modifica ni interrumpe la voz local.
        android.widget.TextView aiButton = new android.widget.TextView(this);
        aiButton.setText("IA");
        aiButton.setTextColor(0xFFE8F3F2);
        aiButton.setTextSize(12f);
        aiButton.setGravity(android.view.Gravity.CENTER);
        aiButton.setBackground(makeAuthButtonBackground());
        aiButton.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams aiParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        aiParams.topMargin = dp(12);
        aiParams.rightMargin = dp(12);
        root.addView(aiButton, aiParams);
        aiButton.setOnClickListener(v -> showOnlinePairingDialog());

        setContentView(root);
    }

    // ---------------------------------------------------------------------------
    // Rutina autónoma: sueño, escalera del aburrimiento y juegos
    // ---------------------------------------------------------------------------

    private boolean isAsleep() { return autonomy != null && autonomy.isSleeping(); }

    private boolean isForcedState() {
        return autonomy != null && (autonomy.isForcedSleep() || "forced".equals(pendingSleepReason));
    }

    private boolean isBusy() {
        return System.currentTimeMillis() < busyUntil || pendingSleepTask != null || hideSeekActive
                || (playfield != null && playfield.isRunning());
    }

    private ConversationMemory memory() { return conversationEngine == null ? null : conversationEngine.memory(); }

    private boolean containsWakeWord(String text) {
        return text != null && text.matches("(?s).*\\bbellota\\b.*");
    }

    private static String simplify(String spoken) {
        if (spoken == null) return "";
        return spoken.toLowerCase(new Locale("es", "AR"))
                .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u").trim();
    }

    private void runAutonomousTick() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        boolean quiet = hour >= 0 && hour < 7;
        if (autonomy.isSleeping()) {
                AutonomousBehavior.Action sleepAction = autonomy.next(petLife, quiet);

            if (sleepAction == AutonomousBehavior.Action.WAKE_UP) wakeUp(false, true);
            return;
        }
        if (isBusy()) return;
        applyStageMood();
        boolean wasForcedSleep = autonomy.isForcedSleep();
        AutonomousBehavior.Action action = autonomy.next(petLife, quiet);

        if (action != AutonomousBehavior.Action.NONE && petMemory != null) petMemory.remember("actividad", action.name());
        runAction(action);
    }

    /** El ánimo de fondo acompaña la escalera: aburrida, fastidiada u ofendida. */
    private void applyStageMood() {
        int stage = autonomy.getStage(petLife);
        EyePetView.Mood mood = eyes.getMood();
        int irritation = autonomy.getTrait("irritation");
        if (irritation >= 65) {
            if (mood != EyePetView.Mood.OFFENDED) {
                eyes.setMood(EyePetView.Mood.OFFENDED);
                eyes.lookAway(rnd.nextBoolean() ? 1 : -1);
            }
        } else if (irritation >= 40) {
            if (mood != EyePetView.Mood.ANGRY && mood != EyePetView.Mood.OFFENDED)
                eyes.setMood(EyePetView.Mood.ANGRY);
        } else if (stage >= 5) {
            if (mood != EyePetView.Mood.OFFENDED) {
                eyes.setMood(EyePetView.Mood.OFFENDED);
                eyes.lookAway(rnd.nextBoolean() ? 1 : -1);
            }
        } else if (stage == 4) {
            if (mood != EyePetView.Mood.ANGRY && mood != EyePetView.Mood.OFFENDED) {
                eyes.setMood(rnd.nextInt(100) < 70 ? EyePetView.Mood.ANGRY : EyePetView.Mood.BORED);
            }
        } else if (stage >= 1 && stage <= 2) {
            if (mood != EyePetView.Mood.BORED && rnd.nextInt(100) < 50) eyes.setMood(EyePetView.Mood.BORED);
        } else if (stage == 0 && (mood == EyePetView.Mood.ANGRY || mood == EyePetView.Mood.OFFENDED
                || mood == EyePetView.Mood.BORED)) {
            eyes.setMood(EyePetView.Mood.CALM);
        }
    }

    private void runAction(AutonomousBehavior.Action action) {
        long now = System.currentTimeMillis();
        switch (action) {
            case REST_TIRED: startSleepSequence("tired"); break;
            case REST_BORED: startSleepSequence("bored"); break;
            case REST_NIGHT: startSleepSequence("night"); break;
            case REST_FORCED: startSleepSequence("forced"); break;
            case WAKE_UP: wakeUp(false, true); break;
            case THINK: eyes.setMood(EyePetView.Mood.THINKING); break;
            case LOOK_AROUND:
                if (autonomy.getStage(petLife) <= 3) eyes.performAutonomousBehavior();
                else eyes.lookDirection(rnd.nextFloat() * 0.6f - 0.3f, rnd.nextFloat() * 0.3f - 0.15f, 900L);
                if (rnd.nextInt(100) < 20) moveEyesAroundScreen();
                break;
            case YAWN:
                eyes.yawn();
                busyUntil = now + 3000L;
                if (rnd.nextInt(100) < 60) speakIfEnabled(lines.yawn());
                break;
            case MONOLOGUE:
                eyes.setMood(rnd.nextBoolean() ? EyePetView.Mood.THINKING : EyePetView.Mood.CURIOUS);
                speakIfEnabled(lines.monologue(memory()));
                busyUntil = now + 4000L;
                break;
            case INITIATE_CHAT:
                doInitiative();
                busyUntil = now + 5000L;
                break;
            case ANNOY:
                eyes.setMood(EyePetView.Mood.ANGRY);
                eyes.blinkNow();
                speakIfEnabled(lines.annoyed(memory()));
                busyUntil = now + 4000L;
                break;
            case TURN_AWAY:
                eyes.setMood(EyePetView.Mood.OFFENDED);
                eyes.lookAway(rnd.nextBoolean() ? 1 : -1);
                if (rnd.nextInt(100) < 70) speakIfEnabled(lines.sulk());
                busyUntil = now + 3000L;
                break;
            case GAME_DOT:
                eyes.playAutonomousGame();
                busyUntil = now + 4500L;
                applyPlayEffects();
                if (rnd.nextInt(100) < 40) speakIfEnabled(lines.game("dot"));
                break;
            case GAME_BALL: startGame(PlayfieldView.Game.BALL, "ball"); break;
            case GAME_BUBBLES: startGame(PlayfieldView.Game.BUBBLES, "bubbles"); break;
            case GAME_FLY: startGame(PlayfieldView.Game.FLY, "fly"); break;
            case GAME_STARS: startGame(PlayfieldView.Game.STARS, "stars"); break;
            case GAME_HIDE: startHideAndSeek(); break;
            default: break;
        }
    }

    private void applyPlayEffects() {
        if (petLife != null) petLife.setNeeds(Math.max(5, petLife.getEnergy() - 2), Math.min(100, petLife.getFun() + 8));
    }

    /** Charla por iniciativa propia: usa nombre, apodos y gustos. */
    private void doInitiative() {
        ConversationMemory m = memory();
        PetVoiceLines.Talk talk = lines.initiative(m, autonomy.getTrait("irritation"),
                autonomy.getTrait("affection"), false);
        if (autonomy.getTrait("irritation") < 40) eyes.setMood(EyePetView.Mood.HAPPY);
        else if (autonomy.getTrait("irritation") >= 65) eyes.setMood(EyePetView.Mood.OFFENDED);
        else eyes.setMood(EyePetView.Mood.ANGRY);
        if (talk.expect != null && m != null && soundEnabled && localVoice != null) {
            m.expect(talk.expect, System.currentTimeMillis());
        }
        speakIfEnabled(talk.text);
    }

    // ---- Juegos en la capa de juego ----------------------------------------------

    private void startGame(PlayfieldView.Game game, String key) {
        if (playfield == null || eyes == null) return;
        eyes.setMood(EyePetView.Mood.CURIOUS);
        playfield.setFocus(eyes.getX() + eyes.getWidth() / 2f, eyes.getY() + eyes.getHeight() / 2f);
        playfield.start(game);
        if (!playfield.isRunning()) return;
        busyUntil = System.currentTimeMillis() + 15000L;
        applyPlayEffects();
        if (rnd.nextInt(100) < 50) speakIfEnabled(lines.game(key));
    }

    private void handleGameEvent(PlayfieldView.Game game, String event) {
        if (eyes == null || event == null) return;
        switch (event) {
            case "ball_bounce":
                if (eyes.getMood() != EyePetView.Mood.HAPPY) eyes.setMood(EyePetView.Mood.HAPPY);
                eyes.blinkNow();
                break;
            case "bubble_pop":
                eyes.blinkNow();
                break;
            case "fly_close":
                eyes.setMood(EyePetView.Mood.ANGRY);
                handler.postDelayed(() -> {
                    if (eyes != null && playfield != null && playfield.currentGame() == PlayfieldView.Game.FLY
                            && eyes.getMood() == EyePetView.Mood.ANGRY) eyes.setMood(EyePetView.Mood.CURIOUS);
                }, 900L);
                if (rnd.nextInt(100) < 30) speakIfEnabled(lines.game("flyClose"));
                break;
            case "fly_caught":
                eyes.setMood(EyePetView.Mood.HAPPY);
                eyes.bounce();
                if (rnd.nextInt(100) < 70) speakIfEnabled(lines.game("flyCaught"));
                break;
            case "star":
                eyes.setMood(EyePetView.Mood.SURPRISED);
                handler.postDelayed(() -> {
                    if (eyes != null && playfield != null && playfield.currentGame() == PlayfieldView.Game.STARS
                            && eyes.getMood() == EyePetView.Mood.SURPRISED) eyes.setMood(EyePetView.Mood.CURIOUS);
                }, 700L);
                break;
            default: break;
        }
    }

    private void finishGame() {
        busyUntil = System.currentTimeMillis() + 1500L;
        if (eyes == null) return;
        eyes.lookDirection(0f, 0f, 500L);
        eyes.setMood(EyePetView.Mood.HAPPY);
    }

    // ---- Escondite: los ojos se esconden fuera de pantalla y se asoman ---------------

    private void startHideAndSeek() {
        if (petRoot == null || eyes == null || petRoot.getWidth() == 0 || eyes.getWidth() == 0) return;
        final int token = ++hideSeekToken;
        hideSeekActive = true;
        busyUntil = System.currentTimeMillis() + 20000L;
        applyPlayEffects();
        final float rw = petRoot.getWidth(), rh = petRoot.getHeight(), ew = eyes.getWidth(), eh = eyes.getHeight();
        final float cx = (rw - ew) / 2f, cy = (rh - eh) / 2f;
        final int side = rnd.nextInt(3); // 0 izquierda, 1 derecha, 2 abajo
        final float hx, hy, px, py, gx, gy;
        if (side == 0) { hx = -ew * 0.92f; hy = cy; px = -ew * 0.45f; py = cy; gx = 0.34f; gy = 0f; }
        else if (side == 1) { hx = rw - ew * 0.08f; hy = cy; px = rw - ew * 0.55f; py = cy; gx = -0.34f; gy = 0f; }
        else { hx = cx; hy = rh - eh * 0.08f; px = cx; py = rh - eh * 0.55f; gx = 0f; gy = -0.2f; }
        eyes.setMood(EyePetView.Mood.CURIOUS);
        if (rnd.nextInt(100) < 60) speakIfEnabled(lines.game("hide"));
        slideEyes(hx, hy, 900L);
        hideSeekStep(token, 2400L, () -> { slideEyes(px, py, 650L); eyes.lookDirection(gx, gy, 400L); });
        hideSeekStep(token, 3900L, () -> slideEyes(hx, hy, 600L));
        hideSeekStep(token, 5600L, () -> { slideEyes(px, py, 650L); eyes.lookDirection(gx, gy, 400L); });
        hideSeekStep(token, 6900L, () -> {
            slideEyes(cx, cy, 450L);
            eyes.lookDirection(0f, 0f, 300L);
            eyes.setMood(EyePetView.Mood.SURPRISED);
            if (rnd.nextInt(100) < 70) speakIfEnabled(lines.game("peek"));
        });
        hideSeekStep(token, 7600L, () -> { eyes.setMood(EyePetView.Mood.HAPPY); eyes.bounce(); });
        hideSeekStep(token, 9200L, () -> { hideSeekActive = false; busyUntil = System.currentTimeMillis() + 1000L; });
    }

    private void hideSeekStep(final int token, long delayMs, final Runnable step) {
        handler.postDelayed(() -> { if (token == hideSeekToken && hideSeekActive && eyes != null) step.run(); }, delayMs);
    }

    private void slideEyes(float x, float y, long durationMs) {
        eyes.animate().cancel();
        eyes.animate().x(x).y(y).setDuration(durationMs)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
    }

    /** Corta juegos y escondite en curso (por ejemplo, porque el usuario le habló). */
    private void cancelActivities() {
        if (playfield != null) playfield.stop(false);
        if (hideSeekActive) {
            hideSeekActive = false;
            hideSeekToken++;
            if (petRoot != null && eyes != null && petRoot.getWidth() > 0) {
                eyes.animate().cancel();
                eyes.animate().x((petRoot.getWidth() - eyes.getWidth()) / 2f)
                        .y((petRoot.getHeight() - eyes.getHeight()) / 2f).setDuration(350L).start();
            }
        }
        busyUntil = 0L;
    }

    // ---- Sueño ----------------------------------------------------------------------

    /** Avisa que se va a dormir; da unos segundos para decirle "no te duermas". */
    private void startSleepSequence(final String reason) {
        if (autonomy == null || eyes == null || autonomy.isSleeping() || pendingSleepTask != null) return;
        cancelActivities();
        ConversationMemory m = memory();
        String phrase;
        switch (reason) {
            case "bored": phrase = lines.boredSleep(m); break;
            case "night": phrase = lines.nightSleep(m); break;
            case "forced": phrase = lines.forcedSleep(); break;
            case "asked": phrase = lines.askedSleep(m); break;
            default: phrase = lines.tiredSleep(m); break;
        }
        final boolean forced = "forced".equals(reason);
        eyes.setMood(EyePetView.Mood.SLEEPY);
        speakIfEnabled(phrase);
        busyUntil = System.currentTimeMillis() + (forced ? 5000L : 10000L);
        pendingSleepReason = reason;
        pendingSleepTask = () -> { pendingSleepTask = null; pendingSleepReason = null; beginSleep(reason); };
        handler.postDelayed(pendingSleepTask, forced ? 3500L : 8000L);
    }

    private void beginSleep(String reason) {
        if (autonomy == null || eyes == null) return;
        autonomy.startSleep(reason);
        eyes.setMood(EyePetView.Mood.SLEEPING);
        dimScreen(true);

        if (petMemory != null) petMemory.remember("sueno", "Bellota se fue a dormir (" + reason + ").");
    }

    /** Cancela el aviso de sueño si todavía no se durmió (salvo sueño obligatorio). */
    private boolean cancelPendingSleep() {
        if (pendingSleepTask == null || "forced".equals(pendingSleepReason)) return false;
        handler.removeCallbacks(pendingSleepTask);
        pendingSleepTask = null;
        pendingSleepReason = null;
        busyUntil = 0L;
        if (eyes != null) eyes.setMood(EyePetView.Mood.HAPPY);
        return true;
    }

    private void wakeUp(boolean manual, boolean speak) {
        if (autonomy == null || eyes == null) return;
        autonomy.wake(manual);
        dimScreen(false);

        eyes.setMood(manual ? EyePetView.Mood.SURPRISED : EyePetView.Mood.HAPPY);
        if (manual) {
            handler.postDelayed(() -> {
                if (!isAsleep() && eyes != null && eyes.getMood() == EyePetView.Mood.SURPRISED) eyes.setMood(EyePetView.Mood.HAPPY);
            }, 900L);
        }
        eyes.blinkNow();
        if (speak) speakIfEnabled(lines.wake(manual, memory()));
        if (petMemory != null) petMemory.remember("sueno", manual ? "La despertaron." : "Bellota despertó descansada.");
    }

    private void mumbleInSleep() {
        long now = System.currentTimeMillis();
        if (now - lastMumbleAt < 6000L) return;
        lastMumbleAt = now;
        speakIfEnabled(lines.mumble());
    }

    private void dimScreen(boolean dim) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = dim ? 0.03f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(attributes);
    }

    /** Restablece pantalla y ojos según el estado guardado (al abrir o volver a la app). */
    private void applySleepVisuals() {
        if (eyes == null || autonomy == null) return;
        boolean asleep = autonomy.isSleeping();
        dimScreen(asleep);
        if (asleep) eyes.setMood(EyePetView.Mood.SLEEPING);

    }

    // ---- Interacción con el usuario ---------------------------------------------------

    private void onPetTouched(String kind, boolean firm) {
        if (isForcedState()) { // ya no puede mantenerse despierta
            if (petMemory != null) petMemory.remember(kind, "Tocaron a Bellota cuando ya no podía mantenerse despierta.");
            mumbleInSleep();
            return;
        }
        if (cancelPendingSleep() && petMemory != null) petMemory.remember("sueno", "El usuario la tocó y no se durmió.");
        userInteracted(kind, true);
        if (petMemory != null) petMemory.remember(kind,
                firm ? "Toque breve y firme; Bellota lo interpretó como un golpe."
                        : "Contacto prolongado o suave; Bellota lo interpretó como una caricia.");
        if (eyes != null && !isAsleep()) {
            if (firm) eyes.setMood(EyePetView.Mood.SURPRISED);
            else eyes.reactToAttention();
        }
    }

    /** Distingue cariño y disculpas de una interacción cotidiana para ajustar el ánimo. */
    private String classifyEmotionalInteraction(String text) {
        if (text == null) return "attention";
        String value = simplify(text);
        if (value.contains("perdon") || value.contains("lo siento") || value.contains("disculpa")
                || value.contains("no quise") || value.contains("no era mi intencion")
                || value.contains("tenes razon") || value.contains("tienes razon")) return "apology";
        if (value.contains("te quiero") || value.contains("te amo") || value.contains("te adoro")
                || value.contains("te aprecio") || value.contains("sos hermosa") || value.contains("sos linda")
                || value.contains("sos bella") || value.contains("preciosa") || value.contains("mi vida")
                || value.contains("corazon") || value.contains("hermosa bellota") || value.contains("linda bellota"))
            return "affectionate";
        return "attention";
    }

    /** Registra la interacción, despierta si dormía, corta juegos y celebra si estaba fastidiada. */
    private void userInteracted(String kind, boolean speak) {
        if (autonomy == null) return;
        boolean wasSleeping = autonomy.isSleeping();
        autonomy.onInteraction(petLife, kind);
        if (wasSleeping) wakeUp(true, speak);
        cancelActivities();
        busyUntil = System.currentTimeMillis() + 5000L; // un respiro antes de que vuelva a hacer cosas sola
        int reconcile = autonomy.takeReconcileStage();
        if (reconcile >= 4 && !wasSleeping && eyes != null) {
            eyes.lookDirection(0f, 0f, 400L);
            eyes.setMood(EyePetView.Mood.HAPPY);
            eyes.bounce();
            if (speak) speakIfEnabled(lines.reconcile(reconcile, memory()));
        }
    }

    /** "No te duermas", "dormite", "despertate". Devuelve true si el texto era uno de esos comandos. */
    private boolean handleCompanionCommand(String text, boolean speak) {
        if (autonomy == null || text == null || text.isEmpty()) return false;
        if (text.contains("no te duermas") || text.contains("no te vayas a dormir")
                || text.contains("no te vayas a descansar") || text.contains("quedate despierta")) {
            onStayAwake(speak);
            return true;
        }
        if (text.contains("dormite") || text.contains("anda a dormir") || text.contains("andate a dormir")
                || text.contains("anda a descansar")) {
            if (!autonomy.isSleeping() && pendingSleepTask == null) {
                autonomy.setStayAwake(false);
                startSleepSequence("asked");
            }
            return true;
        }
        if ((text.contains("despertate") || text.contains("levantate") || text.contains("desperta"))
                && autonomy.isSleeping() && !autonomy.isForcedSleep()) {
            wakeUp(true, speak);
            return true;
        }
        return false;
    }

    private void onStayAwake(boolean speak) {
        if (isForcedState()) { // con 5% de energía el sueño es obligatorio
            if (speak) speakIfEnabled(lines.cantStayAwake());
            return;
        }
        autonomy.setStayAwake(true); // sigue haciendo compañía hasta quedarse sin energía
        boolean wasSleeping = autonomy.isSleeping();
        cancelPendingSleep();
        if (wasSleeping) wakeUp(true, false);
        if (eyes != null) eyes.setMood(EyePetView.Mood.HAPPY);
        if (speak) speakIfEnabled(lines.stayAwake(memory()));
        if (petMemory != null) petMemory.remember("sueno", "El usuario le pidió que no se duerma.");
    }

    private void moveEyesAroundScreen() {
        if (petRoot == null || eyes == null || petRoot.getWidth() == 0 || petRoot.getHeight() == 0) return;
        float maxX = Math.max(0, petRoot.getWidth() - eyes.getWidth());
        float maxY = Math.max(0, petRoot.getHeight() - eyes.getHeight());
        float targetX = (float)(Math.random() * maxX);
        float targetY = (float)(Math.random() * maxY);
        eyes.animate().cancel();
        eyes.animate().x(targetX).y(targetY).setDuration(1300L + (long)(Math.random() * 1700L))
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void requestMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceCommands();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
    }

    /** Reconocimiento local de Android. */
    private void startVoiceCommands() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        startLocalRecognizer();
    }

    private void startLocalRecognizer() {
        if (paused || isFinishing()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        // Reutilizar la instancia evita destruir y recrear el reconocedor en cada onResume.
        if (voiceCommands != null) {
            voiceCommands.start();
            return;
        }
        voiceCommands = new ContinuousVoiceCommands(this, new ContinuousVoiceCommands.Listener() {
            @Override public void onRecognized(String spoken) {
                DebugLog.i("BellotaVoice", "Transcripción recibida; se procesa el comando.");
                handleVoiceCommand(spoken);
            }
            @Override public void onStatus(String status) {
                if (status == null) return;
                DebugLog.i("BellotaVoice", status);
                if (status.startsWith("Error de reconocimiento:")) {
                    long now = System.currentTimeMillis();
                    if (now - lastVoiceDiagnosticToastAt >= 10000L) {
                        lastVoiceDiagnosticToastAt = now;
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, status, Toast.LENGTH_LONG).show());
                    }
                }
            }
        });
        voiceCommands.start();
    }

    private void handleVoiceCommand(String spoken) {
        handleVoiceCommand(spoken, false);
    }

    private void handleVoiceCommand(String spoken, boolean interactionAlreadyRecorded) {
        if (spoken == null || spoken.trim().isEmpty()) return;
        String text = spoken.toLowerCase(new Locale("es", "AR"))
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").trim();
        // El sueño obligatorio solo termina al recuperar la energía mínima.
        if (isForcedState()) return;
        // En sueño normal, la palabra de activación se exige también en reconocimiento local.
        // Se usa el mismo detector de palabra completa que en el reconocimiento local.
        if (isAsleep() && !containsWakeWord(text)) return;
        if (handleCompanionCommand(text, true)) return;
        if (text.contains("silencio")) {
            soundEnabled = false;
            if (localVoice != null) localVoice.stop();
            if (eyes != null) eyes.setMood(EyePetView.Mood.CALM);
            return;
        }
        if (text.contains("sonido")) {
            soundEnabled = true;
            speakIfEnabled("Ya puedo hablar otra vez.");
            return;
        }
        if (autonomy != null && autonomy.isForcedSleep()) { mumbleInSleep(); return; }
        if (!interactionAlreadyRecorded) {
            String interactionKind = classifyEmotionalInteraction(text);
            userInteracted(interactionKind, true);
        }
        if (eyes != null && !isAsleep()) eyes.reactToAttention();
        if (petMemory != null) petMemory.remember("voz_usuario", spoken);
        if(text.contains("bateria") || text.contains("carga del telefono")){ BatteryManager b=(BatteryManager)getSystemService(BATTERY_SERVICE); int level=b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); speakIfEnabled(level>=0?"El teléfono tiene "+level+" por ciento de batería.":"No pude consultar la batería."); return; }
        if(text.contains("que hora") || text.equals("hora") || text.contains("que dia es") || text.contains("fecha de hoy")){ Date now=new Date(); speakIfEnabled("Son las "+new SimpleDateFormat("HH:mm",new Locale("es","AR")).format(now)+". Hoy es "+new SimpleDateFormat("EEEE d 'de' MMMM",new Locale("es","AR")).format(now)+"."); return; }
        if(text.contains("volumen actual")){ AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE); speakIfEnabled("El volumen multimedia está al "+Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))+" por ciento."); return; }

        String mood = eyes == null ? "NEUTRAL" : eyes.getMood().name();
        Reply result = conversationEngine == null
                ? Reply.text(offlineReplies.reply(text, petLife, mood))
                : conversationEngine.respond(spoken, petLife, mood);
        if (result.type == Reply.Type.NEEDS_WEATHER) {
            requestWeather();
            return;
        }
        if (result.type == Reply.Type.TEXT && result.text != null) speakIfEnabled(result.text);
        else if (result.type == Reply.Type.NEEDS_AI) {
            speakIfEnabled("Ese comando ya está disponible.");
        }
    }

    private JSONArray buildBellotaTools() throws org.json.JSONException {
        JSONArray definitions = new JSONArray();
        definitions.put(new JSONObject()
                .put("name", "get_bellota_status")
                .put("description", "Consulta el estado actual de Bellota: ánimo, energía, diversión y si está despierta.")
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", new JSONObject())));
        definitions.put(new JSONObject()
                .put("name", "set_bellota_emotion")
                .put("description", "Cambia la expresión emocional visible de Bellota. No la despierta si está dormida.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("emotion", new JSONObject()
                                .put("type", "string").put("enum", new JSONArray()
                                        .put("neutral").put("happy").put("thinking").put("surprised")
                                        .put("sleepy").put("calm").put("curious").put("angry").put("bored").put("offended"))))
                        .put("required", new JSONArray().put("emotion"))));
        definitions.put(new JSONObject()
                .put("name", "speak_as_bellota")
                .put("description", "Hace que Bellota diga una frase breve en voz alta.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("text", new JSONObject()
                                .put("type", "string").put("description", "Frase de hasta 240 caracteres.")))
                        .put("required", new JSONArray().put("text"))));
        definitions.put(new JSONObject()
                .put("name", "interact_with_bellota")
                .put("description", "Registra una interacción del usuario para actualizar el vínculo y el estado de Bellota.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("kind", new JSONObject()
                                .put("type", "string").put("enum", new JSONArray()
                                        .put("attention").put("caress").put("affectionate").put("apology"))))
                        .put("required", new JSONArray().put("kind"))));
        definitions.put(new JSONObject()
                .put("name", "play_with_bellota")
                .put("description", "Inicia un minijuego visual con Bellota.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("game", new JSONObject()
                                .put("type", "string").put("enum", new JSONArray()
                                        .put("ball").put("bubbles").put("fly").put("stars"))))
                        .put("required", new JSONArray().put("game"))));
        return definitions;
    }

    private String executeBellotaTool(String name, JSONObject arguments) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return executeBellotaToolOnMainThread(name, arguments);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        handler.post(() -> {
            try { result.set(executeBellotaToolOnMainThread(name, arguments)); }
            catch (Exception e) { failure.set(e); }
            finally { completed.countDown(); }
        });
        if (!completed.await(6, TimeUnit.SECONDS)) throw new Exception("Bellota tardó demasiado en responder.");
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private String executeBellotaToolOnMainThread(String name, JSONObject args) throws Exception {
        if ("get_bellota_status".equals(name)) {
            String mood = eyes == null ? "unknown" : eyes.getMood().name().toLowerCase(Locale.ROOT);
            return "Estado: " + (isAsleep() ? "durmiendo" : "despierta")
                    + "; ánimo: " + mood
                    + "; energía: " + (petLife == null ? "?" : petLife.getEnergy())
                    + "; diversión: " + (petLife == null ? "?" : petLife.getFun())
                    + "; fastidio: " + (autonomy == null ? "?" : autonomy.getTrait("irritation")) + ".";
        }
        if ("set_bellota_emotion".equals(name)) {
            String emotion = args.optString("emotion", "").trim().toUpperCase(Locale.ROOT);
            if (isAsleep() || eyes == null) return "No puedo cambiar mi expresión mientras estoy dormida.";
            try { eyes.setMood(EyePetView.Mood.valueOf(emotion)); }
            catch (IllegalArgumentException e) { throw new Exception("Emoción no admitida."); }
            return "Mi expresión ahora es " + emotion.toLowerCase(Locale.ROOT) + ".";
        }
        if ("speak_as_bellota".equals(name)) {
            String phrase = args.optString("text", "").trim();
            if (phrase.isEmpty() || phrase.length() > 240) throw new Exception("La frase debe tener entre 1 y 240 caracteres.");
            if (isForcedState() || isAsleep()) return "No puedo hablar ahora: estoy durmiendo.";
            if (!soundEnabled) return "La voz está desactivada.";
            if (onlineVoiceManager != null && onlineVoiceManager.isConnected())
                return "Decí en voz alta exactamente esta frase, sin agregar comentarios: " + phrase;
            if (localVoice == null || !localVoice.isReady()) return "La voz local todavía no está lista.";
            speakIfEnabled(phrase);
            return "Estoy diciendo la frase.";
        }
        if ("interact_with_bellota".equals(name)) {
            if (isForcedState()) return "Estoy en descanso obligatorio y no puedo despertarme todavía.";
            String kind = args.optString("kind", "attention");
            if (!"attention".equals(kind) && !"caress".equals(kind)
                    && !"affectionate".equals(kind) && !"apology".equals(kind))
                throw new Exception("Tipo de interacción no admitido.");
            userInteracted(kind, false);
            if (eyes != null && !isAsleep()) eyes.reactToAttention();
            return "Interacción registrada: " + kind + ".";
        }
        if ("play_with_bellota".equals(name)) {
            if (isAsleep() || isBusy()) return "Ahora no puedo empezar un juego.";
            String game = args.optString("game", "");
            PlayfieldView.Game selected;
            String key = game;
            switch (game) {
                case "ball": selected = PlayfieldView.Game.BALL; break;
                case "bubbles": selected = PlayfieldView.Game.BUBBLES; break;
                case "fly": selected = PlayfieldView.Game.FLY; break;
                case "stars": selected = PlayfieldView.Game.STARS; break;
                default: throw new Exception("Juego no admitido.");
            }
            startGame(selected, key);
            return playfield != null && playfield.isRunning() ? "Empecé a jugar: " + game + "." : "No pude iniciar el juego.";
        }
        throw new Exception("Herramienta desconocida: " + name);
    }

    private void setupAmbientTheme(){ sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE); if(sensorManager!=null){ lightSensor=sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT); if(lightSensor!=null)sensorManager.registerListener(this,lightSensor,SensorManager.SENSOR_DELAY_NORMAL); } }
    @Override public void onSensorChanged(SensorEvent event){ if(event.sensor.getType()!=Sensor.TYPE_LIGHT||petRoot==null)return; long now=System.currentTimeMillis(); if(now-lastThemeChange<15000L)return; float lux=event.values[0]; boolean next=lightTheme?lux<85f:lux<25f; if(next!=lightTheme){lightTheme=next;lastThemeChange=now;petRoot.setBackgroundColor(lightTheme?0xFFF2F5F3:0xFF080D10);if(eyes!=null)eyes.setLightTheme(lightTheme);} }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    private void requestWeather() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            waitingForWeatherPermission = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        if (eyes != null) eyes.setMood(EyePetView.Mood.THINKING);
        WeatherService.fetch(this, response -> {
            if (eyes != null) eyes.setMood(EyePetView.Mood.HAPPY);
            speakIfEnabled(response);
        });
    }

    private void speakIfEnabled(String text) {
        if (!soundEnabled || text == null || text.isEmpty()) return;
        // Mientras Realtime está activo, no mezclar la salida TTS local con el audio remoto.
        if (onlineVoiceManager != null && onlineVoiceManager.isConnected()) return;
        if (localVoice == null) return;
        if (voiceCommands != null) voiceCommands.stop();
        localVoice.speak(text);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceCommands();
            else Toast.makeText(this, "Bellota necesita permiso de micrófono para escucharte.", Toast.LENGTH_LONG).show();
        } else if (requestCode == LOCATION_PERMISSION && waitingForWeatherPermission) {
            waitingForWeatherPermission = false;
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) requestWeather();
            else speakIfEnabled("Para decirte el clima necesito permiso para consultar la ubicación del teléfono.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        handler.removeCallbacks(autonomousRoutine);
        handler.postDelayed(autonomousRoutine, 1800L);
        enterImmersiveMode();
        applySleepVisuals();
        if(sensorManager!=null&&lightSensor!=null)sensorManager.registerListener(this,lightSensor,SensorManager.SENSOR_DELAY_NORMAL);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startVoiceCommands();
        if (eyes != null) eyes.resumeAnimation();
    }

    @Override protected void onPause() {
        paused = true;
        disconnectOnlineVoice(false);
        handler.removeCallbacks(autonomousRoutine);

        if (localVoice != null) localVoice.stop();

        cancelActivities();

        if (voiceCommands != null) voiceCommands.stop();

        if (eyes != null) eyes.pauseAnimation();
        if(sensorManager!=null)sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {

        disconnectOnlineVoice(false);
        handler.removeCallbacksAndMessages(null);

        if (voiceCommands != null) { voiceCommands.stop(); voiceCommands = null; }

        if (localVoice != null) { localVoice.stop(); localVoice.release(); localVoice = null; }

        super.onDestroy();
    }
}
