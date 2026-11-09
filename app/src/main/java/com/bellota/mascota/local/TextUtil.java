package com.bellota.mascota.local;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto compartidas por el router y las habilidades. */
public final class TextUtil {
    private static final Locale AR = new Locale("es", "AR");

    private TextUtil() {}

    /** Minúsculas, sin tildes ni signos, con espacios simples. */
    public static String normalize(String value) {
        String n = Normalizer.normalize(value.toLowerCase(AR), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "")
                .replaceAll("[¿?¡!,.;:()\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Compara una palabra escuchada con una esperada. Tolera errores de transcripción
     * solo en palabras largas, para que "hola" y "hora" nunca se confundan.
     */
    public static boolean similar(String token, String word) {
        if (token.equals(word)) return true;
        int len = word.length();
        int tolerance = len >= 9 ? 2 : len >= 6 ? 1 : 0;
        return tolerance > 0
                && Math.abs(token.length() - len) <= tolerance
                && levenshtein(token, word) <= tolerance;
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
