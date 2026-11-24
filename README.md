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
- El reconocimiento local utiliza un Foreground Service. La sesión online actual se crea y administra desde MainActivity; por lo tanto, no se certifica continuidad de OpenAI en segundo plano ni después de destruir la Activity.

## Arquitectura de voz

1. OpenAI Realtime es el proveedor online principal.
2. Gemini Live es el segundo proveedor planificado, pendiente de implementación.
3. La voz y conversación locales son el respaldo operativo actual.
4. La personalidad, memoria, emociones, autonomía y herramientas de Bellota son independientes del proveedor.
5. XiaoZhi fue retirado; no se conserva compatibilidad con ese protocolo.

## Backend OpenAI

El Worker `bellota-openai-realtime` está desplegado en Cloudflare. El binding D1 `DB` apunta a la base existente `bellota-devices`; las migraciones de recuperación de refresh y los índices de retención se aplicaron. Los secretos `ADMIN_TOKEN`, `JWT_SECRET` y `OPENAI_API_KEY` y el binding D1 fueron verificados durante la Revisión 6. La observabilidad de logs y traces está habilitada.

El endpoint `GET /health` comprueba configuración y esquema D1. El código y la infraestructura fueron revisados, pero no se registra en esta certificación una respuesta HTTP externa del endpoint. La emisión y selección real de TURN en una red restrictiva tampoco se considera probada; depende de las credenciales TURN correspondientes.

No subir claves API, tokens, contraseñas ni archivos de firma al repositorio.

## Android y compilación

- `minSdk 24` (Android 7.0), `targetSdk 28`, `compileSdk 35`.
- Java 8 para el código de la aplicación; CI usa Java 17.
- Android Gradle Plugin 8.13.2 y Gradle Wrapper 8.13 con checksum SHA-256.
- WebRTC Android 150.7871.08.
- R8 y reducción de recursos activados únicamente en Release, con reglas de conservación para JNI de WebRTC.
- CI de Revisión 7 completada correctamente: pruebas JVM, Android Lint, compilación Release firmada, verificación del certificado y smoke tests en API 24, 27 y 31.

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
- El reintento de OpenAI es acotado. Tras el fallo se usa la voz local; no existe todavía failover OpenAI → Gemini.
- La prueba de emuladores no sustituye la prueba física de audio, interrupciones, funcionamiento prolongado, cambios de red y segundo plano en los dispositivos objetivo.
- La continuidad de voz online en segundo plano sigue pendiente.
- La prueba real de audio y failover OpenAI → Gemini → local requiere un secreto Gemini válido y dispositivos físicos.
- La certificación de BEL-001 a BEL-035 requiere disponer de los criterios formales originales de cada ticket. El estado trazable conocido se registra en `docs/revision-8-certificacion.md`.

## Documentación

- `docs/arquitectura-motores-voz.md`
- `docs/backend-seguro-dispositivos.md`
- `docs/revision-4-realtime-webrtc.md`
- `docs/revision-5-memory-conversation-tools.md`
- `docs/revision-6-backend-persistence.md`
- `docs/revision-8-certificacion.md`
- `backend/openai-realtime/README.md`
