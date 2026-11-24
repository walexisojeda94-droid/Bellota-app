# Revisión 10: catálogo común y herramientas de Bellota (BEL-052 a BEL-056)

## Arquitectura
- `BellotaToolCatalog` es la única fuente de declaraciones de herramientas.
- OpenAI Realtime y Gemini Live reciben el mismo JSON canónico; cada proveedor conserva únicamente su adaptador de protocolo.
- El despacho de acciones permanece en la aplicación Bellota, no dentro del proveedor.
- Las llamadas siguen asociadas a su proveedor y generación de sesión. Resultados tardíos o duplicados se descartan.
- Las respuestas de error de herramientas se devuelven al motor como resultados de error sin cerrar la sesión de voz.

## Catálogo común
- Estado de Bellota.
- Cambio de emoción.
- Hablar como Bellota.
- Registrar interacción.
- Iniciar minijuego.
- Consultar clima actual.
- Consultar batería.
- Consultar volumen multimedia.
- Ajustar volumen multimedia (0 a 100).
- Consultar fecha y hora locales.

## Clima
La herramienta `get_weather` reutiliza `WeatherService` y Open-Meteo, con ubicación del dispositivo, actualización única bajo demanda y caché acotada. No inicia seguimiento GPS continuo. Si no hay permiso o ubicación utilizable, responde con una explicación y permite continuar la conversación.

## Comandos locales
Se conservan las consultas locales de batería, fecha/hora y volumen. Se añade ajuste de volumen por porcentaje o en pasos de diez puntos con órdenes explícitas, sin confundir menciones incidentales.

## Estado y memoria
Los cambios de emoción, interacciones, juegos y ajustes de volumen se anotan en el diario cifrado de PetMemory. Los turnos online siguen alimentando la memoria persistente compartida existente.

## Pruebas
- Prueba unitaria del catálogo canónico y del rango de volumen.
- Pruebas de intención local para órdenes explícitas de volumen.
- CI debe ejecutar JVM tests, Android Lint y compilación de APK.
- La prueba funcional final debe invocar cada herramienta desde OpenAI y Gemini, verificar el mismo efecto en Bellota y simular fallos de herramienta sin cortar la sesión.
