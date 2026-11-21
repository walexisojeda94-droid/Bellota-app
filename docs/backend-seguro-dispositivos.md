# Backend seguro y alta de dispositivos Bellota

## Objetivo

El backend de Bellota debe emitir credenciales efímeras de OpenAI Realtime sin incluir la clave permanente de OpenAI en el APK. Cada instalación debe tener una identidad revocable. No se utilizará una clave maestra compartida embebida en la aplicación.

## Alta inicial propuesta

1. El propietario crea desde un canal administrativo un código de emparejamiento aleatorio, de un solo uso y con vencimiento corto.
2. El usuario introduce ese código una sola vez en Bellota. No se compila ni se distribuye dentro del APK.
3. El Worker valida el código, lo consume atómicamente y registra una instalación con identificador aleatorio.
4. El Worker entrega un refresh token aleatorio de alta entropía. En la base de datos se almacena únicamente su hash.
5. Android guarda el refresh token mediante Android Keystore. El token no se registra en logs ni se incluye en backups.
6. El dispositivo intercambia su refresh token por una credencial de acceso de corta duración para llamar al endpoint de sesión.
7. El propietario puede revocar la instalación. La revocación invalida futuras renovaciones y emisiones de credenciales.

## Renovación

- La aplicación solicita una credencial temporal nueva al backend antes de crear cada sesión Realtime.
- Si el token de acceso del dispositivo vence, usa el refresh token almacenado para renovarlo automáticamente.
- La rotación del refresh token debe ser atómica: el token anterior queda invalidado cuando se emite el nuevo.
- Si se detecta reutilización de un refresh token ya rotado, se revoca la familia de tokens de esa instalación.
- Los errores 401/403 se reintentan como máximo una vez tras renovar. Los errores 429, 402, 5xx y de red no se tratan como errores de credenciales.
- El vencimiento de la credencial temporal de Realtime no debe confundirse con el vencimiento del refresh token del dispositivo ni con la duración de una sesión activa.

## Endpoint de sesión Realtime

- Requiere autenticación del dispositivo.
- Aplica límite de frecuencia por dispositivo y controles globales de consumo.
- Crea una credencial temporal mediante la API oficial de OpenAI Realtime Client Secrets.
- Devuelve solo la credencial temporal y los metadatos de expiración necesarios para iniciar WebRTC.
- Nunca devuelve la clave permanente de OpenAI.
- No registra credenciales, audio, transcripciones ni contenido privado.
- Distingue cuenta sin crédito, cuota agotada, rate limit, credenciales inválidas y fallos transitorios del proveedor.

## Almacenamiento y protección

- Clave permanente OpenAI: Cloudflare Worker Secret.
- Refresh token: hash criptográfico en D1; valor original solo en Android Keystore.
- Código de emparejamiento: hash, vencimiento, estado de consumo y límite de intentos.
- Identidad de dispositivo: ID aleatorio, fecha de alta, última actividad, estado y versión de app.
- Los identificadores no se derivan de IMEI, número de teléfono, correo ni otros identificadores sensibles del hardware.
- No se debe confiar en una URL oculta, un header estático dentro del APK ni una API key compilada como autenticación.

## Recuperación y pérdida del dispositivo

- El propietario puede revocar una instalación desde el backend.
- Si se pierde el refresh token o se reinstala la aplicación, se realiza un nuevo emparejamiento.
- Si el backend no está disponible, la aplicación conserva el modo local y no intenta eludir la autenticación.
- La indisponibilidad de OpenAI, falta de crédito y límites de cuenta son estados diferentes y no se solucionan rotando credenciales.

## Infraestructura Cloudflare prevista

- Worker dedicado para emparejamiento, renovación y emisión de credenciales Realtime.
- D1 para códigos de emparejamiento, dispositivos, hashes de refresh tokens y revocaciones.
- Worker Secrets para la clave permanente de OpenAI y secretos de firma.
- Rate limiting para limitar intentos de emparejamiento, renovación y emisión de sesiones.

## Antes de desplegar

- Confirmar el flujo de emparejamiento inicial en la interfaz de Bellota.
- Definir la política de revocación y recuperación del propietario.
- Crear Worker y D1 en la cuenta correcta.
- Cargar secretos desde el panel de Cloudflare, nunca desde Git.
- Probar alta, renovación, rotación, revocación, reutilización de token, cuotas y caída del backend.
