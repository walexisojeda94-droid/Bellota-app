# Resumen del proyecto Bellota

## Objetivo

Convertir un Samsung Galaxy J7 SM-J710SS en una mascota virtual autónoma. Bellota debe conservar su identidad, interfaz, comportamiento y funciones esenciales en el propio teléfono. XiaoZhi aporta conversación avanzada online, pero no debe ser un punto único de falla.

## Dispositivo objetivo

- Samsung Galaxy J7, variante SM-J710SS.
- Android 8.1.
- Recursos limitados: priorizar una implementación liviana y medir el consumo real.
- La PC se utiliza para desarrollar, compilar e instalar, no para sostener el funcionamiento cotidiano.

## Funciones presentes en el código

### Interfaz y vida virtual
- Interfaz inmersiva horizontal.
- Ojos vectoriales dibujados en tiempo real, con parpadeo, mirada y estados de ánimo.
- Respuesta a toques y caricias.
- Rutinas autónomas para explorar, pensar, jugar, descansar e iniciar interacciones.
- Edad virtual y necesidades básicas persistentes.

### Conversación y voz
- Motor de conversación local con respuestas de respaldo.
- Memoria conversacional sencilla: nombre, gustos, actividad reciente y pausas entre conversaciones.
- Android TextToSpeech como voz local.
- Reconocimiento de comandos mediante SpeechRecognizer del sistema.
- Integración OTA de XiaoZhi y clientes de voz WebSocket y MQTT/UDP con Opus.

### Funciones complementarias
- Consulta meteorológica mediante Open-Meteo y última ubicación conocida.
- Conexión MCP opcional con configuración privada almacenada en el teléfono.

## Arquitectura de conexión

El núcleo Android mantiene la interfaz, la conducta y el modo local. La integración XiaoZhi se ejecuta en componentes separados. La actividad selecciona el transporte entregado por OTA y, ante un error de sesión, dispone del reconocimiento y TTS locales como respaldo.

La reconexión automática robusta después de una pérdida de servicio todavía debe verificarse y completarse. No se debe afirmar que la experiencia online es estable hasta probar activación, autenticación, audio bidireccional y recuperación en el dispositivo.

## Estado real y criterios de aceptación

La implementación está avanzada a nivel de código, pero quedan pruebas físicas y de integración. La lista de validación se mantiene en [Plan de trabajo](plan-de-trabajo.md).

No se ejecutan modelos de lenguaje grandes localmente. El modo local utiliza reglas, contexto y memoria acotada para mantener una respuesta básica sin depender de internet.
