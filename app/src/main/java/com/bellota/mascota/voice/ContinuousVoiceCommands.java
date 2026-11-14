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
 public void start(){
  if(running)return;
  if(!SpeechRecognizer.isRecognitionAvailable(context)){status("No hay un servicio de reconocimiento de voz disponible.");return;}
  running=true;
  try {
   recognizer=SpeechRecognizer.createSpeechRecognizer(context);
   recognizer.setRecognitionListener(this);
   status("Servicio de reconocimiento Android iniciado; esperando disponibilidad del micrófono.");
   listenAgain(250);
  } catch(Exception e) {
   running=false;
   status("No se pudo crear el reconocedor: "+e.getClass().getSimpleName()+".");
  }
 }
 public void stop(){
  running=false; restarting=false; handler.removeCallbacksAndMessages(null);
  if(recognizer!=null){try{recognizer.cancel();recognizer.destroy();}catch(Exception ignored){}recognizer=null;}
  status("Reconocimiento local detenido.");
 }
 private void listenAgain(long delay){
  if(!running||restarting)return;
  restarting=true;
  handler.postDelayed(()->{
   restarting=false;
   if(!running||recognizer==null)return;
   try{
    Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,new Locale("es","AR").toLanguageTag());
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"es-AR");
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
    intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
    recognizer.startListening(intent);
    status("Solicitud de escucha enviada a Android.");
   }catch(Exception e){
    status("No se pudo iniciar la escucha: "+e.getClass().getSimpleName()+".");
    listenAgain(1800);
   }
  },delay);
 }
 @Override public void onReadyForSpeech(Bundle b){status("Android está listo para escuchar.");}
 @Override public void onBeginningOfSpeech(){status("Android detectó el comienzo de voz.");}
 @Override public void onEndOfSpeech(){status("Android detectó el fin de voz; esperando el resultado.");}
 @Override public void onResults(Bundle results){
  ArrayList<String> matches=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
  if(matches!=null&&!matches.isEmpty()&&matches.get(0)!=null&&!matches.get(0).trim().isEmpty()){
   String recognized=matches.get(0).trim();
   status("Android entregó una transcripción ("+recognized.length()+" caracteres).");
   if(listener!=null)listener.onRecognized(recognized);
  }else{
   status("Android finalizó la escucha, pero no entregó ninguna transcripción.");
  }
  listenAgain(450);
 }
 @Override public void onError(int error){
  status("Error de reconocimiento: "+errorName(error)+" (código "+error+").");
  listenAgain(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY?1500:900);
 }
 private String errorName(int error){
  switch(error){
   case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:return "tiempo de espera de red";
   case SpeechRecognizer.ERROR_NETWORK:return "error de red";
   case SpeechRecognizer.ERROR_AUDIO:return "error de audio";
   case SpeechRecognizer.ERROR_SERVER:return "error del servidor de reconocimiento";
   case SpeechRecognizer.ERROR_CLIENT:return "error del cliente de reconocimiento";
   case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:return "no se detectó voz";
   case SpeechRecognizer.ERROR_NO_MATCH:return "no se pudo reconocer lo dicho";
   case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:return "reconocedor ocupado";
   case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:return "permiso de micrófono insuficiente";
   default:return "error no clasificado";
  }
 }
 @Override public void onRmsChanged(float v){}
 @Override public void onBufferReceived(byte[] b){}
 @Override public void onPartialResults(Bundle b){}
 @Override public void onEvent(int i,Bundle b){}
 private void status(String value){if(listener!=null)listener.onStatus(value);}
}
