# Integración con XiaoZhi oficial (xiaozhi.me)

## Servicio predeterminado

Bellota utilizará el servicio oficial asociado al panel https://xiaozhi.me. El panel y la cuenta no deben confundirse con el endpoint de comunicación del dispositivo.

## Protocolo oficial revisado

La documentación vigente de desarrollo de XiaoZhi describe un protocolo WebSocket de dispositivo:

- Handshake con cabeceras `Authorization`, `Protocol-Version`, `Device-Id` y `Client-Id`.
- Mensaje inicial JSON `hello`, con versión, transporte y parámetros de audio.
- Respuesta `hello` del servidor con transporte y, potencialmente, `session_id`.
- Mensajes JSON de control como `listen`, `stt`, `llm`, `tts`, `mcp` y `alert`.
- Audio bidireccional en tramas binarias Opus. El formato, la frecuencia de muestreo y la variante de trama deben negociarse y respetarse.

La documentación también describe una alternativa MQTT para control más UDP cifrado para audio. No vamos a implementar ambos transportes en paralelo: primero se evaluará el WebSocket por ser el camino más directo para el cliente Android.

Referencias técnicas oficiales:
- Guía de desarrollo: https://xiaozhi.dev/en/docs/development/
- Protocolo WebSocket: https://xiaozhi.dev/en/docs/development/websocket/
- Protocolo MQTT + UDP: https://xiaozhi.dev/en/docs/development/mqtt-udp/
- Firmware oficial abierto: https://github.com/78/xiaozhi-esp32

## Lo que está validado y lo que falta

**Validado documentalmente:** formato general del protocolo WebSocket, cabeceras, handshake, tipos de mensajes y transporte de audio Opus.

**Todavía no validado para Bellota Android:** que el servicio alojado en xiaozhi.me admita el alta de un cliente Android genérico, el flujo de activación que le corresponde, el endpoint de sesión que debe recibir, la forma autorizada de obtener el token y la identidad de dispositivo aceptada.

No se debe asumir que el panel web es un endpoint WebSocket. Tampoco se deben inventar endpoints, tokens, MAC, Client-ID ni reutilizar identidades de hardware ajeno.

## Arquitectura de voz

- La voz local Android TextToSpeech permanece disponible como respaldo.
- La voz remota, una vez validada, se implementará detrás de un adaptador independiente.
- La interfaz y el núcleo local no deben bloquearse por conexión, audio o autenticación remotos.
- Si falla XiaoZhi, Bellota vuelve a las respuestas y voz locales.

## Próximos pasos

1. Verificar en el J7 que el motor TTS tenga datos españoles instalados y funcione sin internet.
2. Consultar y validar la configuración `websocket`/`mqtt` recibida del OTA oficial sin mostrar ni registrar tokens.
3. Confirmar con documentación y flujo oficial si se puede registrar un cliente Android propio.
4. Obtener endpoint y credenciales mediante el procedimiento legítimo de activación.
5. Implementar WebSocket, handshake, negociación de audio y codificación/decodificación Opus en un módulo aislado.
5. Probar conversación real, desconexión, fallback y reconexión en Android 8.1.

## Estado

La solicitud OTA ahora conserva los parámetros de transporte que devuelve XiaoZhi y usa el locale español de Troko. Esto todavía no establece una sesión de voz: el WebSocket/MQTT, el audio Opus y la conversación real siguen pendientes de implementación y prueba en el J7.
