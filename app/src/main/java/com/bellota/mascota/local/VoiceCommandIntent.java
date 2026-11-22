package com.bellota.mascota.local;

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

    private static boolean matches(Utterance utterance, String command) {
        return utterance.text.equals(command) || utterance.text.equals(command + " por favor");
    }
}
