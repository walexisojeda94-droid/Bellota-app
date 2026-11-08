package com.bellota.mascota.local;

import com.bellota.mascota.core.PetLife;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Random;

/** Respuestas locales básicas y variadas mientras no está disponible el asistente remoto. */
public class OfflineReplies {
    private final Random random = new Random();
    private String lastReply = "";

    public synchronized String reply(String input) {
        return reply(input, null);
    }

    public synchronized String reply(String input, PetLife petLife) {
        if (input == null) return choose("Estoy acá.", "Te escucho.", "Sigo con vos.");

        String text = normalize(input);

        // Consultas sobre el tiempo de existencia tienen prioridad sobre las de edad.
        if (asksHowLongExists(text)) {
            return existenceReply(petLife);
        }

        if (asksAge(text)) {
            return ageReply(petLife);
        }

        if (text.contains("hola")) return choose(
                "¡Hola! Qué bueno escucharte.",
                "¡Buenas! Acá estoy.",
                "Hola, te estaba escuchando."
        );
        if (text.contains("como estas") || text.contains("como andas")) return choose(
                "Estoy tranquila, explorando un poquito.",
                "Tengo curiosidad por saber qué estás haciendo.",
                "Estoy bien, gracias por preguntarme."
        );
        if (text.contains("gracias")) return choose("¡De nada!", "No hay de qué.", "Me gusta poder ayudarte.");
        if (text.contains("chau") || text.contains("adios") || text.contains("hasta luego")) return choose(
                "Hasta luego. Me quedo por acá.",
                "Nos vemos pronto.",
                "Que estés bien."
        );
        return choose(
                "Todavía no sé responder bien eso sin conexión, pero te escuché.",
                "Esa conversación la quiero seguir cuando pueda conectarme.",
                "No tengo una buena respuesta local para eso todavía."
        );
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(new Locale("es", "AR")), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").replaceAll("[¿?¡!,.;]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean asksHowLongExists(String text) {
        boolean existence = text.contains("exist") || text.contains("naciste") ||
                text.contains("desde cuando estas") || text.contains("desde hace cuanto estas") ||
                text.contains("cuanto tiempo llevas");
        boolean duration = text.contains("cuanto") || text.contains("cuanto tiempo") ||
                text.contains("desde cuando") || text.contains("hace cuanto");
        return existence && duration;
    }

    private boolean asksAge(String text) {
        return text.contains("que edad") || text.contains("cuantos anos") ||
                text.contains("cuantos anitos") || text.contains("que tantos anos") ||
                text.contains("sos grande") || text.contains("edad tenes") ||
                text.contains("anos tenes") || text.contains("anos cumpliste");
    }

    private String ageReply(PetLife petLife) {
        if (petLife == null) return "Todavía no puedo calcular mi edad.";
        long years = petLife.getAgeYears();
        if (years == 0) return choose(
                "Tengo 0 años. Todavía soy chiquita, aún no cumplí mi primer mes.",
                "Cero años por ahora. Mi primer añito llega cuando cumpla 30 días.",
                "Todavía tengo 0 años. ¡Estoy en mi primer mes de vida!"
        );
        return choose(
                "Tengo " + years + (years == 1 ? " año." : " años."),
                "Ya cumplí " + years + (years == 1 ? " año." : " años."),
                "Mi edad es de " + years + (years == 1 ? " año." : " años.")
        );
    }

    private String existenceReply(PetLife petLife) {
        if (petLife == null) return "Todavía no puedo calcular cuánto hace que existo.";
        long days = petLife.getAgeDays();
        if (days == 0) return choose(
                "Empecé a existir hoy. ¡Es mi primer día!",
                "Nací hoy, así que llevo menos de un día existiendo."
        );
        if (days < 7) return choose(
                "Existo desde hace " + days + (days == 1 ? " día." : " días."),
                "Llevo " + days + (days == 1 ? " día existiendo." : " días existiendo.")
        );
        if (days < 30) {
            long weeks = days / 7;
            long remainingDays = days % 7;
            if (remainingDays == 0) return "Existo desde hace " + weeks + (weeks == 1 ? " semana." : " semanas.");
            return "Existo desde hace " + weeks + (weeks == 1 ? " semana y " : " semanas y ") +
                    remainingDays + (remainingDays == 1 ? " día." : " días.");
        }
        long months = days / 30;
        long remainingDays = days % 30;
        if (remainingDays == 0) return "Existo desde hace " + months + (months == 1 ? " mes." : " meses.");
        return "Existo desde hace " + months + (months == 1 ? " mes y " : " meses y ") +
                remainingDays + (remainingDays == 1 ? " día." : " días.");
    }

    private String choose(String... options) {
        if (options.length == 1) return options[0];
        int selected = random.nextInt(options.length);
        for (int i = 0; i < options.length; i++) {
            String candidate = options[(selected + i) % options.length];
            if (!candidate.equals(lastReply)) {
                lastReply = candidate;
                return candidate;
            }
        }
        return options[selected];
    }
}
