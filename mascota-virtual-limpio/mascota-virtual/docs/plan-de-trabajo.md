# Plan de trabajo: Bellota híbrida en Samsung J7

## Etapa 0. Organización y diagnóstico
- [x] Crear una rama separada para el trabajo del J7.
- [x] Inventariar recursos visuales, sonidos y documentación de referencia.
- [ ] Verificar modelo exacto, ABI, espacio libre y estado del J7.
- [ ] Comprobar conexión USB y transferencia de archivos.
- [ ] Definir el servicio XiaoZhi y el modo de conexión.

## Etapa 1. Prototipo visual local
- [ ] Crear pantalla principal de Bellota.
- [ ] Mostrar una expresión inicial y cambiarla al tocar.
- [ ] Probar y seleccionar GIFs.
- [ ] Añadir estados visuales básicos.
- [ ] Guardar preferencias localmente.
- [ ] Instalar y medir fluidez y memoria en el J7.

Criterio: la interfaz debe funcionar en modo avión.

## Etapa 2. Núcleo local y voz de respaldo
- [ ] Implementar personalidad y respuestas locales básicas.
- [ ] Guardar estado y memoria sencilla.
- [ ] Comprobar TTS español sin conexión.
- [ ] Probar respuestas habladas en modo avión.
- [ ] Evaluar reconocimiento local de comandos, si el rendimiento lo permite.

Criterio: Bellota debe poder responder oralmente sin internet.

## Etapa 3. Integración XiaoZhi online
- [ ] Identificar servicio/servidor XiaoZhi elegido.
- [ ] Confirmar protocolo, autenticación y formato de audio.
- [ ] Crear adaptador de conexión separado del núcleo local.
- [ ] Conectar y realizar una conversación de prueba.
- [ ] Gestionar errores y tiempos de espera.

Criterio: conversación online funcional sin que la interfaz dependa de la sesión.

## Etapa 4. Respaldo y reconexión
- [ ] Detectar pérdida de internet.
- [ ] Distinguir falta de red de caída del servicio.
- [ ] Cambiar a respuestas y voz locales.
- [ ] Reconectar automáticamente al volver el servicio.
- [ ] Evitar mensajes duplicados y bloqueos.
- [ ] Probar cortes durante una conversación.

Criterio: nunca quedarse muda ni exigir reinicio.

## Etapa 5. Funciones adicionales
- [ ] Temporizador, reloj y Pomodoro.
- [ ] Recordatorios locales.
- [ ] Minijuegos.
- [ ] Comentarios espontáneos configurables.
- [ ] Ampliar memoria y personalización.

## Reglas del proyecto
- No comprar hardware para esta primera etapa.
- La PC solo se usa para desarrollar, compilar e instalar.
- El teléfono no depende de la PC para funcionar.
- XiaoZhi mejora la conversación, pero no sustituye el núcleo local.
- No elegir motores de voz sin probarlos en el J7 real.
- No incorporar dependencias pesadas sin medir RAM, almacenamiento y batería.
- No fusionar la rama de trabajo con main sin revisión y autorización.
