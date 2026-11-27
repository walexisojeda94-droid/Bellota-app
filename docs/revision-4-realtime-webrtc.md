# Revisión 4: OpenAI Realtime y WebRTC

## Flujo de conexión

1. Android solicita al Worker una credencial efímera de OpenAI usando la vinculación del dispositivo.
2. El Worker configura la sesión con `max_output_tokens=1024` y truncamiento automático.
3. Si están configurados `TURN_KEY_ID` y `TURN_KEY_API_TOKEN`, el Worker obtiene credenciales Cloudflare TURN temporales y entrega solo esas credenciales al APK.
4. Android configura STUN y los servidores TURN recibidos, crea la oferta SDP y negocia contra `/v1/realtime/calls`.
5. El secreto efímero se descarta después de aplicar la respuesta SDP remota.

## Límites de sesión

- Duración máxima: 45 minutos, fijada también en `session.expires_at`; Android programa el cierre unos segundos antes.
- Máximo de salida configurado: 1.024 tokens por respuesta.
- Tope acumulado local: 24.000 tokens reportados por los eventos `response.done`.
- Truncamiento automático del contexto.

El tope acumulado es una protección del cliente, no un límite de facturación impuesto por OpenAI. Configurar límites y alertas de gasto en la cuenta sigue siendo necesario.

## Recuperación y liberación

- Timeout global de negociación: 60 segundos.
- Recolección ICE con envío al completar o al cumplirse el límite de espera.
- Estado ICE `DISCONNECTED`: período de gracia de 10 segundos.
- Fallo transitorio: un reintento automático; espera mayor ante HTTP 429.
- Callbacks asociados a intentos anteriores se descartan por identificador.
- Desconexión idempotente, cancelación de tareas, aborto de HTTP SDP y liberación de recursos WebRTC.

## Fallback actual

`VoiceSessionService` registra OpenAI Realtime como proveedor principal y `GeminiLiveProvider` como segundo proveedor online. Si ambos fallan según la política de recuperación, `VoiceProviderManager` solicita el motor local. La presencia del adaptador Android no certifica por sí sola la conexión real ni el audio bidireccional con Gemini.

## Estado operativo del backend

El Worker `bellota-openai-realtime` está desplegado en Cloudflare con binding D1 `DB` a `bellota-devices`. Las migraciones de recuperación de refresh e índices de retención fueron aplicadas. Se verificaron los secretos principales `ADMIN_TOKEN`, `JWT_SECRET` y `OPENAI_API_KEY`, además del binding D1. Logs y traces están habilitados.

El handler `GET /health` está implementado para comprobar secretos y esquema D1. Esta revisión no conserva una prueba HTTP externa que certifique el código de respuesta en producción. Tampoco se da por probada la negociación WebRTC real ni el audio bidireccional con credenciales de usuario.

## TURN y redes restrictivas

La app admite servidores ICE recibidos desde el Worker. La disponibilidad real de relay TURN depende de que los secretos `TURN_KEY_ID` y `TURN_KEY_API_TOKEN` estén configurados y que Cloudflare emita credenciales. No se considera TURN certificado sin observar un candidato relay y completar audio bidireccional en una red restrictiva.

## Validación pendiente

- Audio bidireccional en dispositivo físico.
- Red restrictiva con relay TURN confirmado.
- Interrupciones, cambios de red y sesión prolongada.
- Funcionamiento online con Activity recreada y en segundo plano.
- Prueba del proveedor secundario Gemini Live una vez implementado.

La CI verde verifica compilación, pruebas automatizadas, Lint y arranque smoke-test en emuladores; no sustituye estas pruebas operativas.
