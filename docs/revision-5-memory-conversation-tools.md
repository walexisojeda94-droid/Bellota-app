# Revisión 5: memoria, conversación y herramientas

Alcance: BEL-016, BEL-018, BEL-019, BEL-028, BEL-029 y BEL-033.

## Memoria local y Realtime

- La conversación local y las transcripciones finales de OpenAI Realtime actualizan la misma instancia de `LocalConversationEngine` y la misma `ConversationMemory` persistida.
- Los mensajes locales y online del usuario, y las respuestas habladas de Bellota, se guardan en el diario cifrado `PetMemory`.
- Al iniciar una sesión Realtime, se incorpora un resumen acotado de datos conocidos: nombre, gustos, apodos, una pregunta pendiente y la continuidad después de una pausa larga.
- Los mensajes online se procesan con `rememberOnlineMessage`: actualizan la memoria sin generar una segunda respuesta local.
- Las preferencias se comparan sin distinguir acentos. Una negación clara puede retirar una preferencia; frases tentativas como «no sé si me gusta» no se guardan como gustos.
- Las preguntas pendientes sólo se resuelven con respuestas afirmativas o negativas suficientemente claras. Una respuesta ambigua no borra la pregunta pendiente.

## Transcripción de entrada

Realtime no emite transcripciones de entrada si no se activa esa opción. La sesión ahora configura `audio.input.transcription.model = gpt-transcribe` sin fijar el idioma, de modo que el reconocedor auxiliar pueda detectarlo automáticamente.

La transcripción auxiliar llega de forma asíncrona y puede diferir de lo que el modelo de audio entendió. Se utiliza para continuidad y memoria, no para reemplazar la interpretación nativa del modelo. La transcripción añade uso facturable aparte del procesamiento de audio Realtime. No depende del idioma configurado en Android.

Los eventos finales se deduplican por `item_id` para no registrar dos veces un mismo turno.

## Herramientas y sesiones

- `VoiceProviderManager` asocia cada `call_id` con el proveedor y la generación de sesión que lo originaron.
- Los identificadores finalizados, descartados o pertenecientes a sesiones anteriores se retiran y no se reutilizan para aceptar resultados tardíos.
- Los resultados sólo se envían al proveedor que originó la llamada. Un resultado de una sesión anterior no puede pasar a una sesión nueva ni al proveedor de fallback.
- La ejecución de herramientas en Android se serializa en un ejecutor dedicado.
- Antes de ejecutar una acción y nuevamente al despacharla al hilo principal, se verifica que la llamada siga perteneciendo a la sesión activa.
- Los resultados de varias llamadas de función de una misma respuesta se agrupan. Se envían todos los `function_call_output` y luego un solo `response.create`.
- Los errores de ejecución se devuelven al modelo como salida explícita de error.
- Las respuestas visuales y las transcripciones de una sesión antigua se descartan si ya no corresponde actualizar la interfaz.

## Interpretación de mensajes

- Las órdenes de silencio, sonido, batería, hora, fecha, volumen, sueño y despertar requieren frases de intención explícitas. Se evita activar acciones por menciones incidentales de esas palabras.
- El enrutamiento local de clima, hora, fecha, nombre, saludos y despedidas usa formas de pregunta o frase al comienzo del mensaje, no coincidencias arbitrarias dentro de cualquier oración.
- Se distinguen afirmaciones, negaciones y expresiones de incertidumbre al aprender o retirar gustos.

## Pruebas automatizadas añadidas

- Memoria compartida entre turnos locales y online.
- Contexto online con pregunta pendiente y regreso tras pausa prolongada.
- Preferencias negativas, afirmaciones, incertidumbre, frases contrastivas y diferencias de acentuación.
- Interpretación explícita de comandos de voz y rechazo de falsos positivos.
- Enrutamiento de resultados de herramientas, desconexión, cambio de sesión, fallback y reutilización de identificadores.
- Enrutamiento local de clima, hora, nombre, saludos y despedidas sin falsos positivos.

## Límites y validación pendiente

- La extracción semántica persistente es deliberadamente acotada y basada en patrones claros; no equivale a conservar un resumen completo de cada conversación.
- El contexto persistente se inyecta al crear la sesión. Durante una sesión activa, el propio historial Realtime conserva los turnos recientes; los datos nuevos quedan persistidos para sesiones posteriores.
- La transcripción auxiliar puede contener errores y no debe tratarse como una transcripción literal garantizada.
- La activación de `gpt-transcribe` añade consumo. Configurar alertas y límites de gasto de OpenAI sigue siendo necesario.
- La CI verifica código, pruebas JVM, Lint y emuladores. No reemplaza una prueba real de audio bidireccional con credenciales de OpenAI, TURN operativo y el dispositivo físico objetivo.
