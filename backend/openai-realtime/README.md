# Bellota OpenAI Realtime backend

Worker de Cloudflare para validar la clave fija de Bellota y emitir credenciales efímeras de OpenAI Realtime y Gemini Live.

## Configuración de producción

- Worker: `bellota-openai-realtime`.
- URL: `https://bellota-openai-realtime.w-alexisojeda94.workers.dev`.
- Secretos requeridos:
  - `BELLOTA_ACCESS_KEY`: clave fija elegida por el propietario, mínimo 8 caracteres. No vence ni rota automáticamente.
  - `OPENAI_API_KEY`: clave permanente de OpenAI, solo en Cloudflare.
- Secreto opcional:
  - `GEMINI_API_KEY`: clave permanente de Gemini, solo en Cloudflare.
- TURN opcional:
  - `TURN_KEY_ID` y `TURN_KEY_API_TOKEN`.

No guardar ninguna de estas claves en el repositorio, recursos Android, APK ni logs. La clave fija de Bellota se ingresa en cada teléfono una sola vez y se conserva cifrada con Android Keystore.

## Vinculación y autorización

- `POST /v1/pair`: valida `Authorization: Bearer <BELLOTA_ACCESS_KEY>` y confirma la vinculación. No genera códigos ni crea sesiones temporales.
- `POST /v1/realtime/client-secret`: requiere la misma clave fija y emite una credencial temporal de OpenAI Realtime.
- `POST /v1/gemini/live-token`: requiere la misma clave fija y emite un token efímero de Gemini Live.
- `GET /health`: informa si la clave fija y al menos un proveedor están configurados, sin revelar sus valores.

La clave fija no tiene vencimiento automático. Al desvincular un teléfono, la app borra la copia local; los demás teléfonos no se ven afectados. Si el propietario cambia `BELLOTA_ACCESS_KEY` en Cloudflare, debe ingresar la nueva clave en cada teléfono que quiera seguir usando.

La autorización es compartida entre los dispositivos del propietario. No existe revocación remota individual: quitar la clave de un teléfono la elimina solo de ese teléfono; cambiar la clave en Cloudflare invalida la anterior en todos.

## Proveedores

### OpenAI Realtime

El Worker solicita una credencial temporal a `https://api.openai.com/v1/realtime/client_secrets`. La API key permanente permanece en Cloudflare. TURN, cuando está configurado, devuelve credenciales temporales al cliente.

### Gemini Live

El Worker emite tokens efímeros restringidos al modelo `gemini-3.8-live`, con vencimiento y uso único. La clave `GEMINI_API_KEY` permanece en Cloudflare. Android conecta por WebSocket `BidiGenerateContentConstrained`.

## Pruebas

Desde este directorio:

```bash
npm test
node --check src/index.js
```

Las migraciones y tablas D1 históricas se conservan en el repositorio por trazabilidad, pero el flujo de autenticación nuevo no depende de D1. No ejecutar migraciones para habilitar la clave fija.
