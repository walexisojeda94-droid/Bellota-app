# Referencia XiaoZhi

Esta carpeta conserva documentación del protocolo y del ecosistema XiaoZhi para consulta técnica. No es código ejecutable de la aplicación Android.

## Decisión del proyecto

Bellota utilizará XiaoZhi oficial mediante `https://xiaozhi.me` como servicio online predeterminado, una vez verificados e implementados el registro, la autenticación y el protocolo compatibles con un cliente Android propio.

La URL del panel no debe asumirse como endpoint de comunicación. Los detalles de conexión deben confirmarse con documentación vigente y pruebas reales.

## Uso de esta documentación

Puede consultarse para estudiar:
- Formatos de mensajes y transporte.
- Audio bidireccional.
- Estados, emociones y notificaciones.
- Integración de funciones y herramientas.

El núcleo local de Bellota seguirá funcionando sin XiaoZhi. Estos documentos son referencias y no sustituyen la implementación del adaptador Android.

Ver también:
- [Integración con xiaozhi.me](../integracion-xiaozhi-me.md)
- [Arquitectura híbrida](../arquitectura-j7-offline.md)
