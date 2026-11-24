# Revisión 12: compatibilidad Android y reconocimiento local

## Política de reconocimiento (BEL-069 y BEL-070)

Bellota fija el idioma de reconocimiento en español. Primero solicita `es-AR`, después `es-ES` y finalmente `es-US`. Nunca toma el idioma del teléfono como idioma de reconocimiento.

- Android 7 / API 24 y Android 8.1 / API 27: usa el servicio SpeechRecognizer instalado y envía `EXTRA_PREFER_OFFLINE=true`. Android 7-11 no ofrece una API pública fiable para consultar si el proveedor tiene descargado un modelo offline. Por eso es una preferencia, no una garantía. Si el proveedor informa un error de red, Bellota comunica que necesita conexión y reintenta con espera creciente.
- Android 12 / API 31 y posteriores: exige `SpeechRecognizer.isOnDeviceRecognitionAvailable()` y crea `createOnDeviceSpeechRecognizer()`. Si no hay servicio on-device, no inicia silenciosamente un reconocedor que pueda enviar audio a Internet. Si el modelo español no está instalado, informa que falta y reintenta más tarde.

La disponibilidad del servicio y la disponibilidad del modelo español son dos condiciones distintas. Un proveedor instalado no implica que tenga español descargado.

## Micrófono y ciclo de vida (BEL-071)

- El permiso `RECORD_AUDIO` se comprueba en el servicio antes de activar el micrófono y también antes de iniciar el reconocedor.
- La captura en segundo plano se mantiene mediante un servicio foreground con notificación persistente y acción para detener.
- La sesión local usa `START_NOT_STICKY`: Android no vuelve a abrir el micrófono por su cuenta después de matar el proceso.
- Al volver a abrir Bellota, `onResume()` vuelve a solicitar el reconocimiento si el permiso existe y no hay una sesión online usando el micrófono.
- Al retirar la tarea desde recientes, el servicio local se detiene (`stopWithTask=true`).
- No se registra un receptor de arranque ni se activa el micrófono después de reiniciar el teléfono. La reanudación requiere que el usuario abra la aplicación. Es una decisión deliberada de privacidad.

## TTS y voces (BEL-072)

El motor TTS solicita español argentino y, si no está disponible, español general. Después busca una voz española instalada que no requiera conexión y prioriza es-AR. Si no puede confirmar una voz offline, informa que la voz disponible podría requerir Internet. Si no existe una voz española compatible, muestra un aviso en lugar de simular que la salida hablada está lista.

## Servicios y reinicio (BEL-073 y BEL-074)

El servicio local usa foreground service, notificación visible y no se reinicia silenciosamente tras muerte de proceso o reinicio del sistema. La aplicación restaura la escucha al abrirse, con permiso concedido. Las sesiones online conservan su propio ciclo de vida y son mutuamente excluyentes con el reconocedor local para evitar competencia por el micrófono.

## Validación pendiente en dispositivos

La política se cubre con pruebas JVM para API 24, 27 y 31. La CI debe ejecutar instalación, permiso, arranque, suspensión/reanudación y pruebas instrumentadas en emuladores API 24, 27 y 31. La disponibilidad de un paquete de reconocimiento español y de una voz TTS offline depende de la imagen del emulador y del dispositivo real; no se considera certificada solo por compilar.
