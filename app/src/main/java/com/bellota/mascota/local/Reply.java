package com.bellota.mascota.local;

/**
 * Resultado de una consulta. Distingue lo que se resolvió localmente de lo que
 * hay que delegar a un servicio especializado (por ejemplo, el clima).
 */
public final class Reply {
    public enum Type { TEXT, NEEDS_WEATHER }

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

    public Reply withSkill(String id) { return new Reply(type, text, id); }

}
