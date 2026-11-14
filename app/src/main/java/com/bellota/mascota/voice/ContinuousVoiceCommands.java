package com.bellota.mascota.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import com.bellota.mascota.core.DebugLog;
import java.util.ArrayList;

/** Reconocimiento continuo con recuperación de idioma y del servicio Android. */
public class ContinuousVoiceCommands {
 private static final String TAG = "BellotaSpeech";
 public interface Listener { void onRecognized(String text); void onStatus(String status); }

 // ERROR_LANGUAGE_NOT_SUPPORTED se incorporó en versiones nuevas de Android.
 private static final int ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT = 12;
 private final Context context;
 private final Listener listener;
 private final Handler handler = new Handler(Looper.getMainLooper());
 private SpeechRecognizer recognizer;
 private boolean running;
 private boolean restarting;
 private boolean preferOffline = true;
 private int languageMode;
 private int clientErrorCount;
 private long generation;

 public ContinuousVoiceCommands(Context context, Listener listener) {
  this.context = context.getApplicationContext();
  this.listener = listener;
 }

 public void start() {
  if (running) return;
  if (!SpeechRecognizer.isRecognitionAvailable(context)) {
   status("No hay un servicio de reconocimiento de voz disponible.");
   return;
  }
  running = true;
  preferOffline = true;
  languageMode = 0;
  clientErrorCount = 0;
  final long session = ++generation;
  try {
   createRecognizer(session);
   status("Servicio de reconocimiento Android iniciado; preparando el micrófono.");
   listenAgain(session, 250);
  } catch (Exception e) {
   running = false;
   generation++;
   releaseRecognizer();
   status("No se pudo crear el reconocedor: " + e.getClass().getSimpleName() + ".");
  }
 }

 public void stop() {
  running = false;
  restarting = false;
  generation++;
  handler.removeCallbacksAndMessages(null);
  releaseRecognizer();
  status("Reconocimiento local detenido.");
 }

 private void createRecognizer(long session) {
  SpeechRecognizer created = SpeechRecognizer.createSpeechRecognizer(context);
  recognizer = created;
  created.setRecognitionListener(new SessionListener(session, created));
 }

 private void releaseRecognizer() {
  SpeechRecognizer current = recognizer;
  recognizer = null;
  if (current != null) {
   try { current.cancel(); } catch (Exception ignored) { }
   try { current.destroy(); } catch (Exception ignored) { }
  }
 }

 private boolean isCurrent(long session, SpeechRecognizer source) {
  return running && generation == session && recognizer == source;
 }

 private void listenAgain(final long session, long delay) {
  if (!running || generation != session || recognizer == null || restarting) return;
  restarting = true;
  handler.postDelayed(() -> {
   if (!running || generation != session || recognizer == null) return;
   restarting = false;
   SpeechRecognizer current = recognizer;
   try {
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    String language = languageTag();
    if (language != null) {
     intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
    }
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    // Primero intenta el modelo descargado. Si Android devuelve 12, se permite
    // reconocimiento online y luego se prueban variantes de español.
    intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    DebugLog.i(TAG, "startListening: locale=" + (language == null ? "sistema" : language)
      + "; offline=" + preferOffline + "; generation=" + session + ".");
    current.startListening(intent);
    status("Escucha solicitada a Android (" + (language == null ? "idioma del sistema" : language)
      + (preferOffline ? ", modo sin conexión" : ", modo en línea") + ").");
   } catch (Exception e) {
    if (!running || generation != session || recognizer != current) return;
    status("No se pudo iniciar la escucha: " + e.getClass().getSimpleName() + ".");
    listenAgain(session, 2500);
   }
  }, delay);
 }

 private String languageTag() {
  switch (languageMode) {
   case 0: return "es-AR";
   case 1: return "es-ES";
   case 2: return "es";
   default: return null; // dejar que el servicio elija el idioma configurado en Android
  }
 }

 private void recoverClientError(long session) {
  if (!running || generation != session || restarting) return;
  restarting = true;
  long delay = Math.min(10000L, 2000L * (1L << Math.min(clientErrorCount++, 2)));
  status("Reiniciando el servicio de reconocimiento Android.");
  handler.postDelayed(() -> {
   if (!running || generation != session) return;
   releaseRecognizer();
   try {
    createRecognizer(session);
    restarting = false;
    listenAgain(session, 350);
   } catch (Exception e) {
    restarting = false;
    status("Android no pudo reiniciar el reconocimiento; se volverá a intentar.");
    recoverClientError(session);
   }
  }, delay);
 }

 private final class SessionListener implements RecognitionListener {
  private final long session;
  private final SpeechRecognizer source;
  SessionListener(long session, SpeechRecognizer source) {
   this.session = session;
   this.source = source;
  }

  @Override public void onReadyForSpeech(Bundle b) {
   if (isCurrent(session, source)) {
    clientErrorCount = 0;
    status("Android está listo para escuchar.");
   }
  }
  @Override public void onBeginningOfSpeech() {
   if (isCurrent(session, source)) {
    DebugLog.i(TAG, "onBeginningOfSpeech; locale=" + (languageTag() == null ? "sistema" : languageTag()) + ".");
    status("Android detectó el comienzo de voz.");
   }
  }
  @Override public void onEndOfSpeech() {
   if (isCurrent(session, source)) {
    DebugLog.i(TAG, "onEndOfSpeech; esperando resultado.");
    status("Android detectó el fin de voz; esperando el resultado.");
   }
  }
  @Override public void onResults(Bundle results) {
   if (!isCurrent(session, source)) return;
   ArrayList<String> matches = results == null ? null
     : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
   if (matches != null && !matches.isEmpty() && matches.get(0) != null
     && !matches.get(0).trim().isEmpty()) {
    String recognized = matches.get(0).trim();
    DebugLog.i(TAG, "onResults: candidatos=" + matches.size() + "; primerResultadoChars=" + recognized.length() + ".");
    status("Android entregó una transcripción (" + recognized.length() + " caracteres).");
    if (listener != null && isCurrent(session, source)) listener.onRecognized(recognized);
   } else {
    DebugLog.w(TAG, "onResults sin candidatos de transcripción.");
    status("Android finalizó la escucha, pero no entregó ninguna transcripción.");
   }
   listenAgain(session, 450);
  }
  @Override public void onError(int error) {
   if (!isCurrent(session, source)) return;
   DebugLog.w(TAG, "onError=" + error + " (" + errorName(error) + "); locale="
     + (languageTag() == null ? "sistema" : languageTag()) + "; offline=" + preferOffline
     + "; languageMode=" + languageMode + ".");
   status("Error de reconocimiento: " + errorName(error) + " (código " + error + ").");
   if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
    running = false;
    restarting = false;
    generation++;
    releaseRecognizer();
    status("Reconocimiento detenido: Android no concedió permiso de micrófono.");
    return;
   }
   if (error == ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT) {
    if (preferOffline) {
     preferOffline = false;
     status("El modelo de español sin conexión no está disponible; probando reconocimiento en línea.");
    } else if (languageMode < 3) {
     languageMode++;
     status("El servicio no admite el idioma solicitado; probando otra variante de español.");
    } else {
     status("Android no admite las variantes de español probadas. Se intentará con el idioma del sistema.");
    }
    listenAgain(session, 900);
    return;
   }
   if (error == SpeechRecognizer.ERROR_CLIENT) {
    recoverClientError(session);
    return;
   }
   listenAgain(session, error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 2500 : 1500);
  }
  @Override public void onRmsChanged(float v) { }
  @Override public void onBufferReceived(byte[] b) { }
  @Override public void onPartialResults(Bundle b) { }
  @Override public void onEvent(int i, Bundle b) { }
 }

 private String errorName(int error) {
  switch (error) {
   case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "tiempo de espera de red";
   case SpeechRecognizer.ERROR_NETWORK: return "error de red";
   case SpeechRecognizer.ERROR_AUDIO: return "error de audio";
   case SpeechRecognizer.ERROR_SERVER: return "error del servidor de reconocimiento";
   case SpeechRecognizer.ERROR_CLIENT: return "error del cliente de reconocimiento";
   case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no se detectó voz";
   case SpeechRecognizer.ERROR_NO_MATCH: return "no se pudo reconocer lo dicho";
   case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "reconocedor ocupado";
   case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "permiso de micrófono insuficiente";
   case ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT: return "idioma no compatible";
   default: return "error no clasificado";
  }
 }

 private void status(String value) {
  if (listener != null) listener.onStatus(value);
 }
}
