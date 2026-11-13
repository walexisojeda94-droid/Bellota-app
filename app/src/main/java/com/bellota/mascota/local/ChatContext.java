package com.bellota.mascota.local;

import com.bellota.mascota.core.PetLife;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Todo lo que una habilidad necesita saber en este turno. Se crea uno nuevo por mensaje. */
public final class ChatContext {
    private static final Locale AR = new Locale("es", "AR");

    public final ConversationMemory memory;
    public final ResponsePack pack;
    public final PetLife pet;
    public final long now;
    private final String mood;
    private final String expected;
    private final Map<String, String> vars = new HashMap<>();

    public ChatContext(ConversationMemory memory, ResponsePack pack, PetLife pet, String mood, long now) {
        this.memory = memory;
        this.pack = pack;
        this.pet = pet;
        this.now = now;
        this.mood = normalizeMood(mood);
        this.expected = memory.expecting(now);
    }

    private static String normalizeMood(String m) {
        if (m == null) return "neutral";
        String s = m.toUpperCase(Locale.ROOT);
        if (s.contains("SLEEPING") || s.contains("SLEEPY")) return "sleepy";
        if (s.contains("ANGRY") || s.contains("OFFENDED")) return "sad";
        if (s.contains("HAPPY")) return "happy";
        if (s.contains("CURIOUS") || s.contains("THINKING")) return "curious";
        if (s.contains("SAD")) return "sad";
        return "neutral";
    }

    /** sleepy, happy, curious, sad o neutral. */
    public String moodTag() { return mood; }

    /** madrugada, manana, tarde o noche. */
    public String timeOfDay() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h < 6) return "madrugada";
        if (h < 12) return "manana";
        if (h < 20) return "tarde";
        return "noche";
    }

    /** "long" si pasaron 6 horas o más desde el mensaje anterior. */
    public String gapTag() {
        return memory.silenceMs() >= ConversationMemory.LONG_SILENCE_MS ? "long" : "short";
    }

    /** Qué respuesta esperaba Bellota del usuario en este turno (o null). */
    public String expected() { return expected; }

    public ChatContext with(String key, String value) {
        vars.put(key, value);
        return this;
    }

    /** Valor de una variable {nombre}, {hora}, {fecha} o las agregadas con with(). Null si no existe. */
    public String var(String name) {
        String custom = vars.get(name);
        if (custom != null) return custom;
        if ("nombre".equals(name)) return memory.name();
        if ("hora".equals(name)) return new SimpleDateFormat("H:mm", AR).format(new Date(now));
        if ("fecha".equals(name)) {
            return new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", AR).format(new Date(now));
        }
        return null;
    }

    /** Elige una frase del pack; si el pack no tiene la clave usa las de respaldo. */
    public String say(String key, String... defaults) {
        return pack.pick(key, this, defaults);
    }
}
