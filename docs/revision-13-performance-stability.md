# Revisión 13: rendimiento y estabilidad general

Estado de esta revisión: correcciones de código integradas; validación automatizada y prueba prolongada en dispositivo pendientes de sus resultados.

## Alcance

| ID | Revisión | Estado / evidencia |
| --- | --- | --- |
| BEL-075 | Hilos y operaciones asíncronas | El clima usa un ejecutor único y las operaciones simultáneas se agrupan. Las llamadas de herramientas siguen fuera del hilo principal mediante el ejecutor de la actividad. Los proveedores mantienen sus trabajadores de red separados de la interfaz. |
| BEL-076 | Recursos WebRTC y audio | Gemini reutiliza el buffer PCM de captura, conserva la referencia al hilo del micrófono, detiene la captura y espera su terminación antes de finalizar la liberación de audio. WebRTC conserva su limpieza serializada bajo el bloqueo de recursos. |
| BEL-077 | Animaciones y vistas | EyePetView cancela animadores y callbacks temporales al pausarse o separarse de la ventana. PlayfieldView solo solicita otro cuadro mientras un juego está activo y detiene el juego al finalizar su duración. |
| BEL-078 | Clima y procesos repetidos | WeatherService agrupa peticiones simultáneas en una sola adquisición de ubicación y una única consulta HTTP. MainActivity ignora nuevas peticiones de clima mientras una respuesta está pendiente y descarta callbacks después de destruirse. |
| BEL-079 | RAM, CPU y batería | Se añade una muestra de depuración cada 60 segundos mientras la actividad está en primer plano: PSS, heap nativo, heap Java usado, tiempo CPU acumulado del proceso, RAM disponible del sistema y porcentaje de batería. |
| BEL-080 | Estabilidad prolongada | Se documenta el protocolo de prueba prolongada. No se declara aprobado hasta completar una sesión real y revisar sus registros. |

## Instrumentación de rendimiento

Las muestras se escriben en Logcat con la etiqueta `BellotaPerformance` únicamente en builds DEBUG. No se registran claves, tokens ni transcripciones.

Comando para recopilar muestras:

```bash
adb logcat -c
adb logcat -v time -s BellotaPerformance:I
```

Cada línea incluye:
- `pss_kb`: memoria proporcional atribuida al proceso.
- `native_heap_kb`: heap nativo asignado.
- `java_heap_used_kb`: heap Java actualmente utilizado.
- `process_cpu_ms`: tiempo de CPU acumulado desde el inicio del proceso. Comparar diferencias entre muestras, no el valor aislado.
- `system_available_ram_kb`: RAM disponible del sistema.
- `battery_pct`: porcentaje reportado por Android. No equivale a consumo energético en mAh.

## Protocolo de estabilidad prolongada

Ejecutar en un dispositivo físico y guardar Logcat completo:

1. Registrar una muestra inicial y dejar Bellota en reposo durante 10 minutos.
2. Mantener una conversación de voz durante 10 minutos, incluyendo al menos una desconexión y reconexión controlada si el proveedor está disponible.
3. Realizar 10 consultas meteorológicas, separadas por intervalos breves, y comprobar que las peticiones simultáneas no disparen solicitudes repetidas.
4. Ejecutar los cuatro juegos/animaciones en varias rondas durante 5 minutos.
5. Dejar Bellota activa otros 5 minutos y comparar las últimas muestras con las del final del período inicial.

Duración mínima sugerida: 30 minutos. Repetir el ciclo si se detecta crecimiento sostenido de PSS/heap, aumento anormal del tiempo de CPU en reposo, consumo de batería desproporcionado, audio que no se detiene, ANR o excepción fatal.

## Criterios de aprobación

- Sin ANR, excepción fatal ni bloqueo perceptible durante la prueba.
- Sin crecimiento sostenido de memoria después del período inicial de calentamiento.
- Los recursos de micrófono, AudioTrack, WebSocket y PeerConnection se liberan al desconectar.
- Las animaciones se detienen al pausar o retirar la vista.
- Las consultas meteorológicas simultáneas comparten un único trabajo.
- Registrar modelo del dispositivo, versión Android, duración, proveedor de voz y muestras inicial/final.

## Límites de la evidencia

Las pruebas de emulador y los tests unitarios no sustituyen la prueba de micrófono, WebRTC, batería ni TTS en hardware real. El porcentaje de batería es una lectura indirecta; para medir consumo energético con precisión se requiere una herramienta de medición compatible con el dispositivo o Android Studio Profiler/Power Profiler. Por eso BEL-080 y la aprobación cuantitativa final de BEL-079 quedan pendientes hasta ejecutar el protocolo físico.
