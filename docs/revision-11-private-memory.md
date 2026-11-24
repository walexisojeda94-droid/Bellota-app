# Grupo 5: almacenamiento local y privacidad

Alcance: BEL-064 a BEL-068.

## BEL-064: escritura asíncrona

- `PetMemory.remember()` ya no abre ni sincroniza archivos desde el callback que lo invoca.
- Los eventos se procesan en un único escritor de fondo, con orden estable y una cola acotada de 512 tareas.
- La cola nunca ejecuta una escritura rechazada en el hilo llamador. Si se llena, se contabiliza el rechazo en vez de bloquear la interfaz.
- Se exponen contadores técnicos de pendientes, fallos y rechazos; no contienen texto personal.
- Las preferencias semánticas siguen cifrándose antes de guardarse y usan `SharedPreferences.apply()` para que la escritura física no bloquee el hilo principal.

## BEL-065: claves, pérdida y rotación

- El diario nuevo usa formato BME3: registros AES-GCM autenticados y versión de clave incluida en la cabecera.
- BME1 y BME2 se leen con la clave histórica v1 y se migran a BME3 con clave v2 en la siguiente escritura.
- `rotateKey()` crea una nueva versión, vuelve a cifrar el diario mediante `AtomicFile` y elimina la clave anterior únicamente después de confirmar la escritura.
- Si falta o queda invalidada una clave, el diario cifrado se aparta como `recuerdos.jsonl.locked.*`; nunca se borra automáticamente la clave ni se sobrescribe el archivo bloqueado.
- Las preferencias cifradas tampoco se limpian ni se vuelven a cifrar con una clave nueva si la clave original desapareció. Las operaciones fallan de forma conservadora.
- **Límite criptográfico:** Android Keystore no permite reconstruir una clave AES que se perdió definitivamente. En ese caso se conserva el ciphertext para una posible restauración de la clave original, pero no se puede recuperar su contenido sin esa clave. La app inicia un diario nuevo sólo después de preservar el anterior. Una recuperación portable requeriría un secreto de recuperación externo que el usuario guarde por separado; esta versión no inventa ni almacena ese secreto.

## BEL-066: datos transmitidos a proveedores online

- Compartir memoria online está desactivado por defecto.
- La opción se controla desde el diálogo de voz online y queda guardada en preferencias cifradas.
- Con consentimiento, el contexto de sesión sólo puede incluir nombre, apodos, gustos no sensibles, una pregunta de preferencia pendiente y una señal general de regreso tras una pausa.
- El resumen se limita a 600 caracteres. Se eliminan controles y se excluyen campos con indicadores de salud, medicación, diagnósticos, credenciales, documentos, domicilio, teléfono, tarjetas, religión, política, vida sexual, correos, URL y secuencias telefónicas.
- El diario cifrado y las transcripciones persistidas no se adjuntan como memoria al crear sesiones. OpenAI Realtime y Gemini Live reciben el mismo contexto filtrado a través del contrato de sesión común.
- Desactivar el consentimiento impide adjuntar el resumen en la próxima sesión; no elimina recuerdos locales ni modifica el envío normal de audio/texto necesario para mantener una conversación online.

## BEL-067: conservación y eliminación

- Diario de eventos: máximo 180 días y 512 KiB. Se compacta al menos cada 24 horas y elimina primero los registros vencidos o más antiguos al alcanzar el límite.
- Memoria semántica explícita (nombre, gustos y apodos): persiste hasta que el usuario la borre, borre los datos de la app o desinstale la aplicación.
- La pantalla de vinculación/voz online incluye **Borrar recuerdos locales**. La acción elimina memoria semántica, diario activo, archivos cifrados bloqueados y claves locales asociadas. No revoca la vinculación con el backend.
- `allowBackup=false` sigue evitando que Android incluya estos datos privados en las copias automáticas de la app.

## BEL-068: integridad y recuperación

- Cada registro del diario se autentica de forma independiente con AES-GCM y un IV aleatorio.
- Las escrituras completas se sincronizan con el descriptor del archivo; las compactaciones usan `AtomicFile`.
- Un registro final incompleto por interrupción se descarta sin invalidar los registros completos anteriores.
- Una falla de autenticación, una cabecera inválida o una clave ausente preserva el diario original como archivo bloqueado antes de crear un diario nuevo.
- La rotación sólo elimina la clave anterior después de completar la nueva escritura atómica.

## Pruebas añadidas

- Consentimiento online desactivado por defecto y revocable.
- Exclusión de elementos sensibles del resumen compartido.
- Borrado de recuerdos semánticos y del consentimiento.
- Escritura asíncrona del diario, formato BME3, append cifrado y rotación de versión de clave.
- Pérdida de clave Keystore: preservación del diario cifrado original sin sobrescritura.
- Preferencias cifradas: ausencia de borrado o reemplazo automático ante pérdida de clave.

## Validación pendiente

Las pruebas instrumentadas requieren emulador/dispositivo Android con Keystore real. La CI puede validar compilación, pruebas JVM, Lint y emuladores, pero no demuestra que una clave perdida sea recuperable: esa posibilidad depende de que el Keystore original siga disponible. Tampoco sustituye una prueba de consentimiento y contexto con sesiones reales de OpenAI y Gemini.
