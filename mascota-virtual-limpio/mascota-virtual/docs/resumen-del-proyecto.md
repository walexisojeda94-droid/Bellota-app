# Resumen del proyecto: Bellota híbrida para Samsung J7

## Objetivo

Convertir un Samsung Galaxy J7 en Bellota, una mascota virtual que se conecte a XiaoZhi cuando haya internet y conserve un modo local funcional cuando la conexión falle.

La experiencia debe sentirse como una sola mascota. La interfaz, identidad, personalidad, expresiones y memoria principal pertenecen a la aplicación Android. XiaoZhi aporta conversación avanzada online, pero no debe ser indispensable para que Bellota siga viva y responda.

## Comportamiento esperado

### Con internet
- Conectarse al servicio XiaoZhi que se defina para el proyecto.
- Enviar la interacción de voz o texto al servicio y reproducir la respuesta.
- Aprovechar conversación avanzada y capacidades compatibles del servicio.
- Mantener la identidad y presentación visual de Bellota en la app local.

### Sin internet o sin servicio
- No cerrar ni bloquear la aplicación.
- Seguir mostrando animaciones y expresiones.
- Responder a comandos y frases locales acotadas.
- Hablar con una voz española instalada y comprobada para uso offline.
- Conservar estado, configuración y memoria local.
- Informar de forma natural que está usando su modo local, si corresponde.

### Al recuperar conexión
- Detectar disponibilidad de red y comprobar el servicio.
- Reconectar automáticamente con límites y reintentos razonables.
- Volver al modo online sin reiniciar la app.
- No perder el contexto local ni duplicar mensajes durante el cambio.

## Dispositivo inicial

- Samsung Galaxy J7, variante informada: SM-J710SS.
- Android 8.1.
- 2 GB de RAM.
- 16 GB de almacenamiento interno.
- microSD de 8 GB.

El modelo exacto de procesador y ABI debe verificarse. La compatibilidad de cada motor de voz o biblioteca debe medirse en el dispositivo real.

## PC de preparación

- Windows 10 Pro.
- AMD A8-7600 Radeon R7 a 3,10 GHz.
- 8 GB de RAM instalados.
- Sistema de 64 bits.

La PC sirve para editar, compilar, investigar y transferir la app. No debe ser un servidor obligatorio ni participar en el funcionamiento cotidiano.

## Otros dispositivos

Hay un Samsung Galaxy S5 G900H y una tablet H863H03. No forman parte de la primera versión. Cada equipo tendrá una instalación independiente en el futuro.

## Recursos reutilizables

- 21 expresiones animadas GIF de 240 x 240.
- Un archivo `index.json` asociado a las caras.
- Cinco efectos OGG: exclamation, low_battery, popup, success y vibration.

La procedencia y licencia de estos recursos debe verificarse. La licencia MIT de XiaoZhi no implica automáticamente que todos los GIF y sonidos tengan esa licencia.

## Límites técnicos

- El J7 con 2 GB de RAM no es el lugar adecuado para ejecutar un modelo de lenguaje grande.
- La conversación avanzada se procesa en el servicio XiaoZhi cuando hay conexión.
- El modo local será más acotado: comandos, respuestas predefinidas o reglas, estado de ánimo y memoria sencilla.
- La voz offline debe comprobarse con el motor TTS disponible en Android o una alternativa liviana compatible.
- El reconocimiento de voz offline es una función a evaluar, no una capacidad garantizada.
- La app debe distinguir entre tener internet y tener disponible el servidor XiaoZhi: puede haber red, pero el servicio no responder.
- No se debe instalar firmware Troko/ESP32 en el teléfono. El material ESP32 se conserva como referencia.

## Funciones previstas

### Base visual y personalidad
- Cara animada y expresiones.
- Reacción al toque.
- Estados de ánimo y energía.
- Identidad y personalidad consistentes entre modo online y offline.
- Preferencias y memoria local persistente.

### Voz e interacción
- Voz online mediante XiaoZhi.
- Voz local de respaldo sin internet.
- Comandos locales básicos.
- Reconocimiento de voz offline, si el J7 lo soporta con rendimiento aceptable.
- Activación manual como primera opción; escucha permanente solo si batería y memoria lo permiten.

### Funciones de mascota
- Reloj, temporizador y Pomodoro.
- Recordatorios y alarmas locales.
- Minijuegos sencillos.
- Comentarios espontáneos configurables.

## Decisiones pendientes

1. Definir qué servidor o servicio XiaoZhi se utilizará y cómo se autentica.
2. Verificar qué protocolo y flujo de cliente admite ese servicio para Android.
3. Comprobar voz TTS local en español en el J7.
4. Medir el consumo de memoria, batería y almacenamiento.
5. Definir qué datos de conversación se guardan localmente y cuáles se envían al servicio online.

## Próximo paso

Preparar una primera pantalla Android local con las expresiones existentes y probarla en el J7. En paralelo, verificar las capacidades reales de voz offline. Luego se integra la conexión XiaoZhi detrás de una capa que permita cambiar entre online y respaldo local sin afectar la interfaz.
