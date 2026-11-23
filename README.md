# Bellota

Aplicación Android nativa de mascota virtual. Bellota mantiene su interfaz, comportamiento, memoria, personalidad y conversación local en el teléfono. La arquitectura permite conectar proveedores online sin delegarles el núcleo de la aplicación.

## Estado real del proyecto

- Interfaz horizontal e inmersiva con ojos vectoriales animados, reacciones al tacto y comportamientos autónomos.
- Estado de energía, diversión y edad virtual persistente.
- Conversación local, memoria conversacional y respuestas de respaldo.
- Voz local mediante Android TextToSpeech y reconocimiento mediante SpeechRecognizer.
- Consulta del clima mediante Open-Meteo.
- Integración Android de OpenAI Realtime mediante WebRTC, autenticación con backend, límites de sesión, recuperación acotada y fallback local implementados en el código.
- Memoria semántica compartida entre conversación local y Realtime, con transcripción auxiliar de idioma automático y diario cifrado de ambos lados de la conversación.
- Las llamadas de herramientas quedan asociadas a su sesión de origen, se serializan y descartan si llegan tarde; varias llamadas de una respuesta se devuelven en un único lote.
- OpenAI Realtime es el único proveedor online implementado. Gemini Live todavía no está integrado.
- El reconocimiento local se ejecuta mediante un Foreground Service con notificación persistente mientras la tarea permanece activa. El servicio se detiene al cerrar la actividad/tarea; la sesión OpenAI WebRTC sigue ligada a MainActivity y no está garantizada en segundo plano.

**Importante:** que la integración exista en el código y que CI compile no significa que OpenAI o TURN estén validados en producción. La conexión real requiere desplegar el Worker, configurar D1 y secretos, y probar con credenciales válidas y una red restrictiva.

## Arquitectura de voz

1. OpenAI Realtime es el proveedor online principal.
2. Gemini Live queda como proveedor futuro, no como fallback disponible actualmente.
3. La voz y conversación locales son el respaldo operativo.
4. La personalidad, memoria, emociones, autonomía y herramientas de Bellota son independientes del proveedor.
5. XiaoZhi fue retirado y no se conserva compatibilidad con ese protocolo.

## Backend OpenAI

El directorio `backend/openai-realtime/` contiene:
- Worker para códigos de vinculación de un solo uso.
- Registro de dispositivos en Cloudflare D1.
- Refresh tokens almacenados como hash, rotación recuperable y revocación remota.
- Access tokens firmados de corta duración.
- Emisión de credenciales efímeras de OpenAI Realtime.
- Emisión opcional de credenciales temporales Cloudflare TURN.

El backend **no está desplegado ni configurado en producción**. Antes de desplegar:
1. Crear la base D1 y reemplazar `REPLACE_WITH_D1_DATABASE_ID` en `wrangler.toml`.
2. Aplicar el esquema o, si la base ya existe, la migración correspondiente una sola vez.
3. Configurar `OPENAI_API_KEY`, `ADMIN_TOKEN` y `JWT_SECRET`.
4. Para relay TURN, configurar también `TURN_KEY_ID` y `TURN_KEY_API_TOKEN`.
5. Configurar límites/alertas de gasto de OpenAI y reglas de rate limiting.
6. Desplegar y probar emparejamiento, renovación, revocación, audio bidireccional y recuperación.

No subir claves API, tokens, contraseñas ni archivos de firma al repositorio.

## Android y compilación

- `minSdk 24` (Android 7.0), `targetSdk 28`, `compileSdk 35`.
- Java 8 para el código de la aplicación.
- Android Gradle Plugin 8.13.2 y Gradle Wrapper 8.13 con checksum SHA-256 verificado.
- WebRTC Android 150.7871.08 se mantiene fijado porque ya era la versión estable más reciente al revisar; no se fuerza una actualización innecesaria.
- R8 y reducción de recursos activados para release, con reglas de conservación para la superficie JNI de WebRTC.
- CI ejecuta pruebas JVM, Android Lint, pruebas instrumentadas y smoke tests en Android 7/API 24, Android 8.1/API 27 y Android 12/API 31.

Comandos locales:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

La CI puede generar un APK debug temporal si no están configurados los secretos de firma permanente. Ese APK no equivale a una versión firmada con la identidad definitiva.

## Límites y validación pendiente

- Sesiones Realtime configuradas para 45 minutos y 1.024 tokens máximos de salida por respuesta.
- El límite acumulado local de 24.000 tokens depende de los eventos de uso recibidos y no es un límite de facturación garantizado por OpenAI.
- La reconexión automática está acotada a un reintento; luego se usa el motor local.
- TURN real, redes restrictivas, facturación/cuotas, audio bidireccional en dispositivos físicos y funcionamiento prolongado todavía requieren validación operativa.
- La compatibilidad automatizada de API 24 y API 31 no reemplaza las pruebas prolongadas en el Samsung Galaxy J7 objetivo.

## Documentación

- `docs/arquitectura-motores-voz.md`
- `docs/backend-seguro-dispositivos.md`
- `docs/revision-4-realtime-webrtc.md`
- `docs/revision-5-memory-conversation-tools.md`
- `backend/openai-realtime/README.md`
