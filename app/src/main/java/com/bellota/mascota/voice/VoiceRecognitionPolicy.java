package com.bellota.mascota.voice;

import java.util.List;

/**
 * Compatibilidad explícita del reconocimiento local por nivel de API.
 *
 * Android 7-11 no expone una API pública fiable para consultar si el servicio
 * de reconocimiento instalado dispone de un modelo offline. En esas versiones
 * EXTRA_PREFER_OFFLINE es una preferencia, no una garantía. Desde Android 12
 * se puede exigir el reconocedor on-device dedicado.
 */
public final class VoiceRecognitionPolicy {
    public enum Mode {
        OFFLINE_PREFERRED_PROVIDER,
        ON_DEVICE_REQUIRED;
    }

    public static final int ON_DEVICE_API = 31;

    private VoiceRecognitionPolicy() { }

    public static Mode modeForApi(int apiLevel) {
        return apiLevel >= ON_DEVICE_API ? Mode.ON_DEVICE_REQUIRED
                : Mode.OFFLINE_PREFERRED_PROVIDER;
    }

    public static boolean usesDedicatedOnDeviceRecognizer(int apiLevel) {
        return modeForApi(apiLevel) == Mode.ON_DEVICE_REQUIRED;
    }

    public static boolean shouldPreferOffline(int apiLevel) {
        return apiLevel >= 24;
    }


    /**
     * Selects a Spanish locale actually advertised by the installed recognizer.
     * The phone's UI locale is deliberately never consulted.
     */
    public static String selectSpanishLanguage(List<String> supportedLanguages) {
        if (supportedLanguages == null || supportedLanguages.isEmpty()) return null;
        java.util.ArrayList<String> spanish = new java.util.ArrayList<>();
        for (String value : supportedLanguages) {
            if (value == null) continue;
            String tag = value.trim().replace('_', '-');
            if (tag.equalsIgnoreCase("es") || tag.toLowerCase(java.util.Locale.ROOT).startsWith("es-")) {
                boolean duplicate = false;
                for (String existing : spanish) {
                    if (existing.equalsIgnoreCase(tag)) { duplicate = true; break; }
                }
                if (!duplicate) spanish.add(tag);
            }
        }
        String[] preferred = {"es-AR", "es-419", "es-ES", "es-MX", "es-US", "es"};
        for (String candidate : preferred) {
            for (String available : spanish) {
                if (candidate.equalsIgnoreCase(available)) return available;
            }
        }
        return spanish.isEmpty() ? null : spanish.get(0);
    }

    public static String descriptionForApi(int apiLevel) {
        if (usesDedicatedOnDeviceRecognizer(apiLevel)) {
            return "Reconocimiento local estricto: Android 12 o posterior requiere un motor on-device y un modelo español instalado.";
        }
        return "Reconocimiento local preferido: Android 7 a 11 recibe la preferencia sin conexión, pero el proveedor de Android puede requerir Internet.";
    }
}
