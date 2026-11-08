# Bellota-app

Proyecto de mascota virtual para Samsung Galaxy J7, con arquitectura **local-first e híbrida**.

La aplicación vive en el teléfono y conserva su interfaz, personalidad, memoria y funciones básicas localmente. Cuando hay internet, puede conectarse a XiaoZhi para conversaciones avanzadas. Si la conexión falla, activa respuestas y voz locales para que Bellota no quede muda.

## Principio central

**XiaoZhi mejora la experiencia, pero no debe ser un punto único de falla.** La aplicación Android mantiene el control de la mascota y debe seguir funcionando sin conexión. La PC se utiliza para desarrollar, compilar e instalar, no durante el uso cotidiano.

## Documentación

- [Resumen del proyecto](mascota-virtual-limpio/mascota-virtual/docs/resumen-del-proyecto.md)
- [Arquitectura híbrida J7](mascota-virtual-limpio/mascota-virtual/docs/arquitectura-j7-offline.md)
- [Plan de trabajo](mascota-virtual-limpio/mascota-virtual/docs/plan-de-trabajo.md)

El material de XiaoZhi y Troko/ESP32 se conserva como referencia. La integración real con XiaoZhi y el servidor compatible se definirá y probará antes de implementarla.
