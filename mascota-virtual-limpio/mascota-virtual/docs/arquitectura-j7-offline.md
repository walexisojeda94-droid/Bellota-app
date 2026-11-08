# Arquitectura híbrida local-first para Samsung J7

## Principio

Bellota es una aplicación Android autónoma que conserva localmente su interfaz, identidad, personalidad, estado y memoria. Cuando hay conexión disponible, delega la conversación avanzada en XiaoZhi. Si internet o el servicio fallan, cambia a respuestas locales y voz offline.

XiaoZhi es una capacidad online opcional, no el punto único de falla de la aplicación.

## Esquema general

```
Aplicación Android Bellota (J7)
  |
  +-- Interfaz, GIFs, sonidos y estados visuales locales
  |
  +-- Núcleo local de Bellota
  |     +-- identidad y personalidad
  |     +-- estado de ánimo y energía
  |     +-- memoria y configuración local
  |     +-- comandos y respuestas de respaldo
  |     +-- voz TTS offline, si está disponible
  |
  +-- Gestor de conexión y sesiones
        +-- comprueba conectividad
        +-- comprueba disponibilidad del servicio
        +-- gestiona conexión, errores y reconexión
        |
        +-- Adaptador XiaoZhi (solo online)
              +-- protocolo y autenticación por definir
              +-- conversación avanzada
              +-- recepción de respuestas y audio
```

## Separación de responsabilidades

### Núcleo local
Debe poder ejecutarse sin permisos de red y sin inicializar el cliente XiaoZhi. Controla:
- pantalla y expresiones;
- identidad y personalidad base;
- memoria local y preferencias;
- comandos esenciales;
- respuestas de contingencia;
- avisos de estado de conexión;
- voz local de respaldo.

### Adaptador online XiaoZhi
Se encarga de:
- iniciar y cerrar sesiones;
- autenticar de forma segura según el servicio elegido;
- enviar entradas y recibir respuestas;
- gestionar audio o texto según el protocolo disponible;
- informar errores al gestor de conexión.

No debe controlar directamente la pantalla ni almacenar la única copia de la memoria de Bellota.

## Estados de conectividad

1. **LOCAL:** sin red disponible. La app usa únicamente funciones locales.
2. **CONECTANDO:** hay red, pero todavía no se confirmó una sesión XiaoZhi operativa.
3. **ONLINE:** el servicio responde y la conversación avanzada está disponible.
4. **RECUPERANDO:** se perdió una sesión y se está intentando reconectar.

Tener Wi-Fi o datos móviles no significa necesariamente que XiaoZhi esté disponible. El cambio de estado debe comprobar también el servicio.

## Cambio de modo sin interrupción

- Si una petición online falla, la app debe ofrecer una respuesta local breve en lugar de dejar al usuario esperando indefinidamente.
- Las peticiones deben tener tiempo límite.
- La reconexión debe usar espera progresiva para evitar consumir batería.
- No se deben enviar mensajes duplicados al recuperar una sesión.
- La interfaz debe seguir respondiendo durante los intentos de conexión.
- El usuario debe poder seguir usando comandos locales aunque el servicio online esté caído.

## Voz

### Salida
- Online: usar el audio devuelto por XiaoZhi si el protocolo elegido lo permite.
- Offline: usar un motor TTS español descargado y probado en el J7.
- Si el audio online falla, intentar la respuesta local y reproducirla con TTS local.

### Entrada
- Online: capturar y enviar audio solo conforme al protocolo y permisos del servicio elegido.
- Offline: reconocer comandos locales si un motor compatible supera las pruebas.
- Primera versión: priorizar pulsar para hablar; escucha permanente queda sujeta a pruebas de batería y memoria.

## Memoria y privacidad

- Guardar localmente preferencias, identidad, estado y recuerdos simples.
- No hacer que la memoria dependa exclusivamente del historial remoto.
- Definir qué fragmentos de conversación se envían a XiaoZhi y qué información se conserva.
- No almacenar credenciales en texto plano.
- Informar al usuario cuándo se utiliza el servicio online.

## Compatibilidad y rendimiento

El J7 informado tiene Android 8.1 y 2 GB de RAM. Por ello:
- no se ejecutará un LLM grande en el teléfono;
- el cliente de red y la interfaz deben ser livianos;
- evitar procesos de audio simultáneos innecesarios;
- medir RAM, batería, temperatura y fluidez;
- verificar compatibilidad de bibliotecas con la ABI real del equipo.

## Pruebas de aceptación

1. Abrir la app sin internet y usar la interfaz.
2. Confirmar que Bellota responde con voz local en modo avión.
3. Con internet, establecer sesión con el servicio XiaoZhi elegido.
4. Cortar la conexión durante una conversación y verificar el paso a modo local.
5. Restaurar internet y verificar reconexión sin reiniciar.
6. Simular internet disponible pero servicio XiaoZhi caído.
7. Confirmar que memoria, identidad y preferencias se mantienen entre modos.
8. Confirmar que una falla de red no bloquea la interfaz.

## Decisiones aún abiertas

- Servicio XiaoZhi: hospedado oficial, instancia propia u otro servicio compatible.
- Protocolo concreto y mecanismo de autenticación.
- Formato de audio y estrategia de streaming.
- Motor TTS offline y reconocimiento local compatible con Android 8.1.
