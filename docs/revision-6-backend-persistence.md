# Revisión 6: backend, ubicación, validación y persistencia

Esta revisión agrupa BEL-020, BEL-024, BEL-025 y BEL-026.

## BEL-020: ubicación bajo demanda

- Se conserva la última ubicación conocida cuando tiene menos de dos horas.
- Si no hay una ubicación suficientemente reciente, se solicita una única actualización a los proveedores disponibles.
- La solicitud se ejecuta sólo cuando se consulta el clima y termina al recibir una ubicación o al cumplirse diez segundos.
- Los proveedores se prueban de forma independiente, para que la falta de permiso GPS no bloquee el proveedor de red.
- No se incorpora seguimiento continuo ni servicio de ubicación en segundo plano.
- Si no se consigue una ubicación nueva, se conserva el fallback del último clima guardado durante un máximo de seis horas.

## BEL-024: health check

`GET /health` comprueba la presencia de `ADMIN_TOKEN`, `JWT_SECRET` y `OPENAI_API_KEY`, y verifica en D1 que existan las tablas y columnas requeridas de `devices` y `pairing_codes`. Responde `200` sólo cuando ambos controles pasan y `503` en caso contrario. La respuesta sólo informa estados booleanos; no revela secretos ni errores internos.

El Worker está desplegado y el binding D1 y los secretos principales fueron verificados desde Cloudflare. El código del health check está desplegado; no se registra aquí una respuesta HTTP externa de `/health`, por lo que no se afirma una comprobación de extremo a extremo del endpoint.

## BEL-025: validación de solicitudes

- Se exige `application/json` en endpoints con cuerpo.
- El cuerpo se lee en streaming y tiene un límite de 16 KiB.
- JSON malformado, arrays, valores nulos, tipos incorrectos y campos fuera de longitud se rechazan con errores HTTP específicos.
- Las instrucciones de Realtime tienen un máximo de 12.000 caracteres.
- Las rutas conocidas rechazan métodos incorrectos con `405` y el encabezado `Allow`.

## BEL-026: retención y eficiencia

- Los códigos de vinculación vencidos se limpian conservando una ventana de siete días.
- Los registros de dispositivos revocados o con refresh vencido se conservan 180 días antes de su eliminación.
- Se aplican índices separados para la limpieza de dispositivos.
- El diario local migra formatos anteriores a BME2.
- BME2 cifra cada entrada de forma independiente con AES-GCM y una IV aleatoria.
- Cada entrada individual se limita a 256 KiB; el archivo total se limita a 512 KiB incluyendo cabeceras e IV.
- La compactación aplica retención de 180 días y conserva Android Keystore, AES-GCM, AtomicFile y cuarentena ante fallos de autenticación.

## Despliegue e infraestructura comprobados

- Worker: `bellota-openai-realtime`, publicado en Cloudflare.
- D1: `bellota-devices`, binding `DB` verificado.
- Migraciones `0001_recoverable_refresh.sql` y `0002_retention_indexes.sql` aplicadas.
- Secretos principales verificados: `ADMIN_TOKEN`, `JWT_SECRET`, `OPENAI_API_KEY`.
- Observabilidad de logs y traces habilitada.
- No se incluyen valores secretos en el repositorio.

## Validación automatizada

- Worker: `node --check` y `npm test` con Node.js 22.
- D1: el workflow valida migraciones, pairing atómico y retención mediante SQLite.
- Android: pruebas JVM, Lint, compilación y pruebas instrumentadas.
- La prueba instrumentada de PetMemory verifica migración a BME2, cifrado y escritura incremental.

La ubicación real depende de permisos y proveedores del dispositivo. El health check HTTP, TURN en red restrictiva, conexión OpenAI real y audio bidireccional siguen necesitando una prueba operativa explícita.
