# GhostP2 · Plan 3: Interfaz (Compose, QR, fotos, bloqueo, ajustes y entrega) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sustituir la pantalla de diagnóstico por la interfaz definitiva en Compose (bienvenida, contactos, añadir contacto con QR, detalle con número de seguridad, chat de texto y fotos, ajustes y bloqueo), con los pocos cambios de motor, datos y Tor que necesita, y publicar APK de versión desde CI.

**Architecture:** MVVM sobre el único `AppContainer` del proceso: cada pantalla es un `@Composable` sin estado (recibe un `UiState` y callbacks) y su `ViewModel` combina los `StateFlow` del motor (`status`, `presence`, `deleting`, `conversations`), los repositorios y los ajustes. Una raíz (`GhostRoot`) decide entre almacenamiento ilegible, carga, bloqueo, bienvenida y el `NavHost`. Las fotos pasan por un codificador JPEG, una cola de una foto en vuelo y una caché de bitmaps propia de la interfaz; nada toca el disco.

**Tech Stack:** lo del plan 2 (AGP 8.13.2, compileSdk/targetSdk 36, Kotlin 2.4.21, Compose BOM 2026.06.01 → Compose UI 1.11.4 y Material 3 1.4.0, lifecycle 2.10.0, Robolectric 4.17 con JUnit4) más: navigation-compose 2.9.8, lifecycle-viewmodel-compose 2.10.0, `material-icons-core` (del BOM), `ui-test-junit4` y `ui-test-manifest` (del BOM), androidx.biometric 1.1.0, androidx.fragment 1.8.9, CameraX 1.6.2 (`camera-camera2`, `camera-lifecycle`, `camera-view`), ZXing `com.google.zxing:core` 3.5.4, androidx.exifinterface 1.4.2. Comprobado en los metadatos AAR: ninguna exige más de compileSdk 36 / AGP 8.9.1 (navigation 2.10.x y lifecycle 2.11 exigen compileSdk 37 / AGP 9.1: descartadas, como en R12).

**Spec:** `docs/superpowers/specs/2026-10-31-ghostp2-android-design.md` (§2, §6.4 paso 5, §6.6, §6.7, §6.8, §6.9, §6.10, §7, §8, §9). Reglas vigentes: `docs/PROTOCOLO.md` §14–§15 y "Decisiones durante la ejecución" (R9–R19) del plan 2.

## Global Constraints

- Paquetes nuevos bajo `org.ghostp2.app` (`.ui`, `.ui.theme`, `.ui.contacts`, `.ui.pairing`, `.ui.contact`, `.ui.chat`, `.ui.settings`, `.ui.onboarding`, `.photo`, `.lock`).
- Textos visibles en español, todos en `res/values/strings.xml`; comentarios en español.
- Sin Google Play Services, Firebase, ML Kit ni librerías propietarias; licencia Apache-2.0. CI lo comprueba (Task 15).
- Mensajes, fotos, borradores y claves **nunca** en disco ni en logs. Prohibido `rememberSaveable`, `SavedStateHandle` y `rememberTextFieldState` con contenido: el `Bundle` guardado lo escribe el sistema en disco. En rutas de navegación solo ids de contacto o de elemento.
- Todos los campos de texto son `GhostTextField` (Task 4): `IME_FLAG_NO_PERSONALIZED_LEARNING` y sin autocorrección.
- `FLAG_SECURE` en la ventana de la actividad; diálogos y menús con la `SecureFlagPolicy.Inherit` por defecto (no cambiarla).
- Un único `TorTransport`, un único almacenamiento y un único `MessengerEngine` por proceso, los del `AppContainer`; ningún `ViewModel`, receptor ni servicio crea los suyos.
- La interfaz solo envía con el motor `Online` y a contactos guardados (R15). Sin sesión, el envío se hace igual y queda descartado (spec §6.7); nunca hay cola de entrega diferida.
- Fotos: Photo Picker del sistema (`PickVisualMedia`, solo imágenes, sin permiso de galería); recodificación JPEG (sin EXIF), lado mayor por defecto 2048 px (ajustes: 1280, 2048, 3072), máximo `PeerSession.MAX_PHOTO_BYTES` = 3 145 728 bytes; una sola foto en vuelo y como mucho 3 esperando.
- Notificaciones sin contenido; con "Ocultar el nombre" ni siquiera el nombre.
- Pruebas de módulos Android: JUnit4 + Robolectric (`sdk=35`, R11), Compose con `createComposeRule()` y `@Config(application = TestGhostApp::class)`; bitmaps reales con `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
- Textos fijados por la spec: "Conectando a la red Tor…", "enviando…", "✓ entregado", "No entregado: tu contacto no está conectado. Descartado", "No entregado. Descartado", "GhostP2 activo · cifrado", "Nuevo mensaje de <nombre>", "<nombre> te ha eliminado de sus contactos".
- Reglas nuevas de este plan (se añaden a PROTOCOLO §14 en la Task 16):
  - **R20**: opción "Activar GhostP2 al encender el móvil", desactivada por defecto (spec §6.9); modifica R19.
  - **R21**: `ghostp2.bin` pasa al formato 2 (ajustes y borrados pendientes); se siguen leyendo archivos de formato 1.
  - **R22**: el bloqueo de la app es una puerta de la interfaz (`BiometricPrompt` con huella o credencial del dispositivo); la clave de Keystore no exige autenticación.
  - **R23**: sin cámara para fotos: `TakePicture` exige escribir la foto en un archivo y `TakePicturePreview` solo da una miniatura; la cámara solo se usa para escanear el QR.

## Review Focus

1. El presupuesto de fotos (64 MiB) pone a cero los bytes de una foto mientras el chat o el visor la decodifican ⇒ se ve "Foto descartada para liberar memoria", nunca una imagen corrupta ni un cierre. → Task 12 `zeroedBytesGiveNull`, `zeroingBeforeHeaderCheckIsDetected`.
2. El sistema mata la app en segundo plano con un borrador escrito ⇒ nada del borrador ni de la conversación queda en el estado guardado. → Task 4 `noSaveableStateInSources`, Task 10 `draftIsNotSaveable`.
3. Con el bloqueo activado, el usuario quita el bloqueo de pantalla del móvil ⇒ GhostP2 no queda inaccesible: se desbloquea, desactiva su bloqueo y lo avisa. → Task 13 `missingDeviceCredentialDisablesLock`.
4. Al abrir la app con bloqueo, mientras los ajustes aún se leen, no se ve ni un instante la lista de contactos. → Task 13 `nothingSensitiveWhileSettingsLoad`.
5. Se escanea un QR que no es de GhostP2, o se pega una invitación con espacios, saltos de línea o mayúsculas ⇒ el QR ajeno se ignora sin conectar a nada y la invitación válida se acepta. → Task 8 `foreignQrIsIgnored`, Task 7 `pastedInvitationIsTrimmed`.

## Pendientes del plan 2: asignación

| Pendiente | Destino |
|---|---|
| Reinicio de Tor tras "Empezar de cero" | Task 3 + PRUEBA-MANUAL (Task 16) |
| Persistir los borrados de contacto pendientes | Task 1 (motor) + Task 2 (datos) |
| Teclado de incógnito en todos los campos | Task 4 |
| API del número de seguridad y fallos al guardar la identidad | Task 1 (API) + Task 4 (interfaz) |
| Tema Material 3 de la app | Task 4 |
| Subir el byte de formato al añadir campos | Task 2 (R21) |
| KDoc de `forceReset` (borrado de la clave de mejor esfuerzo) | Task 2 |
| `GhostService.onDestroy` sin `foreground = false`; probar el relanzamiento en el reinicio STICKY | Task 14 |
| Fotos puestas a cero sin avisar: decodificar una vez, tolerar ceros, cola pequeña | Tasks 11 y 12 |
| Estado de Tor obsoleto entre ejecuciones de `KmpTorTransport` | Aplazado: no se reproduce sin Tor real; su efecto es un `Error` con "Reintentar", que la interfaz ya cubre |
| Carrera de `activeInvitationOnion` | Aplazado: la comprobación por identidad propia ya la cubre |
| Orden de los deshacer en emparejamientos simultáneos con el mismo contacto | Aplazado: exige dos invitaciones cruzadas a la vez con la misma persona; la interfaz solo tiene una invitación activa |
| Cerrojo bloqueante de `FakeTor` | Aplazado: solo afecta a pruebas |
| `stop()` no interrumpible si `tor.stop()` se cuelga | Aplazado: `KmpTorTransport` ya acota su parada |
| Sesión preferida obsoleta ~60 s | Aplazado: limitación documentada (§14.3); la interfaz muestra "conectando…" |
| `incomingInFlight` global | Aplazado: solo llegan quienes tienen autorización de cliente |
| `deliverUnpair` cancela sin esperar | Aplazado: inofensivo |
| Una sola recomprobación de Keystore a los 300 ms | Aplazado: la pantalla de datos ilegibles ya ofrece "Reintentar", que solo vuelve a leer |
| Prueba de parar el motor a mitad de una foto | Aplazado: solo calidad de una prueba del motor |
| `Connections.wrap` si `onSessionEvent` lanza | Aplazado: `onSessionEvent` solo ejecuta `Conversations.onEvent` y un `emit` con búfer; la interfaz no puede provocarlo |

Aplazados también de la spec §8: detekt y ktlint (el código actual no tiene base de referencia y la spec no fija reglas; CI pasa Android Lint) y la prueba instrumentada de Tor (necesita emulador en CI; la cubre PRUEBA-MANUAL).

---

### Task 1: Motor — número de seguridad, error de almacenamiento al arrancar y borrados pendientes

**Files:**
- Modify: `core/engine/src/main/kotlin/org/ghostp2/engine/MessengerEngine.kt`, `Repositories.kt`
- Modify (testFixtures): `core/engine/src/testFixtures/kotlin/org/ghostp2/engine/InMemoryRepositories.kt`, `EngineHarness.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/SafetyNumberApiTest.kt`, `PendingDeletionTest.kt`, `EngineStartTest.kt`

**Interfaces:**
- Consumes: `SafetyNumber.compute(a, b)` (núcleo); `deleteContact`, `bootstrap`, `Connections.start()` actuales.
- Produces (en `org.ghostp2.engine`):
  - `enum class EngineErrorKind { TOR, STORAGE }` y `EngineStatus.Error(val message: String, val kind: EngineErrorKind = EngineErrorKind.TOR)`. `TOR` = todo lo que no es del almacenamiento.
  - En `MessengerEngine`: `suspend fun safetyNumber(contactId: String): String` — 30 dígitos en 6 grupos, igual al `safetyNumber` de `Paired`; funciona con el motor parado; `NoSuchElementException` si el id no es un contacto guardado. Rellena con ceros las claves privadas de la identidad leída.
  - `interface DeletionJournal { suspend fun pending(): Set<String>; suspend fun add(contactId: String); suspend fun remove(contactId: String) }` y `object NoDeletionJournal : DeletionJournal` (no guarda nada).
  - Constructor de `MessengerEngine` con un último parámetro nuevo `deletions: DeletionJournal = NoDeletionJournal` (las llamadas posicionales actuales no cambian).
  - testFixtures: `class InMemoryDeletionJournal : DeletionJournal`; `EngineHarness` expone `journal(i)` y pasa uno por motor.
- Reglas:
  - En `bootstrap`, toda llamada a los repositorios (`contacts.all()`, `identities.load()`, `createIdentity()`, y el `IdentityRepository` que recibe `OnionPublisher`, envuelto en un delegado interno) que lance algo distinto de cancelación se relanza como `internal class StorageStartException(cause)`. `start()` la traduce a `Error("No se pudieron leer o guardar tus datos cifrados", EngineErrorKind.STORAGE)`.
  - `deleteContact`: tras leer el contacto, `deletions.add(id)` (si falla, el borrado sigue solo en memoria); tras `removeLocally` (también si se conservó un re-emparejamiento), `deletions.remove(id)`.
  - Al arrancar, justo después de `c.start()`: por cada id de `deletions.pending()` (un fallo al leerlo ⇒ ninguno), si es contacto guardado se lanza `deleteContact(id)` en el scope del motor (`CoroutineStart.UNDISPATCHED`); si no, `deletions.remove(id)`.

- [ ] **Step 1: Pruebas que fallan:**
  - `SafetyNumberApiTest.safetyNumberMatchesBothSides` — tras emparejar, `A.safetyNumber(idB) == B.safetyNumber(idA) == Paired.safetyNumber`, y casa con `Regex("""\d{5}( \d{5}){5}""")`.
  - `SafetyNumberApiTest.safetyNumberWorksWhileStopped` y `unknownContactThrows` (`NoSuchElementException`).
  - `EngineStartTest.identitySaveFailureIsStorageError` — repositorio de identidad cuyo `save` lanza `IOException` ⇒ `status == Error(…, kind = STORAGE)` y Tor nunca arrancado.
  - `EngineStartTest.torFailureIsTorError` — `FakeTor` que falla al arrancar ⇒ `kind == TOR`.
  - `PendingDeletionTest.interruptedDeletionResumesOnStart` — A y B emparejados; se para A; `journal(0).add(idB)`; se arranca A ⇒ `idB` aparece en `deleting`, B emite `ContactUnpairedYou`, A ya no tiene a B y el diario queda vacío.
  - `PendingDeletionTest.journalIsClearedAfterDelete` y `staleJournalEntryIsDropped` (id sin contacto ⇒ se quita al arrancar sin borrar nada).
- [ ] **Step 2:** `./gradlew :core:engine:test` → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:engine:test` → PASA (y `PendingDeletionTest` 3 veces).
- [ ] **Step 5:** Commit `feat(engine): número de seguridad, error de almacenamiento al arrancar y borrados pendientes`.

### Task 2: `:core:data` — formato 2 con ajustes y borrados pendientes

**Files:**
- Create: `core/data/src/main/kotlin/org/ghostp2/data/Settings.kt`
- Modify: `core/data/src/main/kotlin/org/ghostp2/data/StoredModels.kt`, `EncryptedStore.kt`, `DataStoreRepositories.kt`
- Modify: `app/src/main/kotlin/org/ghostp2/app/GhostApp.kt` (`AppStorage`, `StoredAppStorage`, `AppContainer`), `app/src/test/kotlin/org/ghostp2/app/TestSupport.kt`
- Test: `core/data/src/test/kotlin/org/ghostp2/data/SettingsRepositoryTest.kt`, `EncryptedStoreTest.kt`

**Interfaces:**
- Consumes: `DeletionJournal` (Task 1).
- Produces (en `org.ghostp2.data`):
  - `data class AppSettings(val onboardingDone: Boolean = false, val lockEnabled: Boolean = false, val lockAfterMinutes: Int = 1, val hideNotificationNames: Boolean = false, val maxPhotoSidePx: Int = 2048, val startOnBoot: Boolean = false)` con `companion object { val LOCK_MINUTES = listOf(0, 1, 5, 15); val PHOTO_SIDES = listOf(1280, 2048, 3072) }` y `fun sanitized(): AppSettings` (un valor fuera de esas listas vuelve al de por defecto).
  - `interface SettingsRepository { val settings: Flow<AppSettings>; suspend fun get(): AppSettings; suspend fun update(transform: (AppSettings) -> AppSettings); suspend fun wipe() }` — ambos lecturas devuelven `sanitized()`.
  - `StoredData` con `settings: StoredSettings? = null` y `pendingDeletions: List<String> = emptyList()`; `isEmpty` exige además ajustes por defecto y ningún borrado pendiente.
  - `class StoredSettingsRepository(store) : SettingsRepository`, `class StoredDeletionJournal(store) : DeletionJournal`; `StoredRepositories` gana `val settings` y `val deletions`.
  - `EncryptedStore.FORMAT_VERSION = 2`; lee los formatos 1 y 2 (datos asociados = byte del archivo ‖ etiqueta), escribe siempre 2; otro byte ⇒ `StoreCorruptedException`.
  - `:app`: `AppStorage` gana `val settings: SettingsRepository` y `val deletions: DeletionJournal`; `AppContainer` expone `val settings: SettingsRepository` y crea el motor con `deletions = store.deletions`. `TestStorage` usa `InMemoryDeletionJournal` y un `InMemorySettingsRepository` nuevo de `TestSupport.kt`.
- Reglas: `settings.wipe()` y los `wipe()` existentes pasan por `updateOrReset`. KDoc de `StoredRepositories.forceReset`: el borrado de la clave de Keystore es de mejor esfuerzo.

- [ ] **Step 1: Pruebas que fallan:** `SettingsRepositoryTest.settingsRoundTrip`, `outOfRangeValuesAreSanitized` (`lockAfterMinutes = 7` ⇒ 1, `maxPhotoSidePx = 999` ⇒ 2048), `nonDefaultSettingsKeepTheFile` (sin identidad ni contactos el archivo sigue existiendo), `defaultSettingsAndNoDataDeleteTheFile`, `pendingDeletionsRoundTrip`, `settingsFlowEmitsUpdates`; `EncryptedStoreTest.readsFormat1AndRewritesFormat2` (archivo de formato 1 escrito a mano con la misma AEAD ⇒ se lee; tras `update`, primer byte `2`), `unknownFormatIsCorrupted` (byte `3`).
- [ ] **Step 2:** `./gradlew :core:data:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar `:core:data` y el cableado de `:app`.
- [ ] **Step 4:** `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(data): formato 2 con ajustes y borrados pendientes`.

### Task 3: `:core:tor` — Tor vuelve a arrancar tras borrar su estado

**Files:**
- Modify: `core/tor/src/main/kotlin/org/ghostp2/tor/KmpTorTransport.kt`
- Test: `core/tor/src/test/kotlin/org/ghostp2/tor/KmpTorTransportTest.kt`

**Interfaces:**
- Produces: `wipeState()` descarta además el `TorDriver` en caché (`driver = null`), de modo que el siguiente `start()` llama otra vez a `driverFactory` con `torDir` recién vacío.
- Reglas: antes de implementar, leer en las fuentes de kmp-tor 2.6.0 si `TorRuntime.Environment.Builder` devuelve la misma instancia para el mismo `workDirectory` y si los recursos (geoip, torrc) se reinstalan en cada `startDaemon`; si el entorno queda en caché y no reinstala, provocar la reinstalación con la API que exista y anotarlo en PROTOCOLO §14.9 (Task 16). No volver a suscribir observadores con la misma etiqueta sobre el mismo runtime.

- [ ] **Step 1: Pruebas que fallan:** `startAfterWipeStateUsesAFreshDriver` (con `driverFactory` que cuenta: tras `start`/`Ready`/`wipeState`/`start`, la fábrica se llamó 2 veces y el segundo driver recibió `startDaemon`), `wipeStateDuringStartingStopsTheRun` (`wipeState` mientras `Starting` ⇒ `Off`, directorio borrado, el primer driver recibió `stopDaemon`).
- [ ] **Step 2:** `./gradlew :core:tor:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:tor:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `fix(tor): driver nuevo tras borrar el estado de Tor`.

### Task 4: Base de la interfaz — dependencias, tema, campos de incógnito, raíz y errores

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/res/values/themes.xml`, `strings.xml`, `MainActivity.kt`
- Create: `app/src/main/res/values-night/themes.xml`, `app/src/main/kotlin/org/ghostp2/app/ui/theme/Theme.kt`, `ui/GhostTextField.kt`, `ui/GhostRoot.kt`, `ui/Routes.kt`, `ui/StorageErrorScreen.kt`, `ui/EngineBanner.kt`, `ui/Clipboard.kt`
- Delete: `app/src/main/kotlin/org/ghostp2/app/DiagnosticScreen.kt` (y sus cadenas `diag_*` que no se reutilicen)
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/GhostTextFieldTest.kt`, `SourceRulesTest.kt`, `GhostRootTest.kt`, `EngineBannerTest.kt`

**Interfaces:**
- Consumes: `AppContainer.storage/checkStorage/wipeCorrupted/forceReset/start/startFailed/settings` (plan 2, Task 2); `EngineStatus.Error.kind` (Task 1).
- Produces:
  - Catálogo: alias `androidx-navigation-compose`, `androidx-lifecycle-viewmodel-compose`, `androidx-compose-material-icons-core`, `androidx-compose-ui-test-junit4`, `androidx-compose-ui-test-manifest`, `androidx-biometric`, `androidx-fragment`, `androidx-camera-camera2`, `androidx-camera-lifecycle`, `androidx-camera-view`, `zxing-core`, `androidx-exifinterface` con las versiones del Tech Stack (todos se añaden aquí; `ui-test-manifest` como `debugImplementation`, `ui-test-junit4` como `testImplementation`).
  - `@Composable fun GhostTheme(content: @Composable () -> Unit)` — `MaterialTheme` de Material 3 con esquemas claro y oscuro según el sistema y color dinámico en API 31+. `Theme.GhostP2` (XML) conserva el padre de plataforma `android:Theme.Material.Light.NoActionBar` (sin Material Components) y solo añade su variante `values-night` para que el fondo de arranque no destelle en blanco.
  - `@Composable fun GhostTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null, singleLine: Boolean = false, enabled: Boolean = true, imeAction: ImeAction = ImeAction.Default, onImeAction: () -> Unit = {})` — `OutlinedTextField` dentro de `InterceptPlatformTextInput`, `KeyboardOptions(autoCorrectEnabled = false)`.
  - `internal class NoLearningRequest(delegate: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest` — tras `delegate.createInputConnection(outAttributes)` añade `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` a `outAttributes.imeOptions`.
  - `fun copySensitive(context: Context, label: String, text: String)` (movida desde la pantalla de diagnóstico, con `EXTRA_IS_SENSITIVE` en API 33+).
  - `class ActivityActions(val activate: () -> Unit, val disconnect: () -> Unit, val requestBatteryExemption: () -> Unit, val requestNotifications: () -> Unit)`.
  - `@Composable fun GhostRoot(container: AppContainer, actions: ActivityActions)`: `StorageState.Unknown` ⇒ comprueba y muestra carga; `Corrupted`/`Unavailable`/`Failed` ⇒ `StorageErrorScreen` (mismos paneles, textos y confirmaciones que el plan 2: "Reintentar", "Empezar de cero", doble confirmación tras 3 fallos); ajustes sin leer ⇒ carga (sin nada más); después el `NavHost` con `Routes.CONTACTS` como inicio (las tareas siguientes añaden destinos y los pasos de bienvenida y bloqueo).
  - `object Routes { const val CONTACTS = "contactos"; const val ADD = "anadir"; const val NAME = "nombrar/{id}"; const val CONTACT = "contacto/{id}"; const val CHAT = "chat/{id}"; const val PHOTO = "foto/{id}/{item}"; const val SETTINGS = "ajustes"; fun name(id: String); fun contact(id: String); fun chat(id: String); fun photo(id: String, item: String) }`.
  - `@Composable fun EngineBanner(status: EngineStatus, startFailed: Boolean, onActivate: () -> Unit, onDisconnect: () -> Unit)`: `Stopped` ⇒ "GhostP2 está desactivado" + "Activar"; `StartingTor(p)` ⇒ "Conectando a la red Tor… p %" + "Desconectar"; `Online` ⇒ nada; `Error(kind = TOR)` ⇒ "Sin conexión con Tor" + "Reintentar" + "Desconectar"; `Error(kind = STORAGE)` ⇒ "No se pudieron leer o guardar tus datos cifrados" + "Reintentar" (el `onActivate` de `GhostRoot` llama antes a `container.checkStorage()`, y si el almacenamiento falla manda `StorageErrorScreen`); `startFailed` ⇒ "Error inesperado" + "Reintentar".
- `MainActivity`: `setContent { GhostTheme { GhostRoot(container, actions) } }`; conserva `FLAG_SECURE`, permiso de notificaciones, exención de batería y `onAppOpened()`.

- [ ] **Step 1: Pruebas que fallan:**
  - `GhostTextFieldTest.noLearningRequestSetsFlag` — delegado falso; `EditorInfo().imeOptions` contiene `IME_FLAG_NO_PERSONALIZED_LEARNING` y conserva los bits que puso el delegado.
  - `SourceRulesTest.allTextFieldsAreGhostTextField` — en `app/src/main/kotlin`, fuera de `GhostTextField.kt`, ninguna coincidencia de `Regex("""\b(Outlined)?TextField\(|BasicTextField\(""")`.
  - `SourceRulesTest.noSaveableStateInSources` — ninguna coincidencia de `rememberSaveable`, `SavedStateHandle` ni `rememberTextFieldState` en `app/src/main/kotlin`.
  - `GhostRootTest.corruptedStorageShowsRetryAndReset` (`TestStorage.failure = StoreCorruptedException(...)` ⇒ "No se pudieron leer tus datos cifrados", "Reintentar", "Empezar de cero"), `resetOfferedAfterThreeUnavailable`, `identitySaveFailureShowsStorageBanner` (`TestStorage` con un fallo solo en `save` ⇒ tras arrancar, texto "No se pudieron leer o guardar tus datos cifrados" y "Reintentar").
  - `EngineBannerTest` — un caso por estado de arriba con sus textos y botones exactos; `Online` no muestra nada.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar (añadir a `TestStorage` el fallo solo de `save`).
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASA.
- [ ] **Step 5:** Commit `feat(app): tema Material 3, campos de incógnito y raíz con estados de error`.

### Task 5: Bienvenida (activación, notificaciones y batería)

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/onboarding/OnboardingScreen.kt`, `Onboarding.kt`
- Modify: `BatteryOptimization.kt`, `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/onboarding/OnboardingTest.kt`, `OnboardingScreenTest.kt`

**Interfaces:**
- Consumes: `AppSettings.onboardingDone`, `SettingsRepository.update` (Task 2), `ActivityActions` (Task 4).
- Produces:
  - `enum class OnboardingStep { WELCOME, NOTIFICATIONS, BATTERY }`; `fun onboardingSteps(sdkInt: Int, notificationsGranted: Boolean, batteryIgnoring: Boolean): List<OnboardingStep>` — `WELCOME` siempre; `NOTIFICATIONS` solo en API 33+ sin permiso; `BATTERY` solo sin exención.
  - `@StringRes fun BatteryOptimization.manufacturerHint(manufacturer: String): Int` — sin distinguir mayúsculas: xiaomi/redmi/poco ⇒ `battery_hint_xiaomi`; samsung ⇒ `battery_hint_samsung`; huawei/honor ⇒ `battery_hint_huawei`; oneplus/oppo/realme ⇒ `battery_hint_oppo`; cualquier otro ⇒ `battery_hint_generic`. Cada texto dice dónde desactivar el ahorro de energía en ese fabricante.
  - `@Composable fun OnboardingScreen(steps: List<OnboardingStep>, batteryIgnoring: Boolean, onRequestNotifications: () -> Unit, onRequestBattery: () -> Unit, onFinish: () -> Unit)`.
- Reglas: `WELCOME` explica en pocas líneas: sin servidores ni cuentas; mensajes solo en memoria; si el contacto no está conectado se descarta; ambos deben tener GhostP2 activo; conectar por Tor tarda. Botón final "Activar GhostP2": `settings.update { it.copy(onboardingDone = true) }` y después `actions.activate()`. `GhostRoot` muestra la bienvenida mientras `onboardingDone == false`.

- [ ] **Step 1: Pruebas que fallan:** `OnboardingTest.notificationsStepOnlyOnApi33WithoutPermission`, `batteryStepSkippedWhenExempt`, `manufacturerHints` ("Xiaomi" ⇒ xiaomi, "samsung" ⇒ samsung, "HONOR" ⇒ huawei, "realme" ⇒ oppo, "Google" ⇒ genérico); `OnboardingScreenTest.welcomeExplainsDiscard` (aparece "se descarta"), `finishActivatesAndMarksDone` (pulsar "Activar GhostP2" en el último paso llama a `onFinish`); `GhostRootTest.onboardingShownUntilDone`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'org.ghostp2.app.ui.*'` → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** → PASA.
- [ ] **Step 5:** Commit `feat(app): bienvenida con permisos y ayuda de batería por fabricante`.

### Task 6: Lista de contactos

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/contacts/ContactsViewModel.kt`, `ContactsScreen.kt`
- Modify: `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/contacts/ContactsViewModelTest.kt`, `ContactsScreenTest.kt`

**Interfaces:**
- Consumes: `container.contacts.contacts`, `engine.presence`, `engine.deleting`, `engine.status`, `container.startFailed`; `EngineBanner` (Task 4).
- Produces:
  - `data class ContactRow(val id: String, val name: String, val presence: Presence?, val verified: Boolean, val identityChanged: Boolean, val deleting: Boolean)`
  - `fun contactRows(contacts: List<Contact>, presence: Map<String, Presence>, deleting: Set<String>): List<ContactRow>` (orden por nombre sin distinguir mayúsculas; `identityChanged` si el contacto es `IDENTITY_CHANGED` o la presencia lo es).
  - `sealed interface ContactsUiState { data object Loading; data object Failed; data class Loaded(val rows: List<ContactRow>) }`
  - `class ContactsViewModel(container: AppContainer) : ViewModel` con `val state: StateFlow<ContactsUiState>` (un fallo del flujo ⇒ `Failed`, nunca lista vacía) y `fun retry()`.
  - `@Composable fun ContactsScreen(state: ContactsUiState, banner: @Composable () -> Unit, onOpen: (ContactRow) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onRetry: () -> Unit)`.
- Reglas: fila = nombre, "en línea" / "conectando…" / "desconectado" (sin presencia con el motor parado: "desconectado"), marca "verificado"; `identityChanged` ⇒ "Identidad cambiada · vuelve a emparejar" y abre el detalle; `deleting` ⇒ "Borrando…" y no se puede pulsar; los demás abren el chat. Vacía ⇒ "Aún no tienes contactos" + "Añadir contacto". Barra superior: "Añadir contacto", "Ajustes" y, con el motor no parado, "Desconectar".

- [ ] **Step 1: Pruebas que fallan:** `ContactsViewModelTest.rowsFlagIdentityChangedAndDeleting` (sobre `contactRows`), `rowsAreSortedByName`, `pairedPeerShowsOnline` (motor de `TestGhostApp` emparejado con un `EngineHarness` ⇒ fila con `Presence.ONLINE`); `ContactsScreenTest.rowShowsPresenceAndVerified`, `identityChangedRowOpensDetail`, `deletingRowIsNotClickable`, `emptyStateOffersAddContact`, `failedStateOffersRetry`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar y poner `ContactsScreen` en `Routes.CONTACTS`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): lista de contactos con presencia y estados`.

### Task 7: Añadir contacto — QR, texto, pegar y nombrar

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/pairing/QrCode.kt`, `PairingViewModel.kt`, `NameContactViewModel.kt`, `AddContactScreen.kt`, `NameContactScreen.kt`
- Modify: `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/pairing/QrCodeTest.kt`, `PairingViewModelTest.kt`, `NameContactViewModelTest.kt`, `AddContactScreenTest.kt`

**Interfaces:**
- Consumes: `engine.createInvitation()`, `InvitationHandle`, `InvitationState`, `engine.acceptInvitation`, `AcceptResult`, `engine.safetyNumber` (Task 1), `engine.renameContact`, `engine.setVerified`, `MAX_CONTACT_NAME_CHARS`, `copySensitive` (Task 4).
- Produces:
  - `object QrCode { fun encode(text: String, sizePx: Int): BitMatrix; fun toImageBitmap(matrix: BitMatrix): ImageBitmap }` — ZXing `QRCodeWriter`, corrección `M`, margen 2.
  - `sealed interface AcceptUi { data object Idle; data object Working; data class Failed(val message: String); data class Paired(val contactId: String) }`
  - `class PairingViewModel(container: AppContainer) : ViewModel` con `val invitation: StateFlow<InvitationState?>`, `fun createInvitation()`, `fun cancelInvitation()`, `val accept: StateFlow<AcceptUi>`, `fun accept(text: String)` (recorta espacios), `val pairedContactId: StateFlow<String?>` (de `InvitationState.Paired` o `AcceptUi.Paired`). `onCleared` cancela la invitación.
  - `class NameContactViewModel(container: AppContainer, contactId: String) : ViewModel` con `val safetyNumber: StateFlow<String?>`, `fun save(name: String, verified: Boolean): Boolean` (vacío o más de `MAX_CONTACT_NAME_CHARS` ⇒ `false` y `error`), `val error: StateFlow<Int?>` (id de cadena).
  - `@Composable fun AddContactScreen(...)` con tres pestañas: "Mi código" (QR, "Caduca en mm:ss", texto seleccionable, "Copiar", "Compartir" con `ACTION_SEND`, "Crear otra" al caducar o fallar), "Escanear" (Task 8; aquí un hueco) y "Pegar" (`GhostTextField` + "Aceptar"). Sin motor `Online` ⇒ "Activa GhostP2 para añadir contactos" + "Activar".
  - `@Composable fun NameContactScreen(...)` — número de seguridad en 6 grupos, "Comparadlo por otro canal (en persona o por llamada)", campo de nombre, casilla "Hemos comprobado que coincide", "Guardar" y "Más tarde" (deja "Contacto nuevo"); después va al chat.
- Reglas: al emparejar se navega a `Routes.name(id)` y se quita `ADD` de la pila.

- [ ] **Step 1: Pruebas que fallan:** `QrCodeTest.encodeThenDecodeRoundTrips` (decodificar el `BitMatrix` con `QRCodeReader` sobre un `RGBLuminanceSource` ⇒ el mismo texto de invitación); `PairingViewModelTest.invitationShowsWaitingThenPairs` (otro motor de `EngineHarness` acepta el texto ⇒ `pairedContactId` no nulo), `pastedInvitationIsTrimmed` (texto válido con espacios y saltos de línea alrededor y en mayúsculas ⇒ `Paired`), `garbageFailsWithMessage` (empieza por "Invitación no válida"), `clearingTheViewModelCancelsTheInvitation` (`ViewModelStore().clear()` ⇒ `Cancelled` y servicio temporal retirado de `FakeTorNetwork.published`); `NameContactViewModelTest.saveRenamesAndVerifies`, `blankOrTooLongNameIsRejected`; `AddContactScreenTest.waitingShowsQrTextAndCountdown`, `notOnlineShowsActivate`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): añadir contacto con QR, texto y nombre tras emparejar`.

### Task 8: Escanear el QR (CameraX + ZXing)

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/pairing/QrScanner.kt`, `QrDecoding.kt`
- Modify: `AddContactScreen.kt`, `AndroidManifest.xml`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/pairing/QrDecodingTest.kt`, `QrScannerTest.kt`

**Interfaces:**
- Consumes: `QrCode.encode` (Task 7, para las pruebas), `PairingViewModel.accept` (Task 7).
- Produces:
  - `fun decodeQrLuminance(data: ByteArray, width: Int, height: Int, rowStride: Int): String?` — `PlanarYUVLuminanceSource` + `HybridBinarizer` + `QRCodeReader` con `TRY_HARDER`; `null` si no hay QR o está dañado.
  - `fun invitationOrNull(text: String): String?` — recorta; solo si empieza por `ghostp2:` (sin distinguir mayúsculas).
  - `class QrAnalyzer(onInvitation: (String) -> Unit) : ImageAnalysis.Analyzer` — copia el plano Y en un búfer reutilizado, decodifica, informa una sola vez y siempre cierra el `ImageProxy`.
  - `@Composable fun QrScanner(onInvitation: (String) -> Unit, onUsePaste: () -> Unit)` — pide `CAMERA` al mostrarse; denegado ⇒ "Sin permiso de cámara. Puedes pegar la invitación" + "Pegar"; concedido ⇒ `PreviewView` (`AndroidView`) con `ProcessCameraProvider`, cámara trasera, `ImageAnalysis` `STRATEGY_KEEP_ONLY_LATEST`, desvinculada al salir.
- Manifest: `<uses-permission android:name="android.permission.CAMERA" />` y `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`.

- [ ] **Step 1: Pruebas que fallan:** `QrDecodingTest.decodesInvitationFromLuminancePlane` (QR de una invitación pintado en un plano Y de 640×480 con `rowStride = 704` y márgenes ⇒ texto exacto), `noiseDecodesToNull`, `foreignQrIsIgnored` (`invitationOrNull("https://example.com") == null`; `"  GHOSTP2:abc…\n"` ⇒ recortado); `QrScannerTest.deniedPermissionOffersPaste` (Robolectric con `CAMERA` denegado ⇒ texto y botón "Pegar").
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar y poner `QrScanner` en la pestaña "Escanear" (al leer, `accept(texto)` y pestaña "Pegar" con el progreso).
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): escanear la invitación con la cámara`.

### Task 9: Detalle del contacto

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/contact/ContactDetailViewModel.kt`, `ContactDetailScreen.kt`
- Modify: `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/contact/ContactDetailViewModelTest.kt`, `ContactDetailScreenTest.kt`

**Interfaces:**
- Consumes: `engine.safetyNumber`, `renameContact`, `setVerified`, `clearConversation`, `presence`, `deleting`; `container.deleteContact(id): Deferred<UnpairDelivery>`.
- Produces:
  - `data class ContactDetailUiState(val name: String, val verified: Boolean, val identityChanged: Boolean, val presence: Presence?, val safetyNumber: String?, val deleting: Boolean)`; `ContactDetailViewModel(container, contactId)` con `val state: StateFlow<ContactDetailUiState?>` (`null` cuando el contacto ya no existe), `fun rename(name: String): Boolean`, `val renameError: StateFlow<Int?>`, `fun setVerified(v: Boolean)`, `fun clearChat()`, `fun delete()`, `val deleteResult: StateFlow<UnpairDelivery?>`.
- Reglas: número de seguridad en 6 grupos con fuente monoespaciada; "Marcar como verificado" / "Quitar verificación"; con identidad cambiada: "La identidad de <nombre> ha cambiado. Puede que haya reinstalado GhostP2 o que alguien intente suplantarle: volved a emparejar" + "Volver a emparejar" (va a `ADD`). Borrar: diálogo "¿Borrar a <nombre>?" con "Se le avisará si está conectado o en los próximos 2 minutos. Se borran sus claves y la conversación."; resultado "<nombre> ha recibido el aviso" o "No se pudo avisar a <nombre>; se ha borrado igualmente" y vuelta a la lista. "Borrar chat" con confirmación.

- [ ] **Step 1: Pruebas que fallan:** `ContactDetailViewModelTest.showsSafetyNumber`, `renameValidatesAndPersists` (vacío y 65 caracteres ⇒ `false`; válido ⇒ nombre guardado), `verifyToggles`, `deleteReportsDelivery` (par conectado ⇒ `DELIVERED` y `state == null`); `ContactDetailScreenTest.identityChangedShowsRepairAction`, `safetyNumberIsGrouped`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): detalle del contacto con número de seguridad, verificar, renombrar y borrar`.

### Task 10: Chat de texto

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/chat/ChatViewModel.kt`, `ChatScreen.kt`, `StatusLabels.kt`
- Modify: `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/chat/StatusLabelsTest.kt`, `ChatViewModelTest.kt`, `ChatScreenTest.kt`

**Interfaces:**
- Consumes: `engine.conversations`, `sendText`, `clearConversation`, `status`, `presence`; `PeerSession.MAX_TEXT_CHARS` = 8 000.
- Produces:
  - `data class StatusLabel(@StringRes val text: Int?, val struck: Boolean, val progress: Float?)`; `fun statusLabel(status: DeliveryStatus): StatusLabel` — `Received` ⇒ sin texto; `Sending(s, t)` ⇒ "enviando…" con `progress = s/t` solo si `t > 0`; `Delivered` ⇒ "✓ entregado"; `Failed(NOT_CONNECTED)` ⇒ "No entregado: tu contacto no está conectado. Descartado", tachado; `Failed(CONNECTION_LOST | NO_ACK)` ⇒ "No entregado. Descartado", tachado.
  - `data class ChatUiState(val name: String, val presence: Presence?, val engineOnline: Boolean, val identityChanged: Boolean, val items: List<ChatItem>)`; `ChatViewModel(container, contactId)` con `val state: StateFlow<ChatUiState?>`, `var draft: String` (`mutableStateOf`, solo en memoria del `ViewModel`), `fun send(): Boolean` (falso y borrador intacto si el motor no está `Online`, si está vacío o si supera 8 000 caracteres), `fun clear()`.
- Reglas: entrada deshabilitada sin motor `Online` ("Activa GhostP2 para enviar") o con identidad cambiada (aviso que lleva al detalle); contacto no `ONLINE` ⇒ aviso "Tu contacto no está conectado: lo que envíes se descartará", pero se puede enviar. Contador al pasar de 7 500 caracteres. Desplazamiento al último al llegar uno nuevo. Menú: "Ver contacto" y "Borrar chat" (confirmación "Se borrará de la memoria de este móvil").

- [ ] **Step 1: Pruebas que fallan:** `StatusLabelsTest.everyStatusHasItsLabel` (los cinco casos y `Sending(0, 0)` sin progreso); `ChatViewModelTest.sendWhileContactOfflineIsDiscarded` (par parado ⇒ elemento `Failed(NOT_CONNECTED)`), `sendWithEngineStoppedStoresNothing`, `tooLongTextKeepsDraft`, `deliveredToOnlinePeer`, `clearEmptiesConversation`, `draftIsNotSaveable` (tras `ViewModelStore().clear()`, un `ChatViewModel` nuevo del mismo contacto empieza con el borrador vacío: no hay estado guardado del que restaurarlo); `ChatScreenTest.failedMessageShowsDiscardText`, `identityChangedDisablesInput`, `stoppedEngineDisablesSend`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): chat de texto con estados de entrega y descartes`.

### Task 11: Fotos — recodificación JPEG y cola de envío

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/photo/PhotoEncoder.kt`, `PhotoQueue.kt`
- Modify: `GhostApp.kt` (`AppContainer.photos`; `stop()` y `stopEverything()` llaman a `photos.clear()`)
- Test: `app/src/test/kotlin/org/ghostp2/app/photo/PhotoEncoderTest.kt`, `PhotoQueueTest.kt`

**Interfaces:**
- Consumes: `engine.sendPhoto`, `engine.conversations`, `engine.status`, `AppSettings.maxPhotoSidePx` (Task 2).
- Produces:
  - `class PhotoException(val reason: Reason) : Exception()` con `enum class Reason { UNREADABLE, TOO_LARGE, NOT_ACTIVE }`.
  - `object PhotoEncoder { fun encode(input: ByteArray, maxSidePx: Int): ByteArray; suspend fun encode(resolver: ContentResolver, uri: Uri, maxSidePx: Int): ByteArray }` — la segunda lee el `Uri` en memoria (más de 64 MiB ⇒ `TOO_LARGE`) en `Dispatchers.IO` y llama a la primera en `Dispatchers.Default`.
  - `class PhotoQueue(scope: CoroutineScope, encode: suspend (Uri) -> ByteArray, send: (contactId: String, jpeg: ByteArray) -> String?, awaitFinished: suspend (contactId: String, itemId: String) -> Unit, capacity: Int = 3)` con `fun enqueue(contactId: String, uri: Uri): Boolean`, `val preparing: StateFlow<Map<String, Int>>`, `val errors: SharedFlow<Pair<String, PhotoException.Reason>>`, `fun clear()`.
  - `AppContainer.photos`: `send` devuelve `null` si el motor no está `Online` (⇒ `NOT_ACTIVE`); `awaitFinished` espera en `engine.conversations` a que el elemento deje de estar en `Sending` o desaparezca.
- Algoritmo de `encode(input, maxSidePx)`: límites con `inJustDecodeBounds`, `inSampleSize` potencia de 2 hasta que el lado mayor quede ≥ `maxSidePx`, orientación EXIF leída con `ExifInterface(ByteArrayInputStream)` y aplicada con `Matrix`, escalado a lado mayor ≤ `maxSidePx` (nunca se amplía), `Bitmap.compress(JPEG)` con calidades 85, 75, 65, 55, 45 hasta caber en 3 145 728 bytes; si a 45 no cabe, lado ×0,75 y otra vez (como mucho 3 veces) y si no, `TOO_LARGE`. Imagen indecodificable ⇒ `UNREADABLE`. El búfer de entrada se rellena con ceros al terminar y los bitmaps se reciclan.
- Reglas de la cola: un solo trabajador; codifica la siguiente foto solo cuando `awaitFinished` de la anterior ha vuelto (una foto en vuelo); `enqueue` devuelve `false` si ya hay `capacity` fotos entre esperando y en curso; tras `send` se rellenan con ceros los bytes codificados (el motor guarda su copia).

- [ ] **Step 1: Pruebas que fallan** (`@GraphicsMode(NATIVE)`): `PhotoEncoderTest.outputIsJpegWithoutExif` (entrada JPEG con `TAG_MAKE` y GPS escritos con `ExifInterface` en un archivo temporal de la prueba ⇒ salida empieza por `FF D8` y su `ExifInterface` no tiene `TAG_MAKE` ni `latLong`), `longSideIsCapped` (4000×1000, 2048 ⇒ 2048×512), `smallImageIsNotUpscaled`, `orientationIsApplied` (100×50 con orientación 6 ⇒ 50×100), `noisyImageFitsTheLimit` (ruido 3000×3000, 3072 ⇒ ≤ 3 145 728 bytes), `garbageIsUnreadable`; `PhotoQueueTest.rejectsBeyondCapacity`, `sendsOneAtATime` (el segundo `encode` no empieza hasta completar el primer `awaitFinished`), `wipesEncodedBytesAfterSend`, `encodeFailureEmitsError`, `notActiveEmitsError`, `clearCancelsPending`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): recodificación de fotos a JPEG sin metadatos y cola de envío`.

### Task 12: Fotos en el chat, Photo Picker y visor con "Guardar"

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/photo/PhotoBitmaps.kt`, `PhotoSaver.kt`, `app/src/main/kotlin/org/ghostp2/app/ui/chat/PhotoViewerScreen.kt`
- Modify: `ChatViewModel.kt`, `ChatScreen.kt`, `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/photo/PhotoBitmapsTest.kt`, `PhotoSaverTest.kt`, `app/src/test/kotlin/org/ghostp2/app/ui/chat/ChatPhotoTest.kt`

**Interfaces:**
- Consumes: `ChatContent.Photo.jpeg` (array vivo del motor), `ChatContent.PhotoDiscarded`, `AppContainer.photos` (Task 11).
- Produces:
  - `internal fun decodeSampled(bytes: ByteArray, sidePx: Int): Bitmap?` (`inSampleSize` hasta lado mayor ≥ `sidePx`) y `class PhotoBitmaps(maxBytes: Int = 32 * 1024 * 1024, decode: (ByteArray, Int) -> Bitmap? = ::decodeSampled)` con `fun thumbnail(itemId: String, jpeg: ByteArray, sidePx: Int = 1024): ImageBitmap?` (caché `LruCache` por `itemId`, tamaño = `Bitmap.byteCount`, una decodificación por elemento), `fun full(jpeg: ByteArray, sidePx: Int): ImageBitmap?` (sin caché), `fun retain(ids: Set<String>)`, `fun clear()`.
  - Algoritmo de lectura segura (lo comparten `thumbnail`, `full` y `PhotoSaver`): `copy = jpeg.copyOf()`; si **después** de copiar `jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()` ⇒ `null` (el motor rellena con ceros desde el índice 0, así que una cabecera intacta tras la copia garantiza una copia intacta); decodificar `copy`; rellenar `copy` con ceros; un fallo del decodificador ⇒ `null`.
  - `object PhotoSaver { suspend fun save(resolver: ContentResolver, target: Uri, jpeg: ByteArray): Boolean }` (falso si los bytes ya están a cero).
  - `ChatViewModel` gana `val bitmaps: PhotoBitmaps` (retiene solo los ids de fotos vivas de la conversación; `clear()` en `onCleared`), `fun pickPhoto(uri: Uri): Boolean` (⇒ `photos.enqueue`), `val preparingPhotos: StateFlow<Int>`, `val photoErrors`.
- Reglas: botón de foto con `rememberLauncherForActivityResult(PickVisualMedia())` e `ImageOnly`; cola llena ⇒ "Espera a que terminen de enviarse las fotos"; mientras codifica ⇒ "Preparando foto…"; burbuja con la miniatura y barra de progreso en `Sending`; `PhotoDiscarded` o `thumbnail == null` ⇒ "Foto descartada para liberar memoria". Pulsar abre `Routes.photo(id, item)`: imagen completa, "Guardar" con `CreateDocument("image/jpeg")` y nombre `GhostP2-yyyyMMdd-HHmmss.jpg`; si ya no está ⇒ "La foto ya no está en memoria".

- [ ] **Step 1: Pruebas que fallan:** `PhotoBitmapsTest.decodesOncePerItem` (decodificador que cuenta), `zeroedBytesGiveNull`, `zeroingBeforeHeaderCheckIsDetected` (array con cabecera a cero y resto intacto ⇒ `null` sin llamar al decodificador), `retainEvictsOthers`, `cacheRespectsByteBudget`; `PhotoSaverTest.savesExactBytes`, `zeroedPhotoIsNotSaved`; `ChatPhotoTest.discardedPhotoShowsPlaceholder`, `sendingPhotoShowsProgress`, `fullQueueShowsWaitMessage`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): fotos en el chat, selector del sistema y visor con Guardar`.

### Task 13: Bloqueo con huella o credencial y `FLAG_SECURE`

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/lock/AppLock.kt`, `DeviceLock.kt`, `LockScreen.kt`
- Modify: `MainActivity.kt` (pasa a `FragmentActivity`), `GhostApp.kt` (`AppContainer.lock`), `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/lock/AppLockTest.kt`, `LockRootTest.kt`, `app/src/test/kotlin/org/ghostp2/app/MainActivityTest.kt`

**Interfaces:**
- Consumes: `AppSettings.lockEnabled`, `lockAfterMinutes`, `SettingsRepository.update` (Task 2).
- Produces:
  - `class AppLock(clock: () -> Long = SystemClock::elapsedRealtime)` con `val locked: StateFlow<Boolean>`, `fun configure(enabled: Boolean, afterMinutes: Int)`, `fun onStop(changingConfigurations: Boolean)`, `fun onStart()`, `fun unlock()`. Reglas: la **primera** `configure` del proceso con `enabled` bloquea (arranque en frío); las siguientes solo cambian los parámetros; `enabled = false` desbloquea; `onStart` bloquea si `enabled` y pasaron ≥ `afterMinutes` desde el último `onStop` (0 = siempre); `onStop(changingConfigurations = true)` no cuenta.
  - `object DeviceLock { fun available(context: Context): Boolean }` — `BiometricManager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS` (esta combinación funciona desde API 26).
  - `@Composable fun LockScreen(onUnlocked: () -> Unit)` — "GhostP2 está bloqueado" + "Desbloquear"; lanza `BiometricPrompt` (título "Desbloquear GhostP2", `setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`, sin botón negativo) al mostrarse.
  - `AppContainer.lock: AppLock`, alimentado desde `settings.settings` en el scope del proceso.
- Reglas: `GhostRoot` con ajustes sin leer ⇒ solo carga; bloqueado ⇒ solo `LockScreen` (ni bienvenida ni `NavHost` en la composición). Si `DeviceLock.available` es falso al ir a desbloquear: `settings.update { it.copy(lockEnabled = false) }`, `unlock()` y aviso "Tu móvil ya no tiene bloqueo de pantalla; se ha desactivado el bloqueo de GhostP2". `MainActivity.onStart/onStop` llaman a `lock.onStart()`/`lock.onStop(isChangingConfigurations)`.

- [ ] **Step 1: Pruebas que fallan:** `AppLockTest.coldStartLocksWhenEnabled`, `shortBackgroundStaysUnlocked`, `longBackgroundLocks`, `immediateLocksOnAnyStop`, `configurationChangeDoesNotLock`, `enablingLaterDoesNotLockImmediately`, `disablingUnlocks`; `LockRootTest.lockedRootShowsOnlyLockScreen` (con un contacto guardado: su nombre no aparece), `nothingSensitiveWhileSettingsLoad` (ajustes que no emiten aún ⇒ ni nombres ni bienvenida), `missingDeviceCredentialDisablesLock` (`DeviceLock` falso por inyección ⇒ desbloqueado, `lockEnabled == false`, aviso visible); `MainActivityTest.windowHasFlagSecure`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar. Para las pruebas, `AppContainer` tiene `internal var deviceLockAvailable: (Context) -> Boolean = DeviceLock::available`, que usan `LockScreen` y `SettingsViewModel`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASA.
- [ ] **Step 5:** Commit `feat(app): bloqueo con huella o credencial del dispositivo`.

### Task 14: Ajustes, notificaciones sin nombre, arranque al encender y "Borrar todo"

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`, `app/src/main/kotlin/org/ghostp2/app/BootReceiver.kt`
- Modify: `GhostApp.kt` (`AppContainer.wipeAll()`), `GhostService.kt`, `Notifications.kt`, `AndroidManifest.xml`, `ui/GhostRoot.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/ui/settings/SettingsViewModelTest.kt`, `SettingsScreenTest.kt`, `app/src/test/kotlin/org/ghostp2/app/BootReceiverTest.kt`, `NotificationsTest.kt`, `GhostServiceTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository` (Task 2), `DeviceLock` (Task 13), `BatteryOptimization.manufacturerHint` (Task 5), `engine.myOnion`, `container.lastDiagnostic`, `engine.wipeAll()`.
- Produces:
  - `suspend fun AppContainer.wipeAll()` — `stopEverything()`, `photos.clear()` y, con `storageLock`: `engine.wipeAll()`, `settings.wipe()`, nueva comprobación del almacenamiento. Tras él `onboardingDone == false`: la raíz vuelve a la bienvenida.
  - `SettingsViewModel(container)` con `val state: StateFlow<AppSettings?>`, `fun setLock(enabled: Boolean): Boolean` (falso si `!container.deviceLockAvailable(context)` ⇒ "Configura un bloqueo de pantalla en el móvil"), `fun setLockAfter(minutes: Int)`, `fun setHideNames(hide: Boolean)`, `fun setMaxPhotoSide(px: Int)`, `fun setStartOnBoot(on: Boolean)`, `fun wipeAll()`, `val wiping: StateFlow<Boolean>`.
  - `Notifications.forNotice(context, notice, contactName: String?, hideNames: Boolean)` — con `hideNames`: "Nuevo mensaje", "Un contacto te ha eliminado de sus contactos", "La identidad de un contacto ha cambiado".
  - `class BootReceiver : BroadcastReceiver` — solo `ACTION_BOOT_COMPLETED`; con `goAsync()` lee los ajustes del contenedor; si `startOnBoot && onboardingDone` ⇒ `ContextCompat.startForegroundService(GhostService.startIntent(...))`; un fallo al leer ⇒ no hace nada.
- Pantalla: Seguridad ("Bloqueo con huella o PIN", "Bloquear tras": Inmediatamente / 1 min / 5 min / 15 min); Notificaciones ("Ocultar el nombre del contacto"); Fotos ("Tamaño máximo": 1280 / 2048 / 3072 px); Segundo plano (estado de la exención, "Permitir en segundo plano", ayuda del fabricante, "Activar GhostP2 al encender el móvil"); Datos ("Borrar todo", confirmación: "Se borrarán tu identidad, tus contactos y sus claves. Se avisará a los contactos conectados. No se puede deshacer."); Acerca de (versión, "Licencia Apache-2.0", mi .onion abreviada y el último aviso de diagnóstico).
- `GhostService`: lee `settings.get().hideNotificationNames` por aviso (si falla la lectura, oculta); `onDestroy` pone `foreground = false`. Manifest: `RECEIVE_BOOT_COMPLETED` y `<receiver android:name=".BootReceiver" android:exported="true">` con su `intent-filter`.

- [ ] **Step 1: Pruebas que fallan:** `SettingsViewModelTest.lockNeedsDeviceCredential`, `optionsPersist`, `wipeAllReturnsToOnboarding` (tras emparejar con un `EngineHarness`: sin contactos, sin identidad, `onboardingDone == false`, motor `Stopped` y el otro lado recibe `ContactUnpairedYou`); `SettingsScreenTest.wipeAllAsksForConfirmation`; `NotificationsTest.hiddenNamesNeverShowTheName` (los tres avisos: ni el título ni la versión pública contienen el nombre); `GhostServiceTest.hiddenNamesSettingIsHonoured`, `stickyRestartRethrowsOtherIllegalStateException` (un `enterForeground` que lanza `IllegalStateException` no de tipo `ForegroundServiceStartNotAllowedException` ⇒ se relanza); `BootReceiverTest.bootStartsServiceOnlyWhenEnabled`, `bootDoesNothingBeforeOnboarding`, `otherActionsAreIgnored`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest :app:lintDebug` → PASA.
- [ ] **Step 5:** Commit `feat(app): ajustes, notificaciones sin nombre, arranque al encender y borrar todo`.

### Task 15: CI — Lint, APK de versión firmado y comprobación de dependencias

**Files:**
- Modify: `app/build.gradle.kts`, `app/proguard-rules.pro`, `.github/workflows/ci.yml`, `docs/DESARROLLO.md`

**Interfaces:**
- Produces:
  - `signingConfigs.release` solo si existen las cuatro variables `GHOSTP2_KEYSTORE_FILE`, `GHOSTP2_KEYSTORE_PASSWORD`, `GHOSTP2_KEY_ALIAS`, `GHOSTP2_KEY_PASSWORD`; si falta alguna, `assembleRelease` genera `app-<abi>-release-unsigned.apk`.
  - `dependenciesInfo { includeInApk = false; includeInBundle = false }` (F-Droid); `lint { abortOnError = true; checkReleaseBuilds = true }` en `:app`, `:core:data` y `:core:tor`.
  - Job `test` (cada push y PR): `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` y después `! ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -iE 'com\.google\.android\.gms|com\.google\.firebase|com\.google\.mlkit'`.
  - Job `release` (solo etiquetas `v*`, `permissions: contents: write`, `needs: test`), con `HAS_KEY: ${{ secrets.GHOSTP2_KEYSTORE_BASE64 != '' }}`:
    - Con clave: decodifica el secreto en `$RUNNER_TEMP/release.jks`, exporta las variables, `./gradlew :app:assembleRelease`, `apksigner verify` de cada APK, `sha256sum *.apk > SHA256SUMS.txt` y `gh release create "$GITHUB_REF_NAME" <apks> SHA256SUMS.txt`.
    - Sin clave: compila los APK sin firmar, los sube como artefacto `apk-sin-firmar` con su `SHA256SUMS.txt` y **no** crea Release (aviso `::warning::`).
- Reglas: si R8 rompe algo en la versión de lanzamiento, añadir solo las reglas `-keep` mínimas a `proguard-rules.pro`, con un comentario por regla.

- [ ] **Step 1:** Firma: generar una clave de prueba (`keytool -genkeypair -keystore $TMP/prueba.jks -alias prueba -keyalg RSA -keysize 4096 -validity 30 -storepass prueba -keypass prueba -dname CN=prueba`), exportar las cuatro variables y `./gradlew :app:assembleRelease` → `apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk` muestra `CN=prueba`. Sin variables → existe `app-arm64-v8a-release-unsigned.apk`.
- [ ] **Step 2:** `./gradlew test lintDebug :app:assembleRelease` → `BUILD SUCCESSFUL`, sin errores de lint; la comprobación de dependencias no imprime nada.
- [ ] **Step 3:** Escribir `ci.yml` y validar su sintaxis (`actionlint` si está disponible, o `python3 -c 'import yaml,sys; yaml.safe_load(open(sys.argv[1]))' .github/workflows/ci.yml`).
- [ ] **Step 4:** `docs/DESARROLLO.md`: variables de firma, cómo crear la clave y guardar `GHOSTP2_KEYSTORE_BASE64` y las contraseñas en los secretos de GitHub, y qué hace CI sin ellos.
- [ ] **Step 5:** Commit `ci: lint, APK de versión firmado con secretos y comprobación sin Play Services`.

### Task 16: Documentación y prueba manual con la interfaz definitiva

**Files:**
- Modify: `README.md`, `docs/PRUEBA-MANUAL.md`, `docs/PROTOCOLO.md` (§14.6, §14.8, §14.9, §15)

- [ ] **Step 1:** `docs/PRUEBA-MANUAL.md` reescrita para la interfaz: bienvenida (permiso de notificaciones, batería y ayuda del fabricante); Tor hasta "en línea"; invitación en A mostrada como QR y escaneada en B; también copiada y pegada; mismo número de seguridad, nombre y "verificado"; textos en ambos sentidos con "✓ entregado"; modo avión en B ⇒ "No entregado: tu contacto no está conectado. Descartado"; foto con EXIF de GPS enviada desde A ⇒ guardada en B con "Guardar" y sin GPS (`exiftool`); muchas fotos hasta superar 64 MiB ⇒ "Foto descartada para liberar memoria"; bloqueo (inmediato, tras 1 min y quitando el bloqueo del móvil); "Ocultar el nombre"; "Activar al encender el móvil" con un reinicio real; borrar contacto con aviso y matando la app a mitad (`am kill` con B desconectado ⇒ el borrado se reanuda al volver a activar); "Borrar todo" ⇒ vuelve la bienvenida y Tor arranca de nuevo (sustituye los pasos 37–42); captura de pantalla y miniatura de recientes bloqueadas; APK de versión (firmado o sin firmar) instalado y usado de punta a punta.
- [ ] **Step 2:** `README.md`: estado (plan 3 listo), instalación desde GitHub Releases y comprobación del SHA-256, cómo emparejar (QR o texto, número de seguridad), modelo de seguridad (bloqueo R22, ajustes cifrados) y limitaciones (sin cámara para fotos R23, el bloqueo no cifra con la huella).
- [ ] **Step 3:** `docs/PROTOCOLO.md`: borrados pendientes persistidos (§14.6), R20 (§14.8), R21 y el arranque de Tor tras borrar su estado (§14.9), R22 y R23 (§15).
- [ ] **Step 4:** `./gradlew test :app:assembleDebug` → PASA.
- [ ] **Step 5:** Commit `docs: interfaz definitiva, prueba manual y reglas R20–R23`.
