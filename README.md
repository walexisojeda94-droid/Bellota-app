# Bellota

Aplicación Android de mascota virtual para un **Samsung Galaxy J7 (SM-J710SS)**. Bellota está diseñada con una arquitectura local-first: su interfaz, comportamiento, memoria básica y respuestas de respaldo viven en el teléfono. XiaoZhi aporta conversación online cuando el servicio está disponible.

## Estado del proyecto

El repositorio contiene una aplicación Android nativa en Java. La integración OTA de XiaoZhi y los transportes de voz WebSocket y MQTT/UDP están implementados en código, pero todavía requieren validación completa en el dispositivo físico. La compilación automatizada se realiza mediante GitHub Actions.

## Funciones implementadas en el código

- Interfaz inmersiva horizontal con ojos vectoriales animados.
- Reacciones al tacto y comportamientos autónomos: explorar, pensar, jugar, descansar e iniciar interacciones.
- Estado básico de energía, diversión y edad virtual persistente.
- Conversación local con respuestas de respaldo y memoria sencilla.
- Voz local mediante Android TextToSpeech y reconocimiento mediante SpeechRecognizer.
- Solicitud de activación/configuración OTA de XiaoZhi.
- Sesiones de voz XiaoZhi por WebSocket u MQTT/UDP con audio Opus.
- Consulta del clima mediante Open-Meteo y ubicación del teléfono.
- Conexión MCP opcional, con credencial almacenada mediante el almacén seguro de la aplicación.

La presencia de una función en el código no significa que ya haya superado pruebas de campo en el J7.

## Estructura

- `app/`: aplicación Android y código fuente.
- `docs/`: arquitectura, estado, plan de trabajo y referencias técnicas.
- `.github/workflows/`: compilación automatizada del APK.
- `build.gradle`, `settings.gradle`, `gradle.properties`: configuración Gradle.

## Compilar

La compilación de CI utiliza Java 17, Android SDK 35 y Gradle 8.7.

En un entorno con Android SDK y Gradle instalados:

```bash
gradle --no-daemon :app:assembleDebug
```

El APK de depuración queda en `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions publica el APK como artefacto de la ejecución.

## Documentación

- [Resumen del proyecto](docs/resumen-del-proyecto.md)
- [Arquitectura local-first](docs/arquitectura-j7-offline.md)
- [Integración XiaoZhi](docs/integracion-xiaozhi-me.md)
- [Plan de trabajo](docs/plan-de-trabajo.md)
- [Referencias técnicas XiaoZhi](docs/references/xiaozhi/README.md)

## Alcance y seguridad

Bellota está orientada al uso personal en el teléfono. No se deben subir tokens, contraseñas, claves privadas ni archivos de firma al repositorio. Las pruebas de voz, consumo de batería, memoria y reconexión deben realizarse en el dispositivo objetivo antes de considerar estable una versión.
