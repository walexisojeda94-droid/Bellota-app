# Plan de trabajo

## Reglas del proyecto

- Mantener el núcleo de Bellota en Android.
- OpenAI Realtime será el proveedor principal; Gemini Live será el segundo proveedor y el motor local será el respaldo.
- No mantener código, dependencias ni compatibilidad con proveedores retirados.
- No inventar endpoints, credenciales ni identidades.
- No publicar secretos ni archivos de firma.
- No marcar una función como validada sin probarla en el Samsung Galaxy J7 SM-J710SS.

## Etapa 1. Base Android e interfaz

- [x] Proyecto Android nativo y compilación mediante GitHub Actions.
- [x] Pantalla horizontal e inmersiva.
- [x] Ojos vectoriales animados y estados de ánimo.
- [x] Reacciones al tacto.
- [ ] Medir fluidez y consumo en el J7.

## Etapa 2. Vida y autonomía

- [x] Edad virtual y necesidades básicas persistentes.
- [x] Conductas autónomas de exploración, pensamiento, juego y descanso.
- [x] Iniciativa ocasional de interacción.
- [ ] Ajustar frecuencia y comportamiento mediante pruebas prolongadas.

## Etapa 3. Conversación y voz local

- [x] Motor de respuestas locales.
- [x] Memoria conversacional sencilla.
- [x] Android TextToSpeech con selección de español y alternativa genérica.
- [x] Reconocimiento de comandos mediante SpeechRecognizer.
- [ ] Confirmar voz y reconocimiento sin internet en el dispositivo objetivo.

## Etapa 4. Retirada de la integración heredada

- [x] Eliminar activación OTA, WebSocket, MQTT/UDP, Opus y almacenamiento de credenciales de la integración anterior.
- [x] Desacoplar el reconocimiento y la síntesis locales del ciclo de vida de proveedores online.
- [x] Retirar dependencias y documentación exclusivas de la integración anterior.
- [x] Mantener la voz local como funcionamiento autónomo y respaldo de los proveedores online.

## Etapa 5. Resiliencia

- [x] Camino de respaldo local ante errores de la sesión online.
- [x] Reconexión automática con backoff acotado; falta validación prolongada en el J7.
- [ ] Evitar respuestas duplicadas y conservar el contexto al recuperar conexión.
- [ ] Probar cortes de red y servicio no disponible.

## Etapa 6. Funciones complementarias

- [x] Consulta meteorológica bajo demanda mediante Open-Meteo.
- [ ] Revisar permisos, privacidad y experiencia de configuración de los proveedores nuevos.
- [ ] Evaluar recordatorios, temporizador y nuevas actividades sin sobrecargar el J7.

## Criterio de versión estable

La versión se considera estable cuando compila, se instala y supera pruebas prolongadas en el J7: modo offline, voz local, audio bidireccional OpenAI/Gemini, fallback local, recuperación, batería, memoria y temperatura.


## Etapa 7. Calidad automatizada

- [x] Pruebas JVM para respuestas locales, memoria y normalización de mensajes.
- [x] Ejecutar pruebas unitarias y Android Lint en GitHub Actions antes de empaquetar el APK.
- [x] Corregir las verificaciones de permiso de micrófono detectadas por Lint en los transportes WebSocket y MQTT/UDP.
- [x] Revisar advertencias de Lint: quedan tres justificadas por persistencia síncrona de credencial y orientación horizontal fija.
- [x] Incorporar Gradle Wrapper para compilar localmente sin instalar Gradle por separado.
- [ ] Ampliar cobertura con pruebas de ciclo de vida, reconexión y persistencia.


## Etapa 8. Motores conversacionales online

- [x] Definir OpenAI Realtime como proveedor principal y Gemini Live como segundo proveedor.
- [x] Definir arquitectura independiente del proveedor, autenticación temporal y criterios de resiliencia.
- [x] Auditar y separar los puntos de entrada/salida de voz y conversación actuales.
- [x] Implementar backend seguro para credenciales temporales de OpenAI (pendiente despliegue y configuración real).
- [x] Integrar OpenAI Realtime mediante WebRTC en Android (pendiente prueba con credenciales reales).
- [ ] Validar audio bidireccional, interrupciones, reconexión, sesiones largas y límites de cuenta en dispositivos y redes reales.
- [x] Integrar Gemini Live bajo el mismo contrato.
- [ ] Validar cambio entre proveedores y fallback local en uso real.


## Etapa 9. Memoria, conversación y herramientas

- [x] Compartir la memoria semántica persistente entre conversación local y OpenAI Realtime.
- [x] Incorporar el resumen de memoria, preguntas pendientes y continuidad tras pausas al iniciar una sesión online.
- [x] Activar transcripción auxiliar de idioma automático para registrar turnos online.
- [x] Deduplicar transcripciones finales y persistir los dos lados de la conversación en el diario cifrado.
- [x] Asociar llamadas de herramientas a la sesión y proveedor de origen; invalidar resultados tardíos.
- [x] Serializar acciones y agrupar resultados de varias herramientas antes de reanudar el modelo.
- [x] Endurecer la interpretación de órdenes y preguntas para evitar falsos positivos.
- [ ] Validar memoria y herramientas durante conversaciones largas con OpenAI Realtime y en el Samsung Galaxy J7.
