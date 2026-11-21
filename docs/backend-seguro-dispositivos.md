# Backend seguro y alta de dispositivos Bellota

## Decisión confirmada

El alta inicial utilizará **un código de un solo uso**. El propietario lo genera desde un canal administrativo seguro y lo ingresa una única vez en Bellota. No se utilizará QR ni una clave maestra embebida en el APK.

## Flujo implementado en el Worker

1. El administrador solicita `POST /admin/pairing-code` con `ADMIN_TOKEN`.
2. El Worker genera un código aleatorio, guarda únicamente su hash en D1 y lo hace vencer a los 10 minutos.
3. El usuario ingresa el código en Bellota.
4. `POST /v1/pair` consume el código mediante una actualización SQL condicional, evitando que se utilice dos veces.
5. El Worker crea el dispositivo y devuelve un refresh token aleatorio y un access token firmado de 15 minutos.
6. El backend guarda solo el hash del refresh token.
7. El dispositivo usa el access token para solicitar credenciales temporales de OpenAI Realtime.
8. El refresh token se rota cuando se renueva la sesión de autenticación del dispositivo.

## Endpoints del Worker

- `POST /admin/pairing-code`: crea un código de vinculación de un solo uso.
- `POST /v1/pair`: consume el código y registra el dispositivo.
- `POST /v1/auth/refresh`: rota el refresh token y emite un access token nuevo.
- `POST /v1/realtime/client-secret`: emite una credencial temporal OpenAI.
- `GET /health`: comprobación de disponibilidad.

Los detalles de cuerpos y respuestas están en `backend/openai-realtime/README.md`.

## Renovación y errores

- Access token del dispositivo: 15 minutos.
- Refresh token: 90 días, con rotación al renovar.
- Credencial temporal OpenAI: 10 minutos. Su vencimiento impide iniciar sesiones nuevas, pero no corta una sesión ya iniciada.
- 401/403 de vinculación: renovar una vez; si falla, pedir nueva vinculación.
- 429: límite de solicitudes.
- 402 o cuota agotada: error de cuenta, no se soluciona renovando tokens.
- 5xx o red: fallo transitorio del backend o proveedor.

## Almacenamiento

- Clave permanente OpenAI: Worker Secret `OPENAI_API_KEY`.
- Secreto administrativo: Worker Secret `ADMIN_TOKEN`.
- Firma de access tokens: Worker Secret `JWT_SECRET`.
- Refresh token: Android Keystore en el dispositivo; hash en D1.
- Código de emparejamiento: hash, vencimiento y marca de consumo en D1.
- Identidad de dispositivo: UUID aleatorio, nombre opcional, fechas y estado.

## Infraestructura

- Worker: `backend/openai-realtime/`.
- D1: `bellota-devices`.
- SQL inicial: `backend/openai-realtime/schema.sql`.
- Configuración: `backend/openai-realtime/wrangler.toml`.

## Pendiente antes de producción

- Crear D1 y reemplazar el marcador `REPLACE_WITH_D1_DATABASE_ID`.
- Cargar los tres secretos desde Cloudflare, nunca desde Git.
- Configurar rate limiting de Cloudflare para los endpoints administrativos y de autenticación.
- Implementar revocación administrativa de dispositivos y protección completa ante reutilización de refresh tokens.
- Agregar pruebas de integración de D1 y pruebas de abuso.
- Desplegar el Worker y registrar su URL en la app.
- Implementar en Android la pantalla de código, almacenamiento mediante Android Keystore y renovación automática.

El Worker no se debe considerar listo para producción hasta completar esos controles y ejecutar las pruebas indicadas.
