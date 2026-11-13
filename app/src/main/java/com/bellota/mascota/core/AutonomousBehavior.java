package com.bellota.mascota.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Decide qué hace Bellota cuando está sola: sueño, escalera del aburrimiento y juegos.
 * Conserva necesidades, rasgos y tiempos entre aperturas de la app.
 *
 * Escalera (minutos sin que le hables o la toques; se acelera si está sin ganas o irritada):
 *   0 atenta (&lt;3)  1 mira y bosteza (3-7)  2 habla sola y empieza a jugar (7-12)
 *   3 juega sola (12-20)  4 se fastidia (20-32)  5 se ofende y da la espalda (32+, hasta 12 min)
 */
public final class AutonomousBehavior {
    public enum Action {
        NONE, LOOK_AROUND, THINK, YAWN, MONOLOGUE, INITIATE_CHAT,
        GAME_DOT, GAME_BALL, GAME_BUBBLES, GAME_FLY, GAME_STARS, GAME_HIDE,
        ANNOY, TURN_AWAY,
        REST_TIRED, REST_BORED, REST_NIGHT, REST_FORCED, WAKE_UP
    }

    /** Por debajo de este nivel de energía decide irse a dormir. */
    public static final int TIRED_ENERGY = 30;
    /** Con este nivel o menos duerme obligatoriamente. */
    public static final int FORCED_SLEEP_ENERGY = 5;
    private static final int WAKE_ENERGY = 100;
    private static final int FORCED_WAKE_ENERGY = 20;
    private static final long RECOVERY_MS = 1L * 60L * 1000L;      // +1 de energía cada minuto dormida
    private static final long DECAY_MS = 15L * 60L * 1000L;        // -2 de energía cada 15 min despierta
    private static final long SULK_MAX_MS = 12L * 60L * 1000L;
    private static final long GRACE_MS = 30L * 60L * 1000L;

    private static final String PREFS = "bellota_autonomy";
    private static final String LAST_TICK = "last_tick", LAST_USER = "last_user", LAST_CHAT = "last_chat";
    private static final String REMAINDER = "decay_remainder", REC_REMAINDER = "recovery_remainder";
    private static final String SLEEPING = "sleeping", SLEEP_FORCED = "sleep_forced", SLEEP_REASON = "sleep_reason";
    private static final String STAY_AWAKE = "stay_awake";
    private static final String GRACE_UNTIL = "grace_until", CYCLE_START = "cycle_start", SULK_SINCE = "sulk_since";

    private final SharedPreferences prefs;
    private final Random random = new Random();
    private final Map<String, Long> lastAt = new HashMap<>();
    private int reconcileStage;

    public AutonomousBehavior(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!prefs.contains(LAST_TICK)) prefs.edit().putLong(LAST_TICK, now).putLong(LAST_USER, now).putLong(LAST_CHAT, now).apply();
        if (!prefs.contains("affection")) prefs.edit().putInt("affection", 50).putInt("curiosity", 65).putInt("humor", 55)
                .putInt("independence", 50).putInt("irritation", 0).putLong("last_ignored_update", now).apply();
    }

    // ---- Interacción ----------------------------------------------------------

    public void onInteraction(PetLife life) { onInteraction(life, "attention"); }

    public void onInteraction(PetLife life, String kind) {
        long now = System.currentTimeMillis();
        int prevStage = getStage(life);
        int affection = getTrait("affection"), humor = getTrait("humor"), irritation = getTrait("irritation");
        int fun = life == null ? 60 : life.getFun();
        if ("caress".equals(kind)) {
            affection = clamp(affection + 3); humor = clamp(humor + 1); irritation = clamp(irritation - 3);
            if (life != null) life.setNeeds(Math.min(100, life.getEnergy() + 1), Math.min(100, fun + 8));
        } else if ("rough_touch".equals(kind)) {
            affection = clamp(affection - 2); irritation = clamp(irritation + 8);
            if (life != null) life.setNeeds(Math.max(0, life.getEnergy() - 1), Math.max(0, fun - 2));
        } else {
            affection = clamp(affection + 1); humor = clamp(humor + 1); irritation = clamp(irritation - 1);
            if (life != null) life.setNeeds(Math.min(100, life.getEnergy() + 2), Math.min(100, fun + 5));
        }
        reconcileStage = 0;
        if (prevStage >= 4 && !"rough_touch".equals(kind)) {
            reconcileStage = prevStage;
            irritation = clamp(irritation - 10); // se le pasa el fastidio al hablarle
        }
        prefs.edit().putLong(LAST_USER, now).putInt("affection", affection).putInt("humor", humor)
                .putInt("irritation", irritation).putLong("last_ignored_update", now).putLong(SULK_SINCE, 0L).apply();
    }

    /** Nivel de fastidio que tenía antes de la última interacción (4 o 5), o 0. Se consume al leerlo. */
    public int takeReconcileStage() {
        int stage = reconcileStage;
        reconcileStage = 0;
        return stage;
    }

    // ---- Sueño ----------------------------------------------------------------

    public boolean isSleeping() { return prefs.getBoolean(SLEEPING, false); }
    public boolean isForcedSleep() { return isSleeping() && prefs.getBoolean(SLEEP_FORCED, false); }
    public boolean isStayAwake() { return prefs.getBoolean(STAY_AWAKE, false); }
    public void setStayAwake(boolean value) { prefs.edit().putBoolean(STAY_AWAKE, value).apply(); }

    /** reason: tired, bored, night o forced. */
    public void startSleep(String reason) {
        long now = System.currentTimeMillis();
        boolean forced = "forced".equals(reason);
        // El sueño normal no tiene duración mínima: termina al 100% o por llamada.
        prefs.edit().putBoolean(SLEEPING, true).putBoolean(SLEEP_FORCED, forced).putString(SLEEP_REASON, reason)
                .putLong(REC_REMAINDER, 0L).putLong(LAST_TICK, now).apply();
    }

    /** manual=true: la despertó el usuario (queda 30 min sin dormirse sola). */
    public void wake(boolean manual) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit().putBoolean(SLEEPING, false).putBoolean(SLEEP_FORCED, false)
                .putLong(CYCLE_START, now).putLong(LAST_TICK, now).putLong(REMAINDER, 0L).putLong(SULK_SINCE, 0L);
        if (manual) editor.putLong(GRACE_UNTIL, now + GRACE_MS); else editor.putBoolean(STAY_AWAKE, false);
        editor.apply();
    }

    private boolean shouldWake(int energy) {
        // Todo sueño normal, incluido el nocturno, termina al recuperar el 100%.
        return energy >= WAKE_ENERGY;
    }

    // ---- Escalera del aburrimiento -------------------------------------------

    public int getStage(PetLife life) {
        if (isSleeping()) return 0;
        long now = System.currentTimeMillis();
        long base = Math.max(prefs.getLong(LAST_USER, now), prefs.getLong(CYCLE_START, 0L));
        double minutes = Math.max(0L, now - base) / 60000.0;
        if ((life == null ? 60 : life.getFun()) < 40) minutes *= 1.4;
        minutes *= 1.0 + getTrait("irritation") / 200.0;
        if (minutes < 3) return 0;
        if (minutes < 7) return 1;
        if (minutes < 12) return 2;
        if (minutes < 20) return 3;
        if (minutes < 32) return 4;
        return 5;
    }

    public Action next(PetLife life, boolean quietHours) {
        long now = System.currentTimeMillis();
        account(now, life);
        int energy = life == null ? 70 : life.getEnergy();
        int fun = life == null ? 60 : life.getFun();
        if (isSleeping()) {
            // Al salir del sueño obligatorio al 20%, sigue dormida pero pasa a sueño normal.
            if (prefs.getBoolean(SLEEP_FORCED, false) && energy >= FORCED_WAKE_ENERGY) {
                prefs.edit().putBoolean(SLEEP_FORCED, false).putString(SLEEP_REASON, "tired").apply();
                return Action.NONE;
            }
            return shouldWake(energy) ? Action.WAKE_UP : Action.NONE;
        }

        long idle = now - prefs.getLong(LAST_USER, now);
        growIrritation(now, idle);
        if (energy <= FORCED_SLEEP_ENERGY) return Action.REST_FORCED;
        boolean mayRest = !isStayAwake() && now >= prefs.getLong(GRACE_UNTIL, 0L);
        if (mayRest && quietHours && idle > 10L * 60000L) return Action.REST_NIGHT;
        if (mayRest && energy < TIRED_ENERGY) return Action.REST_TIRED;

        int stage = getStage(life);
        if (stage >= 5) {
            long since = prefs.getLong(SULK_SINCE, 0L);
            if (since == 0L) {
                prefs.edit().putLong(SULK_SINCE, now).apply();
            } else if (now - since > SULK_MAX_MS) { // se le pasa solita: arranca otro ciclo, un poco más resentida
                prefs.edit().putLong(CYCLE_START, now).putLong(SULK_SINCE, 0L)
                        .putInt("irritation", clamp(getTrait("irritation") + 6)).apply();
                stage = 0;
            }
        }
        if (mayRest && stage >= 3 && energy < 75 && cool("bsleep", 20L * 60000L) && random.nextInt(100) < 18) {
            lastAt.put("bsleep", now);
            return Action.REST_BORED;
        }
        return chooseByStage(stage, energy, fun, idle, now);
    }

    private Action chooseByStage(int stage, int energy, int fun, long idle, long now) {
        Bag b = new Bag(energy, idle, now);
        int g = (fun < 40 && energy >= 30) ? 2 : 1; // si se aburre, más ganas de jugar
        switch (stage) {
            case 0:
                b.add(Action.NONE, 45).add(Action.LOOK_AROUND, 30).add(Action.THINK, 12)
                        .add(Action.YAWN, energy < 60 ? 8 : 3).add(Action.GAME_DOT, 5 * g);
                break;
            case 1:
                b.add(Action.NONE, 20).add(Action.LOOK_AROUND, 30).add(Action.THINK, 10).add(Action.YAWN, 20)
                        .add(Action.MONOLOGUE, 12).add(Action.GAME_DOT, 8 * g);
                break;
            case 2:
                b.add(Action.NONE, 10).add(Action.LOOK_AROUND, 15).add(Action.YAWN, 10).add(Action.MONOLOGUE, 25)
                        .add(Action.INITIATE_CHAT, 8).add(Action.GAME_BALL, 10 * g).add(Action.GAME_BUBBLES, 10 * g)
                        .add(Action.GAME_FLY, 8 * g).add(Action.GAME_STARS, 7 * g).add(Action.GAME_HIDE, 8 * g)
                        .add(Action.GAME_DOT, 6 * g);
                break;
            case 3:
                b.add(Action.NONE, 5).add(Action.LOOK_AROUND, 10).add(Action.YAWN, 6).add(Action.MONOLOGUE, 15)
                        .add(Action.INITIATE_CHAT, 8).add(Action.GAME_BALL, 12 * g).add(Action.GAME_BUBBLES, 12 * g)
                        .add(Action.GAME_FLY, 12 * g).add(Action.GAME_STARS, 12 * g).add(Action.GAME_HIDE, 12 * g)
                        .add(Action.GAME_DOT, 8 * g);
                break;
            case 4:
                b.add(Action.ANNOY, 30).add(Action.MONOLOGUE, 15).add(Action.LOOK_AROUND, 10).add(Action.YAWN, 10)
                        .add(Action.NONE, 10).add(Action.INITIATE_CHAT, 5).add(Action.GAME_FLY, 10)
                        .add(Action.GAME_DOT, 5).add(Action.GAME_BALL, 5);
                break;
            default:
                b.add(Action.TURN_AWAY, 40).add(Action.NONE, 40).add(Action.LOOK_AROUND, 10).add(Action.YAWN, 10);
                break;
        }
        return b.draw();
    }

    /** Bolsa de acciones con peso; respeta los tiempos de espera de cada tipo. */
    private final class Bag {
        private final Action[] actions = new Action[16];
        private final int[] weights = new int[16];
        private int size;
        private final int energy;
        private final long idle, now;

        Bag(int energy, long idle, long now) { this.energy = energy; this.idle = idle; this.now = now; }

        Bag add(Action action, int weight) {
            if (weight > 0 && allowed(action) && size < actions.length) { actions[size] = action; weights[size] = weight; size++; }
            return this;
        }

        private boolean allowed(Action a) {
            switch (a) {
                case GAME_DOT: case GAME_BALL: case GAME_BUBBLES: case GAME_FLY: case GAME_STARS: case GAME_HIDE:
                    return energy >= 20 && cool("game", 70000L);
                case YAWN: return cool("yawn", 150000L);
                case MONOLOGUE: return cool("mono", 100000L);
                case ANNOY: return cool("annoy", 180000L);
                case TURN_AWAY: return cool("sulk", 150000L);
                case INITIATE_CHAT:
                    return idle > 10L * 60000L && getTrait("irritation") < 75
                            && now - prefs.getLong(LAST_CHAT, 0L) > 12L * 60000L;
                default: return true;
            }
        }

        Action draw() {
            int total = 0;
            for (int i = 0; i < size; i++) total += weights[i];
            if (total <= 0) return Action.NONE;
            int roll = random.nextInt(total);
            for (int i = 0; i < size; i++) {
                roll -= weights[i];
                if (roll < 0) { mark(actions[i], now); return actions[i]; }
            }
            return Action.NONE;
        }
    }

    private void mark(Action a, long now) {
        switch (a) {
            case GAME_DOT: case GAME_BALL: case GAME_BUBBLES: case GAME_FLY: case GAME_STARS: case GAME_HIDE:
                lastAt.put("game", now); break;
            case YAWN: lastAt.put("yawn", now); break;
            case MONOLOGUE: lastAt.put("mono", now); break;
            case ANNOY: lastAt.put("annoy", now); break;
            case TURN_AWAY: lastAt.put("sulk", now); break;
            case INITIATE_CHAT: prefs.edit().putLong(LAST_CHAT, now).apply(); break;
            default: break;
        }
    }

    private boolean cool(String key, long ms) {
        Long last = lastAt.get(key);
        return last == null || System.currentTimeMillis() - last >= ms;
    }

    // ---- Necesidades ----------------------------------------------------------

    /** Dormida recupera energía (+1 por minuto); despierta la gasta (-2 cada 15 min). */
    private void account(long now, PetLife life) {
        long previous = prefs.getLong(LAST_TICK, now);
        boolean sleeping = isSleeping();
        long cap = (sleeping ? 12L : 6L) * 60L * 60L * 1000L;
        long elapsed = Math.max(0L, Math.min(cap, now - previous));
        SharedPreferences.Editor editor = prefs.edit().putLong(LAST_TICK, now);
        if (life != null) {
            if (sleeping) {
                long acc = prefs.getLong(REC_REMAINDER, 0L) + elapsed;
                int points = (int) (acc / RECOVERY_MS);
                editor.putLong(REC_REMAINDER, acc % RECOVERY_MS);
                if (points > 0) life.setNeeds(Math.min(100, life.getEnergy() + points), life.getFun());
            } else {
                long acc = prefs.getLong(REMAINDER, 0L) + elapsed;
                int periods = (int) (acc / DECAY_MS);
                editor.putLong(REMAINDER, acc % DECAY_MS);
                if (periods > 0) life.setNeeds(Math.max(5, life.getEnergy() - periods * 2), Math.max(5, life.getFun() - periods));
            }
        }
        editor.apply();
    }

    private void growIrritation(long now, long idle) {
        long ignoredUpdate = prefs.getLong("last_ignored_update", now);
        if (idle > 45L * 60000L && now - ignoredUpdate >= 3600000L) {
            int growth = (int) Math.min(3, (now - ignoredUpdate) / 3600000L);
            prefs.edit().putInt("irritation", clamp(getTrait("irritation") + growth)).putLong("last_ignored_update", now).apply();
        }
    }

    public int getTrait(String name) { return prefs.getInt(name, 50); }

    public String getEmotionalState() {
        int irritation = getTrait("irritation"), affection = getTrait("affection"), humor = getTrait("humor");
        if (irritation >= 65) return "molesta";
        if (affection >= 75) return "cariñosa";
        if (humor < 30) return "aburrida";
        return "curiosa";
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
