package com.bellota.mascota.local;

import java.util.Locale;
import java.util.Random;

/** Respuestas locales básicas y variadas mientras no está disponible el asistente remoto. */
public class OfflineReplies {
    private final Random random = new Random();
    private String lastReply = "";

    public synchronized String reply(String input) {
        if (input == null) return choose("Estoy acá.", "Te escucho.", "Sigo con vos.");
        String text = input.trim().toLowerCase(new Locale("es", "AR"));
        if (text.contains("hola")) return choose("¡Hola! Qué bueno escucharte.", "¡Buenas! Acá estoy.", "Hola, te estaba escuchando.");
        if (text.contains("como estas") || text.contains("cómo estás")) return choose("Estoy tranquila, explorando un poquito.", "Tengo curiosidad por saber qué estás haciendo.", "Estoy bien, gracias por preguntarme.");
        if (text.contains("gracias")) return choose("¡De nada!", "No hay de qué.", "Me gusta poder ayudarte.");
        if (text.contains("chau") || text.contains("adios") || text.contains("adiós")) return choose("Hasta luego. Me quedo por acá.", "Nos vemos pronto.", "Que estés bien.");
        return choose("Todavía no sé responder bien eso sin conexión, pero te escuché.", "Esa conversación la quiero seguir cuando pueda conectarme.", "No tengo una buena respuesta local para eso todavía.");
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
