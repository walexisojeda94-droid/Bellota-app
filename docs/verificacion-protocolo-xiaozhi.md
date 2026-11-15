# Verificación del cliente XiaoZhi contra el protocolo oficial

Contrastado con la documentación de `xiaozhi-esp32` (websocket.md, mqtt-udp.md y flujo OTA/activación). La CI compila y empaqueta el APK, pero no ejecuta pruebas funcionales. Falta validar en el J7.

## Cumple
- OTA: POST con `Device-Id`, `Client-Id`, `Activation-Version`; código de 6 dígitos; sondeo de `/activate` (200 = listo, 202 = pendiente).
- WebSocket: URL y token salen del OTA; cabeceras `Authorization: Bearer`, `Protocol-Version`, `Device-Id`, `Client-Id`; hello JSON plano (`type`, `version`, `transport`, `audio_params` Opus 16 kHz / 60 ms); temporizador diagnóstico de hello a 30 s (no cierra el socket).
- MQTT+UDP: hello v3 con `transport: udp`; lee `session_id`, `udp.server/port/key/nonce`; AES-128-CTR; cabecera de 16 B (tipo 0x01, flags, longitud, ssrc, timestamp, secuencia) usando el nonce como base, igual que el firmware oficial; descarta secuencias repetidas.

## Corregido en esta revisión
1. WebSocket: se fija protocolo binario v2 según el cliente de referencia; MQTT/UDP conserva v3.
2. WebSocket: ping cada 30 s para detectar conexiones caídas.
3. OTA: si al refrescar el servidor vuelve a pedir `activation` (desvinculado/token revocado), se reinicia la activación en vez de quedar en bucle de reintentos.
4. MQTT: los mensajes de control se procesan fuera del hilo de callback de Paho (evita bloqueo al publicar QoS 1 desde `messageArrived`).
5. MQTT: `features.mcp` permanece en `false` porque esa sesión MQTT/UDP aún no responde MCP. WebSocket anuncia MCP solo con el manejador activo y responde el intercambio dentro de la sesión de voz.

## Nota sobre el formato `header/payload` (namespace `ai.xiaoai.common`)
Ese formato no figura en la documentación oficial de XiaoZhi: el hello oficial es un JSON plano. Parece corresponder a otro ecosistema. Tampoco hay una URL fija `wss://xiaozhi.me/v1/ws`: la URL y el token los entrega el OTA. Bellota sigue la forma oficial.

## Pendiente / no verificado
- Activation-Version 2 (HMAC con número de serie, como py-xiaozhi): no implementado; Bellota usa v1 (sin serie, cuerpo `{}`), que es el modo del firmware sin eFuse.
- Pruebas reales: activación, audio bidireccional, caída de red y batería en el J7.

## Revisión general del código (segunda pasada)

El inventario anterior de 21 archivos Java quedó desactualizado y la afirmación de que todos tenían sintaxis correcta no era válida: una ejecución posterior de CI detectó un import faltante de `JSONObject` en `MainActivity.java`. Se corrigió y la compilación de release firmada volvió a completarse correctamente. La CI valida compilación y empaquetado, no el comportamiento físico en el teléfono.

Correcciones aplicadas durante esta auditoría:
1. `MainActivity`: agregado el import de `JSONObject` que impedía compilar las herramientas MCP.
2. `MainActivity`: el callback de fin de voz ya no puede reiniciar el reconocimiento local cuando la actividad está pausada.
3. `MainActivity`: el fallback local de XiaoZhi no vuelve a contabilizar una interacción que ya fue registrada al recibir la transcripción.
4. `EyePetView`: al pausar se cancelan los animadores y se limpian bostezo, escena autónoma y rebote para evitar repintados residuales.
5. `PetMemory`: el diario se mantiene dentro del límite de 512 KB, recortando entradas grandes sin partir caracteres Unicode.
6. `MainActivity` y `McpConnection`: se implementó el listado y la ejecución de cinco herramientas MCP de Bellota.

## Pendiente / riesgos conocidos de la auditoría

- `WeatherService` consulta únicamente la última ubicación conocida. Puede no existir o estar desactualizada; usa el último clima guardado como respaldo.
- La CI compila y genera el APK, pero no ejecuta pruebas unitarias o instrumentadas de estos comportamientos.
- El reconocimiento Android solicita `EXTRA_PREFER_OFFLINE`; esto es una preferencia, no una garantía de funcionamiento sin internet. Depende del motor de voz y del paquete de español instalado.
- WeatherService descarta ubicaciones de más de dos horas y caché meteorológica de más de seis horas; prueba permisos aproximados y proveedores disponibles en el J7.
- La pausa detiene TTS, reconocimiento, animaciones y callbacks autónomos; falta verificarlo con la app en segundo plano.
- Falta validar en el Samsung J7: micrófono al pasar a segundo plano, sueño, animaciones, herramientas MCP, audio bidireccional, reconexión tras caída de red y consumo de batería.
- `OfflineReplies` normalmente devuelve una respuesta de texto; por eso la rama de respuesta desconocida de `LocalConversationEngine` puede no alcanzarse en el flujo habitual.

## Mejoras MCP aplicadas después de la revisión

- La dirección se valida contra el host oficial `api.xiaozhi.me`, esquema `wss`, puerto estándar y ruta `/mcp/`.
- `McpConnection` reintenta automáticamente tras una caída, con espera progresiva de 5 a 60 segundos. El contador solo se reinicia tras 60 segundos de conexión estable; los mensajes de sockets anteriores se descartan. Una desconexión solicitada cancela los reintentos.
- `XiaoZhiConnection` cierra su executor al destruirse la actividad.
- El cambio de sueño obligatorio a normal actualiza el mute para volver a aceptar la palabra de activación.
- Los reintentos se registran en Logcat para evitar mostrar notificaciones repetidas; se muestra un aviso al recuperar la conexión.


## Calidad automatizada

- Se agregaron pruebas JVM con JUnit 4.13.2 para respuestas locales, clasificación de estados emocionales, memoria de gustos/apodos, expiración de expectativas y normalización de mensajes.
- GitHub Actions ejecuta `:app:testDebugUnitTest` y `:app:lintDebug` antes de compilar el APK.
- La primera ejecución de Lint encontró tres errores de permiso de micrófono en los transportes de audio. Se añadieron comprobaciones explícitas de `RECORD_AUDIO` antes de crear los grabadores de WebSocket y MQTT/UDP. La ejecución posterior confirmó que no quedan errores de Lint.
- La configuración de CI fija Java 17, Android SDK 35, Gradle 8.7 y Android Gradle Plugin 8.6.1.
- No se incorporó Gradle Wrapper en esta pasada; sigue pendiente generar y versionar sus scripts, propiedades y JAR oficiales.
- Las pruebas JVM y Lint no sustituyen las pruebas funcionales en el Samsung Galaxy J7.


## Resultado de la pasada de calidad (13/11/2026)

- Las 15 pruebas JVM de JUnit pasan en GitHub Actions.
- `:app:lintDebug` termina correctamente con cero errores y tres advertencias revisadas:
  - `ApplySharedPref`: se conserva `commit()` para confirmar la escritura de la configuración MCP cifrada antes de continuar.
  - `DiscouragedApi`: se conserva orientación horizontal porque la interfaz de ojos está diseñada para ese formato.
  - `HardwareIds`: se conserva `ANDROID_ID` como identidad estable del dispositivo requerida por la vinculación con XiaoZhi.
- Se eliminaron las seis comprobaciones `SDK_INT` redundantes para `minSdk 27`, se añadió icono de aplicación y se corrigió la accesibilidad táctil de `EyePetView`.
- Se mantiene `targetSdk 28` y se desactiva únicamente la regla `ExpiredTargetSdkVersion`, ya que el APK se distribuye de forma privada y el comportamiento en el dispositivo Android 8.1 no debe cambiar sin pruebas específicas. No es una configuración apta para publicación en Google Play.
- El flujo de CI completó pruebas, Lint, compilación release, verificación del certificado permanente y subida del APK en el commit `452969af`. Los cambios posteriores de estabilidad descritos a continuación también completaron CI, incluyendo compilación release y verificación de firma.
- El Gradle Wrapper sigue pendiente: no se incorporó un JAR incompleto ni scripts que no puedan ejecutarse.


## Auditoría de estabilidad y privacidad (14/11/2026)

Correcciones aplicadas en `main`:

1. **Reconocimiento Android:** el inicio local es idempotente; la Activity reutiliza la instancia y no destruye/recrea el reconocedor en cada `onResume`.
2. **Callbacks de voz:** cada sesión del reconocedor tiene una generación propia. Resultados y errores tardíos de sesiones anteriores se descartan. La falta de permiso detiene el reconocedor sin reintentos infinitos.
3. **Captura MQTT/UDP:** la captura usa una generación por grabación, valida que el `AudioRecord` siga siendo el actual después de cada lectura y corta ante errores negativos en vez de girar en un bucle caliente. El cierre terminal apaga los ejecutores de I/O, captura y watchdog.
4. **TextToSpeech:** si Android no inicializa TTS o no dispone de voz española, se libera la frase pendiente y se notifica el fin para permitir reanudar la escucha. Las frases pendientes tienen un timeout y se ignoran callbacks de utterances reemplazadas.
5. **WebSocket:** se impide abrir dos conexiones durante el handshake; los callbacks solo se procesan si pertenecen al socket actual. El cierre/fallo es de una sola ejecución y se evita continuar capturando ante errores de lectura.
6. **OTA:** una respuesta que no incluye configuración WebSocket/MQTT conserva la última configuración válida. Solo una respuesta que incluya explícitamente claves de transporte reemplaza la configuración guardada.
7. **Privacidad:** las transcripciones completas dejaron de escribirse en Logcat. El token de XiaoZhi se cifra con AES-GCM y una clave no exportable del Android Keystore; los tokens heredados se migran al almacenamiento cifrado y se retiran de las preferencias antiguas.
8. **Persistencia MCP:** el guardado cifrado ahora comprueba el resultado de `commit()` y falla explícitamente si Android no confirma la escritura.

## Validación de esta revisión

- Los cambios de reconocimiento, MQTT/UDP, TTS, WebSocket, OTA, privacidad y almacenamiento seguro pasaron las tareas JVM y Android Lint.
- La compilación Release, la verificación del certificado permanente y la publicación del artefacto también finalizaron correctamente en GitHub Actions.
- Un commit intermedio de la migración del token se ejecutó antes de que se añadiera la nueva clase de almacenamiento y falló por esa dependencia ausente. El commit siguiente incorporó la clase; la compilación completa del estado resultante de `main` terminó correctamente.
- La CI no puede validar el micrófono, el altavoz, la descarga de idioma ni la reconexión física en los teléfonos. Sigue pendiente instalar y probar en ambos dispositivos, especialmente el J7.


## Auditoría de conformidad WebSocket (15/11/2026)

Se contrastó la implementación con el cliente de referencia del proyecto oficial `tenclass/xiaozhi-protocols`, en particular `tests/test_websocket.py`, `tests/instances.py` y `basemodel/audio.py`.

Cambios aplicados en Bellota:

1. **Versión WebSocket fija en 2:** el cliente oficial establece `PROTOCOL_VERSION = 2` para WebSocket y usa ese mismo valor en `Protocol-Version` y en `hello.version`. Bellota ya no toma la versión binaria de un campo opcional `websocket.version` del OTA ni cae a v1 por ausencia.
2. **Hello:** envía `type=hello`, `version=2`, `features.aec=true`, `features.mcp=true` cuando el manejador MCP está conectado, `transport=websocket` y Opus mono a 16.000 Hz / 60 ms.
3. **Identidad y autenticación:** mantiene los mismos `Device-Id` y `Client-Id` usados por OTA; envía `Authorization: Bearer`, `Protocol-Version: 2`, `Device-Id` y `Client-Id` en el handshake. El `Client-Id` es un UUID persistente.
4. **Respuesta hello:** exige `transport=websocket`, versión compatible si el servidor la incluye y un `session_id` no vacío antes de empezar a transmitir audio.
5. **Listen:** usa `state=start` y `mode=realtime`, como el flujo de prueba WebSocket oficial.
6. **Audio binario v2:** usa el encabezado de 16 bytes `!HHIII` en orden de red: versión 2, tipo 0 (Opus), reservado 0, timestamp 0 y tamaño exacto del payload. La recepción valida versión, reservado y longitud; también reconoce tipo 1 (JSON) y lo deriva al procesador de mensajes.
7. **MCP dentro del WebSocket de voz:** Bellota anuncia MCP solo cuando el manejador está conectado. Responde a `initialize`, `tools/list`, `tools/call` y `ping`; procesa notificaciones sin responder y encapsula las respuestas en `{session_id, type: "mcp", payload}`. Reutiliza las cinco herramientas existentes de Bellota.
8. **Diagnóstico:** el vencimiento de 30 segundos sigue siendo una advertencia, no cancela el WebSocket. Los cierres ahora registran el código y el motivo recibido.

### Límite de identidad en Android

La especificación de firmware describe `Device-Id` como MAC física. Android no permite a una aplicación común leer de forma fiable la MAC Wi-Fi física en versiones modernas. Bellota conserva el identificador estable ya vinculado: una dirección MAC localmente administrada derivada de `ANDROID_ID`. Tiene formato MAC y permanece idéntica entre OTA y WebSocket, pero no debe describirse como la MAC física del teléfono. No se cambia para no romper la identidad ya registrada.

### Validación pendiente

Los cambios están guardados en `main`. La conformidad de código se contrastó con el cliente de referencia, pero la compilación y las pruebas automáticas del último commit deben terminar correctamente antes de considerar validado el artefacto. La CI no demuestra por sí sola micrófono, altavoz ni conversación real con el servidor.
