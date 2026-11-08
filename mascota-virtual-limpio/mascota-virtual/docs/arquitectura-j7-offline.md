# Arquitectura híbrida local-first para Samsung J7

## Principio

Bellota es una aplicación Android autónoma. Conserva localmente interfaz, identidad, personalidad, estado, memoria y funciones básicas. Cuando internet y el servicio oficial XiaoZhi están disponibles, delega allí la conversación avanzada. Si falla la conexión o el servicio, cambia a respuestas y voz offline.

**Servicio online predeterminado:** `https://xiaozhi.me`.

XiaoZhi es una capacidad online, no un punto único de falla.

## Esquema

```
Aplicación Android Bellota (J7)
  +-- Interfaz, GIFs, sonidos y estados locales
  +-- Núcleo local
  |     +-- identidad y personalidad
  |     +-- estado de ánimo y memoria local
  |     +-- comandos y respuestas de respaldo
  |     +-- TTS offline
  +-- Gestor de conectividad y sesiones
        +-- comprueba red y disponibilidad del servicio
        +-- controla timeout, errores y reconexión
        +-- Adaptador XiaoZhi oficial (https://xiaozhi.me)
              +-- activación/autenticación por implementar
              +-- protocolo y formato de audio por verificar
              +-- conversación avanzada
```

## Núcleo local

Debe funcionar aunque el adaptador XiaoZhi no se pueda inicializar. Controla la interfaz, identidad, personalidad base, memoria, comandos esenciales, respuestas de contingencia, estados de conexión y voz local.

## Adaptador XiaoZhi

Debe encapsular inicio/cierre de sesión, autenticación, envío de entradas, recepción de respuestas y audio. No debe controlar directamente la pantalla ni almacenar la única copia de la memoria.

La URL del panel `https://xiaozhi.me` es el destino oficial elegido, pero no se debe asumir que es también el endpoint WebSocket del cliente. El endpoint, el flujo de activación, las credenciales y el protocolo se deben obtener de documentación vigente y comprobar para el tipo de cliente Android que implementemos.

## Estados

1. **LOCAL:** no hay red o el servicio no está disponible.
2. **CONECTANDO:** hay red, se está comprobando el servicio o iniciando sesión.
3. **ONLINE:** sesión XiaoZhi operativa.
4. **RECUPERANDO:** sesión perdida; se intenta reconectar.

## Cambio de modo

- Toda solicitud online tiene timeout.
- Si falla, responder localmente en vez de dejar a la mascota muda.
- Reintentos con espera progresiva.
- Evitar mensajes duplicados al reconectar.
- La interfaz y los comandos locales no deben bloquearse por la red.

## Voz

- Online: audio de XiaoZhi según el protocolo implementado.
- Offline: TTS español instalado y probado en el J7.
- Si falla el audio online, usar TTS local.
- Reconocimiento local de voz solo si supera pruebas de compatibilidad y rendimiento. Pulsar para hablar será la primera opción.

## Memoria y privacidad

- Guardar localmente preferencias, identidad, estado y recuerdos simples.
- No depender exclusivamente del historial remoto.
- Definir qué fragmentos se envían a XiaoZhi.
- No guardar credenciales en texto plano.
- Informar cuándo se utiliza el servicio online.

## Rendimiento

El J7 informado tiene Android 8.1 y 2 GB de RAM. No se ejecutará un LLM grande localmente. Medir RAM, batería, temperatura y fluidez. Verificar ABI y compatibilidad de bibliotecas.

## Pruebas de aceptación

1. Abrir y usar la interfaz en modo avión.
2. Confirmar voz local en modo avión.
3. Establecer sesión real con XiaoZhi oficial.
4. Cortar conexión durante una conversación y comprobar el modo local.
5. Restaurar conexión y verificar reconexión sin reinicio.
6. Simular red disponible con servicio XiaoZhi caído.
7. Confirmar persistencia de memoria e identidad.
8. Confirmar que la app nunca se bloquea por una falla de red.

Ver [Integración con xiaozhi.me](integracion-xiaozhi-me.md).
