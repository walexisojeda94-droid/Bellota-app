package com.bellota.mascota.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Random;

/** Decide actividades autónomas y conserva necesidades/tiempos entre aperturas. */
public final class AutonomousBehavior {
    public enum Action { LOOK_AROUND, PLAY, REST, THINK, INITIATE_CHAT, NONE }
    private static final String PREFS="bellota_autonomy";
    private static final String LAST_TICK="last_tick", LAST_USER="last_user", LAST_CHAT="last_chat";
    private final SharedPreferences prefs;
    private final Random random=new Random();

    public AutonomousBehavior(Context context) {
        prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(!prefs.contains(LAST_TICK)) prefs.edit().putLong(LAST_TICK,now).putLong(LAST_USER,now).putLong(LAST_CHAT,now).apply();
    }

    public void onInteraction(PetLife life) {
        long now=System.currentTimeMillis();
        prefs.edit().putLong(LAST_USER,now).apply();
        if(life!=null) life.setNeeds(Math.min(100,life.getEnergy()+2),Math.min(100,life.getFun()+5));
    }

    public Action next(PetLife life, boolean quietHours) {
        long now=System.currentTimeMillis();
        long previous=prefs.getLong(LAST_TICK,now);
        long elapsed=Math.max(0,Math.min(6*60*60*1000L,now-previous));
        prefs.edit().putLong(LAST_TICK,now).apply();
        if(life!=null && elapsed>=60000L) {
            int periods=(int)(elapsed/900000L);
            if(periods>0) life.setNeeds(Math.max(5,life.getEnergy()-periods),Math.max(5,life.getFun()-periods));
        }
        if(quietHours) return Action.REST;
        int energy=life==null?70:life.getEnergy();
        int fun=life==null?60:life.getFun();
        if(energy<25) return Action.REST;
        if(fun<45 && energy>35) {
            if(life!=null) life.setNeeds(energy-3,Math.min(100,fun+14));
            return Action.PLAY;
        }
        if(random.nextInt(100)<18) return Action.PLAY;
        if(random.nextInt(100)<25) return Action.THINK;
        if(random.nextInt(100)<55) return Action.LOOK_AROUND;
        long idle=now-prefs.getLong(LAST_USER,now);
        long sinceChat=now-prefs.getLong(LAST_CHAT,now);
        if(idle>5*60*1000L && sinceChat>12*60*1000L && random.nextInt(100)<18) {
            prefs.edit().putLong(LAST_CHAT,now).apply();
            return Action.INITIATE_CHAT;
        }
        return Action.NONE;
    }

    public String initiativeLine() {
        String[] lines={
            "Che, ¿seguís por ahí? Me dieron ganas de charlar un ratito.",
            "Estaba mirando alrededor y se me ocurrió preguntarte cómo viene tu día.",
            "Tengo ganas de jugar un poquito. ¿Me acompañás?",
            "Me quedé pensando en algo. Me gusta cuando compartimos estos ratitos.",
            "¿Sabés qué? Me estaba aburriendo un poquito y quise venir a saludarte."
        };
        return lines[random.nextInt(lines.length)];
    }
}
