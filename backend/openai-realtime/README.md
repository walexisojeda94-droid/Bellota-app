# Bellota OpenAI Realtime backend

Worker privado para vinculación de dispositivos y emisión de credenciales efímeras de OpenAI Realtime.

## Endpoints

- `POST /admin/pairing-code`: genera un código de un solo uso (10 minutos). Requiere `Authorization: Bearer <ADMIN_TOKEN>`.
- `POST /v1/pair`: consume el código y crea una instalación. Body: `{"code":"...","device_name":"Bellota Android"}`.
- `POST /v1/auth/refresh`: rota el refresh token. Body: `{"refresh_token":"..."}`.
- `POST /v1/realtime/client-secret`: requiere access token Bearer de 15 minutos. Body opcional: `{"instructions":"...","voice":"marin"}`.
- `GET /health`: comprobación básica.

## Preparación

1. Crear D1: `npx wrangler d1 create bellota-devices`.
2. Reemplazar `database_id` en `wrangler.toml` con el ID devuelto.
3. Aplicar el esquema: `npx wrangler d1 execute bellota-devices --remote --file=./schema.sql`.
4. Cargar secretos sin guardarlos en el repositorio:
   - `npx wrangler secret put OPENAI_API_KEY`
   - `npx wrangler secret put ADMIN_TOKEN`
   - `npx wrangler secret put JWT_SECRET`
5. Desplegar: `npx wrangler deploy`.

Generá ADMIN_TOKEN y JWT_SECRET con al menos 32 bytes aleatorios. Nunca uses el código de emparejamiento como secreto administrativo.

## Flujo de emparejamiento

El administrador genera un código desde un entorno confiable:

```sh
curl -X POST "https://<worker>.<subdomain>.workers.dev/admin/pairing-code" \
  -H "Authorization: Bearer $BELLOTA_ADMIN_TOKEN"
```

La app intercambia ese código una sola vez por un refresh token aleatorio. Android debe guardarlo exclusivamente mediante Android Keystore, excluirlo de backups y no escribirlo en logs. El backend guarda únicamente su hash. El refresh token se rota al renovarse; el access token firmado dura 15 minutos.

## Seguridad pendiente antes de producción

- Configurar rate limiting de Cloudflare para /admin/pairing-code, /v1/pair, /v1/auth/refresh y /v1/realtime/client-secret.
- Configurar alertas y límites de consumo en OpenAI.
- Implementar revocación administrativa de dispositivos y protección contra reutilización de refresh tokens.
- Agregar pruebas de integración con D1 y pruebas de abuso/rate-limit.
- Restringir la emisión de códigos administrativos a un canal de administración seguro.
- No desplegar hasta reemplazar el ID D1 y cargar los tres secretos.

El Worker no registra tokens, audio ni transcripciones. El cliente secret de OpenAI dura 10 minutos y su vencimiento impide crear nuevas sesiones, no corta una sesión ya iniciada.
