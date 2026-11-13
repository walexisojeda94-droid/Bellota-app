package com.bellota.mascota.local;

import android.content.Context;
import android.content.SharedPreferences;
import com.bellota.mascota.core.PetLife;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Punto único de entrada del diálogo local y su memoria persistente. */
public final class LocalConversationEngine {
    private final ConversationMemory memory;
    private final ResponsePack pack = new ResponsePack();
    private final OfflineReplies legacy = new OfflineReplies();

    public LocalConversationEngine(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("bellota_conversation", Context.MODE_PRIVATE);
        memory = new ConversationMemory(new ConversationMemory.Store() {
            @Override public String get(String key) { return prefs.getString(key, null); }
            @Override public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
        });
    }

    public synchronized Reply respond(String input, PetLife pet, String mood) {
        long now = System.currentTimeMillis();
        Utterance u = Utterance.of(input);
        if (u.isEmpty()) return Reply.text("Te escucho.");
        memory.onUserMessage(now);

        // Respuesta a una pregunta de Bellota sobre un gusto ("¿Seguís con ganas de...?")
        String waiting = memory.expecting(now);
        if (waiting != null && waiting.startsWith("like:")) {
            String likeValue = waiting.substring(5);
            memory.clearExpecting();
            if (isNo(u)) {
                memory.removeLike(likeValue);
                memory.afterTurn("like_no");
                return Reply.text("Ah, ya no te gusta. Lo saco de mis notas.");
            }
            if (isYes(u)) {
                memory.afterTurn("like_yes");
                return Reply.text("¡Qué bueno! Me alegra que te siga gustando.");
            }
        }

        String learnedName = extractName(input);
        if (learnedName != null) {
            memory.setName(learnedName);
            memory.afterTurn("remember_name");
            return Reply.text("¡Listo! Voy a recordar que te llamás " + learnedName + ".");
        }
        String nickname = extractNickname(input);
        if (nickname != null) {
            memory.addNickname(nickname);
            memory.afterTurn("remember_nickname");
            return Reply.text("¡Dale! A veces te voy a decir " + nickname + ".");
        }
        String unliked = extractUnlike(input);
        if (unliked != null) {
            memory.removeLike(unliked);
            memory.afterTurn("unlike");
            return Reply.text("Entendido, lo saco de las cosas que te gustan.");
        }
        String like = extractLike(input);
        if (like != null) {
            memory.addLike(like);
        }

        ChatContext context = new ChatContext(memory, pack, pet, mood, now);
        if (memory.takeWelcomeBack()) {
            memory.afterTurn("welcome_back");
            return Reply.text("¡Volviste! Pasó un buen rato desde la última vez que hablamos. ¿Cómo estuviste?");
        }

        if (u.hasPhrase("silencio") || u.hasPhrase("sonido")) {
            memory.afterTurn("voice_mode");
            return Reply.needsAi().withSkill("voice_mode");
        }

        if (legacy.isWeatherQuestion(input)) {
            memory.afterTurn("weather");
            return Reply.weather().withSkill("weather");
        }

        String local = legacy.reply(input, pet, mood);
        if (local != null) {
            if (u.hasPhrase("hola") || u.startsWith("buen dia") || u.startsWith("buenas")) local = context.say("hello", local);
            else if (u.hasWord("gracias")) local = context.say("thanks", local);
            else if (u.hasWord("chau") || u.hasWord("adios")) local = context.say("bye", local);
            else if (u.hasPhrase("como te llamas") || u.hasPhrase("cual es tu nombre")) local = context.say("name", local);
            else if (u.hasPhrase("que haces") || u.hasPhrase("que estas haciendo")) local = context.say("doing", local);
            memory.afterTurn("local");
            return Reply.text(local).withSkill("local");
        }

        memory.afterTurn("unknown");
        return Reply.text(context.say("unknown",
                "No estoy segura de haber entendido bien. ¿Me lo explicás de otra manera?"));
    }

    /** Registra mensajes enviados al motor online sin generar una segunda respuesta local. */
    public synchronized void rememberOnlineMessage(String input) {
        if (input == null || input.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        Utterance u = Utterance.of(input);
        memory.onUserMessage(now);
        String learnedName = extractName(input);
        if (learnedName != null) memory.setName(learnedName);
        String nickname = extractNickname(input);
        if (nickname != null) memory.addNickname(nickname);
        String unliked = extractUnlike(input);
        if (unliked != null) memory.removeLike(unliked);
        String like = extractLike(input);
        if (like != null) memory.addLike(like);
        memory.afterTurn("xiaozhi");
    }

    public ConversationMemory memory() { return memory; }

    private static final Pattern NAME = Pattern.compile("(?i)\\b(?:me llamo|mi nombre es)\\s+((?:\\p{L}{2,20})(?:[ '-](?:de|del|la|las|los))?(?:\\s+(?!y\\b|pero\\b|porque\\b|que\\b)\\p{L}{2,20}){0,3})\\b");
    private static final Pattern NICKNAME = Pattern.compile(
            "(?i)\\b(?:ll[aá]mame|decime|me dicen|mi apodo es|mi sobrenombre es)\\s+(\\p{L}{2,20})\\s*[.!¡]*\\s*$");
    private static final Pattern LIKE = Pattern.compile("(?i)(?<!no )\\bme (?:gusta|gustan|encanta|encantan)\\s+(.{2,80})$");
    private static final Pattern UNLIKE = Pattern.compile("(?i)\\bya no me (?:gusta|gustan|encanta|encantan)\\s+(.{2,80})$");
    private static final String[] NOT_NAMES = {"algo", "hora", "fecha", "clima", "chiste", "cuando", "como", "que",
            "donde", "quien", "por", "si", "no", "la", "el", "una", "un", "lo", "los", "las", "mas"};

    private String extractName(String raw) {
        if (raw == null) return null;
        Matcher m = NAME.matcher(raw);
        if (!m.find()) return null;
        String candidate = m.group(1).trim();
        String firstName = candidate.split("\\s+")[0];
        for (String stop : NOT_NAMES) if (stop.equalsIgnoreCase(firstName)) return null;
        return capitalizeName(candidate);
    }

    /** Capitaliza cada componente y conserva partículas habituales de apellidos en minúscula. */
    private static String capitalizeName(String name) {
        String[] words = name.toLowerCase(new Locale("es", "AR")).split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) result.append(' ');
            if ("de".equals(word) || "del".equals(word) || "la".equals(word)
                    || "las".equals(word) || "los".equals(word)) {
                result.append(word);
                continue;
            }
            String[] parts = word.split("-", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) result.append('-');
                if (!parts[i].isEmpty()) result.append(capitalize(parts[i]));
            }
        }
        return result.toString();
    }

    private String extractNickname(String raw) {
        if (raw == null) return null;
        Matcher m = NICKNAME.matcher(raw.trim());
        if (!m.find()) return null;
        String candidate = m.group(1);
        for (String stop : NOT_NAMES) if (stop.equalsIgnoreCase(candidate)) return null;
        return capitalize(candidate);
    }

    private String extractLike(String raw) {
        if (raw == null) return null;
        Matcher m = LIKE.matcher(raw.trim());
        return m.find() ? cleanTail(m.group(1)) : null;
    }

    private String extractUnlike(String raw) {
        if (raw == null) return null;
        Matcher m = UNLIKE.matcher(raw.trim());
        return m.find() ? cleanTail(m.group(1)) : null;
    }

    private static String cleanTail(String value) {
        String out = value.replaceAll("[\\s.!?¡¿,;:]+$", "").trim();
        return out.length() >= 2 ? out : null;
    }

    private static String capitalize(String word) {
        Locale ar = new Locale("es", "AR");
        return word.substring(0, 1).toUpperCase(ar) + word.substring(1).toLowerCase(ar);
    }

    private static boolean isNo(Utterance u) {
        return u.hasWord("no") || u.hasWord("nunca") || u.hasPhrase("para nada") || u.hasPhrase("ya no");
    }

    private static boolean isYes(Utterance u) {
        return u.hasWord("si") || u.hasWord("dale") || u.hasWord("obvio") || u.hasWord("claro")
                || u.hasWord("siempre") || u.hasWord("mucho") || u.hasPhrase("por supuesto");
    }
}
