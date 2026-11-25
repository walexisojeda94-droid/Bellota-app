# Revisión 9: Gemini Live (BEL-045 a BEL-051)

## Decisión conservada

- OpenAI Realtime sigue siendo el proveedor principal.
- Gemini Live (gemini-3.8-live) es el segundo proveedor.
- La voz local es el último respaldo.
- No se incorporan motores adicionales.
- Ninguna clave permanente se distribuye dentro del APK.
- La conversación se fija en español rioplatense (es-AR) y no depende del idioma de Android.

## Implementación

- GeminiLiveProvider: transporte WebSocket Gemini Live, captura PCM16 mono 16 kHz y reproducción PCM16 24 kHz.
- Transcripciones de entrada y salida, interrupciones, cierre de turnos y eventos de herramientas.
- Conversión de declaraciones de herramientas al formato Gemini y devolución de resultados al nombre de función asociado al ID de llamada.
- Tokens efímeros de un solo uso emitidos por el Worker en /v1/gemini/live-token, API v1beta.
- GEMINI_API_KEY se lee exclusivamente desde un secreto del Worker.
- Renovación de credenciales, reanudación con handle de sesión, contexto reciente y recuperación acotada.
- VoiceProviderManager mantiene el orden OpenAI → Gemini → local; los resultados tardíos de herramientas no cruzan generaciones de sesión.
- VoiceSessionService administra ambos proveedores como servicio foreground. La voz local permanece en su servicio separado.

## Estado por ticket

| ID | Implementación | Estado de certificación |
|---|---|---|
| BEL-045 | GeminiLiveProvider integrado como segundo proveedor | Código integrado; falta probar conexión real |
| BEL-046 | Endpoint seguro de tokens efímeros | Worker desplegado y `GEMINI_API_KEY` registrada como secreto; falta validar emisión real |
| BEL-047 | Transporte de audio bidireccional PCM16 | Código integrado; prueba física pendiente |
| BEL-048 | Transcripciones, interrupciones, turnos y reanudación | Código integrado; prueba física pendiente |
| BEL-049 | Herramientas convertidas al protocolo Gemini | Código integrado; prueba con funciones reales pendiente |
| BEL-050 | Renovación, clasificación de errores y reconexión | Código integrado; prueba con token real pendiente |
| BEL-051 | OpenAI → Gemini → voz local | Orden validado con pruebas JVM; failover online real pendiente |

## Infraestructura Cloudflare

Worker: bellota-openai-realtime

Endpoint: POST /v1/gemini/live-token

Modelo: gemini-3.8-live

El Worker fue actualizado mediante la API de contenido sin reemplazar su configuración. Se verificó que permanecen los bindings D1 y los secretos existentes (ADMIN_TOKEN, JWT_SECRET, OPENAI_API_KEY). El secreto `GEMINI_API_KEY` fue agregado y verificado como binding en Cloudflare. El Worker quedó desplegado en la versión 20 con el flujo Gemini Live `v1beta`. La presencia del secreto está confirmada, pero todavía falta comprobar que Google acepte la clave y emita el token.

## Validaciones

- Pruebas Worker y validación JavaScript: aprobadas en CI.
- Pruebas JVM del gestor de proveedores y failover: aprobadas.
- Android Lint: aprobado después de agregar la comprobación explícita de permiso de micrófono.
- Compilación APK debug/API 24: aprobada.
- La matriz de emuladores debe comprobar que la app permanece viva al apagar y encender la pantalla. El ciclo de vida de VoiceSessionService ya tiene pruebas instrumentadas independientes de restauración tras teardown.
- No se declara certificación de audio Gemini real hasta cargar la clave Google y probar con un dispositivo físico.

## Activación pendiente

1. Probar desde Bellota la emisión del token Gemini y confirmar que el Worker no devuelve errores de autenticación o cuota.
2. Validar audio bidireccional, transcripciones, herramientas, interrupciones y recuperación en el moto g52.
3. Provocar un fallo controlado de OpenAI y confirmar el paso a Gemini; después provocar un fallo de Gemini y confirmar el modo local.