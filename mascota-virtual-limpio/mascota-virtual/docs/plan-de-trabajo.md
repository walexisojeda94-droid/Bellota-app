# Plan de trabajo

## Etapa 0. Orden y diagnóstico
- [x] Crear una rama separada para el trabajo del J7 offline.
- [x] Documentar el requisito de independencia total.
- [x] Separar conceptualmente recursos reutilizables y referencias ESP32/XiaoZhi.
- [ ] Verificar el modelo exacto del teléfono y su arquitectura.
- [ ] Comprobar conexión USB y transferencia de archivos.
- [ ] Comprobar espacio libre y estado general del J7.

## Etapa 1. Prototipo visual offline
- [ ] Crear la primera pantalla de la mascota.
- [ ] Mostrar una expresión inicial.
- [ ] Cambiar de expresión al tocar la mascota.
- [ ] Probar las 21 caras y seleccionar las más útiles.
- [ ] Añadir estados visuales básicos y transiciones sencillas.
- [ ] Probar en el J7 y medir fluidez y memoria.

Criterio para avanzar: la pantalla debe abrir y funcionar en modo avión, sin errores ni pausas molestas.

## Etapa 2. Persistencia y personalidad
- [ ] Guardar configuración local.
- [ ] Guardar estado de ánimo y energía.
- [ ] Crear respuestas locales basadas en reglas.
- [ ] Definir personalidad y nombre con el usuario.

Criterio para avanzar: cerrar y abrir la aplicación no debe borrar los datos locales.

## Etapa 3. Voz de salida
- [ ] Comprobar Text-to-Speech español offline de Android.
- [ ] Probar pronunciación y velocidad.
- [ ] Medir memoria y tiempo de respuesta.
- [ ] Evaluar motor alternativo solo si el motor Android no sirve.

Criterio para avanzar: hablar correctamente en modo avión.

## Etapa 4. Reconocimiento de voz
- [ ] Comprobar micrófono y permisos.
- [ ] Evaluar Vosk u otro motor offline compatible.
- [ ] Probar comandos breves en español.
- [ ] Medir RAM, batería y velocidad.
- [ ] Elegir botón para hablar o escucha limitada según resultados.

Criterio para avanzar: reconocer comandos útiles sin internet y sin bloquear la interfaz.

## Etapa 5. Funciones de mascota
- [ ] Recordatorios y temporizador locales.
- [ ] Minijuegos sencillos.
- [ ] Comentarios espontáneos configurables.
- [ ] Memoria local ampliada.

## Reglas para todas las etapas
- No agregar servicios en la nube.
- No requerir una conexión a la PC.
- No comprar hardware para resolver una limitación antes de agotar las opciones existentes.
- No incorporar una biblioteca sin comprobar compatibilidad con Android 8.1.
- Medir el consumo en el J7 real antes de dar una función por terminada.
