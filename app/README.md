# Aplicación Android Bellota

Proyecto Android orientado al Samsung Galaxy J7 con Android 8.1 (API 27).

## Funciones presentes en esta etapa

- Pantalla principal con expresiones GIF.
- Pantalla siempre encendida mientras la actividad está visible.
- Edad virtual persistente: un año de Bellota equivale a 30 días reales desde el primer inicio.
- Estado básico de energía y diversión guardado localmente.
- Comportamientos autónomos sencillos durante los períodos de inactividad.
- Solicitud de permisos de micrófono y cámara.
- Reconocimiento de voz por sesiones consecutivas mediante el servicio instalado en Android.
- Comandos iniciales: «Silencio, Bellota» y «Sonido, Bellota».
- Voz local Android TextToSpeech como respuesta de respaldo.

## Limitaciones pendientes

- El reconocimiento continuo depende del proveedor de voz instalado y puede tener pausas o requerir conectividad. No equivale todavía a la escucha de baja latencia integrada con XiaoZhi.
- «Mira esto, Bellota» y la captura real de cámara frontal con análisis visual todavía no están conectados.
- La activación OTA, autenticación, WebSocket y audio Opus de XiaoZhi todavía no están implementados.
- La memoria de esta etapa guarda edad y necesidades básicas; todavía no conserva recuerdos semánticos de conversaciones.
- Los comportamientos autónomos iniciales usan reglas y frases locales, no decisiones de un modelo de IA.

## Compilación

El workflow de GitHub Actions compila la aplicación con Java 17 y Gradle 8.7 y publica el APK de depuración como artefacto de la ejecución.
