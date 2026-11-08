# Mascota virtual offline para Samsung J7

Proyecto para transformar un Samsung Galaxy J7 en una mascota virtual independiente: tendrá una cara animada, personalidad, estados de ánimo, voz, reconocimiento de comandos y memoria local.

**Regla principal:** después de instalarla, debe funcionar sin internet y sin depender de la PC ni de otro dispositivo. La PC se utiliza solamente para preparar los archivos, compilar si hace falta y transferir la aplicación al teléfono.

## Estado del proyecto

Estamos en la etapa de organización y validación técnica. Todavía no se debe dar por confirmado que el reconocimiento de voz o una voz sintética concreta funcionen en este modelo de J7. Primero se comprobarán las capacidades reales del teléfono y luego se elegirá la implementación más liviana.

## Estructura

- `assets/caras/`: expresiones animadas GIF para la interfaz.
- `assets/sonidos/`: efectos de interfaz OGG. Son opcionales y se podrán desactivar.
- `docs/resumen-del-proyecto.md`: objetivo, dispositivos, decisiones y funciones previstas.
- `docs/arquitectura-j7-offline.md`: arquitectura técnica objetivo y límites.
- `docs/plan-de-trabajo.md`: etapas, pruebas y criterios para avanzar.
- `docs/xiaozhi/`: documentación original conservada como referencia. No implica que vayamos a usar sus servidores o protocolos.
- `reference/troko-esp32/`: material específico de la alternativa ESP32, separado del desarrollo Android.
- `LICENSE-XIAOZHI`: licencia del proyecto XiaoZhi. No debe interpretarse como licencia automática de todos los recursos gráficos o sonidos.

## Principios de desarrollo

1. Priorizar Android nativo y funciones locales compatibles con el teléfono.
2. No incluir conexiones obligatorias a servidores, cuentas, APIs o servicios en la nube.
3. No depender de la PC durante el uso cotidiano.
4. Diseñar para memoria y almacenamiento limitados.
5. Mantener los recursos gráficos y sonoros locales dentro de la aplicación.
6. Implementar primero una mascota visual funcional; después sumar voz, comandos y memoria.
7. No borrar referencias originales hasta comprobar que no se necesitan.

## Primer objetivo técnico

Crear una pantalla de mascota que funcione sin conexión, muestre las expresiones disponibles, reaccione al toque y conserve una configuración básica local. La voz y el reconocimiento offline se incorporarán después de probar compatibilidad y consumo de memoria en el J7.

Ver también:
- [Arquitectura offline](docs/arquitectura-j7-offline.md)
- [Plan de trabajo](docs/plan-de-trabajo.md)
- [Resumen del proyecto](docs/resumen-del-proyecto.md)
