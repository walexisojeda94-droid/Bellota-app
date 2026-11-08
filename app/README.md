# Módulo Android de Bellota

## Requisitos de desarrollo
- Android Studio compatible con Android Gradle Plugin 8.6.1.
- JDK 17 para ejecutar Gradle.
- Android SDK Platform 35.
- Dispositivo de prueba: Samsung Galaxy J7, Android 8.1 (API 27).

## Compatibilidad
- `minSdk 27`: Android 8.1 como mínimo.
- `targetSdk 28`: configuración inicial conservadora para el primer ciclo de pruebas en el J7.
- Código Java 8 y sin dependencias externas en esta primera etapa.

## Estructura prevista
- `ui/`: pantalla, controles y animaciones de Bellota.
- `core/`: estado general y coordinación del asistente.
- `local/`: respuestas, comandos y memoria disponibles sin internet.
- `voice/`: captura de audio y síntesis de voz.
- `xiaozhi/`: cliente del servicio online, pendiente de validar el flujo oficial de alta y protocolo.
- `data/`: preferencias y persistencia local.

La primera pantalla está implementada en `MainActivity.java`. Los módulos de voz, modo local y XiaoZhi todavía no están conectados. No se deben agregar endpoints, credenciales ni identidades de dispositivo inventadas.

## Compilación
Abrir la carpeta raíz del repositorio desde Android Studio y permitir que sincronice Gradle. Esta primera entrega todavía no incluye Gradle Wrapper; Android Studio puede generar/usar la distribución de Gradle compatible con el plugin indicado.
