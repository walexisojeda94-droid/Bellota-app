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
            String safeDetail = detail == null ? "" : detail;
            entry.put("detail", safeDetail);
            byte[] line = (entry.toString() + "\n").getBytes(Charset.forName("UTF-8"));

            // Una sola entrada tampoco puede superar el límite completo del diario.
            if (line.length > MAX_JOURNAL_BYTES) {
                int low = 0;
                int high = safeDetail.codePointCount(0, safeDetail.length());
                while (low < high) {
                    int mid = low + (high - low + 1) / 2;
                    int end = safeDetail.offsetByCodePoints(0, mid);
                    entry.put("detail", safeDetail.substring(0, end));
                    byte[] candidate = (entry.toString() + "\n").getBytes(Charset.forName("UTF-8"));
                    if (candidate.length <= MAX_JOURNAL_BYTES) low = mid;
                    else high = mid - 1;
                }
                int end = safeDetail.offsetByCodePoints(0, low);
                entry.put("detail", safeDetail.substring(0, end));
                line = (entry.toString() + "\n").getBytes(Charset.forName("UTF-8"));
            }

            trimForIncoming(line.length);
            FileOutputStream output = new FileOutputStream(journal, true);
            try { output.write(line); output.flush(); }
            finally { output.close(); }
        } catch (Exception ignored) {
            // Un fallo del diario nunca debe detener la mascota.
        }
    }

    /**
     * Hace espacio para la nueva entrada antes de escribirla.
     * Conserva las entradas más recientes completas y nunca supera el límite.
     */
    private void trimForIncoming(long incomingBytes) {
        try {
            long currentLength = journal.length();
            if (currentLength + incomingBytes <= MAX_JOURNAL_BYTES) return;

            long keep = Math.max(0L, MAX_JOURNAL_BYTES - incomingBytes);
            if (keep == 0L || !journal.exists()) {
                new FileOutputStream(journal, false).close();
                return;
            }

            java.io.RandomAccessFile file = new java.io.RandomAccessFile(journal, "r");
            byte[] tail;
            try {
                long readLength = Math.min(keep, file.length());
                file.seek(file.length() - readLength);
                tail = new byte[(int) readLength];
                file.readFully(tail);
            } finally {
                file.close();
            }

            int start = 0;
            while (start < tail.length && tail[start] != '\n') start++;
            start = Math.min(start + 1, tail.length); // descartar la primera línea parcial

            FileOutputStream output = new FileOutputStream(journal, false);
            try {
                output.write(tail, start, tail.length - start);
                output.flush();
            } finally {
                output.close();
            }
        } catch (Exception ignored) {
            // Un fallo del diario nunca debe detener la mascota.
        }
    }

    public File getJournalFile() { return journal; }
}
