package com.bellota.mascota.local;

import android.content.Context;
import android.content.SharedPreferences;
import com.bellota.mascota.core.PetLife;
import java.util.Locale;

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

        String learnedName = extractName(u.text);
        if (learnedName != null) {
            memory.setName(learnedName);
            memory.afterTurn("remember_name");
            return Reply.text("¡Listo! Voy a recordar que te llamás " + learnedName + ".");
        }
        String like = extractLike(u.text);
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

    public ConversationMemory memory() { return memory; }

    private String extractName(String text) {
        String[] markers = {"me llamo ", "mi nombre es ", "soy "};
        for (String marker : markers) {
            int at = text.indexOf(marker);
            if (at >= 0) {
                String candidate = text.substring(at + marker.length()).trim();
                if (candidate.matches("[a-záéíóúñ]{2,20}")) return candidate.substring(0,1).toUpperCase(new Locale("es","AR")) + candidate.substring(1);
            }
        }
        return null;
    }

    private String extractLike(String text) {
        String[] markers = {"me gusta ", "me encanta "};
        for (String marker : markers) {
            int at = text.indexOf(marker);
            if (at >= 0) {
                String value = text.substring(at + marker.length()).trim();
                if (value.length() >= 2 && value.length() <= 80) return value;
            }
        }
        return null;
    }
}