# Revisión 14: código, dependencias, CI y documentación

## Alcance

Esta revisión cubre BEL-081 a BEL-087. No se declara certificada hasta que la nueva CI termine correctamente y se verifique la configuración de protección de la rama en GitHub.

## Estado y evidencia

| ID | Estado | Evidencia / límite |
| --- | --- | --- |
| BEL-081 | En curso | README corregido para reflejar el resultado real de la última matriz Android. Esta documentación se completa con los resultados de esta revisión. |
| BEL-082 | Parcial | Se retiró `Reply.Type.NEEDS_AI`, su factory y el tratamiento residual en `MainActivity`. El motor local actual solo devuelve texto o solicita clima. Se requiere búsqueda final en todo el repositorio y CI. |
| BEL-083 | Parcial | El workflow ahora se ejecuta en Pull Requests hacia `main`. Para impedir merges, GitHub debe exigir los checks de CI mediante protección de rama o ruleset. La conexión disponible permite leer, pero no configurar esa política administrativa. |
| BEL-084 | Parcial | Se añadió revisión de dependencias en Pull Requests y escaneo OSV programado/semanal. La cobertura depende de los manifiestos y lockfiles que OSV pueda resolver; no se debe interpretar como auditoría completa hasta revisar el primer reporte. |
| BEL-085 | Parcial | Se añadió generación CycloneDX agregada y publicación del SBOM como artefacto de CI. La integridad criptográfica y retención se validarán en la primera ejecución exitosa. |
| BEL-086 | Pendiente | `MainActivity.java` tiene aproximadamente 1.397 líneas y concentra UI, ciclo de vida, autonomía, comandos, permisos y coordinación de voz. No se hará una extracción masiva sin pruebas de regresión por flujo. |
| BEL-087 | Parcial | Release mantiene R8 y resource shrinking. Las reglas conservan la superficie Java de WebRTC, métodos JNI y atributos de reflexión. La CI incorpora compilación Release minificada; falta verificar el resultado y probar el APK minificado en dispositivo. |

## Controles incorporados

- Pull Requests hacia `main` ejecutan la misma workflow de compilación y pruebas.
- Dependency Review rechaza cambios que incorporen vulnerabilidades de severidad moderada o superior, cuando GitHub Dependency Graph puede resolver el diff.
- OSV-Scanner ejecuta un escaneo recursivo del repositorio en los eventos configurados y un escaneo programado semanal. La detección de dependencias transitivas de Gradle depende de que existan lockfiles reconocibles; el SBOM CycloneDX complementa el inventario resuelto por Gradle.
- Dependabot revisa semanalmente dependencias Gradle y GitHub Actions.
- CycloneDX genera el SBOM agregado desde las dependencias resueltas por Gradle y CI lo conserva como artefacto.
- CI compila la variante Release con minificación y reducción de recursos para detectar fallos de configuración R8.

## Protección obligatoria de main

En GitHub, abrir Settings → Branches (o Rulesets) y exigir para `main`:
1. Require a pull request before merging.
2. Require status checks to pass before merging.
3. Seleccionar el job `build` y `dependency-review` (y el check OSV si aparece como estado independiente).
4. Require branches to be up to date before merging.
5. Bloquear force pushes y eliminación de la rama.

La política no se considera activa hasta verla guardada en la configuración del repositorio. Los workflows por sí solos no impiden que alguien con permisos haga push directo a `main`.

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
- Ejecutar la nueva CI completa, incluyendo Dependency Review, OSV, SBOM y Release minificado.
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
