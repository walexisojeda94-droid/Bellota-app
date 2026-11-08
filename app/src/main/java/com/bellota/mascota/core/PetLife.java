package com.bellota.mascota.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.TimeUnit;

/** Identidad temporal y necesidades básicas persistentes de Bellota. */
public class PetLife {
 private static final String PREFS="bellota_life", KEY_BIRTH="birth_time", KEY_ENERGY="energy", KEY_FUN="fun";
 private final SharedPreferences preferences;
 public PetLife(Context context) {
  preferences=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
  if(!preferences.contains(KEY_BIRTH)) preferences.edit().putLong(KEY_BIRTH,System.currentTimeMillis()).putInt(KEY_ENERGY,85).putInt(KEY_FUN,70).apply();
 }
 public long getAgeDays(){ long elapsed=Math.max(0L,System.currentTimeMillis()-preferences.getLong(KEY_BIRTH,System.currentTimeMillis())); return TimeUnit.MILLISECONDS.toDays(elapsed); }
 public long getAgeYears(){ return getAgeDays()/30L; }
 public int getDaysToNextBirthday(){ return 30-(int)(getAgeDays()%30L); }
 public int getEnergy(){ return preferences.getInt(KEY_ENERGY,85); }
 public int getFun(){ return preferences.getInt(KEY_FUN,70); }
 public void setNeeds(int energy,int fun){ preferences.edit().putInt(KEY_ENERGY,clamp(energy)).putInt(KEY_FUN,clamp(fun)).apply(); }
 private int clamp(int value){ return Math.max(0,Math.min(100,value)); }
}
