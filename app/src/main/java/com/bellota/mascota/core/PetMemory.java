package com.bellota.mascota.core;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/** Diario privado y persistente de Bellota, guardado en el almacenamiento interno. */
public final class PetMemory {
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
            byte[] line = (entry.toString() + "\n").getBytes(Charset.forName("UTF-8"));
            FileOutputStream output = new FileOutputStream(journal, true);
            try { output.write(line); output.flush(); }
            finally { output.close(); }
        } catch (Exception ignored) {
            // Un fallo del diario nunca debe detener la mascota.
        }
    }

    public File getJournalFile() { return journal; }
}