# Referencias técnicas de XiaoZhi

Esta carpeta conserva documentación técnica del protocolo y del ecosistema XiaoZhi. Es material de consulta, no código ejecutable de Bellota.

## Relación con Bellota

La aplicación ya contiene un cliente OTA y sesiones de voz WebSocket y MQTT/UDP. La documentación ayuda a revisar formatos de mensajes, negociación de audio, estados, notificaciones y herramientas MCP. La compatibilidad real debe comprobarse con la versión vigente del servicio y con pruebas en el dispositivo.

La URL del panel web no debe confundirse con el endpoint de comunicación del dispositivo. Bellota utiliza los parámetros de conexión entregados por el flujo OTA.

## Contenido

- `websocket.md`, `mqtt-udp.md`: transporte y negociación de audio.
- `mcp-protocol.md`, `mcp-usage.md`: protocolo y herramientas MCP.
- `glyph-push.md`, `notify.md`: mensajes complementarios.
- `README-xiaozhi-original.md`: referencia original conservada.
- `LICENSE-XIAOZHI`: licencia asociada al material de referencia.

Ver también:
- [Integración XiaoZhi en Bellota](../../integracion-xiaozhi-me.md)
- [Arquitectura local-first](../../arquitectura-j7-offline.md)
