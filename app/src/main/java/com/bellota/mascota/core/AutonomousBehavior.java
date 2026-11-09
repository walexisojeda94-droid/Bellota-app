package com.bellota.mascota.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Random;

/** Decide actividades autonomas y conserva necesidades, rasgos y tiempos entre aperturas. */
public final class AutonomousBehavior {
    public enum Action { LOOK_AROUND, PLAY, REST, THINK, INITIATE_CHAT, NONE }
    private static final String PREFS="bellota_autonomy";
    private static final String LAST_TICK="last_tick", LAST_USER="last_user", LAST_CHAT="last_chat";
    private static final String REMAINDER="decay_remainder";
    private final SharedPreferences prefs;
    private final Random random=new Random();

    public AutonomousBehavior(Context context) {
        prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(!prefs.contains(LAST_TICK)) prefs.edit().putLong(LAST_TICK,now).putLong(LAST_USER,now).putLong(LAST_CHAT,now).apply();
        if(!prefs.contains("affection")) prefs.edit().putInt("affection",50).putInt("curiosity",65).putInt("humor",55).putInt("independence",50).putInt("irritation",0).putLong("last_ignored_update",now).apply();
    }

    public void onInteraction(PetLife life) { onInteraction(life,"attention"); }

    public void onInteraction(PetLife life, String kind) {
        long now=System.currentTimeMillis();
        int affection=getTrait("affection"), humor=getTrait("humor"), irritation=getTrait("irritation");
        int fun=life==null?60:life.getFun();
        if("caress".equals(kind)) {
            affection=clamp(affection+3); humor=clamp(humor+1); irritation=clamp(irritation-3);
            if(life!=null) life.setNeeds(Math.min(100,life.getEnergy()+1),Math.min(100,fun+8));
        } else if("rough_touch".equals(kind)) {
            affection=clamp(affection-2); irritation=clamp(irritation+8);
            if(life!=null) life.setNeeds(Math.max(0,life.getEnergy()-1),Math.max(0,fun-2));
        } else {
            affection=clamp(affection+1); humor=clamp(humor+1); irritation=clamp(irritation-1);
            if(life!=null) life.setNeeds(Math.min(100,life.getEnergy()+2),Math.min(100,fun+5));
        }
        prefs.edit().putLong(LAST_USER,now).putInt("affection",affection).putInt("humor",humor)
                .putInt("irritation",irritation).putLong("last_ignored_update",now).apply();
    }

    public Action next(PetLife life, boolean quietHours) {
        long now=System.currentTimeMillis();
        long previous=prefs.getLong(LAST_TICK,now);
        long elapsed=Math.max(0,Math.min(6*60*60*1000L,now-previous));
        long accumulated=prefs.getLong(REMAINDER,0L)+elapsed;
        int periods=(int)(accumulated/900000L);
        prefs.edit().putLong(LAST_TICK,now).putLong(REMAINDER,accumulated%900000L).apply();
        if(life!=null && periods>0) life.setNeeds(Math.max(5,life.getEnergy()-periods),Math.max(5,life.getFun()-periods));

        long idle=now-prefs.getLong(LAST_USER,now);
        int energy=life==null?70:life.getEnergy();
        int fun=life==null?60:life.getFun();
        int irritation=getTrait("irritation");
        long ignoredUpdate=prefs.getLong("last_ignored_update",now);
        if(idle>45*60*1000L && now-ignoredUpdate>=3600000L) {
            int growth=(int)Math.min(3,(now-ignoredUpdate)/3600000L);
            prefs.edit().putInt("irritation",clamp(irritation+growth)).putLong("last_ignored_update",now).apply();
            irritation=getTrait("irritation");
        }
        if(quietHours || energy<18) return Action.REST;
        if(fun<32 && energy>30) return Action.PLAY;
        if(idle>20*60*1000L && irritation<75 && now-prefs.getLong(LAST_CHAT,now)>25*60*1000L
                && random.nextInt(100)<22) {
            prefs.edit().putLong(LAST_CHAT,now).apply();
            return Action.INITIATE_CHAT;
        }
        int curiosity=getTrait("curiosity"), independence=getTrait("independence");
        int roll=random.nextInt(100);
        if(energy<35 && roll<55) return Action.REST;
        if(fun<55 && energy>35 && roll<25) return Action.PLAY;
        if(roll<Math.min(45,12+curiosity/4)) return Action.LOOK_AROUND;
        if(roll<Math.min(75,35+independence/3)) return Action.THINK;
        return Action.NONE;
    }

    public int getTrait(String name) { return prefs.getInt(name,50); }
    public String getEmotionalState() {
        int irritation=getTrait("irritation"), affection=getTrait("affection"), humor=getTrait("humor");
        if(irritation>=65) return "molesta";
        if(affection>=75) return "cariñosa";
        if(humor<30) return "aburrida";
        return "curiosa";
    }
    public String initiativeLine() {
        int irritation=getTrait("irritation"), affection=getTrait("affection");
        String[] lines;
        if(irritation>=60) lines=new String[]{"Hace bastante que no compartimos un rato. Me estoy quedando un poco aburrida.","Me vendría bien un poco de atención cuando puedas."};
        else if(affection>=70) lines=new String[]{"¡Qué bueno verte! Tenía ganas de compartir un ratito con vos.","Me gusta cuando aparecés. ¿Cómo viene tu día?"};
        else lines=new String[]{"Estaba explorando por acá y se me ocurrió saludarte.","Tengo ganas de jugar un poquito. ¿Me acompañás?","Me quedé pensando en algo. ¿Charlamos?"};
        return lines[random.nextInt(lines.length)];
    }
    private int clamp(int value) { return Math.max(0,Math.min(100,value)); }
}