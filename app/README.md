# Aplicación Android Bellota

Proyecto inicial orientado al Samsung Galaxy J7 (Android 8.1, API 27).

## Módulos internos

- `core/`: estado y expresiones de Bellota.
- `ui/`: pantalla y reproducción de GIF.
- `local/`: respuestas de respaldo sin conexión.
- `voice/`: voz local Android TTS y estado de voz.
- `xiaozhi/`: adaptador reservado para la integración oficial, pendiente de validar.
- `assets/faces/`: 21 expresiones GIF empaquetadas dentro de la aplicación.

## Voz local

La aplicación usa el motor TextToSpeech instalado en Android, intenta primero español de Argentina y luego español genérico. No incorpora bibliotecas pesadas ni necesita internet si el dispositivo ya tiene instalados los datos de voz correspondientes. La disponibilidad y calidad del paquete español deben comprobarse en el J7.

La pantalla incluye los botones «Saludar» y «Repetir mensaje en voz alta». La voz se libera al cerrar la actividad.

## XiaoZhi

La integración remota no está activada. Antes hay que validar el registro de un cliente Android en el servicio oficial, el endpoint de sesión, la autenticación, los identificadores requeridos y el flujo de audio. No se deben reutilizar credenciales o identidades de otro dispositivo.

## Abrir y ejecutar

Abrir la carpeta raíz del repositorio en Android Studio, sincronizar Gradle y ejecutar el módulo `app` en un dispositivo Android 8.1 o superior.

La voz depende del motor TTS y de sus datos instalados en el teléfono. La integración XiaoZhi y el reconocimiento de voz todavía no están implementados.
