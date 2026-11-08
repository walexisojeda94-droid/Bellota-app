# Plan de trabajo: Bellota híbrida en Samsung J7

## Etapa 0. Organización y diagnóstico
- [x] Crear rama de trabajo separada.
- [x] Inventariar recursos y documentación.
- [x] Definir arquitectura local-first híbrida.
- [x] Elegir XiaoZhi oficial (`https://xiaozhi.me`) como servicio predeterminado.
- [ ] Verificar modelo exacto, ABI, espacio libre y estado del J7.
- [ ] Comprobar USB y transferencia de archivos.

## Etapa 1. Prototipo visual local
- [ ] Crear pantalla principal.
- [ ] Mostrar expresión inicial y cambiarla al tocar.
- [ ] Seleccionar GIFs y estados visuales.
- [ ] Guardar preferencias localmente.
- [ ] Instalar y medir fluidez y memoria en el J7.

Criterio: interfaz funcional en modo avión.

## Etapa 2. Núcleo local y voz de respaldo
- [ ] Implementar personalidad y respuestas locales.
- [ ] Guardar estado y memoria sencilla.
- [ ] Comprobar TTS español sin conexión.
- [ ] Probar respuestas habladas en modo avión.
- [ ] Evaluar reconocimiento local de comandos.

Criterio: Bellota responde oralmente sin internet.

## Etapa 3. Integración XiaoZhi oficial
- [ ] Verificar protocolo y flujo de cliente Android compatibles con xiaozhi.me.
- [ ] Implementar activación/autenticación segura.
- [ ] Crear adaptador separado del núcleo local.
- [ ] Realizar conversación de prueba.
- [ ] Gestionar errores, timeouts y cierre de sesión.

Criterio: conversación online real y estable.

## Etapa 4. Respaldo y reconexión
- [ ] Detectar pérdida de red y caída del servicio.
- [ ] Cambiar a respuestas y voz locales.
- [ ] Reconectar automáticamente.
- [ ] Evitar duplicados y bloqueos.
- [ ] Probar cortes durante conversaciones.

Criterio: nunca quedarse muda ni exigir reinicio.

## Etapa 5. Funciones adicionales
- [ ] Temporizador, reloj y Pomodoro.
- [ ] Recordatorios locales.
- [ ] Minijuegos.
- [ ] Comentarios espontáneos.
- [ ] Ampliar memoria y personalización.

## Reglas
- No comprar hardware en esta etapa.
- La PC solo se usa para desarrollo, compilación e instalación.
- El teléfono no depende de la PC.
- XiaoZhi mejora la conversación, no sustituye el núcleo local.
- No inventar endpoints ni credenciales.
- No integrar motores pesados sin medir rendimiento.
- No marcar una integración como terminada sin probarla en el J7.
