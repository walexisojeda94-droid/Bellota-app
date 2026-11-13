# Plan de trabajo

## Reglas del proyecto

- Mantener el núcleo de Bellota en Android.
- XiaoZhi complementa el modo local, nunca debe ser un punto único de falla.
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

## Etapa 4. XiaoZhi online

- [x] Cliente de activación y configuración OTA.
- [x] Transporte de voz WebSocket con Opus.
- [x] Transporte MQTT/UDP con Opus.
- [x] Selección del transporte a partir de la respuesta OTA.
- [ ] Validar vinculación y sesión real con la cuenta del usuario.
- [ ] Probar audio bidireccional y estabilidad en Android 8.1.

## Etapa 5. Resiliencia

- [x] Camino de respaldo local ante errores de la sesión online.
- [x] Reconexión automática con backoff acotado; falta validación prolongada en el J7.
- [ ] Evitar respuestas duplicadas y conservar el contexto al recuperar conexión.
- [ ] Probar cortes de red y servicio no disponible.

## Etapa 6. Funciones complementarias

- [x] Consulta meteorológica bajo demanda mediante Open-Meteo.
- [x] Conexión MCP opcional.
- [ ] Revisar permisos, privacidad y experiencia de configuración.
- [ ] Evaluar recordatorios, temporizador y nuevas actividades sin sobrecargar el J7.

## Criterio de versión estable

La versión se considera estable cuando compila, se instala y supera pruebas prolongadas en el J7: modo offline, voz local, vinculación XiaoZhi, audio bidireccional, fallback, recuperación, batería, memoria y temperatura.


## Etapa 7. Calidad automatizada

- [x] Pruebas JVM para respuestas locales, memoria y normalización de mensajes.
- [x] Ejecutar pruebas unitarias y Android Lint en GitHub Actions antes de empaquetar el APK.
- [x] Corregir las verificaciones de permiso de micrófono detectadas por Lint en los transportes WebSocket y MQTT/UDP.
- [x] Revisar advertencias de Lint: quedan tres justificadas por persistencia síncrona de credencial, orientación horizontal fija e identidad estable de XiaoZhi.
- [ ] Incorporar Gradle Wrapper para compilar localmente sin instalar Gradle por separado.
- [ ] Ampliar cobertura con pruebas de ciclo de vida, reconexión y persistencia.
