# Arquitectura objetivo: J7 completamente offline

## Regla de funcionamiento

La aplicación debe ejecutarse dentro del Samsung J7. Durante el uso normal no debe realizar solicitudes de red ni depender de un servidor local o remoto.

La PC es una herramienta de desarrollo e instalación, no una pieza de la arquitectura de ejecución.

## Diseño por capas

```
Interfaz de mascota
  ├─ GIFs y sonidos incluidos en la aplicación
  ├─ animaciones, toque y estados visuales
  └─ controles de voz y configuración
          |
Lógica local de la mascota
  ├─ personalidad basada en reglas
  ├─ estado de ánimo y energía
  ├─ comandos reconocidos
  └─ respuestas y eventos
          |
Servicios Android locales
  ├─ almacenamiento privado / SQLite
  ├─ Text-to-Speech offline, si está disponible
  └─ reconocimiento offline, si supera las pruebas
```

No hay servidor, nube, API externa ni conexión con la PC en esta arquitectura.

## Plataforma a evaluar

Primera opción: aplicación Android nativa compatible con Android 8.1. Esto permite integrar de forma más directa el almacenamiento local, el audio y las funciones de accesibilidad del dispositivo.

Una página HTML local puede utilizarse como prototipo visual, pero no se debe elegir como solución definitiva para voz hasta comprobar las limitaciones del navegador Android y su funcionamiento sin red.

## Reconocimiento de voz

Requisito: procesar el audio en el propio teléfono, sin enviar grabaciones a internet.

Candidato a investigar: Vosk con un modelo español pequeño. No se considera aprobado hasta comprobar:
- Compatibilidad con Android 8.1 y la arquitectura del J7.
- Memoria libre durante la carga y el reconocimiento.
- Tiempo de respuesta aceptable.
- Calidad suficiente con el micrófono del teléfono.
- Funcionamiento en modo avión.

Si el reconocimiento continuo consume demasiados recursos, se usará activación por botón y escucha por intervalos breves.

## Voz de salida

Primera prueba: motor Text-to-Speech de Android con una voz española descargada previamente. Se debe activar el modo avión y confirmar que sigue hablando.

Si esa voz no está disponible offline, evaluar una alternativa local compatible con ARM y Android 8.1. No se incorporará un motor pesado sin medir RAM, almacenamiento y velocidad.

## Memoria

La memoria será local:
- Preferencias y configuración en almacenamiento privado.
- Datos estructurados y recuerdos simples en SQLite.
- Sin sincronización ni copia automática en la nube.
- Opción futura de exportación manual, si el usuario la solicita.

## Recursos gráficos y sonoros

- Copiar los GIF seleccionados dentro de los recursos de la aplicación.
- Evitar cargar las 21 animaciones a la vez si aumenta el consumo.
- Cargar solo la expresión activa y liberar recursos anteriores cuando corresponda.
- Mantener los efectos de sonido opcionales y permitir silenciarlos.
- Verificar licencias y atribuciones antes de distribuir públicamente los recursos.

## Restricciones de rendimiento

El J7 informado tiene 2 GB de RAM y almacenamiento limitado. Por eso:
- No se planifica ejecutar un LLM de 1B a 3B en el teléfono.
- La primera personalidad será un sistema de reglas y respuestas locales.
- No se ejecutarán reconocimiento de voz, animaciones pesadas y procesos innecesarios en segundo plano simultáneamente.
- Se medirán memoria, batería, temperatura y fluidez en cada etapa.

## Pruebas obligatorias

1. Instalar y abrir la aplicación sin conexión.
2. Usar la interfaz y cambiar expresiones durante varios minutos.
3. Cerrar y volver a abrir, verificando que se conserva la configuración.
4. Probar voz de salida en modo avión.
5. Probar reconocimiento de voz en modo avión.
6. Confirmar que la aplicación sigue funcionando tras reiniciar el teléfono.

Una función que no supere la prueba offline no se considerará implementada.
