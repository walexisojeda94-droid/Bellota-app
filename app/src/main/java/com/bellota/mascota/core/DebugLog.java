package com.bellota.mascota.core;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Registro en memoria de los últimos eventos de conexión, para poder verlos en
 * pantalla cuando no hay depuración USB. Además de guardar la línea, la manda a
 * Logcat. No debe recibir tokens, claves ni transcripciones.
 */
public final class DebugLog {
    private static final int MAX_LINES = 120;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private DebugLog() { }

    public static void i(String tag, String message) { add("I", tag, message, null); Log.i(tag, message); }
    public static void w(String tag, String message) { add("W", tag, message, null); Log.w(tag, message); }
    public static void w(String tag, String message, Throwable t) { add("W", tag, message, t); Log.w(tag, message, t); }
    public static void e(String tag, String message) { add("E", tag, message, null); Log.e(tag, message); }
    public static void e(String tag, String message, Throwable t) { add("E", tag, message, t); Log.e(tag, message, t); }

    private static synchronized void add(String level, String tag, String message, Throwable t) {
        String shortTag = tag == null ? "" : tag.replace("Bellota", "");
        StringBuilder line = new StringBuilder();
        line.append(TIME.format(new Date())).append(' ').append(level).append(' ')
                .append(shortTag).append(": ").append(message == null ? "" : message);
        if (t != null) line.append(" [").append(t.getClass().getSimpleName())
                .append(t.getMessage() == null ? "" : ": " + t.getMessage()).append(']');
        LINES.addLast(line.toString());
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder out = new StringBuilder();
        for (String line : LINES) out.append(line).append('\n');
        return out.toString();
    }
}
