package com.bellota.mascota.voice;

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
        OFFLINE_PREFERRED_PROVIDER(24),
        ON_DEVICE_REQUIRED(31);

        private final int minimumApi;
        Mode(int minimumApi) { this.minimumApi = minimumApi; }
        public int getMinimumApi() { return minimumApi; }
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

    public static String descriptionForApi(int apiLevel) {
        if (usesDedicatedOnDeviceRecognizer(apiLevel)) {
            return "Reconocimiento local estricto: Android 12 o posterior requiere un motor on-device y un modelo español instalado.";
        }
        return "Reconocimiento local preferido: Android 7 a 11 recibe la preferencia sin conexión, pero el proveedor de Android puede requerir Internet.";
    }
}
