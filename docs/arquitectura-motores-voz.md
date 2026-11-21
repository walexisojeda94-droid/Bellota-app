# Arquitectura de motores conversacionales de Bellota

## Decisión de producto

- OpenAI Realtime será el proveedor conversacional principal.
- Gemini Live será el segundo proveedor, incorporado después de validar OpenAI.
- No se incorporan otros motores en esta etapa.
- La conversación local existente permanece como respaldo cuando no haya Internet o los proveedores online no estén disponibles.
- La memoria, personalidad, estado emocional, autonomía, comandos y funciones propias de Bellota no pertenecen a ningún proveedor. Deben seguir siendo responsabilidad de la aplicación.

## Reglas que no se negocian

1. Ninguna clave API permanente de OpenAI o Google se almacena en el APK, recursos, preferencias ni código fuente.
2. Las credenciales temporales se solicitan a un backend controlado por Bellota y se mantienen solo el tiempo necesario en memoria.
3. La app no solicita al usuario renovar tokens manualmente.
4. Los errores de autenticación se distinguen de falta de red, límites, saldo, errores del proveedor y fallos de audio.
5. La reconexión usa backoff con límites, evita bucles infinitos y no duplica mensajes ni acciones.
6. La aplicación conserva el contexto de Bellota y reconstruye el contexto conversacional cuando una sesión debe reiniciarse.
7. Un cambio de proveedor nunca se considera exitoso hasta que el segundo proveedor confirma que la sesión está lista.
8. El modo local sigue disponible aunque fallen ambos proveedores.

## Arquitectura objetivo

- conversation/core: personalidad, memoria, contexto, estado emocional, reglas y herramientas propias de Bellota.
- voice/provider: contrato común de proveedor, eventos, estados y errores independientes del fabricante.
- voice/openai: implementación Android de OpenAI Realtime mediante WebRTC.
- voice/gemini: implementación futura de Gemini Live.
- voice/orchestrator: selección del proveedor, ciclo de vida, reconexión, fallback y prevención de respuestas duplicadas.
- Backend de Bellota: emite credenciales temporales, aplica controles de acceso y límites, registra métricas sin guardar audio ni secretos y mantiene las claves permanentes fuera del dispositivo.
- local: motor de conversación y voz local existentes, usados como fallback.

La separación es lógica. El cliente XiaoZhi se elimina por completo; se conservan solo las funciones propias de Bellota que no dependen de ese protocolo.

## Autenticación OpenAI

La aplicación solicitará al backend una credencial temporal para crear una sesión Realtime. El backend usará la clave permanente de OpenAI y nunca la enviará al dispositivo. La app utilizará la credencial temporal para establecer WebRTC.

La credencial temporal autoriza la creación de sesiones hasta su vencimiento. Su vencimiento no corta una sesión ya establecida. Antes de crear una sesión nueva, la app debe pedir una credencial nueva. Ante un rechazo de autenticación, puede solicitar una credencial nueva y reintentar una sola vez de forma controlada; no debe repetir indefinidamente.

El backend debe aplicar autenticación/autorización propia, limitación de frecuencia, límites de consumo y protección contra abuso. La clave de OpenAI se guarda como secreto del entorno del backend. No se considera suficiente ocultar una URL del backend dentro del APK.

## Transporte y compatibilidad Android

La integración principal prevista es WebRTC nativo para Android. Antes de añadir dependencias se verificará:
- compatibilidad real de la biblioteca WebRTC elegida con minSdk 27 y el toolchain actual;
- tamaño del APK, ABI, consumo de memoria y CPU;
- captura y reproducción de audio, permisos y ciclo de vida;
- negociación SDP, ICE, cambios de red y reconexión;
- interrupciones de audio y cancelación de respuestas;
- compatibilidad con el dispositivo de prueba moto g52 Android 13 y el objetivo histórico Galaxy J7 Android 8.1.

No se incorporará una dependencia solo porque funcione en un ejemplo web.

## Estados de conexión

El orquestador debe distinguir al menos:
IDLE, REQUESTING_CREDENTIAL, CONNECTING, CONNECTED, RECONNECTING, AUTH_ERROR, QUOTA_ERROR, NETWORK_ERROR, PROVIDER_ERROR, FALLBACK_LOCAL y STOPPED.

Los errores de cuota, facturación o cuenta no se solucionan renovando tokens. Deben mostrarse como tales y activar el respaldo local. La aplicación no debe alternar proveedores en bucle cuando el error sea permanente o compartido por la cuenta.

## Continuidad conversacional

El proveedor recibe un contexto acotado construido por Bellota. La memoria persistente continúa residiendo localmente. Al reiniciar una sesión se reconstruye el contexto a partir de la memoria y de los últimos turnos relevantes, sin enviar indiscriminadamente todo el historial privado.

Cada turno tendrá un identificador interno para evitar duplicados al reconectar. Las herramientas y comandos sensibles se validan y ejecutan en Bellota, no por confianza ciega en el texto generado por el modelo.

## Orden de implementación

1. Retirar por completo el cliente XiaoZhi y desacoplar el flujo de voz local de su ciclo de vida.
2. Definir el contrato común de proveedores sin alterar el comportamiento actual.
3. Implementar backend de credenciales temporales OpenAI y configuración de sesión.
4. Integrar WebRTC Android y conectar el nuevo proveedor al orquestador.
5. Ejecutar pruebas automatizadas y pruebas reales de audio, interrupciones, sesiones largas, reconexión y errores de cuenta.
6. Incorporar Gemini Live detrás del mismo contrato.
7. Probar el cambio controlado OpenAI ↔ Gemini y el fallback local.
8. Mantener únicamente OpenAI, Gemini y el respaldo local; no conservar compatibilidad con XiaoZhi.

## Criterios de aceptación

- Ninguna clave permanente aparece en el APK, repositorio, logs o preferencias.
- La app obtiene credenciales nuevas sin intervención del usuario.
- Una credencial vencida no provoca que una sesión activa se corte.
- Los fallos de autenticación, red, cuota y proveedor se clasifican correctamente.
- No hay bucles de reconexión ni mensajes duplicados.
- La memoria, personalidad y funciones de Bellota sobreviven a reinicios y cambios de proveedor.
- OpenAI, Gemini y el modo local se pueden seleccionar sin reescribir el núcleo conversacional.
- Compila y supera pruebas JVM/Lint; luego se valida en dispositivos reales. Compilar no equivale a validar el funcionamiento de voz.
