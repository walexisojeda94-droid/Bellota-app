# Revisión 6: backend, ubicación, validación y persistencia

Esta revisión agrupa BEL-020, BEL-024, BEL-025 y BEL-026. Los cambios se preparan en la rama `fix/revision-6-backend-persistence`; no se despliega el Worker ni se alteran secretos de Cloudflare.

## BEL-020: ubicación bajo demanda

- Se conserva el uso de la última ubicación conocida cuando tiene menos de dos horas.
- Si no hay una ubicación suficientemente reciente, se solicita una única actualización a los proveedores disponibles.
- La solicitud se ejecuta sólo cuando la persona consulta el clima y termina al recibir una ubicación o al cumplirse diez segundos.
- Se prueban proveedores disponibles de forma independiente, para que la ubicación aproximada o la falta de permiso para GPS no bloquee el proveedor de red.
- No se incorpora seguimiento continuo ni servicio en segundo plano.
- Si no se consigue una ubicación nueva, se conserva el fallback del último clima guardado durante un máximo de seis horas.

## BEL-024: health check real

`GET /health` comprueba la presencia de `ADMIN_TOKEN`, `JWT_SECRET` y `OPENAI_API_KEY`, y verifica en D1 que existan las tablas `devices` y `pairing_codes`. Responde `200` sólo cuando ambos controles pasan y `503` en caso contrario. La respuesta sólo informa estados booleanos; no revela secretos ni errores internos.

## BEL-025: validación de solicitudes

- Se exige `application/json` en endpoints con cuerpo.
- El cuerpo se lee en streaming y tiene un límite de 16 KiB, con rechazo temprano si supera el límite declarado.
- JSON malformado, arrays, valores nulos, tipos incorrectos y campos fuera de longitud se rechazan con errores HTTP específicos.
- Las instrucciones de Realtime tienen un máximo de 12.000 caracteres; el backend deja de truncar silenciosamente datos inválidos.
- Las rutas conocidas rechazan métodos incorrectos con `405` y el encabezado `Allow`.

## BEL-026: retención y eficiencia

- Los códigos de vinculación vencidos se limpian conservando una ventana de siete días.
- Los registros de dispositivos revocados o con refresh vencido se conservan 180 días antes de su eliminación.
- El diario local migra automáticamente el formato anterior (JSONL plano o BME1) al formato BME2.
- BME2 cifra cada entrada de forma independiente con AES-GCM y una IV aleatoria; un alta normal agrega sólo el registro cifrado nuevo en vez de descifrar y volver a cifrar todo el archivo.
- La compactación aplica la retención de 180 días y el límite de 512 KiB como máximo una vez por día o antes si el archivo se acerca al límite.
- Se conserva Android Keystore, AES-GCM, AtomicFile para compactaciones y cuarentena del diario cuando falla la autenticación de un registro. Una escritura incompleta al final del archivo se descarta sin borrar registros anteriores.

## Validación automatizada

- Worker: `node --check` y `npm test` con Node.js 22.
- D1: el workflow aplica la migración en SQLite y verifica que un código de vinculación sólo pueda consumirse una vez.
- Android: pruebas JVM, lint, compilación debug y pruebas instrumentadas en emuladores API 24 y API 31.
- La prueba instrumentada de PetMemory verifica la migración a BME2, que el contenido siga cifrado y que una entrada normal se agregue sin reescribir el diario completo.

La validación real de ubicación depende de permisos y proveedores disponibles en el dispositivo físico. La disponibilidad de D1, OpenAI y TURN en producción requiere los bindings y secretos reales, por lo que no se despliega desde esta revisión.
