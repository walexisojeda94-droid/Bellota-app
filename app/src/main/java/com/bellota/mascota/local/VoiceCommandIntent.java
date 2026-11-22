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

    private static boolean matches(Utterance utterance, String command) {
        return utterance.text.equals(command) || utterance.text.equals(command + " por favor");
    }
}
