# Mascota virtual para el Samsung J7

## Objetivo
Convertir un Samsung J7 sin uso en una mascota virtual de escritorio, para dejarla al lado de la PC. Que hable, reaccione, tenga ánimo propio y haga compañía. Se prefiere que todo sea local. La idea es algo propio, inspirado en XiaoZhi, y decidir qué funciones agregarle.

## Equipo
- Teléfono: Samsung J7 (Android viejo, poca RAM, procesador flojo). No puede correr IA local, pero sirve de pantalla, parlante, micrófono y táctil.
- PC: Windows 10 Pro, AMD A8-7600 con gráficos Radeon R7, 8 GB de RAM, sin placa dedicada.
- Lo que aguanta la PC en local: reconocimiento de voz chico (Whisper tiny/base o Vosk) y voz sintética (Piper). Un modelo de lenguaje chico (1B a 3B) funcionaría muy lento.

## Decisiones tomadas
- La mascota se hace para el J7 como página web o app propia, no para ESP32.
- El ESP32 queda como alternativa si más adelante se quiere un objeto físico (haría falta una placa ESP32-S3, micrófono I2S, amplificador y parlante).

## Material de referencia
- Proyecto XiaoZhi (código fuente en C++ para ESP32, licencia MIT): https://github.com/78/xiaozhi-esp32
  - Reutilizable: el protocolo de comunicación (docs/websocket.md), los servidores para la PC y las ideas de diseño de emociones.
  - Clientes que no son ESP32: xiaozhi-android-client (TOM88812) y py-xiaozhi (huangjunsen0406). No se verificó si funcionan en el J7.
  - Servidor para la PC: xinnan-tech/xiaozhi-esp32-server (Python).
  - Generador de emojis, fuentes y palabras de activación propias: 78/xiaozhi-assets-generator.
- Repo https://github.com/walexisojeda94-droid/Bellota-app: contiene solo un firmware ya compilado (troko-wk-esp32s3-dev-es.bin) para ESP32-S3, una guía en HTML, LEEME, licencia MIT y checksums. No hay código fuente.

## Lo reutilizable del firmware troko-wk-esp32s3-dev-es.bin
- 21 caras animadas en GIF de 240x240, ojos blancos sobre fondo negro, entre 40 y 80 cuadros, en bucle: angry, confident, confused, cool, crying, delicious, embarrassed, funny, happy, kissy, laughing, loving, neutral, relaxed, sad, shocked, silly, sleepy, surprised, thinking, winking.
- El index.json que las lista.
- Todo está en el archivo caras-troko.zip.

## Lo que no sirve para el J7
- srmodels.bin: el modelo de la palabra de activación "Troko", hecho para el chip ESP32-S3.
- El firmware en sí.

## Datos del LEEME.txt del firmware (referencia para la ruta ESP32)
- El firmware es de la marca "Troko", versión en español, basado en XiaoZhi ESP32. Se graba completo en la dirección 0x0.
- Hardware que pide: ESP32-S3-N16R8 (16 MB de flash, 8 MB de PSRAM), pantalla ST7789 de 240x240 por SPI, micrófono I2S tipo INMP441 y amplificador I2S tipo MAX98357A con altavoz.
- Conexiones (GPIO): pantalla MOSI 8, CLK 9, CS 11, DC 12, RST 13, BLK 10; micrófono SCK 42, WS 40, SD 41; amplificador BCLK 47, LRC 48, DIN 21; botones BOOT 0 (hablar), volumen+ 38, volumen- 39; LED 14. Si la placa está cableada distinto, ese firmware no funciona.
- Cómo se instala: con esptool-js desde Chrome/Edge o con esptool por línea de comandos.
- Importante para el objetivo "todo local": este firmware usa el servidor xiaozhi.me, que es un servicio de terceros en la nube. Para que sea local hay que usar un servidor propio (por ejemplo xiaozhi-esp32-server).
- El archivo SHA256SUMS solo verifica el .bin. El hash coincide con el archivo que se subió.

## Pendiente de resolver
- Quién dibujó las caras: el repo es MIT con copyright de la empresa de XiaoZhi, pero no está confirmado el autor de los GIFs. Decidir si se usan o se hacen caras propias.
- Si el cliente Android de XiaoZhi funciona en el J7.

## Ideas de funciones (de más simple a más complejo)
Base:
- Cara con emociones y parpadeo aleatorio
- Se duerme cuando no hay nadie y se despierta al volver
- Ánimo y energía que cambian con el día y con el trato

Interacción:
- Reacciona al toque
- Voz propia con Piper
- Comentarios espontáneos (tomar agua, llevás horas sentado)
- Saludo a la mañana y despedida al apagar la PC

Útiles:
- Pomodoro, recordatorios, alarmas
- Reloj, clima, agenda
- Música o ruido de fondo

De mascota:
- Minijuegos simples
- Nivel de cariño que crece con el uso
- Personalidad propia (nombre, forma de hablar, manías)
- Memoria de cosas que se le contaron

Conexión con la PC (más avanzado):
- Saber si estás jugando, trabajando o ausente
- Leer notificaciones en voz alta

## Próximo paso propuesto
Armar una página web de prueba que muestre una cara según el estado de ánimo y cambie al tocarla, probarla en el navegador del J7, y después sumar voz. Falta definir la personalidad de la mascota (tierna, sarcástica, perezosa, otra).
