# Revisión 8: documentación y certificación

## Alcance

Cierre documental de BEL-031, revisión de BEL-036 y trazabilidad de regresión BEL-001 a BEL-035. Esta matriz distingue implementación, automatización y validación operativa. Un test simulado no acredita un proveedor real; compilar no acredita audio, segundo plano ni red restrictiva.

## Evidencia reproducible disponible

### Revisión 7 integrada

La Revisión 7 (BEL-021, BEL-022 y BEL-032) fue integrada en `main` mediante el PR #7 después de completar CI correctamente. El workflow ejecutó y aprobó:
- pruebas JVM;
- Android Lint;
- compilación del APK de smoke test;
- instrumentadas y arranque de la app en Android 7/API 24;
- instrumentadas y arranque en Android 8.1/API 27;
- instrumentadas y arranque en Android 12/API 31;
- compilación Release firmada;
- verificación del certificado de firma;
- publicación del artefacto Release.

Workflow: https://github.com/walexisojeda94-droid/Bellota-app/actions/runs/45095604826

El artefacto publicado por GitHub Actions es un ZIP de 23.654.323 bytes. Ese tamaño corresponde al archivo de descarga del artefacto, no al APK individual; no usarlo como medición del APK. La CI sí confirma que R8 y resource shrinking pudieron producir el Release firmado.

### Backend de Revisión 6

El Worker `bellota-openai-realtime` está desplegado, enlazado a D1 `bellota-devices`, con migraciones 0001/0002 aplicadas y secretos principales comprobados. La observabilidad está habilitada. La prueba del código y de la configuración no equivale a un GET externo exitoso de `/health`. TURN real y audio WebRTC en una red restrictiva siguen sin certificación operativa.

## Matriz de trazabilidad BEL-001 a BEL-035

| Tickets | Evidencia actual | Estado de certificación |
|---|---|---|
| BEL-001 a BEL-015 | No se recuperaron definiciones ni criterios formales por ticket en el repositorio o documentación disponible en esta revisión. | No certificables sin alcance verificable. |
| BEL-016, BEL-018, BEL-019 | Documentados en Revisión 5; pruebas de memoria/conversación, preferencias, interpretación y herramientas incluidas en la suite JVM. | Código y regresión automatizada presentes; comportamiento real integral requiere pruebas de usuario. |
| BEL-017 | No hay criterio de aceptación identificable en la documentación consultada. | No certificable. |
| BEL-020 | Ubicación bajo demanda, timeout, proveedores independientes y fallback de clima documentados. | Implementado; falta prueba física de permisos/proveedores en dispositivos objetivo. |
| BEL-021 | AGP 8.13.2, Gradle 8.13 y dependencias AndroidX Test actualizadas; WebRTC se mantiene fijado. | Integrado; CI verde. |
| BEL-022 | Suite JVM ampliada y regresión instrumentada API 24/27/31. | Integrado; CI verde. |
| BEL-023 | No hay criterio de aceptación identificable en la documentación consultada. | No certificable. |
| BEL-024 | Health check valida configuración y esquema D1; Worker desplegado. | Implementación desplegada; falta evidencia de respuesta HTTP externa. |
| BEL-025 | Validación de método, JSON, tipos, campos y tamaño; pruebas Worker en CI. | Código y automatización presentes; CI verde. |
| BEL-026 | Retención, índices, diario BME2 y compactación documentados; migraciones validadas. | Código y automatización presentes; CI verde. |
| BEL-027 | No hay criterio de aceptación identificable en la documentación consultada. | No certificable. |
| BEL-028, BEL-029, BEL-033 | Documentados en Revisión 5; pruebas de memoria compartida, contexto, herramientas y sesiones. | Código y regresión automatizada presentes. |
| BEL-030 | No hay criterio de aceptación identificable en la documentación consultada. | No certificable. |
| BEL-031 | README raíz, guía Android, arquitectura de voz y documentos de Revisión 4/6 alineados con el estado desplegado y real. | Actualización documental realizada; requiere mantenerla en cada release. |
| BEL-032 | R8 y reducción de recursos habilitados en Release; reglas conservadoras para JNI WebRTC; Release firmado construido y certificado. | Integrado; CI verde. El tamaño individual del APK no se ha medido en esta revisión. |
| BEL-034, BEL-035 | No hay criterios de aceptación identificables en la documentación consultada. | No certificables. |

La tabla no sustituye la lista original de requisitos. No se inventan descripciones para tickets sin especificación. Para certificar formalmente todos los ítems BEL-001 a BEL-035 se debe asociar cada ticket a su criterio de aceptación original y a una prueba verificable.

## BEL-036: segundo proveedor online

**Resultado de la inspección: pendiente, no implementado.**

- `VoiceProvider` y `VoiceProviderManager` permiten un proveedor secundario.
- Los tests del manager crean un `FakeProvider("gemini")` para probar el failover simulado. No existe una clase `GeminiLiveProvider`, conexión a Google Live, emisión de token Gemini, captura/reproducción específica ni prueba de sesión real.
- `MainActivity` instancia únicamente `OpenAIRealtimeWebRtcProvider` y construye el manager con proveedor secundario `null`.
- El Worker solo emite credenciales efímeras OpenAI. No tiene secreto `GEMINI_API_KEY` ni endpoint de token Gemini.

### Diseño técnico recomendado para implementar BEL-036

La documentación oficial vigente describe Gemini Live como un transporte WebSocket bidireccional. El cliente móvil puede usar el endpoint restringido con un token efímero de un solo uso emitido por un backend propio. El audio de entrada es PCM16 little-endian a 16 kHz y la salida de audio PCM16 a 24 kHz. El modelo Live, la versión de API y las restricciones del token deben fijarse de manera coherente y probarse juntos.

Fuentes oficiales:
- https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens

Firebase AI Logic tiene una ruta de integración Android, pero la guía de Android indica que Live está en developer preview y documenta limitaciones de interrupción. No se toma esa vía como base de certificación estable.

### Pruebas obligatorias para cerrar BEL-036

1. Token efímero de un solo uso emitido por el Worker; la clave Google nunca aparece en APK, logs ni repositorio.
2. WebSocket Live abre, confirma setup y distingue errores de autenticación, cuota, red y proveedor.
3. Audio PCM bidireccional en dispositivo físico, interrupción/barge-in y cierre limpio.
4. Renovación de credencial y recuperación sin pedir al usuario un token manual.
5. Memoria y contexto de Bellota conservados al cambiar de OpenAI a Gemini.
6. Herramientas enrutadas al proveedor y generación de sesión correctos, sin duplicados ni callbacks tardíos.
7. Failover real OpenAI → Gemini → voz local y recuperación controlada.
8. Sesiones prolongadas, cambios de red, Android 7/API 24 y Android 12/API 31.
9. Prueba con pantalla apagada y Activity destruida/recreada.

## Bloqueos de certificación final

1. Gemini Live no está implementado: BEL-036 sigue abierto.
2. La voz online OpenAI depende de `MainActivity`; el Foreground Service actual mantiene el reconocimiento local, no el transporte online. No se cumple todavía la continuidad online en segundo plano.
3. Falta prueba real de audio WebRTC, red restrictiva/TURN y sesiones prolongadas en dispositivo físico.
4. Falta medir el tamaño del APK individual, no el ZIP del artefacto.
5. Faltan las especificaciones originales para los tickets sin criterio formal en esta matriz.

## Veredicto

La Revisión 7 tiene evidencia de CI completa y la documentación de BEL-031 quedó corregida. La infraestructura principal de OpenAI está desplegada y la regresión automatizada disponible pasó. **Bellota no debe declararse certificada al 100% todavía**: BEL-036, voz online en segundo plano, pruebas operativas de audio/red y trazabilidad de los tickets sin criterios recuperables siguen abiertos.
