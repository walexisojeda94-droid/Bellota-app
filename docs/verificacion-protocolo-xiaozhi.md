# Verificación del cliente XiaoZhi contra el protocolo oficial

Contrastado con la documentación de `xiaozhi-esp32` (websocket.md, mqtt-udp.md y flujo OTA/activación). Compilado y probado solo por CI; falta validar en el J7.

## Cumple
- OTA: POST con `Device-Id`, `Client-Id`, `Activation-Version`; código de 6 dígitos; sondeo de `/activate` (200 = listo, 202 = pendiente).
- WebSocket: URL y token salen del OTA; cabeceras `Authorization: Bearer`, `Protocol-Version`, `Device-Id`, `Client-Id`; hello JSON plano (`type`, `version`, `transport`, `audio_params` Opus 16 kHz / 60 ms); timeout de hello de 10 s.
- MQTT+UDP: hello v3 con `transport: udp`; lee `session_id`, `udp.server/port/key/nonce`; AES-128-CTR; cabecera de 16 B (tipo 0x01, flags, longitud, ssrc, timestamp, secuencia) usando el nonce como base, igual que el firmware oficial; descarta secuencias repetidas.

## Corregido en esta revisión
1. WebSocket: ahora soporta protocolo binario v2 y v3 (antes rechazaba cualquier versión distinta de 1).
2. WebSocket: ping cada 30 s para detectar conexiones caídas.
3. OTA: si al refrescar el servidor vuelve a pedir `activation` (desvinculado/token revocado), se reinicia la activación en vez de quedar en bucle de reintentos.
4. MQTT: los mensajes de control se procesan fuera del hilo de callback de Paho (evita bloqueo al publicar QoS 1 desde `messageArrived`).
5. MQTT: `features.mcp` pasa a `false`, igual que en WebSocket, porque la sesión no responde mensajes MCP.

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

- `McpConnection` todavía no reconecta automáticamente después de una caída de red.
- La validación del endpoint MCP debe endurecerse para comprobar el host y la ruta exactos, no solo el prefijo del esquema.
- `WeatherService` consulta únicamente la última ubicación conocida. Puede no existir o estar desactualizada; usa el último clima guardado como respaldo.
- La CI compila y genera el APK, pero no ejecuta pruebas unitarias o instrumentadas de estos comportamientos.
- Falta validar en el Samsung J7: micrófono al pasar a segundo plano, sueño, animaciones, herramientas MCP, audio bidireccional, caída de red y consumo de batería.
- `OfflineReplies` normalmente devuelve una respuesta de texto; por eso la rama de respuesta desconocida de `LocalConversationEngine` puede no alcanzarse en el flujo habitual.
