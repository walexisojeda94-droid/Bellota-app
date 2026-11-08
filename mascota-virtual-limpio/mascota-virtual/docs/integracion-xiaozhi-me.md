# Integración con XiaoZhi oficial (xiaozhi.me)

## Servicio predeterminado

Bellota utilizará el servicio oficial de XiaoZhi asociado al panel:

- Panel y cuenta: https://xiaozhi.me
- Estado: elegido como predeterminado; cliente Android todavía no implementado.
- La aplicación debe permitir que el usuario configure el servicio más adelante, pero esta primera configuración apunta al servicio oficial.

## Importante: panel web no equivale al endpoint de comunicación

La dirección del panel no debe tratarse automáticamente como endpoint WebSocket. El cliente debe implementar el protocolo de dispositivo compatible con el servicio y usar los endpoints, encabezados, autenticación y formato de audio definidos por documentación vigente.

No copiar endpoints de clientes de terceros sin verificar su compatibilidad con el servicio oficial actual.

## Activación y vinculación

El flujo documentado para dispositivos XiaoZhi compatibles incluye conectividad, solicitud de activación, código de verificación y vinculación de ese dispositivo a una cuenta desde la consola. El procedimiento exacto puede variar según firmware, servidor y tipo de dispositivo.

Para Bellota en Android se debe verificar si xiaozhi.me admite el alta de un cliente Android genérico o si exige identidad/firmware de dispositivo específico. No se debe fingir un código de activación ni reutilizar la identidad de otro dispositivo.

## Trabajo de implementación

1. Revisar documentación vigente del protocolo XiaoZhi y del servicio oficial.
2. Confirmar si el servicio permite un cliente Android propio y cuál es el procedimiento legítimo de registro.
3. Definir identidad única de instalación y almacenamiento seguro de credenciales.
4. Implementar cliente de red en un módulo aislado.
5. Probar conexión, audio bidireccional, cierre de sesión y reconexión en Android 8.1.
6. Integrar el cliente con el gestor de estados sin permitir que una falla online bloquee el núcleo local.

## Referencias

- Panel oficial: https://xiaozhi.me
- Guía de activación y vinculación del ecosistema XiaoZhi: https://xiaozhi.dev/en/docs/usage/activation/
- Repositorio abierto XiaoZhi y documentación de desarrollo: https://github.com/78/xiaozhi-esp32

## Estado

La selección de xiaozhi.me como destino predeterminado está decidida. La conexión real no está implementada ni probada. Antes de declararla funcional hay que confirmar compatibilidad del cliente Android, completar el alta autorizada y probar una conversación real.
