# Bellota OpenAI Realtime backend

Worker privado para vinculación de dispositivos y emisión de credenciales efímeras de OpenAI Realtime.

## Endpoints

- `POST /admin/pairing-code`: genera un código de un solo uso (10 minutos). Requiere `Authorization: Bearer <ADMIN_TOKEN>`.
- `POST /v1/pair`: consume el código y crea una instalación. Body: `{"code":"...","device_name":"Bellota Android"}`.
- `POST /v1/auth/refresh`: rota el refresh token de forma recuperable. Si la respuesta se pierde, el mismo token anterior permite recuperar la misma rotación durante 120 segundos. Body: `{"refresh_token":"..."}`.
- `POST /v1/device/revoke`: revoca remotamente el dispositivo. Body: `{"refresh_token":"..."}`. Es idempotente para permitir reintentos cuando se pierde la respuesta.
- `POST /v1/realtime/client-secret`: requiere access token Bearer de 15 minutos. Body opcional: `{"instructions":"...","voice":"marin"}`.
- `GET /health`: comprueba que existan las tablas `devices` y `pairing_codes` en D1 y que estén presentes `ADMIN_TOKEN`, `JWT_SECRET` y `OPENAI_API_KEY`. Devuelve `503` si falta alguna dependencia, sin exponer secretos ni detalles internos.

## Preparación

1. Crear D1: `npx wrangler d1 create bellota-devices`.
2. Reemplazar `database_id` en `wrangler.toml` con el ID devuelto.
3. En una base nueva, aplicar el esquema: `npx wrangler d1 execute bellota-devices --remote --file=./schema.sql`.
   En una base existente, aplicar una sola vez la migración: `npx wrangler d1 execute bellota-devices --remote --file=./migrations/0001_recoverable_refresh.sql`.
4. Cargar secretos sin guardarlos en el repositorio:
   - `npx wrangler secret put OPENAI_API_KEY`
   - `npx wrangler secret put ADMIN_TOKEN`
   - `npx wrangler secret put JWT_SECRET`
   - `npx wrangler secret put TURN_KEY_ID` (ID de la clave TURN de Cloudflare)
   - `npx wrangler secret put TURN_KEY_API_TOKEN` (token API asociado a esa clave TURN)
5. Desplegar: `npx wrangler deploy`.

Generá ADMIN_TOKEN y JWT_SECRET con al menos 32 bytes aleatorios. Nunca uses el código de emparejamiento como secreto administrativo.

## Flujo de emparejamiento

El administrador genera un código desde un entorno confiable:

```sh
curl -X POST "https://<worker>.<subdomain>.workers.dev/admin/pairing-code" \
  -H "Authorization: Bearer $BELLOTA_ADMIN_TOKEN"
```

La app intercambia ese código una sola vez por un refresh token aleatorio. Android debe guardarlo exclusivamente mediante Android Keystore, excluirlo de backups y no escribirlo en logs. El backend guarda únicamente su hash. El refresh token se rota al renovarse; el access token firmado dura 15 minutos. La rotación deriva el siguiente token con HMAC del secreto del Worker y conserva el hash anterior por 120 segundos, sin guardar tokens en claro en D1. La vinculación consume el código y crea el dispositivo en un único lote transaccional D1.

## Validación y retención

Las rutas JSON rechazan tipos de contenido incorrectos, JSON malformado, cuerpos superiores al límite y campos con tipos o longitudes inválidos. Las rutas conocidas responden `405` cuando se usa un método incorrecto. El endpoint de salud ejecuta `SELECT 1` contra D1 y no informa valores de secretos. Al generar códigos de vinculación se ejecuta una limpieza acotada: los códigos vencidos se conservan durante siete días y los dispositivos revocados o con refresh vencido durante 180 días antes de eliminarse.

Ejecutar las pruebas del Worker desde esta carpeta con `npm test` (Node.js 20 o superior).

## Seguridad pendiente antes de producción

- Configurar rate limiting de Cloudflare para /admin/pairing-code, /v1/pair, /v1/auth/refresh y /v1/realtime/client-secret.
- Configurar alertas y límites de consumo en OpenAI.
- Añadir pruebas automatizadas de integración para revocación, concurrencia y recuperación de refresh tokens.
- Agregar pruebas de integración con D1 y pruebas de abuso/rate-limit.
- Restringir la emisión de códigos administrativos a un canal de administración seguro.
- No desplegar hasta reemplazar el ID D1 y cargar los tres secretos.

El Worker no registra tokens, audio ni transcripciones. El cliente secret de OpenAI dura 10 minutos y su vencimiento impide crear nuevas sesiones, no corta una sesión ya iniciada. Cada sesión se configura con un máximo de 1.024 tokens de salida por respuesta, truncamiento automático y `expires_at` fijado a 45 minutos desde la emisión de la credencial. OpenAI aplica el vencimiento del lado servidor y Android programa el cierre unos segundos antes. También se aplica un tope acumulado local de 24.000 tokens de uso informado por Realtime. El límite acumulado es una protección del cliente, no un límite de facturación impuesto por OpenAI; configurar también límites y alertas de gasto en la plataforma.

## TURN para redes restrictivas

La app obtiene los servidores ICE desde este Worker. Si `TURN_KEY_ID` y `TURN_KEY_API_TOKEN` están configurados, el Worker solicita credenciales Cloudflare TURN efímeras con TTL de 3.600 segundos y las devuelve al cliente. La clave TURN y el token API nunca salen del Worker. Si no están configurados, la app conserva STUN como compatibilidad, pero no tendrá relay TURN y algunas redes pueden impedir la conexión.

Crear primero una TURN key en Cloudflare Realtime y guardar su ID y API token como secretos del Worker. La credencial efímera dura una hora, por encima del límite de sesión de 45 minutos. Cloudflare factura el tráfico TURN saliente cuando se usa fuera de su Realtime SFU; revisar la tarifa vigente antes de habilitarlo. Una falla al generar credenciales cuando TURN está configurado devuelve `503 turn_unavailable` y no degrada silenciosamente a STUN.

## Recuperación y cierre WebRTC

La app aplica un timeout global de negociación, espera 10 segundos ante `ICE DISCONNECTED`, realiza un único reintento automático ante fallos transitorios (con espera mayor para HTTP 429), y luego entrega el control al fallback del gestor de proveedores. Las sesiones terminadas cancelan timers, abortan la negociación HTTP activa, liberan DataChannel, PeerConnection, pistas, fuentes, fábrica y módulo de audio, y apagan el ejecutor. La desconexión es idempotente. `MainActivity` tiene OpenAI como único proveedor online registrado; si el reintento falla, el gestor pasa al motor local. Gemini Live todavía no está implementado como proveedor secundario. Ver el detalle en `docs/revision-4-realtime-webrtc.md`.
