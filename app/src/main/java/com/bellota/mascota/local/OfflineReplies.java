package com.bellota.mascota.local;

/** Respuestas básicas locales: no requieren internet ni XiaoZhi. */
public class OfflineReplies {
    public String reply(String input) {
        if (input == null) return "Estoy acá. ¿Qué necesitás?";
        String text = input.trim().toLowerCase();
        if (text.contains("hola")) return "¡Hola! Me alegra que estés acá.";
        if (text.contains("cómo estás") || text.contains("como estas")) return "Estoy bien y lista para acompañarte.";
        if (text.contains("gracias")) return "¡De nada!";
        if (text.contains("chau") || text.contains("adiós") || text.contains("adios")) return "Hasta pronto. Voy a estar por acá.";
        return "Todavía estoy aprendiendo a responder eso, pero sigo acá aunque no haya internet.";
    }
}
