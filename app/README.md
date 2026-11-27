# Aplicación Android

Módulo Android nativo de Bellota. Paquete: `com.bellota.mascota`.

## Plataforma

- Android mínimo: API 24 (Android 7.0).
- Target SDK: API 28.
- Compile SDK: API 35.
- Código de aplicación: Java 8.
- Compilación CI: Java 17, Android Gradle Plugin 8.13.2 y Gradle Wrapper 8.13.
- Orientación de la experiencia: horizontal, pantalla inmersiva.

## Componentes

| Paquete | Responsabilidad |
| --- | --- |
| `core/` | Ciclo de vida, necesidades, memoria de eventos y conducta autónoma. |
| `local/` | Conversación offline, normalización de texto, contexto y memoria conversacional. |
| `ui/` | Ojos vectoriales, expresiones y animaciones. |
| `voice/` | TextToSpeech local, reconocimiento de comandos y contrato independiente para proveedores online. |
| `weather/` | Consulta meteorológica mediante Open-Meteo y ubicación bajo demanda. |

## Comportamiento actual

- La pantalla mantiene visibles los ojos animados y reacciona a toques.
- La rutina autónoma puede hacer que Bellota explore, piense, juegue, descanse o inicie una interacción.
- La edad virtual y las necesidades básicas se guardan localmente.
- El motor local ofrece respuestas acotadas y conserva datos de conversación.
- La voz local usa el motor TextToSpeech instalado en Android.
- El reconocimiento local se ejecuta en `VoiceRecognitionService`, un Foreground Service con notificación persistente.
- OpenAI Realtime está implementado con WebRTC y credenciales efímeras obtenidas desde el backend.
- `VoiceSessionService` administra la sesión online como Foreground Service, incluyendo OpenAI Realtime y Gemini Live. `MainActivity` inicia, consulta y detiene el servicio, pero no es propietaria de la sesión.
- Gemini Live cuenta con el adaptador Android `GeminiLiveProvider`, registrado en `VoiceSessionService` como segundo proveedor. La conexión y el audio bidireccional reales todavía requieren validación con credenciales y dispositivo físico.
- El clima se consulta bajo demanda y requiere permiso de ubicación.

## Compilación y pruebas

Desde la raíz del repositorio:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

La distribución es privada. El workflow puede generar un APK Release firmado cuando están configurados los secretos de firma. Nunca guardar el keystore ni sus contraseñas dentro del repositorio.

## Validación conocida

La Revisión 7 pasó CI en emuladores API 24, API 27 y API 31, junto con pruebas JVM, Lint, Release y verificación de certificado. Esto acredita la compilación y el arranque smoke-test en esas imágenes; no acredita funcionamiento real de audio, sesiones prolongadas ni continuidad online en segundo plano.

Pendientes de certificación funcional: dispositivo físico objetivo, audio bidireccional con credenciales reales, red restrictiva/TURN, interrupciones, recuperación y segundo proveedor Gemini Live.
