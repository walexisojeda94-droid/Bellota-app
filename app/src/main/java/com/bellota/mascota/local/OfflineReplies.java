package com.bellota.mascota.local;

import com.bellota.mascota.core.PetLife;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** Respuestas locales contextuales para las consultas cotidianas de Bellota. */
public class OfflineReplies {
    private final Random random = new Random();
    private String lastReply = "";

    public synchronized String reply(String input) { return reply(input, null, "NEUTRAL"); }

    public synchronized String reply(String input, PetLife petLife) {
        return reply(input, petLife, "NEUTRAL");
    }

    public synchronized String reply(String input, PetLife petLife, String mood) {
        if (input == null) return choose("Estoy acá.", "Te escucho.", "Sigo con vos.");
        String text = normalize(input);

        if (asksHowLongExists(text)) return existenceReply(petLife);
        if (asksAge(text)) return ageReply(petLife);

        if (asksTime(text)) return "Ahora son las " + new SimpleDateFormat("H:mm", new Locale("es", "AR")).format(new Date()) + ".";
        if (asksDate(text)) return "Hoy es " + new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", new Locale("es", "AR")).format(new Date()) + ".";
        if (asksName(text)) return choose("Me llamo Bellota.", "Soy Bellota, ¡esa soy yo!", "Bellota. Ese es mi nombre.");
        if (asksWeather(text)) return null; // Se resuelve con ubicación y el servicio meteorológico.
        if (asksHowAreYou(text)) return moodReply(mood);

        if (text.contains("hola") || text.startsWith("buen dia") || text.startsWith("buenas tardes") || text.startsWith("buenas noches"))
            return choose("¡Hola! Qué bueno escucharte.", "¡Buenas! Acá estoy.", "Hola, te estaba escuchando.");
        if (text.contains("gracias")) return choose("¡De nada!", "No hay de qué.", "Me gusta poder ayudarte.");
        if (text.contains("chau") || text.contains("adios") || text.contains("hasta luego"))
            return choose("Hasta luego. Me quedo por acá.", "Nos vemos pronto.", "Que estés bien.");
        if (text.contains("que haces") || text.contains("que estas haciendo"))
            return choose("Estaba mirando alrededor y pensando en cosas.", "Acá ando, explorando un poquito.", "Me entretengo observando y moviendo los ojos.");
        if (text.contains("quien sos") || text.contains("quien eres"))
            return "Soy Bellota, tu compañera virtual. Estoy acá para conversar y descubrir cosas.";
        return choose(
                "Te escuché. Todavía estoy aprendiendo a conversar mejor cuando no tengo conexión.",
                "Quiero seguir hablando de eso, pero todavía no tengo una respuesta local preparada.",
                "Esa pregunta me deja pensando. Cuando esté conectada voy a poder responder mejor."
        );
    }

    public boolean isWeatherQuestion(String input) { return input != null && asksWeather(normalize(input)); }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(new Locale("es", "AR")), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").replaceAll("[¿?¡!,.;]", " ").replaceAll("\\s+", " ").trim();
    }
    private boolean asksHowLongExists(String t) {
        return (t.contains("exist") || t.contains("naciste") || t.contains("desde cuando estas") || t.contains("cuanto tiempo llevas"))
                && (t.contains("cuanto") || t.contains("desde cuando") || t.contains("hace cuanto") || t.contains("tiempo"));
    }
    private boolean asksAge(String t) {
        return t.contains("que edad") || t.contains("cuantos anos") || t.contains("cuantos anitos") ||
                t.contains("edad tenes") || t.contains("anos tenes") || t.contains("anos cumpliste");
    }
    private boolean asksTime(String t) {
        return t.contains("que hora") || t.equals("hora") || t.contains("decime la hora") || t.contains("decir la hora");
    }
    private boolean asksDate(String t) {
        return t.contains("que dia") || t.contains("fecha de hoy") || t.contains("que fecha") || t.contains("que estamos");
    }
    private boolean asksName(String t) {
        return t.contains("como te llamas") || t.contains("cual es tu nombre") || t.contains("tu nombre") ||
                t.contains("como te llamas") || t.equals("como te llamas");
    }
    private boolean asksWeather(String t) {
        return t.contains("clima") || t.contains("temperatura") || t.contains("cuantos grados") ||
                t.contains("que tiempo hace") || t.contains("como esta el tiempo");
    }
    private boolean asksHowAreYou(String t) {
        return t.contains("como estas") || t.contains("como andas") || t.contains("como te sentis") || t.contains("como te encuentras");
    }
    private String moodReply(String mood) {
        String state = mood == null ? "NEUTRAL" : mood.toUpperCase(Locale.ROOT);
        if (state.contains("SLEEPY")) return choose("Estoy un poquito soñolienta, me vendría bien descansar.", "Tengo algo de sueño, pero me gusta que me hables.");
        if (state.contains("HAPPY")) return choose("Estoy contenta y con ganas de charlar.", "¡Estoy de buen humor! Me siento animada.");
        if (state.contains("CURIOUS") || state.contains("THINKING")) return choose("Estoy curiosa, con la cabeza llena de preguntas.", "Ando pensativa, tratando de descubrir algo nuevo.");
        if (state.contains("SAD")) return "Estoy un poquito bajoneada, pero me hace bien conversar.";
        return choose("Estoy tranquila y disfrutando este ratito.", "Me siento calmada, observando lo que pasa.", "Estoy bien, con ganas de acompañarte.");
    }
    private String ageReply(PetLife life) {
        if (life == null) return "Todavía no puedo calcular mi edad.";
        long years = life.getAgeYears();
        if (years == 0) return choose("Tengo 0 años. Todavía no cumplí mi primer mes.", "Cero años por ahora. Mi primer añito llega cuando cumpla 30 días.", "Todavía tengo 0 años. ¡Estoy en mi primer mes de vida!");
        return "Tengo " + years + (years == 1 ? " año." : " años.");
    }
    private String existenceReply(PetLife life) {
        if (life == null) return "Todavía no puedo calcular cuánto hace que existo.";
        long days = life.getAgeDays();
        if (days == 0) return "Empecé a existir hoy. ¡Es mi primer día!";
        if (days < 7) return "Existo desde hace " + days + (days == 1 ? " día." : " días.");
        if (days < 30) {
            long weeks = days / 7, rest = days % 7;
            return rest == 0 ? "Existo desde hace " + weeks + (weeks == 1 ? " semana." : " semanas.") :
                    "Existo desde hace " + weeks + (weeks == 1 ? " semana y " : " semanas y ") + rest + (rest == 1 ? " día." : " días.");
        }
        long months = days / 30, rest = days % 30;
        return rest == 0 ? "Existo desde hace " + months + (months == 1 ? " mes." : " meses.") :
                "Existo desde hace " + months + (months == 1 ? " mes y " : " meses y ") + rest + (rest == 1 ? " día." : " días.");
    }
    private String choose(String... options) {
        if (options.length == 1) return options[0];
        int selected = random.nextInt(options.length);
        for (int i = 0; i < options.length; i++) {
            String candidate = options[(selected + i) % options.length];
            if (!candidate.equals(lastReply)) { lastReply = candidate; return candidate; }
        }
        return options[selected];
    }
}
