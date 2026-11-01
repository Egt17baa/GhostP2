# GhostP2 · Desconectar al salir de la app — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nueva opción en Ajustes, «Desconectar al salir de la app». Si la app queda en segundo
plano más del plazo elegido, se para el motor y Tor, se borran las conversaciones de memoria y
desaparece la notificación.

**Architecture:** Un `AutoDisconnect` en el `AppContainer`, hermano de `AppLock` y alimentado
igual. Recibe `onStart`/`onStop(changingConfigurations)` desde `MainActivity`. Al parar arma un
temporizador en `container.scope`. Al volver lo cancela y, si el plazo ya venció (reloj
`elapsedRealtime`), desconecta antes de nada. La desconexión reutiliza el camino de
«Desconectar»: parar `GhostService` y `container.stop()`.

**Tech Stack:** el de los planes 2 y 3 (Kotlin, coroutines, Compose, Robolectric con JUnit4).

**Spec:** `docs/superpowers/specs/2026-11-01-ghostp2-desconexion-al-salir-design.md`

## Global Constraints

- Valores permitidos: `AppSettings.DISCONNECT_MINUTES = listOf(0, 2, 5, 15)`. 0 = «Nunca», que
  es el valor por defecto. `sanitized()` lleva cualquier otro valor a 0.
- Textos en `strings.xml`, en español:
  - título «Desconectar al salir de la app»;
  - opciones «Nunca», «A los 2 minutos», «A los 5 minutos», «A los 15 minutos»;
  - ayuda: «Al pasar ese tiempo fuera de la app se cierra Tor y se borran las conversaciones.
    No recibirás mensajes hasta que vuelvas a activar GhostP2.»
- Formato 2 (R21): campo nuevo con valor por defecto. Los archivos sin él se leen con 0.
- Rotar el móvil (`isChangingConfigurations`) no arma el temporizador.
- Sin `onStop` de la actividad en esta ejecución no hay temporizador. Es el caso del arranque al
  encender (R20) y del reinicio STICKY.
- Rethrow de `CancellationException`. Nada de logs.

## Review Focus

1. El usuario vuelve justo cuando vence el plazo: no queda a medias (motor parado, pero el
   servicio con la notificación «activo» sigue). → `expiryStopsServiceAndEngine`.
2. Volver antes del plazo con la pantalla bloqueada: se cancela y nada se corta. →
   `returningCancels`.
3. Android retrasa el temporizador en reposo: al volver ya vencido se desconecta, aunque el
   temporizador no haya disparado. → `expiredOnReturnDisconnects`.
4. Cambiar el ajuste a «Nunca» mientras hay un temporizador armado: se cancela. →
   `changingToNeverCancelsArmedTimer`.
5. Pasar a «Activar» de nuevo tras el corte funciona igual que tras «Desconectar». →
   `activateAfterAutoDisconnectWorks`.

---

### Task 1: Desconectar al salir (ajuste, temporizador, Ajustes, docs)

**Files:**
- Modify: `core/data/src/main/kotlin/org/ghostp2/data/Settings.kt` (campo, lista, `sanitized`), `StoredModels.kt` (campo almacenado, si los ajustes se guardan campo a campo)
- Create: `app/src/main/kotlin/org/ghostp2/app/lock/AutoDisconnect.kt`
- Modify: `app/src/main/kotlin/org/ghostp2/app/GhostApp.kt` (crear y alimentar `autoDisconnect` como `lock`), `MainActivity.kt` (`onStart`/`onStop`), `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `res/values/strings.xml`
- Modify: `docs/PROTOCOLO.md` (§14, regla R29), `docs/PRUEBA-MANUAL.md`, `README.md`
- Test: `core/data/src/test/kotlin/org/ghostp2/data/SettingsRepositoryTest.kt`, `app/src/test/kotlin/org/ghostp2/app/lock/AutoDisconnectTest.kt`, `app/src/test/kotlin/org/ghostp2/app/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- `AppSettings.disconnectAfterMinutes: Int = 0` y `AppSettings.DISCONNECT_MINUTES`.
- ```kotlin
  class AutoDisconnect(
      private val scope: CoroutineScope,
      private val clock: () -> Long = SystemClock::elapsedRealtime,
      private val disconnect: () -> Unit,
  ) {
      fun configure(minutes: Int)                     // 0 = desactivado; cancela un temporizador armado si pasa a 0
      fun onStop(changingConfigurations: Boolean)     // arma (si minutes > 0 y no es cambio de configuración)
      fun onStart()                                   // cancela; si ya venció, llama a disconnect() una vez
  }
  ```
  El temporizador hace `delay` en `scope` y llama a `disconnect()` como mucho una vez por salida.
- `AppContainer.autoDisconnect`. Se configura desde `settings.settings` junto a
  `lock.configure`. Su `disconnect` es `appContext.stopService(GhostService.stopIntent(appContext))`
  seguido de `stop()`.

- [ ] **Step 1: Pruebas que fallan**
  - `SettingsRepositoryTest`:
    - `disconnectMinutesDefaultsToNeverAndSanitizes`: el valor por defecto es 0; 7 se sanea a 0.
    - `format2FileWithoutDisconnectFieldReadsNever`.
  - `AutoDisconnectTest`, con reloj y `TestScope` inyectados:
    - `expiryDisconnects`: con 2 min, `onStop(false)` y avanzar 2 min llama a disconnect una vez.
    - `returningCancels`: `onStop`, avanzar 1 min y `onStart()` no llama, ni siquiera tras otros 5 min.
    - `expiredOnReturnDisconnects`: el temporizador no avanza (scope pausado), el reloj marca +3 min y `onStart()` llama una vez.
    - `configurationChangeDoesNotArm`: `onStop(true)` y avanzar 20 min no llama.
    - `neverDoesNothing`.
    - `changingToNeverCancelsArmedTimer`.
    - `onlyOncePerExit`: el temporizador vence y un `onStart` posterior no vuelve a llamar.
    - `noStopNoTimer`: sin `onStop`, `onStart` no llama nunca (arranque al encender).
  - Integración, en `GhostServiceTest` o en un test del contenedor:
    - `expiryStopsServiceAndEngine`: motor Online con servicio activo; al vencer, motor Stopped, servicio parado, conversaciones vacías y ninguna notificación fija.
    - `activateAfterAutoDisconnectWorks`.
  - `SettingsScreenTest.disconnectOptionPersists`: muestra las 4 opciones con «Nunca» marcada; elegir «A los 5 minutos» guarda 5.
- [ ] **Step 2:** `./gradlew :core:data:test :app:testDebugUnitTest` → FALLA.
- [ ] **Step 3: Implementar.**
  - Campo y lista en `AppSettings` (y en el almacenado si hace falta).
  - `AutoDisconnect` y su cableado en `AppContainer`.
  - `MainActivity.onStart`/`onStop` llaman también a `container.autoDisconnect`.
  - Sección en Ajustes con radio, como «Bloquear tras», con la ayuda.
  - `SettingsViewModel.setDisconnectAfter(minutes)` ignora valores fuera de la lista.
- [ ] **Step 4: Documentación.**
  - PROTOCOLO §14: R29.
  - PRUEBA-MANUAL: pasos con 2 min; salir y volver al 1 min; salir 3 min; pantalla apagada; arranque al encender sin abrir la app.
  - README: una línea en Ajustes.
- [ ] **Step 5:** `./gradlew test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease` → PASA, 0 avisos de lint.
- [ ] **Step 6:** Commit `feat(app): desconectar al salir de la app (R29)`.
