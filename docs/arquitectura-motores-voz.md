# Arquitectura de motores conversacionales de Bellota

## Decisión de producto

- OpenAI Realtime es el proveedor online principal.
- Gemini Live es el segundo proveedor online, integrado mediante WebSocket con token efímero restringido.
- La voz y conversación local permanecen como respaldo operativo.
- La memoria, personalidad, estado emocional, autonomía, comandos y funciones propias de Bellota pertenecen a la aplicación, no a los proveedores.
- No se incorporan otros motores en esta etapa. XiaoZhi fue retirado.

## Reglas de seguridad y continuidad

1. Ninguna clave API permanente de OpenAI o Google se almacena en el APK, recursos, preferencias ni código fuente.
2. Las credenciales temporales se solicitan al backend controlado por Bellota y se mantienen el tiempo mínimo necesario en memoria.
3. La app no solicita renovaciones manuales de tokens al usuario.
4. Los errores de autenticación, red, cuota, proveedor y audio se distinguen.
5. La reconexión debe tener límites, evitar bucles y no duplicar mensajes ni acciones.
6. El contexto se reconstruye desde la memoria local y un conjunto acotado de turnos.
7. Un cambio de proveedor no se considera exitoso hasta que el proveedor destino confirma que la sesión está lista.
8. El modo local debe permanecer disponible aunque fallen los proveedores online.

## Estado implementado

- Contrato común `VoiceProvider` y coordinador `VoiceProviderManager`, con pruebas JVM.
- Adaptadores `OpenAIRealtimeWebRtcProvider` (WebRTC) y `GeminiLiveProvider` (WebSocket) coordinados por `VoiceProviderManager` desde `VoiceSessionService`.
- Worker Cloudflare para pairing, refresh token rotatorio, revocación y credenciales efímeras de OpenAI y Gemini.
- D1 y secretos principales configurados; migraciones de retención aplicadas.
- Memoria, personalidad y herramientas propias de Bellota separadas de la implementación del proveedor.
- Reconocimiento local en un Foreground Service.
- La sesión OpenAI online aún depende de `MainActivity`; no hay garantía de continuidad al destruir la Activity o pasar a segundo plano.

## Gemini Live: integración y certificación operativa

La documentación vigente de Gemini Live describe un transporte WebSocket bidireccional. Para clientes móviles, el diseño de Bellota debe usar un token efímero de un solo uso emitido por el backend, nunca una API key de Google dentro del APK. El cliente enviaría audio PCM16 mono a 16 kHz y procesaría audio PCM16 de salida a 24 kHz; la configuración inicial debe fijar el modelo, la modalidad de audio y las instrucciones de Bellota.

La API de tokens efímeros y el endpoint WebSocket restringido deben tratarse como una sola pareja de versiones/protocolo. Antes de producción hay que fijar y probar una versión exacta del endpoint, el modelo Live disponible para la cuenta y el formato de sesión. No mezclar ejemplos de SDK o versiones alpha/beta.

La integración implementada incluye:
- secreto `GEMINI_API_KEY` solo en Cloudflare Worker (pendiente de cargar en el entorno de producción);
- endpoint autenticado del Worker para emitir token efímero de un solo uso y acotado al modelo/configuración;
- adaptador Android `GeminiLiveProvider` que implemente `VoiceProvider`;
- captura y reproducción de audio, interrupciones, fin de turno, transcripciones y errores;
- asociación de herramientas con proveedor y generación de sesión;
- expiración, renovación y reconexión sin bucles;
- pruebas de contrato, protocolo, audio y cambio OpenAI ↔ Gemini.

Firebase AI Logic ofrece una ruta Android, pero su integración Live figura en developer preview y tiene limitaciones de interrupción; no se adopta como base de certificación estable.

## Segundo plano

El reconocimiento local funciona como Foreground Service. El transporte online no está alojado allí: hoy su ciclo de vida depende de `MainActivity`. Para cumplir el requisito de conversación online en segundo plano, el gestor y el proveedor activo deben trasladarse a un servicio foreground de larga duración, con notificación persistente, gestión de foco de audio, permisos y cierre explícito. No declarar ese requisito cumplido hasta probarlo con la pantalla apagada, Activity recreada y tarea en segundo plano.

## Criterios de aceptación del conjunto

- No hay claves permanentes en APK, repositorio, logs ni preferencias.
- El backend emite credenciales temporales y el cliente las renueva sin intervención manual.
- Los errores permanentes no se reintentan indefinidamente.
- No hay respuestas ni herramientas duplicadas al reconectar o cambiar de proveedor.
- La memoria y personalidad sobreviven a sesiones y cambios de proveedor.
- OpenAI, Gemini y el modo local se pueden seleccionar mediante el mismo contrato.
- Android 7/API 24 y Android 12/API 31 son plataformas obligatorias; API 27 también forma parte de la matriz de regresión.
- La distribución privada usa APK firmado y verificación de identidad del certificado.
- Compilar y pasar emuladores no sustituye las pruebas reales de audio, segundo plano y red.
