# Aplicación Android

Módulo Android nativo de Bellota. Paquete: `com.bellota.mascota`.

## Plataforma

- Android mínimo: API 27 (Android 8.1).
- Target SDK: API 28.
- Compile SDK: API 35.
- Lenguaje: Java 8.
- Compilación CI: Java 17 y Gradle 8.7.
- Orientación de la experiencia: horizontal, pantalla inmersiva.

## Componentes

| Paquete | Responsabilidad |
| --- | --- |
| `core/` | Ciclo de vida, necesidades, memoria de eventos y conducta autónoma. |
| `local/` | Conversación offline, normalización de texto, contexto y memoria conversacional. |
| `ui/` | Ojos vectoriales, expresiones y animaciones. |
| `voice/` | TextToSpeech local y reconocimiento de comandos de Android. |
| `xiaozhi/` | Activación OTA, sesiones WebSocket/MQTT-UDP, audio Opus y MCP. |
| `weather/` | Consulta meteorológica mediante Open-Meteo. |

## Comportamiento actual

- La pantalla mantiene visibles los ojos animados y reacciona a toques.
- La rutina autónoma puede hacer que Bellota explore, piense, juegue, descanse o inicie una interacción.
- La edad virtual y las necesidades básicas se guardan localmente.
- El motor local ofrece respuestas acotadas y conserva algunos datos de conversación.
- La voz local usa el motor TextToSpeech instalado en Android.
- XiaoZhi solicita la configuración OTA y, según el transporte recibido, inicia WebSocket o MQTT/UDP con audio Opus.
- Si la sesión online falla, la actividad pasa al reconocimiento y la voz local como respaldo.
- El clima se consulta bajo demanda y requiere permiso de ubicación.
- MCP es opcional y se configura desde un acceso oculto mediante pulsación prolongada sobre los ojos.

## Compilación

Desde la raíz del repositorio:

```bash
gradle --no-daemon :app:assembleDebug
```

Para generar una versión release firmada, configurar los secretos de firma documentados en el workflow de GitHub Actions. Nunca guardar el keystore ni sus contraseñas dentro del repositorio.

## Validación pendiente

La integración de red y audio está implementada, pero debe probarse con una vinculación real de XiaoZhi y en el Samsung Galaxy J7 objetivo. También deben medirse estabilidad, latencia, consumo de batería, memoria y funcionamiento offline. No considerar estas pruebas aprobadas solo porque el APK compile.
