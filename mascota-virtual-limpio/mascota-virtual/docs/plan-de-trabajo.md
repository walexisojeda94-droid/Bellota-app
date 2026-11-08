# Plan de trabajo: Bellota híbrida en Samsung J7

## Reglas
- Trabajar directamente en `main`, sin ramas ni Pull Requests.
- Revisar el contenido de `main` después de cada etapa.
- No comprar hardware en esta etapa.
- No inventar endpoints, credenciales ni identidades.
- No declarar una función terminada sin probarla en el J7.

## Etapa 0. Organización
- [x] Arquitectura híbrida local-first.
- [x] Proyecto Android inicial.
- [x] Módulos internos core, ui, local, voice y xiaozhi.
- [x] Incorporar 21 GIF reales en assets Android.
- [ ] Verificar modelo exacto, ABI y espacio libre del J7.

## Etapa 1. Interfaz
- [x] Pantalla principal.
- [x] Reproducción local de GIF y selector de expresiones.
- [ ] Integrar efectos de sonido con control de volumen.
- [ ] Compilar e instalar en el J7.
- [ ] Medir fluidez y memoria.

## Etapa 2. Voz y núcleo local
- [x] Integrar Android TextToSpeech.
- [x] Selección de español Argentina con fallback a español genérico.
- [x] Botones para saludar y repetir el mensaje hablado.
- [ ] Verificar paquete de voz español y funcionamiento sin internet en el J7.
- [ ] Añadir captura de voz / reconocimiento local de comandos.
- [ ] Persistir preferencias y memoria sencilla.

Criterio: Bellota puede responder oralmente sin internet y sin bloquear la interfaz.

## Etapa 3. XiaoZhi oficial
- [x] Revisar documentación oficial del protocolo WebSocket y MQTT/UDP.
- [ ] Confirmar que xiaozhi.me admite un cliente Android genérico y documentar su alta.
- [ ] Obtener endpoint, identidad y autenticación por el flujo oficial.
- [ ] Implementar cliente WebSocket y negociación de audio Opus.
- [ ] Probar una conversación real en Android 8.1.

Criterio: sesión online real, autenticada y estable.

## Etapa 4. Fallback y reconexión
- [ ] Detectar pérdida de red o caída del servicio.
- [ ] Cambiar a respuestas y voz locales.
- [ ] Reconectar automáticamente sin reiniciar.
- [ ] Evitar mensajes duplicados.
- [ ] Probar cortes durante conversaciones.

## Etapa 5. Funciones adicionales
- [ ] Temporizador, reloj y Pomodoro.
- [ ] Recordatorios locales.
- [ ] Minijuegos y personalización.
