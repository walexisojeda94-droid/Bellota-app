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
