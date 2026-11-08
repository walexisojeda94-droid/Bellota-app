# Bellota: mascota virtual híbrida para Samsung J7

Bellota será una mascota virtual Android con funcionamiento **local-first**: conectada a XiaoZhi cuando haya internet y con un modo local de respaldo cuando la conexión o el servicio no estén disponibles.

## Cómo debe comportarse

- **Con conexión:** utilizar XiaoZhi para conversaciones más completas y capacidades avanzadas.
- **Sin conexión:** seguir mostrando expresiones, conservar su personalidad y memoria local, ejecutar comandos básicos y responder con voz local.
- **Al recuperar internet:** intentar reconectarse automáticamente, sin reiniciar la aplicación.
- **En todo momento:** mantener la identidad, la interfaz y el estado de Bellota dentro de la aplicación Android.

La PC se usa para preparar, compilar e instalar. No debe ser necesaria para el funcionamiento diario.

## Dispositivo inicial

- Samsung Galaxy J7, variante informada SM-J710SS.
- Android 8.1.
- 2 GB de RAM y 16 GB de almacenamiento interno.
- microSD de 8 GB.

La compatibilidad de motores de voz y bibliotecas debe probarse en el teléfono real antes de elegirlos.

## Recursos del repositorio

- `assets/caras/`: expresiones GIF locales.
- `assets/sonidos/`: efectos OGG opcionales.
- `docs/resumen-del-proyecto.md`: alcance y decisiones.
- `docs/arquitectura-j7-offline.md`: arquitectura local-first e integración online.
- `docs/plan-de-trabajo.md`: etapas y pruebas.
- `docs/xiaozhi/`: documentación de referencia.
- `reference/troko-esp32/`: material de hardware ESP32, no instalable en Android.

La procedencia y licencia específica de los recursos gráficos y sonoros todavía debe verificarse.

## Regla de diseño

La caída de internet, del servidor o de la sesión de XiaoZhi nunca debe cerrar la aplicación ni dejar a Bellota sin una respuesta local. La voz offline y el reconocimiento local se validarán en el J7 antes de considerarlos funciones terminadas.
