# Pruebas de Android TV

## Alcance

Android TV es el único destino soportado. El manifiesto principal exige Leanback, no exige pantalla táctil y expone únicamente `MainTvActivity`. Los manifiestos móvil/universal se han retirado. Parte del código móvil antiguo sigue compilándose porque utilidades compartidas todavía lo referencian; no tiene launcher ni APK propio. Su eliminación física corresponde a una refactorización posterior, no a esta puerta de calidad.

## Capas

| Capa | Comando | Qué comprueba |
| --- | --- | --- |
| Configuración | `./gradlew -p gradle/build-config-tests check --no-configuration-cache` | Solo TV, precedencia, valores opcionales, escaping y manifiesto Leanback. No necesita SDK ni credenciales. |
| Unidad | `./gradlew :app:testDebugUnitTest` | Reanudación, límites de seek, finalización, siguiente episodio, actualización TV, subtítulos y parser M3U con fixtures `.invalid`. |
| Compilación | `./gradlew :app:assembleDebug` | Un único APK TV sin credenciales de servicios. |
| Instrumentación | `./gradlew :app:connectedDebugAndroidTest` | Room/SQLite real, lanzamiento TV, foco D-pad en proveedores y teclas del `PlayerTvView` real con un jugador simulado. |

Las pruebas de instrumentación desactivan la comprobación de actualizaciones antes de abrir la actividad y no seleccionan un proveedor de streaming. El jugador simulado no abre fuentes de red. Los logos de proveedores pueden solicitar imágenes, pero las assertions no dependen de que se descarguen.

## Emulador local

Usa Android Studio Device Manager para crear un **Android TV 1080p, API 29, x86_64**, o herramientas SDK equivalentes. En Linux necesitas acceso a KVM. Con el AVD TV arrancado:

```bash
./gradlew :app:connectedDebugAndroidTest
```

CI usa `ReactiveCircus/android-emulator-runner@v2.38.0`, imagen `android-tv`, perfil `tv_1080p`, sin snapshots ni animaciones. Los informes de unidad e instrumentación se conservan como artifacts durante siete días, también cuando falla una prueba.

## Qué no afirman estas pruebas

- No validan disponibilidad, legalidad ni calidad de proveedores externos.
- No sustituyen pruebas de códecs, HDMI, audio, DRM o mandos físicos en hardware real.
- No firman ni publican una release durante CI normal.
- Un emulador API 29 no demuestra compatibilidad con todas las API soportadas; es la primera línea de regresión, no una matriz completa de dispositivos.

## Publicación

La release reutiliza el workflow de calidad antes de firmar. Solo publica `streamflix-<tag>-tv.apk`, el único asset que acepta el actualizador TV. Los secretos de firma siguen siendo obligatorios para publicar, nunca para las pruebas.
