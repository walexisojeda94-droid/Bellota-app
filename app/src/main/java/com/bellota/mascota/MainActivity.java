package com.bellota.mascota;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
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
import android.widget.EditText;
import android.text.InputType;
import android.widget.Toast;
import com.bellota.mascota.core.PetLife;
import com.bellota.mascota.core.PetMemory;
import com.bellota.mascota.core.AutonomousBehavior;
import com.bellota.mascota.local.OfflineReplies;
import com.bellota.mascota.local.LocalConversationEngine;
import com.bellota.mascota.local.Reply;
import com.bellota.mascota.ui.EyePetView;
import com.bellota.mascota.voice.ContinuousVoiceCommands;
import com.bellota.mascota.voice.LocalVoice;
import com.bellota.mascota.weather.WeatherService;
import com.bellota.mascota.xiaozhi.XiaoZhiConnection;
import com.bellota.mascota.xiaozhi.XiaoZhiVoiceSession;
import com.bellota.mascota.xiaozhi.XiaoZhiMqttUdpSession;
import com.bellota.mascota.xiaozhi.McpConnection;
import com.bellota.mascota.xiaozhi.McpCredentialStore;
import org.json.JSONArray;
import java.util.Locale;

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
    private PetLife petLife;
    private AutonomousBehavior autonomy;
    private PetMemory petMemory;
    private long touchStartedAt;
    private float touchStartX, touchStartY, touchPressure;
    private XiaoZhiConnection xiaoZhiConnection;
    private XiaoZhiVoiceSession xiaoZhiVoiceSession;
    private XiaoZhiMqttUdpSession xiaoZhiMqttUdpSession;
    private boolean xiaoZhiVoiceFailed;
    private boolean xiaoZhiRefreshPending = true;
    private String pendingXiaoZhiTranscript;
    private Runnable pendingXiaoZhiFallback;
    private McpConnection mcpConnection;
    private McpCredentialStore mcpCredentialStore;
    private String pendingXiaoZhiCode;
    private boolean soundEnabled = true;
    private boolean waitingForWeatherPermission;
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private boolean lightTheme;
    private long lastThemeChange;
    private final Runnable autonomousRoutine = new Runnable() {
        @Override public void run() {
            if (eyes != null && autonomy != null && !isFinishing()) {
                int hour=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                AutonomousBehavior.Action action=autonomy.next(petLife,hour>=0 && hour<7);
                if(action!=AutonomousBehavior.Action.NONE && petMemory!=null) petMemory.remember("actividad", action.name());
                switch(action) {
                    case REST: eyes.setMood(EyePetView.Mood.SLEEPY); break;
                    case PLAY:
                        eyes.playAutonomousGame();
                        if(petLife!=null) petLife.setNeeds(Math.max(5,petLife.getEnergy()-2),Math.min(100,petLife.getFun()+8));
                        break;
                    case THINK: eyes.setMood(EyePetView.Mood.THINKING); break;
                    case LOOK_AROUND:
                        eyes.performAutonomousBehavior();
                        if(Math.random()<0.20d) moveEyesAroundScreen();
                        break;
                    case INITIATE_CHAT:
                        eyes.setMood(EyePetView.Mood.HAPPY);
                        speakIfEnabled(autonomy.initiativeLine());
                        break;
                    default: break;
                }
            }
            handler.postDelayed(this,20000L+(long)(Math.random()*18000L));
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
        xiaoZhiConnection = new XiaoZhiConnection(this);
        mcpCredentialStore = new McpCredentialStore(this);
        connectMcpIfConfigured();
        localVoice = new LocalVoice(this, (ready, detail) -> {
            if (ready && pendingXiaoZhiCode != null) {
                String code = pendingXiaoZhiCode;
                pendingXiaoZhiCode = null;
                speakIfEnabled(code);
            }
        });
        localVoice.setOnSpeechFinished(() -> {
            if (!isFinishing() && voiceCommands != null) voiceCommands.start();
        });
        setupAmbientTheme();
        requestMicrophonePermission();
        requestXiaoZhiActivation();
        handler.postDelayed(autonomousRoutine, 1800L);
    }

    private void buildPetScreen() {
        FrameLayout root = new FrameLayout(this);
        petRoot = root;
        root.setBackgroundColor(0xFF080D10);
        eyes = new EyePetView(this);
        eyes.setLightTheme(false);
        eyes.setOnClickListener(v -> { eyes.reactToAttention(); });
        eyes.setOnTouchListener((view, event) -> {
            if(event.getPointerCount()>1) {
                if(event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN && petMemory!=null)
                    petMemory.remember("contacto_multiple", "Bellota recibió un toque con varios dedos.");
                return false;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                touchStartedAt=System.currentTimeMillis();
                touchStartX=event.getX(); touchStartY=event.getY(); touchPressure=event.getPressure();
            } else if(event.getActionMasked()==MotionEvent.ACTION_UP) {
                long duration=System.currentTimeMillis()-touchStartedAt;
                float distance=(float)Math.hypot(event.getX()-touchStartX,event.getY()-touchStartY);
                boolean stroke=distance>24f || duration>420L;
                boolean firm=!stroke && touchPressure>=0.72f && duration<190L;
                String kind=firm?"rough_touch":"caress";
                if(autonomy!=null) autonomy.onInteraction(petLife,kind);
                if(petMemory!=null) petMemory.remember(kind,
                        firm?"Toque breve y firme; Bellota lo interpretó como un golpe.":"Contacto prolongado o suave; Bellota lo interpretó como una caricia.");
                if(eyes!=null) {
                    if(firm) eyes.setMood(EyePetView.Mood.SURPRISED);
                    else eyes.reactToAttention();
                }
            }
            return false;
        });
        int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.42f);
        int height = (int)(getResources().getDisplayMetrics().heightPixels * 0.34f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = (getResources().getDisplayMetrics().widthPixels - width) / 2;
        params.topMargin = (getResources().getDisplayMetrics().heightPixels - height) / 2;
        root.addView(eyes, params);
        // Acceso oculto: mantener pulsados los ojos para configurar MCP.
        eyes.setOnLongClickListener(v -> { showMcpSetupDialog(); return true; });
        setContentView(root);
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
        if (Build.VERSION.SDK_INT < 23) { startVoiceCommands(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceCommands();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
    }

    private void startVoiceCommands() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (xiaoZhiVoiceSession != null || xiaoZhiMqttUdpSession != null) return;
        if (!xiaoZhiRefreshPending && !xiaoZhiVoiceFailed && xiaoZhiConnection != null && xiaoZhiConnection.isConfigured()
                && xiaoZhiConnection.hasVoiceConfiguration()) {
            if ("mqtt".equals(xiaoZhiConnection.getTransport())) startXiaoZhiMqtt();
            else startXiaoZhiVoice();
            return;
        }
        if (voiceCommands != null) voiceCommands.stop();
        voiceCommands = new ContinuousVoiceCommands(this, new ContinuousVoiceCommands.Listener() {
            @Override public void onRecognized(String spoken) { handleVoiceCommand(spoken); }
            @Override public void onStatus(String status) { }
        });
        voiceCommands.start();
    }

    private void startXiaoZhiVoice() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (xiaoZhiVoiceSession != null || xiaoZhiVoiceFailed || xiaoZhiConnection == null || isFinishing()) return;
        if (voiceCommands != null) voiceCommands.stop();
        xiaoZhiVoiceSession = new XiaoZhiVoiceSession(xiaoZhiConnection, new XiaoZhiVoiceSession.Listener() {
            @Override public void onStatus(String status) {
                android.util.Log.i("BellotaXiaoZhi", status);
            }
            @Override public void onTranscript(String transcript) {
                runOnUiThread(() -> {
                    if (autonomy != null) autonomy.onInteraction(petLife,"attention");
                    if (petMemory != null) petMemory.remember("conversacion_usuario", transcript);
                    queueLocalFallback(transcript);
                    if (eyes != null) eyes.reactToAttention();
                    android.util.Log.i("BellotaXiaoZhi", "Usuario: " + transcript);
                });
            }
            @Override public void onSpeaking(boolean speaking) {
                runOnUiThread(() -> {
                    if (speaking) {
                        completeOnlineTurn();
                        if (localVoice != null) localVoice.stop();
                        if (eyes != null) eyes.setMood(EyePetView.Mood.HAPPY);
                    } else if (eyes != null) {
                        eyes.setMood(EyePetView.Mood.CALM);
                    }
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    android.util.Log.e("BellotaXiaoZhi", message);
                    xiaoZhiVoiceFailed = true;
                    fallbackPendingTranscript();
                    if (xiaoZhiVoiceSession != null) {
                        xiaoZhiVoiceSession.disconnect();
                        xiaoZhiVoiceSession = null;
                    }
                    if (!isFinishing() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startVoiceCommands();
                    }
                });
            }
        });
        xiaoZhiVoiceSession.connect();
    }

    private void startXiaoZhiMqtt() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (xiaoZhiMqttUdpSession != null || xiaoZhiVoiceFailed || xiaoZhiConnection == null || isFinishing()) return;
        if (voiceCommands != null) voiceCommands.stop();
        xiaoZhiMqttUdpSession = new XiaoZhiMqttUdpSession(xiaoZhiConnection, new XiaoZhiMqttUdpSession.Listener() {
            @Override public void onStatus(String status) { android.util.Log.i("BellotaXiaoZhi", status); }
            @Override public void onTranscript(String transcript) {
                runOnUiThread(() -> {
                    if (autonomy != null) autonomy.onInteraction(petLife,"attention");
                    if (petMemory != null) petMemory.remember("conversacion_usuario", transcript);
                    queueLocalFallback(transcript);
                    if (eyes != null) eyes.reactToAttention();
                });
            }
            @Override public void onSpeaking(boolean speaking) {
                runOnUiThread(() -> {
                    if (speaking) { completeOnlineTurn(); if (localVoice != null) localVoice.stop(); if (eyes != null) eyes.setMood(EyePetView.Mood.HAPPY); }
                    else if (eyes != null) eyes.setMood(EyePetView.Mood.CALM);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    android.util.Log.e("BellotaXiaoZhi", message);
                    xiaoZhiVoiceFailed = true;
                    fallbackPendingTranscript();
                    if (xiaoZhiMqttUdpSession != null) { xiaoZhiMqttUdpSession.disconnect(); xiaoZhiMqttUdpSession = null; }
                    if (!isFinishing() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceCommands();
                });
            }
        });
        xiaoZhiMqttUdpSession.connect();
    }

    private void queueLocalFallback(String transcript) {
        cancelLocalFallback(false);
        if (transcript == null || transcript.trim().isEmpty()) return;
        pendingXiaoZhiTranscript = transcript;
        pendingXiaoZhiFallback = () -> {
            String unanswered = pendingXiaoZhiTranscript;
            pendingXiaoZhiTranscript = null;
            pendingXiaoZhiFallback = null;
            if (!isFinishing() && unanswered != null) {
                android.util.Log.w("BellotaVoice", "XiaoZhi no inició TTS; usando respuesta local.");
                handleVoiceCommand(unanswered);
            }
        };
        handler.postDelayed(pendingXiaoZhiFallback, 9000L);
    }

    private void completeOnlineTurn() { cancelLocalFallback(true); }

    private void cancelLocalFallback(boolean answeredOnline) {
        if (pendingXiaoZhiFallback != null) handler.removeCallbacks(pendingXiaoZhiFallback);
        pendingXiaoZhiFallback = null;
        String transcript = pendingXiaoZhiTranscript;
        pendingXiaoZhiTranscript = null;
        if (answeredOnline && transcript != null && conversationEngine != null) {
            conversationEngine.rememberOnlineMessage(transcript);
        }
    }

    private void fallbackPendingTranscript() {
        String transcript = pendingXiaoZhiTranscript;
        cancelLocalFallback(false);
        if (transcript != null && !isFinishing()) handleVoiceCommand(transcript);
    }

    private void handleVoiceCommand(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return;
        String text = spoken.toLowerCase(new Locale("es", "AR"))
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").trim();
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
        if (eyes != null) eyes.reactToAttention();
        if (autonomy != null) autonomy.onInteraction(petLife,"attention");
        if (petMemory != null) petMemory.remember("voz_usuario", spoken);
        if(text.contains("bateria") || text.contains("carga del telefono")){ BatteryManager b=(BatteryManager)getSystemService(BATTERY_SERVICE); int level=b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); speakIfEnabled(level>=0?"El teléfono tiene "+level+" por ciento de batería.":"No pude consultar la batería."); return; }
        if(text.contains("que hora") || text.equals("hora") || text.contains("que dia es") || text.contains("fecha de hoy")){ Date now=new Date(); speakIfEnabled("Son las "+new SimpleDateFormat("HH:mm",new Locale("es","AR")).format(now)+". Hoy es "+new SimpleDateFormat("EEEE d 'de' MMMM",new Locale("es","AR")).format(now)+"."); return; }
        if(text.contains("volumen actual")){ AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE); speakIfEnabled("El volumen multimedia está al "+Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))+" por ciento."); return; }

        String mood = eyes == null ? "NEUTRAL" : eyes.getMood().name();
        Reply result = conversationEngine == null
                ? Reply.text(offlineReplies.reply(text, petLife, mood))
                : conversationEngine.respond(text, petLife, mood);
        if (result.type == Reply.Type.NEEDS_WEATHER) {
            requestWeather();
            return;
        }
        if (result.type == Reply.Type.TEXT && result.text != null) speakIfEnabled(result.text);
        else if (result.type == Reply.Type.NEEDS_AI) {
            speakIfEnabled("Ese comando ya está disponible.");
        }
    }

    private void requestXiaoZhiActivation() {
        if (xiaoZhiConnection == null) return;
        // Siempre consultar OTA: si ya está vinculado, se renueva la configuración
        // WebSocket/MQTT sin pedir otro código de activación.
        xiaoZhiConnection.requestActivationCode(new XiaoZhiConnection.ActivationListener() {
            @Override public void onCodeReceived(String code, String message) {
                runOnUiThread(() -> {
                    StringBuilder spokenCode = new StringBuilder();
                    for (int i = 0; i < code.length(); i++) {
                        if (Character.isDigit(code.charAt(i))) {
                            if (spokenCode.length() > 0) spokenCode.append(" ");
                            spokenCode.append(code.charAt(i));
                        }
                    }
                    String announcement = "Código de vinculación de XiaoZhi: " + spokenCode
                            + ". Ingresalo en xiaozhi punto eme, en tu cuenta, para vincular a Bellota.";
                    if (localVoice != null && localVoice.isReady()) speakIfEnabled(announcement);
                    else pendingXiaoZhiCode = announcement;
                });
            }
            @Override public void onActivated() {
                runOnUiThread(() -> {
                    xiaoZhiRefreshPending = false;
                    String transport = xiaoZhiConnection.getTransport();
                    android.util.Log.i("BellotaXiaoZhi", "OTA actualizado. Transporte recibido: "
                            + (transport == null ? "ninguno" : transport));
                    if ("websocket".equals(transport) && xiaoZhiConnection.hasVoiceConfiguration()) {
                        xiaoZhiVoiceFailed = false;
                        startXiaoZhiVoice();
                    } else if ("mqtt".equals(transport) && xiaoZhiConnection.hasVoiceConfiguration()) {
                        xiaoZhiVoiceFailed = false;
                        startXiaoZhiMqtt();
                    } else {
                        xiaoZhiVoiceFailed = true;
                        speakIfEnabled("Bellota todavía no recibió una configuración de voz de XiaoZhi. Voy a mantener el modo local.");
                        android.util.Log.w("BellotaXiaoZhi", "El OTA no entregó un transporte de voz.");
                        startVoiceCommands();
                    }
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    xiaoZhiRefreshPending = false;
                    xiaoZhiVoiceFailed = true;
                    android.util.Log.w("BellotaXiaoZhi", "OTA no disponible; continúo en modo local: " + message);
                    speakIfEnabled("Todavía no pude obtener la conexión de XiaoZhi. Voy a seguir funcionando en modo local.");
                    startVoiceCommands();
                });
            }
        });
    }

    private void showMcpSetupDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(12f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("wss://api.xiaozhi.me/mcp/?token=...");
        try {
            String saved = mcpCredentialStore.read();
            if (saved != null) input.setText(saved);
        } catch (Exception ignored) { }
        new AlertDialog.Builder(this)
                .setTitle("Conexión privada de Bellota")
                .setMessage("Pegá la dirección MCP de XiaoZhi. Se guardará cifrada en este teléfono y no se enviará a GitHub.")
                .setView(input)
                .setNegativeButton("Cancelar", (dialog, which) -> dialog.dismiss())
                .setNeutralButton("Borrar", (dialog, which) -> {
                    getSharedPreferences("bellota_private_mcp", MODE_PRIVATE).edit().clear().apply();
                    if (mcpConnection != null) mcpConnection.disconnect();
                    Toast.makeText(this, "Configuración MCP borrada del teléfono.", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Guardar y conectar", (dialog, which) -> {
                    String endpoint = input.getText().toString().trim();
                    if (!endpoint.startsWith("wss://") || !endpoint.contains("xiaozhi.me/mcp/")) {
                        Toast.makeText(this, "La dirección no parece ser el endpoint MCP de XiaoZhi.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        mcpCredentialStore.save(endpoint);
                        connectMcpIfConfigured();
                    } catch (Exception e) {
                        Toast.makeText(this, "No pude guardar la configuración segura.", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void connectMcpIfConfigured() {
        if (mcpCredentialStore == null) return;
        try {
            String endpoint = mcpCredentialStore.read();
            if (endpoint == null || endpoint.isEmpty()) return;
            if (mcpConnection == null) mcpConnection = new McpConnection();
            mcpConnection.connect(endpoint, new JSONArray(), null, status ->
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, status, Toast.LENGTH_SHORT).show()));
        } catch (Exception e) {
            android.util.Log.e("BellotaMCP", "No se pudo recuperar la configuración MCP cifrada.");
        }
    }

    private void setupAmbientTheme(){ sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE); if(sensorManager!=null){ lightSensor=sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT); if(lightSensor!=null)sensorManager.registerListener(this,lightSensor,SensorManager.SENSOR_DELAY_NORMAL); } }
    @Override public void onSensorChanged(SensorEvent event){ if(event.sensor.getType()!=Sensor.TYPE_LIGHT||petRoot==null)return; long now=System.currentTimeMillis(); if(now-lastThemeChange<15000L)return; float lux=event.values[0]; boolean next=lightTheme?lux<85f:lux<25f; if(next!=lightTheme){lightTheme=next;lastThemeChange=now;petRoot.setBackgroundColor(lightTheme?0xFFF2F5F3:0xFF080D10);if(eyes!=null)eyes.setLightTheme(lightTheme);} }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    private void requestWeather() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
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
        if (!soundEnabled || localVoice == null || text == null) return;
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
        enterImmersiveMode();
        if(sensorManager!=null&&lightSensor!=null)sensorManager.registerListener(this,lightSensor,SensorManager.SENSOR_DELAY_NORMAL);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startVoiceCommands();
        if (eyes != null) eyes.resumeAnimation();
    }

    @Override protected void onPause() {
        cancelLocalFallback(false);
        if (voiceCommands != null) voiceCommands.stop();
        if (xiaoZhiVoiceSession != null) { xiaoZhiVoiceSession.disconnect(); xiaoZhiVoiceSession = null; }
        if (xiaoZhiMqttUdpSession != null) { xiaoZhiMqttUdpSession.disconnect(); xiaoZhiMqttUdpSession = null; }
        if (eyes != null) eyes.pauseAnimation();
        if(sensorManager!=null)sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        cancelLocalFallback(false);
        handler.removeCallbacksAndMessages(null);
        if (voiceCommands != null) { voiceCommands.stop(); voiceCommands = null; }
        if (xiaoZhiVoiceSession != null) { xiaoZhiVoiceSession.disconnect(); xiaoZhiVoiceSession = null; }
        if (xiaoZhiMqttUdpSession != null) { xiaoZhiMqttUdpSession.disconnect(); xiaoZhiMqttUdpSession = null; }
        if (localVoice != null) { localVoice.stop(); localVoice.release(); localVoice = null; }
        if (mcpConnection != null) { mcpConnection.disconnect(); mcpConnection = null; }
        super.onDestroy();
    }
}
