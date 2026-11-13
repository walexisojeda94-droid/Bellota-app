# Arquitectura local-first de Bellota

## Principio

Bellota es una aplicación Android autónoma. La interfaz, identidad, conducta, estado y conversación básica pertenecen al teléfono. XiaoZhi es una capacidad online opcional, no un requisito para abrir la aplicación ni para conservar el comportamiento local.

## Módulos Android

- `core/`: edad virtual, energía, diversión, memoria de eventos y comportamiento autónomo.
- `local/`: normalización de texto, contexto por turno, respuestas y memoria conversacional.
- `ui/`: ojos vectoriales, estados de ánimo, animación y reacciones.
- `voice/`: TTS local y reconocimiento del sistema Android.
- `xiaozhi/`: activación OTA, credenciales de sesión, transporte WebSocket o MQTT/UDP, Opus y MCP.
- `weather/`: consulta externa de clima.

## Flujo de interacción

1. La interfaz y la rutina autónoma se ejecutan en Android.
2. El reconocimiento local puede recibir comandos y frases.
3. Si hay una sesión XiaoZhi configurada, la voz puede utilizar el transporte recibido por OTA.
4. Si la sesión online falla, la actividad recurre al camino local.
5. La memoria local y el estado de Bellota no dependen del historial remoto.

## XiaoZhi

El código contiene:
- Solicitud de configuración y activación OTA.
- Cliente WebSocket con negociación y audio Opus.
- Cliente MQTT para control y UDP cifrado para audio.
- Selección del transporte según la respuesta OTA.

La implementación debe seguir aislada del núcleo local. No se debe asumir que la URL del panel web es el endpoint de voz: el endpoint y las credenciales deben proceder de la configuración OTA válida.

## Modo local y resiliencia

El TTS Android y las respuestas locales permiten mantener una interacción básica cuando XiaoZhi no está disponible. La recuperación automática y los reintentos controlados ya están implementados: MCP usa backoff progresivo y reinicia el contador solo después de 60 segundos de conexión estable. Falta validar reconexión sostenida y continuidad de contexto en el J7. El reconocimiento Android solicita modo offline, pero la disponibilidad real depende del motor y del paquete de idioma instalado.

## Memoria y privacidad

La aplicación mantiene datos de vida y conversación en almacenamiento privado de Android. La configuración MCP usa un almacén específico de credenciales. No registrar ni publicar tokens, endpoints privados completos, códigos de activación ni datos de conversación.

## Restricciones de rendimiento

El Samsung Galaxy J7 es un dispositivo limitado. Evitar cargas visuales pesadas, modelos locales grandes y procesos de audio redundantes. Medir memoria, batería, temperatura, latencia y fluidez en el hardware real.

## Pruebas de aceptación

- Arranque y uso en modo avión.
- Voz local sin conexión, si el motor español está instalado.
- Activación y vinculación real con XiaoZhi.
- Audio bidireccional por el transporte recibido.
- Pérdida de red durante una conversación y retorno al modo local.
- Recuperación de conectividad sin duplicar respuestas ni perder memoria.
- Medición de batería, RAM, temperatura y estabilidad durante sesiones prolongadas.
