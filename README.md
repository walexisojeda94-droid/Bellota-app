# Bellota

Aplicación Android nativa de mascota virtual. Bellota mantiene su interfaz, comportamiento, memoria, personalidad y respuestas locales en el teléfono. La arquitectura de voz permite conectar proveedores online sin delegarles el núcleo de la aplicación.

## Estado actual

- Interfaz inmersiva horizontal con ojos vectoriales animados.
- Reacciones al tacto y comportamientos autónomos.
- Estado básico de energía, diversión y edad virtual persistente.
- Conversación local con respuestas de respaldo y memoria sencilla.
- Voz local mediante Android TextToSpeech y reconocimiento mediante SpeechRecognizer.
- Consulta del clima mediante Open-Meteo y ubicación del teléfono.
- Contrato común de proveedores de voz y administrador de failover.
- Base inicial del backend seguro para OpenAI Realtime en `backend/openai-realtime/`.

**OpenAI Realtime todavía no está conectado a la aplicación Android ni desplegado.** El Worker incorporado es una base de backend, no una integración terminada. La app continúa usando la conversación y voz locales hasta completar el proveedor WebRTC y el flujo de vinculación.

## Arquitectura de voz

1. OpenAI Realtime será el proveedor principal.
2. Gemini Live se evaluará como proveedor secundario en una etapa posterior.
3. La voz y conversación locales permanecen como respaldo.
4. La personalidad, memoria, emociones, autonomía y herramientas de Bellota siguen siendo independientes del proveedor.
5. XiaoZhi fue eliminado y no se conserva compatibilidad con ese protocolo.

## Backend OpenAI

El directorio `backend/openai-realtime/` contiene:
- Worker para códigos de vinculación de un solo uso.
- Registro de dispositivos en Cloudflare D1.
- Refresh tokens almacenados como hash en D1 y rotación al renovar.
- Access tokens firmados de corta duración.
- Emisión de credenciales temporales de OpenAI Realtime.
- Esquema SQL y configuración inicial de Wrangler.

El backend aún requiere crear y vincular D1, configurar secretos y desplegarse. No contiene una clave OpenAI real.

## Compilar Android

La aplicación usa Android Gradle Plugin 8.6.1, compileSdk 35, minSdk 27 y Java 8 para el código de la app. La CI del repositorio ejecuta pruebas JVM, Android Lint y generación del APK release.

```bash
gradle --no-daemon :app:assembleDebug
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:lintDebug
```

El repositorio no incluye Gradle Wrapper. La compilación requiere una instalación local compatible de Gradle y Android SDK.

## Documentación

- `docs/arquitectura-motores-voz.md`
- `docs/backend-seguro-dispositivos.md`
- `backend/openai-realtime/README.md`

No se deben subir claves API, tokens, contraseñas ni archivos de firma al repositorio. Compilar no equivale a validar el funcionamiento de audio en el dispositivo físico.
