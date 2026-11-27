# Revisión 12: compatibilidad Android y reconocimiento local

## Política de reconocimiento

Bellota fija el idioma de reconocimiento en español y nunca hereda el idioma de interfaz del teléfono.

- Android 13 / API 33 y posteriores: consulta al reconocedor qué idiomas españoles tiene instalados localmente y selecciona uno de esa lista. Si el modelo español está soportado pero no instalado, solicita su descarga. Primero prueba el reconocedor on-device y luego el proveedor de voz configurado en Android.
- Android 7-11 / API 24-30: consulta los idiomas que anuncia el proveedor mediante `ACTION_GET_LANGUAGE_DETAILS`, selecciona una variante española disponible y solicita `EXTRA_PREFER_OFFLINE=true`. Android 7-11 no ofrece una API pública fiable para confirmar que el modelo offline está instalado; la preferencia no garantiza funcionamiento sin conexión.
- Android 12 / API 31-32: conserva el reconocedor on-device dedicado y la selección explícita de variantes españolas. No cambia silenciosamente al idioma del teléfono.

La disponibilidad del servicio y la disponibilidad del modelo español son condiciones distintas. Un proveedor instalado no implica que tenga un modelo español descargado.

## Diagnóstico previo del Motorola moto g52

En Android 13/API 33, el dispositivo tenía TTS local disponible y permiso de micrófono concedido. El reconocimiento fallaba con `es-US`, `es-AR`, `es-ES` y `es`: códigos 13 (idioma no disponible) y 12 (idioma no compatible). El reconocedor llegaba a emitir «Android está listo para escuchar» y luego devolvía el error.

La causa probable no era el idioma de interfaz, sino que Bellota enviaba etiquetas predefinidas sin consultar primero las capacidades del motor seleccionado. La implementación nueva consulta las etiquetas instaladas/soportadas en el servicio real y evita asumir que las cuatro variantes están disponibles.

## Micrófono y ciclo de vida

- El permiso `RECORD_AUDIO` se comprueba antes de activar el micrófono.
- La captura en segundo plano se mantiene mediante un Foreground Service con notificación persistente y acción para detener.
- El servicio local no reactiva el micrófono después de una muerte del proceso o reinicio del teléfono. Al volver a abrir Bellota, se solicita el reconocimiento si el permiso existe.
- Las sesiones online y el reconocimiento local son mutuamente excluyentes para evitar competencia por el micrófono.

## TTS

El motor TTS local se mantiene independiente del reconocedor. El diagnóstico previo confirmó que la síntesis local estaba lista; este cambio no altera su selección de voz.

## Validación pendiente en dispositivos

La selección dinámica se cubre con pruebas JVM para locales anunciados. La compilación y CI deben validar compatibilidad de API. La confirmación final requiere probar en el Motorola moto g52: idioma español instalado, escucha, transcripción, pausa durante TTS y reanudación. También deben conservarse pruebas en Android API 24, 27 y 31.
