# Mascota virtual para el Samsung J7

Mascota de escritorio con cara animada, ánimo propio y voz, pensada para un Samsung J7 que ya no se usa y para correr lo más local posible. Inspirada en XiaoZhi, pero hecha a medida.

Contexto completo, equipo, decisiones e ideas de funciones: `docs/resumen-del-proyecto.md`.

## Qué hay en la carpeta

- `assets/caras/`: 21 caras animadas en GIF (240x240, ojos sobre fondo negro) con su `index.json`. Hoja de vista previa en `_vista-previa.png`.
- `assets/sonidos/`: 5 sonidos cortos de interfaz de XiaoZhi (popup, success, exclamation, vibration, low_battery), en formato .ogg.
- `docs/xiaozhi/`: documentación del protocolo de XiaoZhi, en inglés.
  - `websocket.md`: cómo habla un dispositivo con el servidor (mensaje hello, audio Opus a 16 kHz, mensajes de texto en JSON). Es la base para un cliente propio.
  - `mcp-protocol.md` y `mcp-usage.md`: cómo se controlan funciones del dispositivo desde el servidor.
  - `mqtt-udp.md`: el otro transporte posible.
  - `notify.md` y `glyph-push.md`: notificaciones y texto en pantalla.
  - `README-xiaozhi-original.md`: README del proyecto original, con la lista de servidores y clientes alternativos.
- `reference/troko-esp32/`: LEEME del firmware Troko (hardware y pines de la ruta ESP32) y su LICENSE.
- `LICENSE-XIAOZHI`: licencia MIT del proyecto original. Hay que conservarla si se reutiliza código o material de XiaoZhi.

## Dato útil para el diseño

El servidor XiaoZhi le manda al dispositivo mensajes `{"type": "llm", "emotion": "happy", ...}` para cambiar la cara (también `sad` y `neutral` aparecen en la documentación). Los nombres de los GIF de `assets/caras/` coinciden con ese estilo de emociones, así que se puede mapear emoción a GIF directamente.

## Lo que NO se trajo y por qué

- Código del firmware (C++ para ESP32): no corre en Android.
- `srmodels.bin` (palabra de activación "Troko"): solo para ESP32-S3.
- Definiciones de placas, drivers de pantalla y audio: son específicos de hardware ESP32.
- Voces y textos de configuración en español (WiFi, activación): atados al flujo de instalación del ESP32.

## Pendientes

- Confirmar quién dibujó las caras. El repo es MIT, pero no se sabe el autor de los GIF; decidir si se usan o se hacen caras propias.
- Verificar si el cliente Android de XiaoZhi (`TOM88812/xiaozhi-android-client`) funciona en el J7.
- Definir la personalidad de la mascota.

## Próximo paso

Página web de prueba: una cara según el estado de ánimo que cambia al tocarla, probada en el navegador del J7. Después, la voz.
