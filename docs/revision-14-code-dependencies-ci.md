# Revisión 14: código, dependencias, CI y documentación

## Alcance

Esta revisión cubre BEL-081 a BEL-087. No se declara certificada hasta que la nueva CI termine correctamente y se verifique la configuración de protección de la rama en GitHub.

## Estado y evidencia

| ID | Estado | Evidencia / límite |
| --- | --- | --- |
| BEL-081 | En curso | README corregido para reflejar el resultado real de la última matriz Android. Esta documentación se completa con los resultados de esta revisión. |
| BEL-082 | Parcial | Se retiró `Reply.Type.NEEDS_AI`, su factory y el tratamiento residual en `MainActivity`. El motor local actual solo devuelve texto o solicita clima. Se requiere búsqueda final en todo el repositorio y CI. |
| BEL-083 | Parcial | El workflow se ejecuta en Pull Requests hacia `main`. La protección de rama debe exigir el check `build`; no pude verificar ni cambiar la política porque GitHub devolvió 403 al consultar branch protection. |
| BEL-084 | Parcial | OSV-Scanner audita el lockfile Gradle generado en CI y el resto de archivos compatibles. Dependency Review no se puede activar aún: el Dependency Graph del repositorio está deshabilitado. |
| BEL-085 | Parcial | Se añadió generación CycloneDX agregada y publicación del SBOM como artefacto de CI. La integridad criptográfica y retención se validarán en la primera ejecución exitosa. |
| BEL-086 | Parcial | Se extrajo el enrutamiento de broadcasts de voz a `VoiceEventsReceiver`, dejando en `MainActivity` solo los callbacks de negocio. La Activity sigue concentrando UI, ciclo de vida, autonomía, comandos y permisos; el refactor continúa de forma incremental y requiere validar la nueva separación en CI. |
| BEL-087 | Parcial | Release mantiene R8 y resource shrinking. Las reglas conservan la superficie Java de WebRTC, métodos JNI y atributos de reflexión. La CI incorpora compilación Release minificada; falta verificar el resultado y probar el APK minificado en dispositivo. |

## Controles incorporados

- Pull Requests hacia `main` ejecutan la misma workflow de compilación y pruebas.
- Gradle activa dependency locking para todas las configuraciones. CI resuelve el grafo y genera `app/gradle.lockfile` antes de ejecutar OSV-Scanner, de modo que el escaneo incluya dependencias transitivas del build de esa ejecución. El lockfile generado y el SBOM se conservan como artefactos por ejecución; todavía no se versiona un lockfile estable en Git.
- Dependabot revisa semanalmente dependencias Gradle y GitHub Actions.
- CycloneDX genera el SBOM agregado desde las dependencias resueltas por Gradle; CI conserva el SBOM, su SHA-256 y el lockfile generado como artefactos.
- CI compila la variante Release con minificación y reducción de recursos para detectar fallos de configuración R8.

## Protección obligatoria de main

Antes de reactivar Dependency Review, habilitar en Settings → Security → Code security and analysis → Dependency graph. Luego abrir Settings → Branches (o Rulesets) y exigir para `main`:
1. Require a pull request before merging.
2. Require status checks to pass before merging.
3. Seleccionar el check `build` y el check OSV si se separa como estado independiente.
4. Require branches to be up to date before merging.
5. Bloquear force pushes y eliminación de la rama.

La política no se considera activa hasta verla guardada en la configuración del repositorio. La conexión de GitHub devolvió 403 al consultar la protección de `main`, así que no pude certificar su estado ni cambiarla desde aquí. Los workflows por sí solos no impiden que alguien con permisos haga push directo a `main`.

## MainActivity: criterio de refactor

No mover lógica de voz, memoria o autonomía en bloque. Cada extracción debe:
- mantener el ciclo de vida y el hilo principal explícitos;
- conservar la secuencia de permisos y servicios foreground;
- incluir pruebas para el comportamiento extraído;
- pasar JVM, Lint y la matriz API 24/27/31 antes de la siguiente extracción.

## R8 / ProGuard

Las reglas actuales son deliberadamente conservadoras para WebRTC porque contiene puntos de entrada JNI y clases cargadas en runtime. No añadir reglas globales `-dontwarn` ni `-keep class ** { *; }`: ocultarían problemas y anularían la reducción. Cualquier regla nueva debe responder a una advertencia reproducible o a un fallo observado en Release.

## Pendientes de certificación

- Corregir el fallo API 27 `RemoteServiceException` en `test99CanRecoverPersistedSessionAfterServiceTeardown`; el último run aprobó API 24 y falló API 27, por lo que API 31 fue omitido.
- Ejecutar la nueva CI completa, incluyendo OSV, SBOM y Release minificado; Dependency Review queda pendiente de habilitar Dependency Graph.
- Revisar el reporte de vulnerabilidades y actuar sobre CVE reales, sin actualizar dependencias a ciegas.
- Confirmar la política de protección de `main` desde GitHub Settings.
- Continuar el refactor incremental de `MainActivity` con pruebas específicas.
- Instalar y ejecutar el APK Release minificado en un dispositivo real.

## Comandos locales

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon cyclonedxBom
```

Un SBOM describe las dependencias resueltas en una compilación concreta. No es por sí mismo una prueba de ausencia de vulnerabilidades ni sustituye el escaneo OSV/Dependency Review.
