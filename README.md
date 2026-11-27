# Bellota

Aplicación Android nativa de mascota virtual. Bellota mantiene su interfaz, comportamiento, memoria, personalidad y conversación local en el teléfono. Los proveedores online son transportes intercambiables; no son propietarios del núcleo de la aplicación.

## Estado real del proyecto

- Interfaz horizontal e inmersiva con ojos vectoriales animados, reacciones al tacto y comportamientos autónomos.
- Estado de energía, diversión y edad virtual persistente.
- Conversación local, memoria conversacional y respuestas de respaldo.
- Voz local mediante Android TextToSpeech y reconocimiento mediante SpeechRecognizer.
- Consulta del clima mediante Open-Meteo y ubicación bajo demanda.
- OpenAI Realtime integrado en Android mediante WebRTC, credenciales efímeras emitidas por el Worker, renovación de la vinculación y fallback local.
- Memoria compartida entre conversación local y Realtime; diario cifrado de ambos lados de la conversación.
- Las llamadas de herramientas quedan asociadas a su sesión de origen, se serializan y descartan si llegan tarde.
- OpenAI Realtime es el proveedor online principal y Gemini Live es el segundo proveedor implementado mediante WebSocket y token efímero. La voz local es el último respaldo.
- VoiceSessionService administra OpenAI y Gemini como Foreground Service; MainActivity solo inicia y controla la sesión. El reconocimiento local conserva su propio Foreground Service. La continuidad tras destruir Activity y pantalla apagada requiere prueba física prolongada.

## Arquitectura de voz

1. OpenAI Realtime es el proveedor online principal.
2. Gemini Live (gemini-3.8-live) es el segundo proveedor implementado mediante WebSocket y credenciales efímeras emitidas por Cloudflare.
3. La voz local es el último respaldo cuando ambos proveedores online fallan.
4. La personalidad, memoria, emociones, autonomía y herramientas de Bellota son independientes del proveedor.
5. XiaoZhi fue retirado; no se conserva compatibilidad con ese protocolo.

## Backend OpenAI

El Worker `bellota-openai-realtime` está desplegado en Cloudflare. El binding D1 `DB` apunta a la base existente `bellota-devices`; las migraciones de recuperación de refresh y los índices de retención se aplicaron. Los secretos `ADMIN_TOKEN`, `JWT_SECRET` y `OPENAI_API_KEY` y el binding D1 fueron verificados. El Worker también publica `/v1/gemini/live-token`; el binding secreto `GEMINI_API_KEY` está presente en Cloudflare. La emisión real de tokens y la sesión de audio todavía requieren prueba de extremo a extremo. La observabilidad de logs y traces está habilitada.

El endpoint `GET /health` comprueba configuración y esquema D1. El código y la infraestructura fueron revisados, pero no se registra en esta certificación una respuesta HTTP externa del endpoint. La emisión y selección real de TURN en una red restrictiva tampoco se considera probada; depende de las credenciales TURN correspondientes.

No subir claves API, tokens, contraseñas ni archivos de firma al repositorio.

## Android y compilación

- `minSdk 24` (Android 7.0), `targetSdk 28`, `compileSdk 35`.
- Java 8 para el código de la aplicación; CI usa Java 17.
- Android Gradle Plugin 8.13.2 y Gradle Wrapper 8.13 con checksum SHA-256.
- WebRTC Android 150.7871.08.
- R8 y reducción de recursos activados únicamente en Release, con reglas de conservación para JNI de WebRTC.
- Estado de CI: en la última ejecución completa de la matriz (25/11/2026) aprobaron pruebas JVM, Android Lint, compilación Release, smoke tests API 24, API 27 y API 31, firma y publicación del APK. Esto no sustituye la validación física en el moto g52.

Comandos locales:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

## Límites de certificación

- El límite local de 24.000 tokens depende de eventos de uso recibidos; no es un límite de facturación impuesto por OpenAI.
- VoiceProviderManager coordina el orden OpenAI → Gemini → voz local, con aislamiento de llamadas a herramientas por sesión. La conmutación real con Gemini requiere una clave Google válida y prueba de audio.
- La prueba de emuladores no sustituye la prueba física de audio, interrupciones, funcionamiento prolongado, cambios de red y segundo plano en los dispositivos objetivo.
- La sesión online ya vive en VoiceSessionService foreground; falta validar físicamente pantalla apagada, cambios de red y recuperación prolongada.
- La prueba real de audio y failover OpenAI → Gemini → local requiere probar una sesión con un dispositivo físico y revisar el estado de cada proveedor.
- La certificación de BEL-001 a BEL-035 requiere disponer de los criterios formales originales de cada ticket. El estado trazable conocido se registra en `docs/revision-8-certificacion.md`.

## Documentación

- `docs/arquitectura-motores-voz.md`
- `docs/backend-seguro-dispositivos.md`
- `docs/comportamiento-autonomo.md`
- `docs/plan-de-trabajo.md`
- `docs/revision-4-realtime-webrtc.md`
- `docs/revision-5-memory-conversation-tools.md`
- `docs/revision-6-backend-persistence.md`
- `docs/revision-8-certificacion.md`
- `docs/revision-9-gemini-live.md`
- `docs/revision-10-bellota-tools.md`
- `docs/revision-11-private-memory.md`
- `docs/revision-12-android-local-voice.md`
- `docs/revision-13-performance-stability.md`
- `docs/revision-14-code-dependencies-ci.md`
- `backend/openai-realtime/README.md`
