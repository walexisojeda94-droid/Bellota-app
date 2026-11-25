# Bellota OpenAI Realtime backend

Worker de Cloudflare para vinculación de dispositivos, renovación/revocación y emisión de credenciales efímeras de OpenAI Realtime y Gemini Live.

## Estado desplegado

- Worker: `bellota-openai-realtime`.
- Base D1: `bellota-devices`, enlazada mediante `DB`.
- Migraciones `0001_recoverable_refresh.sql` y `0002_retention_indexes.sql` aplicadas.
- Secretos comprobados como bindings en Cloudflare: `OPENAI_API_KEY`, `GEMINI_API_KEY`, `ADMIN_TOKEN` y `JWT_SECRET` (la presencia del binding no certifica por sí sola que la clave sea válida).
- Logs y traces habilitados.

No se documentan aquí valores de secretos ni identificadores internos de cuenta. No se da por probado el endpoint HTTP `/health` ni el relay TURN real sin una comprobación externa específica. La disponibilidad de TURN depende de los secretos `TURN_KEY_ID` y `TURN_KEY_API_TOKEN`; no asumir que están configurados.

## Endpoints

- `POST /admin/pairing-code`: genera un código de un solo uso (10 minutos). Requiere `Authorization: Bearer <ADMIN_TOKEN>`.
- `POST /v1/pair`: consume el código y crea una instalación. Body: `{"code":"...","device_name":"Bellota Android"}`.
- `POST /v1/auth/refresh`: rota el refresh token de forma recuperable. Si la respuesta se pierde, el token anterior permite recuperar la misma rotación durante 120 segundos.
- `POST /v1/device/revoke`: revoca remotamente el dispositivo. Es idempotente para permitir reintentos.
- `POST /v1/realtime/client-secret`: requiere access token Bearer de 15 minutos. Body opcional: `{"instructions":"...","voice":"marin"}`.
- `POST /v1/gemini/live-token`: requiere access token Bearer de 15 minutos. Emite un token efímero Gemini Live de un solo uso, restringido al modelo `gemini-3.8-live`, con vencimiento de 30 minutos y ventana de inicio de 60 segundos. La provisión REST usa `v1beta`; Android conecta por WebSocket `BidiGenerateContentConstrained` usando `access_token`, obligatorio para credenciales efímeras. La clave `GEMINI_API_KEY` permanece como secreto Cloudflare. Si falta, el endpoint devuelve `provider_not_configured` sin exponer datos de la cuenta.
- `GET /health`: comprueba las tablas/columnas requeridas de D1 y la presencia de los secretos principales. Incluye `checks.gemini` para indicar si `GEMINI_API_KEY` está configurada; Gemini es opcional para mantener OpenAI como proveedor principal.

## Configuración de un entorno nuevo

1. Crear una base D1 y configurar su ID en `wrangler.toml`.
2. En una base nueva, aplicar `schema.sql`. En una base existente, aplicar en orden `migrations/0001_recoverable_refresh.sql` y `migrations/0002_retention_indexes.sql`.
3. Cargar los secretos en Cloudflare, nunca en el repositorio:
   - `OPENAI_API_KEY`
   - `GEMINI_API_KEY`
   - `ADMIN_TOKEN`
   - `JWT_SECRET`
   - Opcional para TURN: `TURN_KEY_ID` y `TURN_KEY_API_TOKEN`
4. Desplegar con `npx wrangler deploy`.

Generar `ADMIN_TOKEN` y `JWT_SECRET` con al menos 32 bytes aleatorios. Nunca usar el código de emparejamiento como secreto administrativo.

## Seguridad y retención

- El código de emparejamiento vence a los 10 minutos y se consume en una operación D1 transaccional.
- D1 guarda el hash del refresh token, nunca su valor en claro.
- El access token dura 15 minutos; el refresh token se rota y la rotación anterior puede recuperarse durante 120 segundos.
- Los dispositivos revocados y refresh vencidos se conservan 180 días antes de su eliminación física.
- Los códigos vencidos se conservan durante una ventana de siete días.
- Las solicitudes JSON se validan en tipo, estructura, longitud y tamaño; las rutas conocidas rechazan métodos incorrectos con `405`.
- El Worker no registra tokens, audio ni transcripciones.

## TURN

Cuando se configuran ambas credenciales TURN, el Worker solicita credenciales temporales Cloudflare TURN con TTL de 3.600 segundos y devuelve únicamente esas credenciales al cliente. Si no están configuradas, el cliente conserva STUN y no dispone de relay TURN. No considerar una red restrictiva certificada hasta observar un candidato relay y completar audio bidireccional.

## Pruebas

Desde este directorio:

```bash
npm test
node --check src/index.js
```

La CI ejecuta Node.js 22, pruebas del Worker y validación SQLite de migraciones, pairing atómico y retención.

## Estado de Gemini Live

Gemini Live emite tokens efímeros restringidos mediante `/v1/gemini/live-token`. La clave `GEMINI_API_KEY` debe cargarse como secreto Cloudflare y nunca en Android ni en el repositorio.
