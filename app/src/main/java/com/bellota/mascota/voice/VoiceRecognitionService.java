package com.bellota.mascota.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;


/**
 * Mantiene el reconocimiento local mientras la interfaz está en segundo plano.
 * El micrófono queda indicado por una notificación persistente con acción Detener.
 */
public class VoiceRecognitionService extends Service {
    public static final String ACTION_START = "com.bellota.mascota.voice.START";
    public static final String ACTION_STOP = "com.bellota.mascota.voice.STOP";
    public static final String ACTION_PAUSE = "com.bellota.mascota.voice.PAUSE";
    public static final String ACTION_RESUME = "com.bellota.mascota.voice.RESUME";
    public static final String ACTION_EXTERNAL_MIC = "com.bellota.mascota.voice.EXTERNAL_MIC";
    public static final String EXTRA_ACTIVE = "active";
    public static final String ACTION_RECOGNIZED = "com.bellota.mascota.voice.RECOGNIZED";
    public static final String ACTION_STATUS = "com.bellota.mascota.voice.STATUS";
    public static final String ACTION_STOPPED = "com.bellota.mascota.voice.STOPPED";
    public static final String EXTRA_TEXT = "text";
    private static final String CHANNEL_ID = "bellota_microphone";
    private static final int NOTIFICATION_ID = 2401;

    private static volatile VoiceRecognitionService instance;

    private ContinuousVoiceCommands recognizer;
    private volatile boolean foregroundStarted;
    private boolean paused;
    private boolean externalMicActive;

    public static void start(Context context) {
        startServiceAction(context, ACTION_START);
    }

    public static void pause(Context context) {
        startServiceAction(context, ACTION_PAUSE);
    }

    public static void resume(Context context) {
        startServiceAction(context, ACTION_RESUME);
    }

    public static void stop(Context context) {
        // stopService() may race with a queued startForegroundService() call. Reset the
        // in-memory promotion state before asking Android to stop the service so a
        // reused instance cannot mistake itself for a foreground service on restart.
        VoiceRecognitionService current = instance;
        if (current != null) current.foregroundStarted = false;
        context.stopService(new Intent(context, VoiceRecognitionService.class));
    }

    public static boolean isForegroundService() {
        VoiceRecognitionService current = instance;
        return current != null && current.foregroundStarted;
    }

    public static void setExternalMicActive(Context context, boolean active) {
        Intent intent = new Intent(context, VoiceRecognitionService.class);
        intent.setAction(ACTION_EXTERNAL_MIC);
        intent.putExtra(EXTRA_ACTIVE, active);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static void startServiceAction(Context context, String action) {
        Intent intent = new Intent(context, VoiceRecognitionService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        // startForegroundService() must be promoted immediately on Android 8+.
        // Doing this in onCreate also covers start/stop command races during recovery.
        ensureForeground();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Promote every command before branching: STOP and pause actions may also be
        // delivered to a service originally launched with startForegroundService().
        ensureForeground();
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Intent stopped = new Intent(ACTION_STOPPED);
            stopped.setPackage(getPackageName());
            sendBroadcast(stopped);
            stopRecognition();
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendStatus("Reconocimiento detenido: Android no concedió permiso de micrófono.");
            stopRecognition();
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_EXTERNAL_MIC.equals(action)) {
            externalMicActive = intent != null && intent.getBooleanExtra(EXTRA_ACTIVE, false);
            paused = externalMicActive;
            if (externalMicActive) stopRecognition();
            else startRecognition();
            updateNotification();
        } else if (ACTION_PAUSE.equals(action)) {
            paused = true;
            externalMicActive = false;
            stopRecognition();
            updateNotification();
        } else if (ACTION_RESUME.equals(action)) {
            paused = false;
            externalMicActive = false;
            updateNotification();
            startRecognition();
        } else {
            paused = false;
            externalMicActive = false;
            updateNotification();
            startRecognition();
        }
        // El micrófono no se reactiva automáticamente después de una muerte del proceso
        // o un reinicio del teléfono. El usuario vuelve a abrir Bellota para reanudarlo.
        return START_NOT_STICKY;
    }

    private void sendStatus(String value) {
        Intent event = new Intent(ACTION_STATUS);
        event.setPackage(getPackageName());
        event.putExtra(EXTRA_TEXT, value);
        sendBroadcast(event);
    }

    private void ensureForeground() {
        if (foregroundStarted) return;
        startForeground(NOTIFICATION_ID, buildNotification());
        foregroundStarted = true;
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, VoiceRecognitionService.class);
        stopIntent.setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(externalMicActive ? "Bellota en conversación online" : paused ? "Micrófono de Bellota en pausa" : "Bellota está escuchando")
                .setContentText(externalMicActive ? "Micrófono activo para OpenAI Realtime." : paused ? "El reconocimiento local está temporalmente pausado."
                        : Build.VERSION.SDK_INT >= 31
                        ? "Audio procesado por el reconocedor del dispositivo."
                        : "Android 7-11: sin conexión preferido, pero el proveedor puede requerir Internet.")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent);
        return builder.build();
    }

    private void updateNotification() {
        if (!foregroundStarted) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Micrófono de Bellota", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Indica cuándo Bellota mantiene activo el reconocimiento de voz.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startRecognition() {
        if (paused) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendStatus("Reconocimiento detenido: Android no concedió permiso de micrófono.");
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
            return;
        }
        if (recognizer == null) {
            recognizer = new ContinuousVoiceCommands(getApplicationContext(),
                    new ContinuousVoiceCommands.Listener() {
                        @Override public void onRecognized(String text) {
                            Intent event = new Intent(ACTION_RECOGNIZED);
                            event.setPackage(getPackageName());
                            event.putExtra(EXTRA_TEXT, text);
                            sendBroadcast(event);
                        }

                        @Override public void onStatus(String status) {
                            sendStatus(status);
                            if (status != null && (status.startsWith("No hay un servicio de reconocimiento")
                                    || status.startsWith("No hay un reconocedor de voz local")
                                    || status.startsWith("No hay un motor de reconocimiento on-device")
                                    || status.startsWith("Reconocimiento detenido: Android no concedió permiso"))) {
                                stopRecognition();
                                stopForeground(true);
                                foregroundStarted = false;
                                stopSelf();
                            }
                        }
                    });
        }
        recognizer.start();
    }

    private void stopRecognition() {
        if (recognizer != null) {
            recognizer.stop();
            recognizer = null;
        }
    }

    @Override public void onDestroy() {
        stopRecognition();
        foregroundStarted = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
