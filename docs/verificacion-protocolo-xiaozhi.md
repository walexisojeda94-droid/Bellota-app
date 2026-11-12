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
Revisados los 21 archivos Java, los 3 XML, el workflow y los docs. Sintaxis correcta en todos. Correcciones:
1. `ContinuousVoiceCommands.stop()` no liberaba el flag `restarting`: si se detenía durante la espera entre reconocimientos, `start()` quedaba inerte y el reconocimiento local no volvía a arrancar.
2. `MainActivity`: el reintento de XiaoZhi seguía corriendo con la app en segundo plano (podía abrir el micrófono). Ahora se cancela en `onPause` y se retoma en `onResume`; se evita lanzar dos activaciones OTA en paralelo; la rutina autónoma no habla en segundo plano.
3. `MainActivity`: tras 3 reintentos fallidos de XiaoZhi se activa el reconocimiento local (antes Bellota quedaba sorda mientras XiaoZhi estaba caído, contra lo que dice la documentación).
4. `app/build.gradle`: el bloque `dependencies` estaba dentro de `android {}`; movido a nivel raíz.
5. `EyePetView`: se quitó la capa de renderizado por software y se dejó de crear `Paint` nuevos en cada cuadro (carga innecesaria en el J7).
6. `PetMemory`: el diario `recuerdos.jsonl` crecía sin límite; ahora se recorta al superar 512 KB.
7. `McpConnection`: versión informada 0.2.0 → 0.4.4, igual que `build.gradle`.

Observaciones sin cambiar: la conexión MCP no se reconecta sola si se cae y no expone herramientas (lista vacía); `OfflineReplies` nunca devuelve null salvo clima, por lo que la rama "unknown" de `LocalConversationEngine` no se alcanza.
