# Backend seguro y alta de dispositivos Bellota

## Decisión confirmada

El alta inicial utiliza un código de un solo uso. El propietario lo genera desde un canal administrativo seguro y lo ingresa una única vez en Bellota. No se utiliza QR ni una clave maestra embebida en el APK.

## Flujo de vinculación

1. El administrador solicita `POST /admin/pairing-code` con `ADMIN_TOKEN`.
2. El Worker genera un código aleatorio, guarda únicamente su hash en D1 y lo hace vencer a los 10 minutos.
3. El usuario ingresa el código en Bellota.
4. `POST /v1/pair` consume el código y registra el dispositivo en un único `D1.batch()` transaccional. El reclamo queda asociado a un UUID específico para evitar que dos solicitudes concurrentes creen dispositivos con el mismo código.
5. El Worker devuelve un refresh token y un access token firmado de 15 minutos. D1 guarda solo el hash del refresh token.
6. El dispositivo utiliza el access token para solicitar credenciales temporales de OpenAI Realtime.

## Renovación recuperable

- Access token del dispositivo: 15 minutos.
- Refresh token: 90 días, con rotación al renovar.
- La siguiente credencial se deriva mediante HMAC del secreto del Worker, el identificador del dispositivo y el hash del token anterior. El valor en claro no se guarda en D1.
- Se conserva el hash anterior durante 120 segundos. Si el cliente pierde la respuesta HTTP o no logra persistir el nuevo token, puede repetir la misma solicitud y recuperar el mismo token resultante.
- Una repetición antigua deja de ser válida cuando expira la ventana o cuando ya ocurrió otra rotación.

## Revocación

- `POST /v1/device/revoke` recibe el refresh token por HTTPS, localiza el dispositivo por su hash y marca `revoked_at`.
- La operación es idempotente: si la respuesta se pierde, el mismo token permite repetir la revocación y confirmar el resultado.
- El cliente borra la credencial local solamente después de recibir confirmación del Worker y de completar el borrado local.
- Si no puede contactar al servidor, conserva la credencial para poder reintentar. Los access tokens de un dispositivo revocado dejan de autorizar nuevas solicitudes porque el Worker comprueba `revoked_at`.

## Protección de claves y memoria en Android

- El refresh token se cifra con AES-GCM y una clave no exportable de Android Keystore. Los access tokens y las credenciales temporales OpenAI permanecen en memoria.
- Si Android invalida la clave del refresh token, la app elimina la credencial ilegible y requiere volver a vincular el dispositivo. No intenta usar el token como texto plano.
- El diario `PetMemory` se cifra con AES-GCM y una clave Keystore independiente. Conserva entradas de hasta 180 días, con un máximo de 512 KiB de contenido descifrado; elimina primero los registros vencidos y luego las entradas más antiguas.
- La memoria conversacional persistente en SharedPreferences también se cifra individualmente con AES-GCM. Los valores previos en texto plano se migran al almacenamiento cifrado al iniciar el motor. Nombre, gustos y apodos se conservan localmente hasta que se borren los datos de la app o se desinstale; no se sincronizan con el Worker.
- Si se invalida la clave del diario, el archivo cifrado queda apartado como archivo `.locked` y Bellota inicia un diario nuevo. Sin la clave original no es posible recuperar esos recuerdos.
- El manifiesto establece `android:allowBackup="false"`, por lo que las credenciales y memorias privadas no se incluyen en los respaldos Android.

## Endpoints del Worker

- `POST /admin/pairing-code`: crea un código de vinculación de un solo uso.
- `POST /v1/pair`: consume el código y registra el dispositivo atómicamente.
- `POST /v1/auth/refresh`: rota el refresh token con recuperación ante respuesta perdida.
- `POST /v1/device/revoke`: revoca el dispositivo de forma idempotente.
- `POST /v1/realtime/client-secret`: emite una credencial temporal OpenAI.
- `GET /health`: comprobación básica de disponibilidad.

Los detalles de cuerpos y respuestas están en `backend/openai-realtime/README.md`.

## Migración D1 requerida

Para una base existente, antes de desplegar el Worker actualizado, ejecutar una sola vez:

```bash
npx wrangler d1 execute bellota-devices --remote --file=./migrations/0001_recoverable_refresh.sql
```

La migración agrega los hashes de rotación anterior y la columna que identifica el reclamo del código. Para una base nueva, utilizar `schema.sql` y no ejecutar la migración incremental encima.

## Infraestructura y pendientes

- Worker: `backend/openai-realtime/`.
- D1: `bellota-devices`.
- SQL inicial: `backend/openai-realtime/schema.sql`.
- Migración incremental: `backend/openai-realtime/migrations/0001_recoverable_refresh.sql`.
- Configuración: `backend/openai-realtime/wrangler.toml`.
- El Worker todavía requiere confirmar el ID real de D1, los secretos y el despliegue en Cloudflare.
- Siguen pendientes el rate limiting de Cloudflare, la administración centralizada de revocaciones y pruebas de integración ejecutadas contra una instancia D1 real.

La validación local/CI de sintaxis del Worker y de la migración SQLite no reemplaza una prueba de integración contra Cloudflare D1 desplegado.
