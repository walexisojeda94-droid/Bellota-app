package com.bellota.mascota.local;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interpreta órdenes explícitas de voz sin activar acciones por una palabra incidental. */
public final class VoiceCommandIntent {
    private VoiceCommandIntent() { }

    public static boolean isMuteRequest(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        if (matches(utterance, "no quiero silencio")
                || matches(utterance, "no me dejes en silencio")
                || matches(utterance, "no pongas silencio")) return false;
        return matches(utterance, "callate")
                || matches(utterance, "no hables")
                || matches(utterance, "no hables mas")
                || matches(utterance, "deja de hablar")
                || matches(utterance, "deja de hablar ahora")
                || matches(utterance, "desactiva el sonido")
                || matches(utterance, "apaga el sonido")
                || matches(utterance, "silencio")
                || matches(utterance, "quiero silencio")
                || matches(utterance, "ponete en silencio")
                || matches(utterance, "quedate en silencio");
    }

    public static boolean isUnmuteRequest(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        return matches(utterance, "sonido")
                || matches(utterance, "activa el sonido")
                || matches(utterance, "prende el sonido")
                || matches(utterance, "enciende el sonido")
                || matches(utterance, "habilita el sonido")
                || matches(utterance, "volvi a hablar")
                || matches(utterance, "volve a hablar")
                || matches(utterance, "habla bellota");
    }

    public static boolean isBatteryQuery(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        return matches(utterance, "bateria")
                || matches(utterance, "nivel de bateria")
                || utterance.startsWith("cuanta bateria tiene")
                || utterance.startsWith("como esta la bateria")
                || utterance.startsWith("cuanta carga tiene el telefono")
                || matches(utterance, "carga del telefono");
    }

    public static boolean isTimeOrDateQuery(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        return matches(utterance, "hora") || utterance.startsWith("que hora es")
                || utterance.startsWith("decime la hora") || utterance.startsWith("decir la hora")
                || utterance.startsWith("que dia es hoy") || utterance.startsWith("que fecha es hoy")
                || matches(utterance, "fecha de hoy") || matches(utterance, "fecha");
    }

    public static boolean isVolumeQuery(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        return matches(utterance, "volumen actual")
                || utterance.startsWith("que volumen tiene")
                || utterance.startsWith("a cuanto esta el volumen")
                || utterance.startsWith("cuanto volumen tiene el telefono");
    }

    public static boolean isVolumeChangeRequest(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        return utterance.startsWith("subi el volumen") || utterance.startsWith("subi volumen")
                || utterance.startsWith("sube el volumen") || utterance.startsWith("sube volumen")
                || utterance.startsWith("baja el volumen") || utterance.startsWith("baja volumen")
                || utterance.startsWith("bajame el volumen") || utterance.startsWith("subime el volumen")
                || utterance.startsWith("pone el volumen") || utterance.startsWith("pon el volumen")
                || utterance.startsWith("ajusta el volumen") || utterance.startsWith("ajustame el volumen")
                || utterance.startsWith("volumen al") || utterance.startsWith("volumen en");
    }

    /** Porcentaje explícito, o -1 cuando la orden es relativa. */
    public static int requestedVolumePercent(Utterance utterance) {
        if (utterance == null) return -1;
        Matcher matcher = Pattern.compile("(?:volumen|sonido).*?(\\d{1,3})").matcher(utterance.text);
        if (!matcher.find()) return -1;
        try {
            int value = Integer.parseInt(matcher.group(1));
            return value >= 0 && value <= 100 ? value : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    /** Incremento porcentual cuando no se indicó un valor exacto. */
    public static int volumeChangeDelta(Utterance utterance) {
        if (utterance == null) return 0;
        return utterance.startsWith("subi") || utterance.startsWith("sube")
                || utterance.startsWith("subime") ? 10 : -10;
    }

    private static boolean matches(Utterance utterance, String command) {
        return utterance.text.equals(command) || utterance.text.equals(command + " por favor");
    }
}
