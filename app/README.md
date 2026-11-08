# Aplicación Android Bellota

Proyecto inicial orientado al Samsung Galaxy J7 (Android 8.1, API 27).

## Módulos internos

- `core/`: estado y expresiones de Bellota.
- `ui/`: pantalla y reproducción de GIF.
- `local/`: respuestas de respaldo sin internet.
- `voice/`: punto de organización para voz local y online.
- `xiaozhi/`: adaptador reservado para la integración oficial, pendiente de verificar.
- `assets/faces/`: expresiones GIF empaquetadas dentro de la aplicación.

## GIF reales

Los GIF se reproducen localmente con la clase Android `Movie`, sin dependencias externas. La pantalla permite seleccionar expresiones y probar el cambio de animación. Esta primera versión utiliza ocho expresiones principales; el resto de los GIF también queda disponible en assets para ampliar el selector.

## Abrir y ejecutar

Abrir la carpeta raíz del repositorio en Android Studio, sincronizar Gradle y ejecutar el módulo `app` en un dispositivo Android 8.1 o superior.

La integración XiaoZhi, el TTS offline y la reconexión automática todavía no están implementados. Las respuestas de demostración funcionan localmente y no requieren conexión.
