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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.text.InputType;
import android.widget.Toast;
import com.bellota.mascota.core.PetLife;
import com.bellota.mascota.core.AutonomousBehavior;
import com.bellota.mascota.local.OfflineReplies;
import com.bellota.mascota.ui.EyePetView;
import com.bellota.mascota.voice.ContinuousVoiceCommands;
import com.bellota.mascota.voice.LocalVoice;
import com.bellota.mascota.weather.WeatherService;
import com.bellota.mascota.xiaozhi.XiaoZhiConnection;
import com.bellota.mascota.xiaozhi.McpConnection;
import com.bellota.mascota.xiaozhi.McpCredentialStore;
import org.json.JSONArray;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int MICROPHONE_PERMISSION = 41;
    private static final int LOCATION_PERMISSION = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OfflineReplies offlineReplies = new OfflineReplies();
    private EyePetView eyes;
    private FrameLayout petRoot;
    private LocalVoice localVoice;
    private ContinuousVoiceCommands voiceCommands;
    private PetLife petLife;
    private AutonomousBehavior autonomy;
    private XiaoZhiConnection xiaoZhiConnection;
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
        eyes.setOnClickListener(v -> { eyes.reactToAttention(); if(autonomy!=null) autonomy.onInteraction(petLife); });
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
        if (voiceCommands != null) voiceCommands.stop();
        voiceCommands = new ContinuousVoiceCommands(this, new ContinuousVoiceCommands.Listener() {
            @Override public void onRecognized(String spoken) { handleVoiceCommand(spoken); }
            @Override public void onStatus(String status) { }
        });
        voiceCommands.start();
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
        if (autonomy != null) autonomy.onInteraction(petLife);
        if(text.contains("bateria") || text.contains("carga del telefono")){ BatteryManager b=(BatteryManager)getSystemService(BATTERY_SERVICE); int level=b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); speakIfEnabled(level>=0?"El teléfono tiene "+level+" por ciento de batería.":"No pude consultar la batería."); return; }
        if(text.contains("que hora") || text.equals("hora") || text.contains("que dia es") || text.contains("fecha de hoy")){ Date now=new Date(); speakIfEnabled("Son las "+new SimpleDateFormat("HH:mm",new Locale("es","AR")).format(now)+". Hoy es "+new SimpleDateFormat("EEEE d 'de' MMMM",new Locale("es","AR")).format(now)+"."); return; }
        if(text.contains("volumen actual")){ AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE); speakIfEnabled("El volumen multimedia está al "+Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))+" por ciento."); return; }

        if (offlineReplies.isWeatherQuestion(text)) {
            requestWeather();
            return;
        }

        String mood = eyes == null ? "NEUTRAL" : eyes.getMood().name();
        String reply = offlineReplies.reply(text, petLife, mood);
        if (reply != null) speakIfEnabled(reply);
        else speakIfEnabled("No tengo ese dato a mano todavía, pero puedo seguir conversando con vos. Contame un poco más.");
    }

    private void requestXiaoZhiActivation() {
        if (xiaoZhiConnection == null || xiaoZhiConnection.isConfigured()) return;
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
                runOnUiThread(() -> speakIfEnabled("Bellota ya está vinculada con XiaoZhi."));
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> speakIfEnabled("Todavía no pude obtener el código de XiaoZhi. " + message));
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
            mcpConnection.connect(endpoint, new McpConnection.Listener() {
                @Override public void onStatus(String status) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, status, Toast.LENGTH_SHORT).show());
                }
                @Override public void onTools(JSONArray tools) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < tools.length(); i++) {
                        org.json.JSONObject tool = tools.optJSONObject(i);
                        if (tool != null) {
                            if (names.length() > 0) names.append(", ");
                            names.append(tool.optString("name", "herramienta"));
                        }
                    }
                    android.util.Log.i("BellotaMCP", "Herramientas disponibles: " + names);
                }
            });
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
        if (voiceCommands != null) voiceCommands.start();
        if (eyes != null) eyes.resumeAnimation();
    }

    @Override protected void onPause() {
        if (voiceCommands != null) voiceCommands.stop();
        if (eyes != null) eyes.pauseAnimation();
        if(sensorManager!=null)sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (voiceCommands != null) { voiceCommands.stop(); voiceCommands = null; }
        if (localVoice != null) { localVoice.stop(); localVoice.release(); localVoice = null; }
        if (mcpConnection != null) { mcpConnection.disconnect(); mcpConnection = null; }
        super.onDestroy();
    }
}
