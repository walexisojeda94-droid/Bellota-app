package com.bellota.mascota;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.bellota.mascota.local.Utterance;
import com.bellota.mascota.local.VoiceCommandIntent;
import com.bellota.mascota.ui.EyePetView;
import com.bellota.mascota.voice.VoiceRecognitionService;
import com.bellota.mascota.voice.VoiceSessionService;
import com.bellota.mascota.voice.LocalVoice;
import com.bellota.mascota.voice.provider.VoiceProvider;
import com.bellota.mascota.voice.provider.VoiceProviderManager;
import com.bellota.mascota.voice.provider.OpenAIRealtimeWebRtcProvider;
import com.bellota.mascota.voice.auth.SecureTokenStore;
import com.bellota.mascota.weather.WeatherService;
import com.bellota.mascota.voice.tools.BellotaToolCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import com.bellota.mascota.local.ConversationMemory;
import com.bellota.mascota.local.PetVoiceLines;
import com.bellota.mascota.ui.PlayfieldView;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int MICROPHONE_PERMISSION = 41;
    private static final int LOCATION_PERMISSION = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService toolCallExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "Bellota-ToolCall");
        thread.setDaemon(true);
        return thread;
    });
    private final OfflineReplies offlineReplies = new OfflineReplies();
    private LocalConversationEngine conversationEngine;
    private EyePetView eyes;
    private FrameLayout petRoot;
    private LocalVoice localVoice;
    private volatile VoiceProviderManager onlineVoiceManager;
    private boolean onlineVoiceConnecting;
    private PetLife petLife;
    private AutonomousBehavior autonomy;
    private PetMemory petMemory;
    private long touchStartedAt;
    private float touchStartX, touchStartY, touchPressure;
    private boolean paused;
    private long lastVoiceDiagnosticToastAt;
    private boolean voiceReceiverRegistered;
    private boolean localRecognitionPaused;
    private final BroadcastReceiver voiceEventsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String value = intent.getStringExtra(VoiceRecognitionService.EXTRA_TEXT);
            if (VoiceRecognitionService.ACTION_RECOGNIZED.equals(intent.getAction())) {
                if (value != null && !value.trim().isEmpty()) handleVoiceCommand(value);
            } else if (VoiceRecognitionService.ACTION_STATUS.equals(intent.getAction())) handleVoiceStatus(value);
            else if (VoiceRecognitionService.ACTION_STOPPED.equals(intent.getAction())) disconnectOnlineVoice(false);
            else if (VoiceSessionService.ACTION_EVENT.equals(intent.getAction())) handleOnlineVoiceEvent(intent);
        }
    };
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
        enterImmersiveMode();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildPetScreen();
        applySleepVisuals();
        localVoice = new LocalVoice(this, (ready, detail) -> { });
        localVoice.setOnSpeechFinished(() -> {
            if (!isFinishing()) resumeLocalRecognition();
        });
        setupAmbientTheme();
        registerVoiceReceiver();
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

        android.widget.CheckBox shareMemory = new android.widget.CheckBox(this);
        shareMemory.setText("Permitir que la voz online use recuerdos relevantes");
        shareMemory.setTextColor(0xFFE7EEEE);
        shareMemory.setTextSize(13f);
        shareMemory.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF00BFA6));
        shareMemory.setChecked(memory() != null && memory().isOnlineMemorySharingEnabled());
        shareMemory.setOnCheckedChangeListener((button, checked) -> {
            ConversationMemory currentMemory = memory();
            if (currentMemory != null) currentMemory.setOnlineMemorySharingEnabled(checked);
        });
        panel.addView(shareMemory);

        android.widget.TextView privacyNote = new android.widget.TextView(this);
        privacyNote.setText("Desactivado por defecto. Si lo activás, se comparte un resumen limitado de nombre, apodos, gustos no sensibles y continuidad pendiente. El diario cifrado y las transcripciones guardadas no se envían como memoria.");
        privacyNote.setTextColor(0xFFB7C8C8);
        privacyNote.setTextSize(11f);
        panel.addView(privacyNote);

        android.widget.Button clearMemoryButton = new android.widget.Button(this);
        clearMemoryButton.setText("Borrar recuerdos locales");
        clearMemoryButton.setTextColor(0xFFFFC0B8);
        clearMemoryButton.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Borrar recuerdos de Bellota")
                .setMessage("Se eliminarán el diario cifrado, los recuerdos semánticos y las copias locales bloqueadas. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (confirm, which) -> {
                    clearMemoryButton.setEnabled(false);
                    disconnectOnlineVoice(false);
                    VoiceRecognitionService.stop(MainActivity.this);
                    new Thread(() -> {
                        boolean semanticCleared = conversationEngine == null
                                || conversationEngine.clearMemory();
                        if (petMemory != null) petMemory.clear();
                        boolean journalCleared = petMemory == null;
                        if (petMemory != null) {
                            try {
                                journalCleared = PetMemory.awaitPendingWrites(10000L)
                                        && PetMemory.wasLastClearSuccessful();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                journalCleared = false;
                            }
                        }
                        final boolean success = semanticCleared && journalCleared;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            clearMemoryButton.setEnabled(true);
                            shareMemory.setChecked(false);
                            VoiceRecognitionService.resume(MainActivity.this);
                            Toast.makeText(MainActivity.this,
                                    success ? "Recuerdos locales eliminados." : "No se pudo confirmar el borrado completo. Revisá el almacenamiento.",
                                    Toast.LENGTH_LONG).show();
                        });
                    }, "Bellota-MemoryDelete").start();
                }).show());
        panel.addView(clearMemoryButton);

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
                .setNeutralButton((onlineVoiceConnecting || VoiceSessionService.isActive(this)) ? "Desconectar" : "Conectar", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.widget.Button primary = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button online = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
            primary.setEnabled(configured);
            online.setEnabled(configured && paired);

            primary.setOnClickListener(v -> {
                if (paired) {
                    if (onlineVoiceManager != null || onlineVoiceConnecting) disconnectOnlineVoice(true);
                    primary.setEnabled(false);
                    online.setEnabled(false);
                    status.setText("Revocando este dispositivo en el servidor...");
                    status.setTextColor(0xFF9ED8C8);
                    new Thread(() -> {
                        String message;
                        boolean success = false;
                        try {
                            com.bellota.mascota.voice.auth.BellotaBackendClient client =
                                    new com.bellota.mascota.voice.auth.BellotaBackendClient(backendUrl, tokenStore);
                            client.unpair();
                            message = "Dispositivo revocado en el servidor y desvinculado de este teléfono.";
                            success = true;
                        } catch (com.bellota.mascota.voice.auth.BellotaBackendClient.BackendException error) {
                            message = error.getMessage();
                        } catch (Exception error) {
                            message = "No se pudo confirmar la revocación. La credencial local se conservó para reintentar.";
                        }
                        final String result = message;
                        final boolean revoked = success;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            status.setText(result);
                            status.setTextColor(revoked ? 0xFF9ED8C8 : 0xFFFFA6A6);
                            online.setEnabled(false);
                            if (revoked) {
                                dialog.dismiss();
                                android.widget.Toast.makeText(MainActivity.this,
                                        "Dispositivo revocado correctamente.", android.widget.Toast.LENGTH_LONG).show();
                            } else if (!tokenStore.hasRefreshToken()) {
                                dialog.dismiss();
                                android.widget.Toast.makeText(MainActivity.this,
                                        "La clave segura dejó de ser válida. Abrí nuevamente la opción online para vincular el dispositivo otra vez.",
                                        android.widget.Toast.LENGTH_LONG).show();
                            } else {
                                primary.setText("Reintentar revocación");
                                primary.setEnabled(true);
                            }
                        });
                    }, "Bellota-Unpair").start();
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
                if (onlineVoiceConnecting || VoiceSessionService.isActive(this)) {
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
        try {
            JSONArray tools = buildBellotaTools();
            String instructions = "Sos Bellota, una compañera virtual con personalidad propia. "
                    + "Hablá en español rioplatense natural, con respuestas conversacionales y concisas. "
                    + "Conservá continuidad y contexto. No describas acciones que no realizaste."
                    + (conversationEngine == null ? "" : conversationEngine.memoryContextForOnline());
            onlineVoiceManager = null;
            onlineVoiceConnecting = true;
            VoiceRecognitionService.stop(this);
            VoiceSessionService.start(getApplicationContext(), backendUrl, instructions,
                    tools.toString(), "marin", "es-AR");
            status.setText("Iniciando OpenAI Realtime en el servicio persistente...");
            onlineButton.setText("Desconectar");
            onlineButton.setEnabled(true);
        } catch (Exception error) {
            onlineVoiceConnecting = false;
            VoiceRecognitionService.resume(this);
            status.setText("No se pudo iniciar OpenAI Realtime: " + error.getMessage());
            onlineButton.setEnabled(true);
        }
    }

    private void disconnectOnlineVoice(boolean resumeLocal) {
        onlineVoiceConnecting = false;
        onlineVoiceManager = null;
        VoiceSessionService.stop(getApplicationContext());
        // VoiceSessionService resumes the local recognizer after releasing WebRTC audio.
    }

    private void handleOnlineVoiceEvent(Intent intent) {
        String event = intent.getStringExtra(VoiceSessionService.EXTRA_EVENT);
        if ("state".equals(event)) {
            String state = intent.getStringExtra(VoiceSessionService.EXTRA_STATE);
            String detail = intent.getStringExtra(VoiceSessionService.EXTRA_DETAIL);
            if (VoiceProvider.State.CONNECTED.name().equals(state)) onlineVoiceConnecting = false;
            else if (VoiceProvider.State.CLOSED.name().equals(state)) {
                onlineVoiceConnecting = false;
                localRecognitionPaused = false;
            }
            if (detail != null && !detail.isEmpty()) DebugLog.i("BellotaOnlineVoice", detail);
            return;
        }
        if ("user_transcript".equals(event)) {
            String text = intent.getStringExtra(VoiceSessionService.EXTRA_TEXT);
            if (intent.getBooleanExtra(VoiceSessionService.EXTRA_FINAL, false)
                    && text != null && !text.trim().isEmpty()) {
                if (conversationEngine != null) conversationEngine.rememberOnlineMessage(text);
                if (petMemory != null) petMemory.remember("voz_usuario", text);
                if (!isAsleep()) {
                    userInteracted("attention", true);
                    if (eyes != null) eyes.reactToAttention();
                }
            }
            return;
        }
        if ("assistant_transcript".equals(event)) {
            String text = intent.getStringExtra(VoiceSessionService.EXTRA_TEXT);
            if (intent.getBooleanExtra(VoiceSessionService.EXTRA_FINAL, false)
                    && text != null && !text.trim().isEmpty() && petMemory != null)
                petMemory.remember("voz_bellota", text);
            return;
        }
        if ("speaking".equals(event)) {
            boolean speaking = Boolean.parseBoolean(intent.getStringExtra(VoiceSessionService.EXTRA_STATE));
            if (eyes != null && !isAsleep())
                eyes.setMood(speaking ? EyePetView.Mood.THINKING : EyePetView.Mood.CALM);
            return;
        }
        if ("tool_call".equals(event)) {
            String callId = intent.getStringExtra(VoiceSessionService.EXTRA_CALL_ID);
            String name = intent.getStringExtra(VoiceSessionService.EXTRA_TOOL_NAME);
            String arguments = intent.getStringExtra(VoiceSessionService.EXTRA_ARGUMENTS);
            if (callId == null || name == null || arguments == null) return;
            try {
                toolCallExecutor.execute(() -> {
                    try {
                        String result = executeBellotaTool(name, new JSONObject(arguments), null, callId);
                        VoiceSessionService.sendToolResult(getApplicationContext(), callId, result, false);
                    } catch (Exception error) {
                        VoiceSessionService.sendToolResult(getApplicationContext(), callId,
                                error.getMessage() == null ? "No se pudo ejecutar la herramienta." : error.getMessage(), true);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                VoiceSessionService.sendToolResult(getApplicationContext(), callId,
                        "Bellota está cerrando y no puede ejecutar esta acción.", true);
            }
        }
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
        Utterance utterance = Utterance.of(text);
        if (utterance.hasPhrase("no te duermas") || utterance.hasPhrase("no te vayas a dormir")
                || utterance.hasPhrase("no te vayas a descansar") || utterance.hasPhrase("quedate despierta")) {
            onStayAwake(speak);
            return true;
        }
        boolean sleepRequest = utterance.text.equals("dormite")
                || utterance.startsWith("anda a dormir") || utterance.startsWith("andate a dormir")
                || utterance.startsWith("anda a descansar") || utterance.startsWith("por favor dormite");
        if (sleepRequest) {
            if (!autonomy.isSleeping() && pendingSleepTask == null) {
                autonomy.setStayAwake(false);
                startSleepSequence("asked");
            }
            return true;
        }
        boolean wakeRequest = utterance.text.equals("despertate") || utterance.text.equals("levantate")
                || utterance.text.equals("desperta") || utterance.startsWith("despertate bellota")
                || utterance.startsWith("levantate bellota");
        if (wakeRequest && autonomy.isSleeping() && !autonomy.isForcedSleep()) {
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

    private void registerVoiceReceiver() {
        if (voiceReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(VoiceRecognitionService.ACTION_RECOGNIZED);
        filter.addAction(VoiceRecognitionService.ACTION_STATUS);
        filter.addAction(VoiceRecognitionService.ACTION_STOPPED);
        filter.addAction(VoiceSessionService.ACTION_EVENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(voiceEventsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(voiceEventsReceiver, filter);
        voiceReceiverRegistered = true;
    }

    private void handleVoiceStatus(String status) {
        if (status == null || status.trim().isEmpty()) return;
        DebugLog.i("BellotaVoice", status);
        if (status.startsWith("Error de reconocimiento:")
                || status.startsWith("No hay un servicio de reconocimiento")
                || status.startsWith("No hay un reconocedor de voz local")
                || status.startsWith("No hay un modelo español local")) {
            long now = System.currentTimeMillis();
            if (!paused && now - lastVoiceDiagnosticToastAt >= 10000L) {
                lastVoiceDiagnosticToastAt = now;
                Toast.makeText(this, status, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void pauseLocalRecognition() {
        if (localRecognitionPaused || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        localRecognitionPaused = true;
        VoiceRecognitionService.pause(this);
    }

    private void resumeLocalRecognition() {
        if (onlineVoiceConnecting || VoiceSessionService.isActive(this)
                || (onlineVoiceManager != null && onlineVoiceManager.isConnected())
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!localRecognitionPaused) return;
        localRecognitionPaused = false;
        VoiceRecognitionService.resume(this);
    }

    private void startVoiceCommands() {
        if (onlineVoiceConnecting || VoiceSessionService.isActive(this)
                || (onlineVoiceManager != null && onlineVoiceManager.isConnected())) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startLocalRecognizer();
    }

    private void startLocalRecognizer() {
        if (isFinishing() || onlineVoiceConnecting || VoiceSessionService.isActive(this)
                || (onlineVoiceManager != null && onlineVoiceManager.isConnected())
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        localRecognitionPaused = false;
        VoiceRecognitionService.start(this);
    }

    private void handleVoiceCommand(String spoken) {
        handleVoiceCommand(spoken, false);
    }

    private void handleVoiceCommand(String spoken, boolean interactionAlreadyRecorded) {
        if (spoken == null || spoken.trim().isEmpty()) return;
        Utterance utterance = Utterance.of(spoken);
        String text = utterance.text;
        // El sueño obligatorio solo termina al recuperar la energía mínima.
        if (isForcedState()) return;
        // En sueño normal, la palabra de activación se exige también en reconocimiento local.
        // Se usa el mismo detector de palabra completa que en el reconocimiento local.
        if (isAsleep() && !containsWakeWord(text)) return;
        if (petMemory != null) petMemory.remember("voz_usuario", spoken);
        if (handleCompanionCommand(text, true)) return;
        if (VoiceCommandIntent.isMuteRequest(utterance)) {
            soundEnabled = false;
            if (localVoice != null) localVoice.stop();
            if (eyes != null) eyes.setMood(EyePetView.Mood.CALM);
            return;
        }
        if (VoiceCommandIntent.isUnmuteRequest(utterance)) {
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
        if (VoiceCommandIntent.isBatteryQuery(utterance)) {
            BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            speakIfEnabled(level >= 0 ? "El teléfono tiene " + level + " por ciento de batería."
                    : "No pude consultar la batería.");
            return;
        }
        if (VoiceCommandIntent.isTimeOrDateQuery(utterance)) {
            Date now = new Date();
            speakIfEnabled("Son las " + new SimpleDateFormat("HH:mm", new Locale("es", "AR")).format(now)
                    + ". Hoy es " + new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", new Locale("es", "AR")).format(now) + ".");
            return;
        }
        if (VoiceCommandIntent.isVolumeChangeRequest(utterance)) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) { speakIfEnabled("No pude acceder al volumen del teléfono."); return; }
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int requested = VoiceCommandIntent.requestedVolumePercent(utterance);
            int target = requested >= 0 ? Math.round(max * requested / 100f)
                    : Math.max(0, Math.min(max, current + (VoiceCommandIntent.volumeChangeDelta(utterance) * max / 100)));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            int actual = max <= 0 ? 0 : Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
            if (petMemory != null) petMemory.remember("volumen_dispositivo", actual + "%");
            speakIfEnabled("Ajusté el volumen multimedia al " + actual + " por ciento.");
            return;
        }
        if (VoiceCommandIntent.isVolumeQuery(utterance)) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            speakIfEnabled("El volumen multimedia está al "
                    + Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f
                    / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) + " por ciento.");
            return;
        }

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
        return BellotaToolCatalog.definitions();
    }

    private boolean isCurrentToolCall(VoiceProviderManager manager, String callId) {
        return manager != null ? manager.isToolCallCurrent(callId)
                : VoiceSessionService.isToolCallCurrent(this, callId);
    }

    private String executeBellotaTool(String name, JSONObject arguments,
                                      VoiceProviderManager manager, String callId) throws Exception {
        if (!isCurrentToolCall(manager, callId))
            throw new Exception("La solicitud pertenece a una sesión que ya terminó.");
        if ("get_weather".equals(name)) return executeWeatherTool(manager, callId);
        if (Looper.myLooper() == Looper.getMainLooper())
            return executeBellotaToolOnMainThread(name, arguments);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        handler.post(() -> {
            try {
                if (!isCurrentToolCall(manager, callId))
                    throw new Exception("La solicitud venció antes de ejecutar la acción.");
                result.set(executeBellotaToolOnMainThread(name, arguments));
            } catch (Exception e) { failure.set(e); }
            finally { completed.countDown(); }
        });
        if (!completed.await(6, TimeUnit.SECONDS)) throw new Exception("Bellota tardó demasiado en responder.");
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private String executeWeatherTool(VoiceProviderManager manager, String callId) throws Exception {
        if (!isCurrentToolCall(manager, callId))
            throw new Exception("La solicitud pertenece a una sesión que ya terminó.");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<>();
        handler.post(() -> { if (eyes != null) eyes.setMood(EyePetView.Mood.THINKING); });
        WeatherService.fetch(getApplicationContext(), value -> {
            if (eyes != null) eyes.setMood(EyePetView.Mood.HAPPY);
            if (petMemory != null) petMemory.remember("consulta_clima", value);
            response.set(value);
            completed.countDown();
        });
        if (!completed.await(22000L, TimeUnit.MILLISECONDS)) {
            handler.post(() -> { if (eyes != null) eyes.setMood(EyePetView.Mood.CALM); });
            throw new Exception("La consulta del clima tardó demasiado. Podés volver a intentarlo.");
        }
        if (!isCurrentToolCall(manager, callId))
            throw new Exception("La sesión terminó antes de completar la consulta del clima.");
        return response.get() == null ? "No pude obtener el clima en este momento." : response.get();
    }

    private String executeBellotaToolOnMainThread(String name, JSONObject args) throws Exception {
        if ("get_battery_status".equals(name)) {
            BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return level >= 0 ? "La batería del dispositivo está al " + level + " por ciento."
                    : "Android no informó el nivel de batería.";
        }
        if ("get_device_volume".equals(name)) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) throw new Exception("El control de audio no está disponible.");
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            return "El volumen multimedia está al " + (max <= 0 ? 0 : Math.round(current * 100f / max)) + " por ciento.";
        }
        if ("set_device_volume".equals(name)) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) throw new Exception("El control de audio no está disponible.");
            if (!args.has("percent") || args.isNull("percent")) throw new Exception("Falta el porcentaje de volumen.");
            int percent = args.getInt("percent");
            if (percent < 0 || percent > 100) throw new Exception("El volumen debe estar entre 0 y 100 por ciento.");
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = Math.round(max * percent / 100f);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            int actual = max <= 0 ? 0 : Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
            if (petMemory != null) petMemory.remember("volumen_dispositivo", actual + "%");
            return "Volumen multimedia ajustado al " + actual + " por ciento.";
        }
        if ("get_current_datetime".equals(name)) {
            Date now = new Date();
            return "Son las " + new SimpleDateFormat("HH:mm", new Locale("es", "AR")).format(now)
                    + ". Hoy es " + new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", new Locale("es", "AR")).format(now) + ".";
        }
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
            if (petMemory != null) petMemory.remember("animo_bellota", emotion.toLowerCase(Locale.ROOT));
            return "Mi expresión ahora es " + emotion.toLowerCase(Locale.ROOT) + ".";
        }
        if ("speak_as_bellota".equals(name)) {
            String phrase = args.optString("text", "").trim();
            if (phrase.isEmpty() || phrase.length() > 240) throw new Exception("La frase debe tener entre 1 y 240 caracteres.");
            if (isForcedState() || isAsleep()) return "No puedo hablar ahora: estoy durmiendo.";
            if (!soundEnabled) return "La voz está desactivada.";
            if (VoiceSessionService.isActive(this))
                return "Decí en voz alta exactamente esta frase, sin agregar comentarios: " + phrase;
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
            if (petMemory != null) petMemory.remember("interaccion", kind);
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
            boolean startedGame = playfield != null && playfield.isRunning();
            if (startedGame && petMemory != null) petMemory.remember("juego", game);
            return startedGame ? "Empecé a jugar: " + game + "." : "No pude iniciar el juego.";
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
            if (petMemory != null) petMemory.remember("consulta_clima", response);
            speakIfEnabled(response);
        });
    }

    private void speakIfEnabled(String text) {
        if (!soundEnabled || text == null || text.isEmpty()) return;
        // Mientras Realtime está activo, no mezclar la salida TTS local con el audio remoto.
        if (onlineVoiceManager != null && onlineVoiceManager.isConnected()) return;
        if (VoiceSessionService.isActive(this)) return;
        if (localVoice == null) return;
        if (petMemory != null) petMemory.remember("voz_bellota", text);
        pauseLocalRecognition();
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
        VoiceSessionService.restoreIfActive(this);
        startVoiceCommands();
        if (eyes != null) eyes.resumeAnimation();
    }

    @Override protected void onPause() {
        paused = true;
        handler.removeCallbacks(autonomousRoutine);

        if (localVoice != null) localVoice.stop();

        cancelActivities();

        if (localRecognitionPaused && onlineVoiceManager == null && !onlineVoiceConnecting
                && !VoiceSessionService.isActive(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            localRecognitionPaused = false;
            VoiceRecognitionService.resume(this);
        }

        if (eyes != null) eyes.pauseAnimation();
        if(sensorManager!=null)sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {

        toolCallExecutor.shutdownNow();
        VoiceRecognitionService.stop(this);
        handler.removeCallbacksAndMessages(null);
        if (voiceReceiverRegistered) {
            unregisterReceiver(voiceEventsReceiver);
            voiceReceiverRegistered = false;
        }

        

        if (localVoice != null) { localVoice.stop(); localVoice.release(); localVoice = null; }

        super.onDestroy();
    }
}
