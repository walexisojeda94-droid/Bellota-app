package com.bellota.mascota.local;

import java.util.Random;

/**
 * Frases de Bellota para sus rutinas autónomas (sueño, aburrimiento, juegos, reconciliación).
 * Los textos usan {n} al inicio para insertar, a veces, el nombre o un apodo del usuario,
 * y {like} para mencionar algo que le gusta.
 */
public final class PetVoiceLines {
    /** Una frase y, si espera respuesta del usuario, la clave de lo que espera. */
    public static final class Talk {
        public final String text;
        public final String expect;
        Talk(String text, String expect) { this.text = text; this.expect = expect; }
    }

    private final Random random = new Random();

    // ---- Sueño ---------------------------------------------------------------

    public String tiredSleep(ConversationMemory m) {
        return fill(pick("Creo que estoy cansada, me voy a dormir.",
                "{n}creo que estoy cansada, me voy a dormir.",
                "Creo que estoy cansada, me voy a dormir. Nos vemos en un rato."), m, null);
    }

    public String boredSleep(ConversationMemory m) {
        return fill(pick("Estoy aburrida, me voy a descansar.",
                "{n}estoy aburrida, me voy a descansar un rato.",
                "Estoy aburrida, me voy a descansar. Despertame si me necesitás."), m, null);
    }

    public String nightSleep(ConversationMemory m) {
        return fill(pick("Ya es tarde, me voy a dormir. ¡Buenas noches!",
                "{n}me voy a dormir, es tardísimo. Buenas noches."), m, null);
    }

    public String askedSleep(ConversationMemory m) {
        return fill(pick("Dale, me voy a dormir. Hasta luego.", "{n}está bien, me voy a descansar un rato."), m, null);
    }

    public String forcedSleep() {
        return pick("Ya no puedo más… me quedo dormida. Hasta luego.",
                "Se me cierran los ojitos… tengo que dormir sí o sí.");
    }

    public String stayAwake(ConversationMemory m) {
        return fill(pick("Está bien, me quedo con vos.",
                "Dale, no me duermo. Acá sigo haciéndote compañía.",
                "{n}¡bueno! Me quedo despierta."), m, null);
    }

    public String cantStayAwake() { return "No puedo… me estoy cayendo de sueño."; }

    public String wake(boolean manual, ConversationMemory m) {
        return manual
                ? fill(pick("¡Ah! Me despertaste. Bueno, acá estoy.", "{n}¿ya es hora? Bueno, acá estoy."), m, null)
                : fill(pick("¡Listo, ya descansé! Estoy como nueva.", "{n}ya me desperté, descansé un montón."), m, null);
    }

    public String mumble() {
        return pick("Zzz… un ratito más…", "Mmm… dejame dormir…", "Zzz… estoy descansando…");
    }

    // ---- Aburrimiento y fastidio ---------------------------------------------

    public String yawn() {
        return pick("Aaaah… me aburro un poquito.", "Uf… ¡qué aburrimiento!", "Aaah… ¿hacemos algo?");
    }

    public String monologue(ConversationMemory m) {
        String like = m == null ? null : m.randomLike(random);
        if (like != null && random.nextInt(100) < 30) {
            return fill(pick("Estaba pensando en {like}… qué buena onda.",
                    "{n}me acordé de {like}. ¡Qué ganas!"), m, like);
        }
        return fill(pick(
                "{n}¿qué estarás haciendo? Yo acá, mirando.",
                "Estoy contando los píxeles de la pantalla. Voy por el doscientos.",
                "Hablo sola para no aburrirme. Ya sé, ya sé.",
                "Tarareo una canción que no existe.",
                "Me pregunto si las nubes saben que pasan.",
                "Si tuviera manos, ya habría ordenado todo el escritorio.",
                "{n}¿viste que no parpadeo hace rato? Ah, no, sí parpadeo.",
                "Mmm… me pregunto qué habrá para comer hoy.",
                "Qué silencio. Me gusta, pero me da ganas de hacer algo.",
                "Estoy practicando mi cara de pensar. ¿Cómo me sale?"), m, null);
    }

    public String annoyed(ConversationMemory m) {
        return fill(pick("{n}¿me vas a ignorar mucho más? Me estoy fastidiando.",
                "Hmpf. Estoy un poco fastidiada, ¿eh?",
                "Eh… ¿hola? Acá estoy, ¿eh?",
                "Me aburro y encima no me hablás. Qué cosa."), m, null);
    }

    public String sulk() {
        return pick("Bueno. Ahora no te miro.", "Me ofendí. Hablame cuando quieras.",
                "Hmm… me quedo mirando para otro lado.", "Estoy ofendida. Hmpf.");
    }

    public String reconcile(int stage, ConversationMemory m) {
        return stage >= 5
                ? fill(pick("{n}¡al fin! Estaba ofendida… pero ya te perdono.",
                        "¡Volviste! Estaba ofendida, pero se me pasó."), m, null)
                : fill(pick("{n}¡qué bueno que me hablás! Ya se me pasó el fastidio.",
                        "¡Ahí estás! Ya no estoy fastidiada."), m, null);
    }

    // ---- Juegos --------------------------------------------------------------

    /** Claves: ball, bubbles, fly, stars, hide, dot, flyClose, flyCaught, peek. */
    public String game(String key) {
        switch (key) {
            case "ball": return pick("¡Una pelota! Voy a jugar un ratito.", "Uh, ¡una pelota!");
            case "bubbles": return pick("Uh, burbujas. ¡Me encantan!", "Voy a hacer burbujas.");
            case "fly": return pick("¿Escuchás ese zumbido? Hay una mosca.", "Hay una mosca por acá…");
            case "stars": return pick("Mirá, ¡estrellas fugaces! Voy a pedir un deseo.", "¡Una estrella fugaz!");
            case "hide": return pick("Voy a esconderme… ¡no mires!", "¿Jugamos a las escondidas? Yo me escondo.");
            case "dot": return pick("Voy a perseguir esta lucecita.", "Uy, una lucecita. ¡La agarro!");
            case "flyClose": return pick("¡Shhh, mosca!", "¡Fuera de acá, mosca!");
            case "flyCaught": return pick("¡Te agarré, mosca!", "¡Ja! Se acabó el zumbido.");
            case "peek": return pick("¡Cucú!", "¡Acá estoy!");
            default: return "";
        }
    }

    // ---- Iniciativa ----------------------------------------------------------

    public Talk initiative(ConversationMemory m, int irritation, int affection, boolean online) {
        String like = m == null ? null : m.randomLike(random);
        if (like != null && random.nextInt(100) < 45) {
            if (!online) {
                String text = fill(pick("{n}¿seguís con ganas de {like}?",
                        "¿Cómo viene lo de {like}? ¿Seguís con ganas?",
                        "Me quedé pensando en {like}. ¿Te sigue copando?"), m, like);
                return new Talk(text, "like:" + like);
            }
            return new Talk(fill(pick("{n}estaba pensando en {like}. ¡Qué buena onda!",
                    "Me acordé de que te gusta {like}. Me encanta eso."), m, like), null);
        }
        String text;
        if (irritation >= 60) {
            text = fill(pick("Hace bastante que no compartimos un rato. Me estoy quedando un poco aburrida.",
                    "{n}me vendría bien un poco de atención cuando puedas."), m, null);
        } else if (affection >= 70) {
            text = fill(pick("{n}¡qué bueno verte! Tenía ganas de compartir un ratito con vos.",
                    "Me gusta cuando aparecés. ¿Cómo viene tu día?"), m, null);
        } else {
            text = fill(pick("Estaba explorando por acá y se me ocurrió saludarte.",
                    "{n}tengo ganas de jugar un poquito. ¿Me acompañás?",
                    "Me quedé pensando en algo. ¿Charlamos?"), m, null);
        }
        return new Talk(text, null);
    }

    // ---- Utilidades ----------------------------------------------------------

    private String pick(String... options) { return options[random.nextInt(options.length)]; }

    /** Reemplaza {n} (a veces con el nombre o apodo) y {like}; deja la primera letra en mayúscula. */
    private String fill(String template, ConversationMemory m, String like) {
        String who = m == null ? null : m.address(random);
        boolean useName = who != null && random.nextInt(100) < 60;
        String out = template.replace("{n}", useName ? who + ", " : "");
        if (like != null) {
            out = out.replace("{like}", like);
            out = out.replace(" de el ", " del ").replace(" a el ", " al ");
        }
        if (!useName) {
            for (int i = 0; i < out.length(); i++) {
                if (Character.isLetter(out.charAt(i))) {
                    out = out.substring(0, i) + Character.toUpperCase(out.charAt(i)) + out.substring(i + 1);
                    break;
                }
            }
        }
        return out;
    }
}
