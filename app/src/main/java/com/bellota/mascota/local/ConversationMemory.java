package com.bellota.mascota.local;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memoria de Bellota.
 * - Corto plazo (en RAM): última habilidad usada y qué respuesta espera del usuario.
 * - Largo plazo (en el Store): nombre, gustos, última vez que hablaron.
 */
public final class ConversationMemory {

    /** Almacenamiento clave-valor. En Android: SharedPrefsStore. */
    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    public static final long LONG_SILENCE_MS = 6L * 60 * 60 * 1000;
    private static final long EXPECT_TTL_MS = 3L * 60 * 1000;
    private static final int MAX_LIKES = 20;

    private final Store store;
    private String lastSkill;
    private String expecting;
    private long expectingAt;
    private long silenceMs;
    private boolean welcomePending;

    public ConversationMemory(Store store) { this.store = store; }

    /** Memoria que se pierde al cerrar la app (útil para pruebas). */
    public static ConversationMemory inMemory() {
        final Map<String, String> map = new HashMap<>();
        return new ConversationMemory(new Store() {
            @Override public String get(String key) { return map.get(key); }
            @Override public void put(String key, String value) { map.put(key, value); }
        });
    }

    // ---- Ciclo de cada turno -------------------------------------------------

    public void onUserMessage(long now) {
        long last = readLong("last_seen");
        silenceMs = last == 0 ? 0 : Math.max(0, now - last);
        welcomePending = silenceMs >= LONG_SILENCE_MS;
        store.put("last_seen", String.valueOf(now));
        store.put("messages", String.valueOf(readLong("messages") + 1));
    }

    public void afterTurn(String skillId) { lastSkill = skillId; }

    public long silenceMs() { return silenceMs; }

    /** True una sola vez por cada regreso tras un silencio largo. */
    public boolean takeWelcomeBack() {
        boolean pending = welcomePending;
        welcomePending = false;
        return pending;
    }

    public String lastSkill() { return lastSkill; }

    // ---- Lo que Bellota espera que el usuario conteste ----------------------

    public void expect(String what, long now) {
        expecting = what;
        expectingAt = now;
    }

    public String expecting(long now) {
        if (expecting != null && now - expectingAt > EXPECT_TTL_MS) expecting = null;
        return expecting;
    }

    public void clearExpecting() { expecting = null; }

    // ---- Largo plazo --------------------------------------------------------

    public String name() {
        String n = store.get("user_name");
        return (n == null || n.isEmpty()) ? null : n;
    }

    public void setName(String name) { store.put("user_name", name); }

    public List<String> likes() {
        List<String> out = new ArrayList<>();
        String raw = store.get("likes");
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split("\\|")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    public void addLike(String like) {
        String clean = like == null ? "" : like.replace("|", " ").trim();
        if (clean.isEmpty()) return;
        List<String> list = likes();
        for (String s : list) if (s.equalsIgnoreCase(clean)) return;
        list.add(clean);
        while (list.size() > MAX_LIKES) list.remove(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(list.get(i));
        }
        store.put("likes", sb.toString());
    }

    private long readLong(String key) {
        try {
            String v = store.get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
