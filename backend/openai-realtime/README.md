# Bellota OpenAI Realtime backend

## Resources
- Worker: `bellota-openai-realtime`
- D1: `bellota-devices`
- Binding: `DB`

## Secrets (Worker secrets, never commit these)
- `OPENAI_API_KEY`: permanent OpenAI project key.
- `ADMIN_TOKEN`: random high-entropy administrator bearer token used only to mint one-time pairing codes.
- `JWT_SECRET`: random high-entropy HMAC signing secret (at least 32 random bytes).

Set each with Wrangler:
```sh
npx wrangler secret put OPENAI_API_KEY
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put JWT_SECRET
```

## Database
Apply `schema.sql` to the D1 database before first deployment:
```sh
npx wrangler d1 execute bellota-devices --remote --file=backend/openai-realtime/schema.sql
```

## Deploy
From `backend/openai-realtime`:
```sh
npx wrangler deploy
```

## Routes
- `GET /health`
- `POST /admin/pairing-code` (Bearer ADMIN_TOKEN; max 5 per IP per 5 minutes)
- `POST /v1/pair` (one-time code; max 10 attempts per IP per 10 minutes)
- `POST /v1/auth/refresh` (rotating refresh token; old-token reuse revokes device)
- `POST /v1/devices/revoke` (revokes the calling device)
- `POST /v1/realtime/client-secret` (authenticated; max 20 per device per 5 minutes)

Pairing codes expire after 10 minutes and are single-use. Access JWTs live 15 minutes. Refresh tokens live 90 days and are stored only as SHA-256 hashes. Realtime client secrets expire after 10 minutes.

## Operational notes
Rate limits are stored in D1 and are per Worker account/database, not a global WAF limit. Keep Cloudflare rate limiting/WAF rules as an additional perimeter control if the account plan supports them. Realtime session costs are billed to the OpenAI project. Monitor usage and set project budgets/alerts in OpenAI.

The Worker never returns upstream OpenAI error bodies, keys, or secrets. The health route reports only whether required configuration exists, not secret values.

## Android
Set `backend_base_url` to the deployed Worker HTTPS origin. Never put any of the three Worker secrets or the permanent OpenAI key in Android resources or source.

## Cloudflare Builds

La conexión de Cloudflare Builds está configurada para desplegar únicamente la rama `main`, usando:
`npx wrangler deploy --config backend/openai-realtime/wrangler.toml`.
Las ramas de trabajo no deben publicar cambios en producción automáticamente. El Worker puede validarse y desplegarse de forma controlada antes de integrar cambios.
