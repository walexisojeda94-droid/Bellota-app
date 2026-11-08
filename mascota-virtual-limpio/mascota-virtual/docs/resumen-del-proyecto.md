# Resumen del proyecto: Bellota híbrida para Samsung J7

## Objetivo

Convertir un Samsung Galaxy J7 en Bellota, una mascota virtual conectada por defecto al servicio oficial XiaoZhi (`https://xiaozhi.me`) y con funcionamiento local de respaldo.

La experiencia debe sentirse como una sola mascota. La interfaz, identidad, personalidad, expresiones y memoria principal pertenecen a la aplicación Android. XiaoZhi aporta conversación avanzada online, pero no debe ser indispensable para que Bellota siga viva y responda.

## Comportamiento esperado

### Con internet y XiaoZhi disponible
- Conectarse al servicio oficial XiaoZhi en `https://xiaozhi.me`.
- Enviar interacción de voz o texto según el protocolo que se implemente.
- Reproducir la respuesta recibida.
- Mantener identidad y presentación visual de Bellota en la app local.

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
- No perder contexto local ni duplicar mensajes.

## Dispositivo inicial

- Samsung Galaxy J7, variante informada: SM-J710SS.
- Android 8.1, 2 GB de RAM, 16 GB internos y microSD de 8 GB.
- Procesador y ABI exactos pendientes de verificación.

## PC de preparación

Windows 10 Pro, AMD A8-7600 Radeon R7, 8 GB RAM. Se usa para desarrollo, compilación y transferencia. No debe alojar servicios obligatorios.

## Otros dispositivos

Samsung Galaxy S5 G900H y tablet H863H03 disponibles para posibles etapas futuras, sin formar parte de la primera versión.

## Recursos reutilizables

- 21 expresiones GIF de 240 x 240.
- Un `index.json` asociado.
- Cinco efectos OGG.

La procedencia y licencia específica de GIF y sonidos debe verificarse. La licencia MIT de XiaoZhi no implica que todos los recursos tengan automáticamente esa licencia.

## Límites técnicos

- El J7 con 2 GB de RAM no es adecuado para ejecutar un modelo de lenguaje grande.
- La conversación avanzada se procesa en XiaoZhi cuando hay conexión.
- El modo local será más acotado: comandos, respuestas basadas en reglas, estado de ánimo y memoria sencilla.
- La voz offline debe comprobarse con TTS español disponible en Android o alternativa liviana.
- El reconocimiento offline es una función a evaluar, no una capacidad garantizada.
- Tener internet no garantiza que el servicio XiaoZhi esté disponible.
- No se instalará firmware Troko/ESP32 en Android.

## Funciones previstas

### Base visual y personalidad
- Cara animada y expresiones.
- Reacción al toque.
- Estados de ánimo y energía.
- Identidad consistente entre modo online y offline.
- Preferencias y memoria local persistente.

### Voz e interacción
- Conversación online con XiaoZhi oficial.
- Voz local de respaldo sin internet.
- Comandos locales básicos.
- Reconocimiento offline si el J7 lo soporta.
- Activación manual como primera opción; escucha permanente sujeta a pruebas de batería y memoria.

### Funciones adicionales
- Reloj, temporizador y Pomodoro.
- Recordatorios y alarmas locales.
- Minijuegos.
- Comentarios espontáneos configurables.

## Decisiones confirmadas

1. Dispositivo inicial: Samsung Galaxy J7 SM-J710SS, Android 8.1.
2. Arquitectura local-first híbrida.
3. Servicio online predeterminado: XiaoZhi oficial, `https://xiaozhi.me`.
4. El núcleo local no depende de XiaoZhi ni de internet.
5. No comprar hardware en esta etapa.

## Pendiente de implementación

- Verificar protocolo, activación y autenticación para un cliente Android.
- Implementar el adaptador de XiaoZhi y probar una conversación real.
- Verificar TTS español offline y reconocimiento local.
- Medir RAM, batería, almacenamiento y fluidez.
- Definir qué datos de conversación se envían al servicio y qué memoria queda local.

Ver [Integración con xiaozhi.me](integracion-xiaozhi-me.md) y [arquitectura híbrida](arquitectura-j7-offline.md).
