# Vinculación de dispositivos Bellota

## Decisión vigente

Bellota utiliza una única clave fija, configurada por el propietario en Cloudflare como secreto `BELLOTA_ACCESS_KEY`. La clave no vence ni se renueva automáticamente.

## Funcionamiento

1. El propietario configura `BELLOTA_ACCESS_KEY` en los secretos del Worker.
2. En cada teléfono se ingresa la clave una sola vez desde «Voz online de Bellota».
3. La app envía la clave por HTTPS a `POST /v1/pair`.
4. El Worker la valida y confirma la vinculación.
5. Android guarda la clave cifrada mediante AES-GCM y una clave no exportable de Android Keystore.
6. Las solicitudes a OpenAI y Gemini usan esa misma clave fija. No hay access tokens de 15 minutos, refresh tokens de 90 días, rotaciones ni códigos de vinculación.

## Desvinculación y cambios

- «Desvincular» borra la clave guardada en ese teléfono.
- Los demás teléfonos continúan funcionando.
- Si el propietario cambia `BELLOTA_ACCESS_KEY` en Cloudflare, la clave anterior deja de autorizar solicitudes en todos los teléfonos. En cada teléfono que quiera conservar, se ingresa la nueva clave.
- No existe revocación individual remota con este modelo compartido.

## Endpoints

- `POST /v1/pair`: valida la clave fija.
- `POST /v1/realtime/client-secret`: emite credencial temporal OpenAI.
- `POST /v1/gemini/live-token`: emite token efímero Gemini.
- `GET /health`: comprueba la presencia de la clave y de al menos un proveedor.

## Claves y datos

- `BELLOTA_ACCESS_KEY`, `OPENAI_API_KEY` y `GEMINI_API_KEY` permanecen como secretos de Cloudflare.
- La clave fija no se incorpora al APK ni al código fuente.
- Android conserva la clave cifrada localmente.
- El Worker no registra claves, audio ni transcripciones.
- Las tablas D1 y migraciones del esquema anterior se conservan por ahora, pero no forman parte del flujo de autenticación nuevo.
