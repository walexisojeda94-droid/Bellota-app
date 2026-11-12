package com.bellota.mascota.core;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/** Diario privado y persistente de Bellota, guardado en el almacenamiento interno. */
public final class PetMemory {
    private static final long MAX_JOURNAL_BYTES = 512L * 1024L;
    private final File journal;

    public PetMemory(Context context) {
        File directory = new File(context.getFilesDir(), "bellota");
        if (!directory.exists()) directory.mkdirs();
        journal = new File(directory, "recuerdos.jsonl");
    }

    public synchronized void remember(String type, String detail) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("type", type == null ? "evento" : type);
            entry.put("detail", detail == null ? "" : detail);
            trimIfTooLarge();
            byte[] line = (entry.toString() + "\n").getBytes(Charset.forName("UTF-8"));
            FileOutputStream output = new FileOutputStream(journal, true);
            try { output.write(line); output.flush(); }
            finally { output.close(); }
        } catch (Exception ignored) {
            // Un fallo del diario nunca debe detener la mascota.
        }
    }

    /** Evita que el diario crezca sin límite en un teléfono con poco almacenamiento. */
    private void trimIfTooLarge() {
        try {
            if (journal.length() <= MAX_JOURNAL_BYTES) return;
            java.io.RandomAccessFile file = new java.io.RandomAccessFile(journal, "r");
            byte[] tail;
            try {
                long keep = MAX_JOURNAL_BYTES / 2;
                file.seek(file.length() - keep);
                tail = new byte[(int) keep];
                file.readFully(tail);
            } finally { file.close(); }
            int start = 0;
            while (start < tail.length && tail[start] != '\n') start++; // descartar línea cortada
            start = Math.min(start + 1, tail.length);
            FileOutputStream output = new FileOutputStream(journal, false);
            try { output.write(tail, start, tail.length - start); }
            finally { output.close(); }
        } catch (Exception ignored) { }
    }

    public File getJournalFile() { return journal; }
}
