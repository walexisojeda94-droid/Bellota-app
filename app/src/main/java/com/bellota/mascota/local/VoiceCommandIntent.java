package com.bellota.mascota.local;

/** Interpreta órdenes explícitas de voz sin activar acciones por una palabra incidental. */
public final class VoiceCommandIntent {
    private VoiceCommandIntent() { }

    public static boolean isMuteRequest(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        if (utterance.hasPhrase("no quiero silencio")
                || utterance.hasPhrase("no me dejes en silencio")
                || utterance.hasPhrase("no pongas silencio")) return false;
        return utterance.hasPhrase("callate")
                || utterance.hasPhrase("no hables")
                || utterance.hasPhrase("deja de hablar")
                || utterance.hasPhrase("desactiva el sonido")
                || utterance.hasPhrase("apaga el sonido")
                || utterance.text.equals("silencio")
                || utterance.text.equals("quiero silencio")
                || utterance.text.equals("ponete en silencio")
                || utterance.text.equals("quedate en silencio");
    }

    public static boolean isUnmuteRequest(Utterance utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        return utterance.text.equals("sonido")
                || utterance.hasPhrase("activa el sonido")
                || utterance.hasPhrase("prende el sonido")
                || utterance.hasPhrase("enciende el sonido")
                || utterance.hasPhrase("habilita el sonido")
                || utterance.hasPhrase("volvi a hablar")
                || utterance.hasPhrase("volve a hablar")
                || utterance.hasPhrase("habla bellota");
    }
}
