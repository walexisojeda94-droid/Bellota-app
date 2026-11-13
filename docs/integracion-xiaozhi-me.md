# Integración con XiaoZhi

## Servicio

Bellota solicita la configuración del dispositivo al servicio OTA de XiaoZhi. El código actual utiliza el endpoint OTA configurado en `XiaoZhiConnection`; la URL del panel web no se utiliza como endpoint directo de audio.

## Componentes implementados

- `XiaoZhiConnection`: identidad persistente del dispositivo, solicitud OTA, activación y almacenamiento de la configuración recibida.
- `XiaoZhiVoiceSession`: sesión WebSocket y audio Opus.
- `XiaoZhiMqttUdpSession`: control MQTT y transporte UDP cifrado para audio Opus.
- `McpConnection` y `McpCredentialStore`: conexión MCP opcional y almacenamiento de su endpoint privado.

La actividad elige WebSocket o MQTT/UDP según la configuración que devuelve OTA. No se debe inventar un endpoint, token, tópico, clave ni identidad si el servidor no los entrega.

## Estado de validación

La integración está implementada en el repositorio, pero eso no demuestra que el proceso completo haya sido validado en el Samsung Galaxy J7. Antes de darla por terminada hay que comprobar:

1. Solicitud OTA y recepción de código de vinculación.
2. Vinculación de Bellota desde la cuenta oficial.
3. Recepción y persistencia de configuración de transporte.
4. Handshake y negociación de audio con parámetros compatibles.
5. Captura de micrófono, codificación Opus, envío y reproducción de audio recibido.
6. Errores de autenticación, pérdida de red y recuperación.
7. Que los tokens y credenciales no aparezcan en logs ni en Git.

## Respaldo local

Si la sesión online falla, la actividad puede volver al reconocimiento de voz y TTS de Android y al motor de conversación local. La reconexión automática con backoff ya está implementada, pero requiere pruebas prolongadas en el dispositivo. El reconocimiento Android solicita preferencia offline, aunque el motor instalado puede no ofrecer reconocimiento sin conexión.

## Referencias técnicas

La documentación de protocolo conservada en [references/xiaozhi](references/xiaozhi/README.md) es material de consulta. Verificar siempre cambios del protocolo contra la documentación oficial antes de modificar el cliente.
