package com.bellota.mascota.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/** Reconocimiento por sesiones consecutivas del servicio de voz instalado en Android. */
public class ContinuousVoiceCommands implements RecognitionListener {
 public interface Listener { void onRecognized(String text); void onStatus(String status); }
 private final Context context; private final Listener listener; private final Handler handler=new Handler(Looper.getMainLooper());
 private SpeechRecognizer recognizer; private boolean running; private boolean restarting;
 public ContinuousVoiceCommands(Context context,Listener listener){ this.context=context.getApplicationContext(); this.listener=listener; }
 public void start(){ if(running)return; if(!SpeechRecognizer.isRecognitionAvailable(context)){status("No hay un servicio de reconocimiento de voz disponible.");return;} running=true; recognizer=SpeechRecognizer.createSpeechRecognizer(context); recognizer.setRecognitionListener(this); listenAgain(250); }
 public void stop(){ running=false; restarting=false; handler.removeCallbacksAndMessages(null); if(recognizer!=null){try{recognizer.cancel();recognizer.destroy();}catch(Exception ignored){} recognizer=null;} }
 private void listenAgain(long delay){ if(!running||restarting)return; restarting=true; handler.postDelayed(()->{restarting=false;if(!running||recognizer==null)return;try{Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,new Locale("es","AR").toLanguageTag());intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"es-AR");intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);recognizer.startListening(intent);status("Escuchando comandos en español…");}catch(Exception e){status("No se pudo iniciar el reconocimiento de voz.");listenAgain(1800);}},delay); }
 @Override public void onResults(Bundle results){ArrayList<String> matches=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(matches!=null&&!matches.isEmpty()&&listener!=null)listener.onRecognized(matches.get(0));listenAgain(450);}
 @Override public void onError(int error){listenAgain(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY?1500:900);}
 @Override public void onReadyForSpeech(Bundle b){} @Override public void onBeginningOfSpeech(){} @Override public void onRmsChanged(float v){} @Override public void onBufferReceived(byte[] b){} @Override public void onEndOfSpeech(){} @Override public void onPartialResults(Bundle b){} @Override public void onEvent(int i,Bundle b){}
 private void status(String value){if(listener!=null)listener.onStatus(value);}
}
