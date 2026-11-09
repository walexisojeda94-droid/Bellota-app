package com.bellota.mascota.local;

/**
 * Resultado de una consulta. Distingue lo que se resolvió localmente de lo que
 * hay que delegar (clima, o modelo de lenguaje como XiaoZhi).
 */
public final class Reply {
    public enum Type { TEXT, NEEDS_WEATHER, NEEDS_AI }

    public final Type type;
    public final String text;
    public final String skillId;

    private Reply(Type type, String text, String skillId) {
        this.type = type;
        this.text = text;
        this.skillId = skillId;
    }

    public static Reply text(String text) { return new Reply(Type.TEXT, text, null); }
    public static Reply weather() { return new Reply(Type.NEEDS_WEATHER, null, null); }
    public static Reply needsAi() { return new Reply(Type.NEEDS_AI, null, null); }

    public boolean isText() { return type == Type.TEXT; }

    public Reply withSkill(String id) { return new Reply(type, text, id); }

    public Reply withPrefix(String prefix) {
        if (type != Type.TEXT || prefix == null || prefix.isEmpty()) return this;
        return new Reply(type, prefix + " " + text, skillId);
    }
}
