# Bellota: mascota virtual híbrida para Samsung J7

Bellota será una mascota virtual Android **local-first**, conectada por defecto al servicio oficial XiaoZhi en `https://xiaozhi.me`.

## Comportamiento

- **Con internet y servicio disponible:** conversar mediante XiaoZhi.
- **Sin internet o si XiaoZhi no responde:** seguir activa con personalidad, memoria, comandos y voz local de respaldo.
- **Al recuperar el servicio:** reconectar automáticamente sin reiniciar la app.
- **Siempre:** mantener la identidad, la interfaz y el estado de Bellota en el propio teléfono.

La PC se usa para preparar, compilar e instalar. No es necesaria para el funcionamiento diario.

## Dispositivo inicial

- Samsung Galaxy J7, variante informada SM-J710SS.
- Android 8.1.
- 2 GB de RAM, 16 GB internos y microSD de 8 GB.

## Recursos

- `assets/caras/`: expresiones GIF locales.
- `assets/sonidos/`: efectos OGG opcionales.
- `docs/`: arquitectura, integración XiaoZhi y plan.
- `reference/troko-esp32/`: referencia de hardware ESP32, no instalable en Android.

La procedencia y licencia de los recursos gráficos y sonoros debe verificarse.

## Estado real de la integración

`https://xiaozhi.me` queda elegido como servicio predeterminado. Todavía falta implementar el cliente Android compatible con el flujo de activación/autenticación y el protocolo admitido por el servicio. No se deben inventar tokens, identificadores ni endpoints. Ver [Integración con xiaozhi.me](docs/integracion-xiaozhi-me.md).
