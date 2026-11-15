package com.bellota.mascota.local;

/** Lo que dijo el usuario: texto original, versión normalizada y palabras. */
public final class Utterance {
    public final String raw;
    public final String text;
    private final String[] tokens;
    private final String padded;

    private Utterance(String raw) {
        this.raw = raw;
        this.text = TextUtil.normalize(raw);
        this.tokens = text.isEmpty() ? new String[0] : text.split(" ");
        this.padded = " " + text + " ";
    }

    public static Utterance of(String raw) {
        return new Utterance(raw == null ? "" : raw);
    }

    public int size() { return tokens.length; }

    public boolean isEmpty() { return tokens.length == 0; }

    /** Frase completa respetando límites de palabra ("hola" no matchea "peholazo"). */
    public boolean hasPhrase(String phrase) {
        String normalized = TextUtil.normalize(phrase);
        return !normalized.isEmpty() && padded.contains(" " + normalized + " ");
    }

    /** Palabra suelta, con tolerancia a errores solo si es larga. */
    public boolean hasWord(String word) {
        String normalized = TextUtil.normalize(word);
        if (normalized.isEmpty()) return false;
        for (String t : tokens) if (TextUtil.similar(t, normalized)) return true;
        return false;
    }

    /** Alguna palabra empieza con la raíz dada ("content" matchea "contenta"). */
    public boolean hasStem(String stem) {
        String normalized = TextUtil.normalize(stem);
        if (normalized.isEmpty()) return false;
        for (String t : tokens) if (t.startsWith(normalized)) return true;
        return false;
    }

    public boolean startsWith(String phrase) {
        String normalized = TextUtil.normalize(phrase);
        return !normalized.isEmpty() && (text.equals(normalized) || text.startsWith(normalized + " "));
    }
}
