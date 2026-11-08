package com.bellota.mascota;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.bellota.mascota.core.BellotaState;
import com.bellota.mascota.core.PetExpression;
import com.bellota.mascota.core.PetLife;
import com.bellota.mascota.local.OfflineReplies;
import com.bellota.mascota.ui.GifFaceView;
import com.bellota.mascota.voice.ContinuousVoiceCommands;
import com.bellota.mascota.voice.LocalVoice;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
 private static final int PERMISSIONS_REQUEST=41;
 private final BellotaState state=new BellotaState();
 private final OfflineReplies offlineReplies=new OfflineReplies();
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Random random=new Random();
 private GifFaceView faceView; private TextView message,voiceStatus,ageStatus,lifeStatus;
 private LocalVoice localVoice; private ContinuousVoiceCommands voiceCommands; private PetLife petLife;
 private boolean soundEnabled=true; private long lastInteractionAt;
 private final Runnable idleRoutine=new Runnable(){@Override public void run(){updateAge();if(System.currentTimeMillis()-lastInteractionAt>45000L)performAutonomousAction();handler.postDelayed(this,18000L);}};

 @Override protected void onCreate(Bundle savedInstanceState){
  super.onCreate(savedInstanceState);
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  petLife=new PetLife(this);lastInteractionAt=System.currentTimeMillis();
  buildHomeScreen();showExpression(PetExpression.HAPPY);
  localVoice=new LocalVoice(this,(ready,detail)->{if(voiceStatus!=null)voiceStatus.setText(detail);});
  requestDevicePermissions();updateAge();handler.postDelayed(idleRoutine,18000L);
 }
 private void buildHomeScreen(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);root.setPadding(dp(20),dp(24),dp(20),dp(20));root.setBackgroundColor(0xFF101820);
  TextView title=new TextView(this);title.setText("BELLOTA");title.setTextColor(0xFF00BFA6);title.setTextSize(27);title.setGravity(Gravity.CENTER);title.setLetterSpacing(0.12f);root.addView(title,matchWrap());
  ageStatus=new TextView(this);ageStatus.setTextColor(0xFF9FB3C8);ageStatus.setTextSize(13);ageStatus.setGravity(Gravity.CENTER);root.addView(ageStatus,matchWrap());
  faceView=new GifFaceView(this);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f);fp.topMargin=dp(16);fp.bottomMargin=dp(12);root.addView(faceView,fp);
  message=new TextView(this);message.setText("¡Hola! Soy Bellota.");message.setTextColor(0xFFFFFFFF);message.setTextSize(19);message.setGravity(Gravity.CENTER);root.addView(message,matchWrap());
  lifeStatus=new TextView(this);lifeStatus.setTextColor(0xFF00BFA6);lifeStatus.setTextSize(13);lifeStatus.setGravity(Gravity.CENTER);root.addView(lifeStatus,matchWrap());
  voiceStatus=new TextView(this);voiceStatus.setText("Preparando escucha…");voiceStatus.setTextColor(0xFF9FB3C8);voiceStatus.setTextSize(12);voiceStatus.setGravity(Gravity.CENTER);root.addView(voiceStatus,matchWrap());
  HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout choices=new LinearLayout(this);
  for(final PetExpression expression:PetExpression.values()){Button b=new Button(this);b.setText(expression.label);b.setAllCaps(false);b.setOnClickListener(v->{lastInteractionAt=System.currentTimeMillis();showExpression(expression);message.setText("Estoy "+expression.label.toLowerCase(Locale.ROOT)+".");});choices.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(48)));}
  scroll.addView(choices);LinearLayout.LayoutParams sp=matchWrap();sp.topMargin=dp(12);root.addView(scroll,sp);
  Button greet=new Button(this);greet.setText("Saludar");greet.setAllCaps(false);greet.setOnClickListener(v->{lastInteractionAt=System.currentTimeMillis();String reply=offlineReplies.reply("hola");message.setText(reply);showExpression(PetExpression.HAPPY);speakIfEnabled(reply);});root.addView(greet,matchWrap());
  setContentView(root);
 }
 private void requestDevicePermissions(){
  if(Build.VERSION.SDK_INT<23){startVoiceCommands();return;}
  boolean mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
  boolean camera=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
  if(mic&&camera)startVoiceCommands();else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA},PERMISSIONS_REQUEST);
 }
 private void startVoiceCommands(){
  if(voiceCommands!=null)return;
  voiceCommands=new ContinuousVoiceCommands(this,new ContinuousVoiceCommands.Listener(){
   @Override public void onRecognized(String spoken){handleVoiceCommand(spoken);}
   @Override public void onStatus(String status){if(voiceStatus!=null)voiceStatus.setText(status);}
  });voiceCommands.start();
 }
 private void handleVoiceCommand(String spoken){
  if(spoken==null)return;
  String text=spoken.toLowerCase(new Locale("es","AR")).replace("á","a").replace("é","e").replace("í","i").replace("ó","o").replace("ú","u");
  lastInteractionAt=System.currentTimeMillis();
  if(text.contains("silencio")&&text.contains("bellota")){soundEnabled=false;if(localVoice!=null)localVoice.stop();message.setText("Me quedo en silencio, pero sigo escuchando.");showExpression(PetExpression.NEUTRAL);}
  else if((text.contains("sonido")||text.contains("habla"))&&text.contains("bellota")){soundEnabled=true;message.setText("¡Volvió mi voz!");showExpression(PetExpression.HAPPY);speakIfEnabled("¡Ya puedo hablar otra vez!");}
  else if(text.contains("mira esto")&&text.contains("bellota")){message.setText("Te escuché. La cámara y el análisis visual todavía se están preparando.");showExpression(PetExpression.SURPRISED);}
  else{String reply=offlineReplies.reply(text);message.setText(reply);showExpression(PetExpression.HAPPY);speakIfEnabled(reply);}
 }
 private void performAutonomousAction(){
  int energy=petLife.getEnergy(),fun=petLife.getFun();
  if(energy<25){showExpression(PetExpression.SLEEPY);message.setText("Creo que voy a dormir un ratito… Zzz.");lifeStatus.setText("Descansando");petLife.setNeeds(Math.min(100,energy+5),fun);return;}
  int action=random.nextInt(4);
  if(action==0){showExpression(PetExpression.THINKING);message.setText("Me pregunto qué habrá para descubrir hoy.");lifeStatus.setText("Curiosa");}
  else if(action==1){showExpression(PetExpression.HAPPY);message.setText("Estoy inventando un juego para mí.");lifeStatus.setText("Jugando sola");petLife.setNeeds(energy-2,Math.min(100,fun+5));}
  else if(action==2){showExpression(PetExpression.HAPPY);message.setText("¡Estoy contenta de acompañarte!");lifeStatus.setText("De buen ánimo");}
  else{showExpression(PetExpression.SLEEPY);message.setText("Un bostezo… creo que necesito descansar.");lifeStatus.setText("Con sueño");petLife.setNeeds(Math.max(0,energy-3),fun);}
 }
 private void updateAge(){if(ageStatus==null||petLife==null)return;long years=petLife.getAgeYears(),days=petLife.getAgeDays();ageStatus.setText(String.format(Locale.getDefault(),"Edad: %d %s · Día %d · Cumpleaños en %d días",years,years==1?"año":"años",days,petLife.getDaysToNextBirthday()));}
 private void speakIfEnabled(String text){if(soundEnabled&&localVoice!=null)localVoice.speak(text);}
 private void showExpression(PetExpression expression){state.setExpression(expression);faceView.loadAsset(this,expression.asset+".gif");}
 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==PERMISSIONS_REQUEST){boolean mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;boolean camera=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;if(mic)startVoiceCommands();if(!mic&&voiceStatus!=null)voiceStatus.setText("Necesito permiso de micrófono para escuchar.");else if(camera&&voiceStatus!=null)voiceStatus.setText("Micrófono autorizado. Cámara autorizada para la función visual.");}}
 @Override protected void onResume(){super.onResume();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(voiceCommands!=null)voiceCommands.start();}
 @Override protected void onPause(){if(voiceCommands!=null)voiceCommands.stop();super.onPause();}
 @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(voiceCommands!=null){voiceCommands.stop();voiceCommands=null;}if(localVoice!=null){localVoice.stop();localVoice.release();localVoice=null;}super.onDestroy();}
 private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);}
 private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
}
