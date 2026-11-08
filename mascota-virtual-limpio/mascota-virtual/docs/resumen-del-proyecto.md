# Resumen del proyecto: mascota virtual offline para Samsung J7

## Objetivo

Convertir un Samsung Galaxy J7 que ya no se usa en una mascota virtual independiente. Debe poder mostrar una cara animada, reaccionar, hablar, escuchar comandos, tener estados de ánimo y guardar recuerdos sencillos.

La condición central no es negociable: **el funcionamiento cotidiano debe ser completamente local**. No debe necesitar internet, una cuenta, un servidor, la PC ni otro teléfono para funcionar. La PC se usará únicamente como herramienta de preparación, desarrollo e instalación.

La idea toma inspiración visual y conceptual de proyectos como XiaoZhi, pero no busca instalar su firmware ni reproducir su arquitectura conectada a la nube.

## Dispositivos disponibles

### Teléfono principal
- Samsung Galaxy J7, variante informada: SM-J710SS.
- Android 8.1.
- 2 GB de RAM.
- 16 GB de almacenamiento interno.
- Tarjeta microSD de 8 GB.
- El modelo exacto de procesador y sus capacidades todavía deben verificarse.

Estos datos son los informados hasta ahora. Antes de elegir bibliotecas de audio o reconocimiento de voz hay que comprobar compatibilidad real en el dispositivo.

### PC de preparación
- Windows 10 Pro.
- AMD A8-7600 Radeon R7 a 3,10 GHz.
- 8 GB de RAM instalados.
- Sistema de 64 bits.

La PC puede utilizarse para editar, compilar, descargar dependencias y transferir la aplicación. **No debe alojar servicios que el teléfono necesite para funcionar.**

### Otros dispositivos
Hay un Samsung Galaxy S5 G900H y una tablet H863H03. No forman parte de la primera versión. Cada dispositivo tendrá su propia instalación independiente en el futuro, sin comunicación obligatoria entre ellos.

## Decisiones confirmadas

- El primer objetivo es el Samsung J7.
- La mascota debe funcionar offline y de manera autónoma.
- No se comprarán placas, sensores ni componentes para esta etapa.
- Se aprovecharán los recursos gráficos y sonidos que ya están en este repositorio, después de revisar su licencia y su peso.
- No se usará el firmware Troko para ESP32 en el teléfono.
- No se adoptará una arquitectura en la que el J7 sea solamente pantalla, micrófono o parlante de una PC.
- El nombre y la personalidad final de la mascota quedan abiertos. No hace falta decidirlos para comenzar con la base visual.

## Qué contiene este repositorio

### Recursos reutilizables
- 21 expresiones animadas GIF de 240 x 240: angry, confident, confused, cool, crying, delicious, embarrassed, funny, happy, kissy, laughing, loving, neutral, relaxed, sad, shocked, silly, sleepy, surprised, thinking y winking.
- Un archivo `index.json` asociado a las caras.
- Cinco efectos de sonido OGG: exclamation, low_battery, popup, success y vibration.

Los GIF y sonidos pueden servir para la interfaz local. Su procedencia y licencia específica todavía deben verificarse. La licencia MIT de XiaoZhi no demuestra por sí sola que todos los recursos extraídos tengan esa misma licencia.

### Material de referencia
- Documentación de XiaoZhi conservada en `docs/xiaozhi/`.
- Documentación y archivos informativos de Troko/ESP32 conservados en `reference/troko-esp32/`.

Estos materiales sirven para estudiar ideas, emociones, interfaz y diseño. No son dependencias del programa Android.

## Límites técnicos y decisiones pendientes

- Un modelo de lenguaje moderno no es un objetivo realista para este J7 con 2 GB de RAM. No se debe prometer una conversación abierta equivalente a ChatGPT.
- Sí se puede investigar una experiencia local más acotada: comandos predefinidos, reconocimiento de voz pequeño si resulta compatible, respuestas locales, reglas de personalidad y memoria sencilla.
- El reconocimiento offline debe probarse en el hardware. Vosk y otros motores son candidatos, no decisiones confirmadas.
- La síntesis de voz debe funcionar sin red. Primero se comprobará si Android dispone de una voz española descargada y operativa offline. Si no, se evaluarán alternativas compatibles y livianas.
- No se debe asumir que la Web Speech API del navegador funciona sin conexión. La voz se resolverá preferentemente con capacidades Android verificadas.
- La palabra de activación `srmodels.bin` incluida en el material Troko está preparada para ESP32-S3 y no se considera utilizable directamente en Android.
- El firmware Troko depende de hardware ESP32 específico y su flujo habitual utiliza un servicio remoto. No cumple el objetivo del J7 autónomo.

## Funciones previstas, por etapas

### Base visual
- Mostrar una cara animada.
- Cambiar de expresión al tocar la pantalla.
- Estados: neutral, feliz, triste, sorprendido, confundido, pensativo y dormido.
- Animaciones y sonidos locales, con opción para silenciarlos.
- Guardar preferencias y estado básico en el teléfono.

### Personalidad y memoria
- Reglas de comportamiento y respuestas locales.
- Ánimo y energía que cambian con el tiempo y la interacción.
- Memoria sencilla y persistente en almacenamiento local.
- Nombre, tono, humor y manías por definir más adelante.

### Voz e interacción
- Escuchar comandos sin conexión, si el hardware y el motor elegido lo permiten.
- Responder mediante voz española offline.
- Activación manual como primera opción. La escucha permanente solo se evaluará si el consumo de batería y memoria es aceptable.

### Herramientas de mascota
- Reloj, temporizador y Pomodoro.
- Recordatorios y alarmas locales.
- Minijuegos sencillos.
- Comentarios espontáneos configurables.

Las funciones que requieran datos externos en tiempo real, como clima online o notificaciones de servicios, no forman parte de la base offline.

## Próximo paso

Ordenar los recursos y preparar una primera pantalla local que muestre la mascota y cambie de expresión al tocarla. Luego se instala en el J7 y se mide fluidez, memoria y consumo. Solo después se incorporan voz, reconocimiento y memoria persistente.
