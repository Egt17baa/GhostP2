# GhostP2 · Puentes de Tor disfrazados — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajuste «Puentes» (No / obfs4 / Snowflake / Mis puentes) que conecta Tor a través de
transportes disfrazados de IPtProxy, sin caer nunca a conexión directa, sin logs y borrando el
estado de Tor al parar.

**Architecture:** El modelo puro de puentes vive en `:core:engine`: líneas, validación, QR, listas
integradas y `BridgeSetup`. `:core:data` guarda el modo y las líneas propias en `AppSettings`
(formato 2). `:core:tor` arranca IPtProxy antes del demonio, inyecta `UseBridges`/`Bridge`/
`ClientTransportPlugin` en la configuración de kmp-tor y lo para todo, con borrado, al parar.
`:app` lo conecta con Ajustes y el motor.

**Tech Stack:**
- La base de los planes 2 y 3.
- `com.netzarchitekten:IPtProxy:5.7.0` (Maven Central; MIT, con Lyrebird GPL-3.0 y Snowflake BSD-3).

**Spec:** `docs/superpowers/specs/2026-11-02-ghostp2-puentes-tor-design.md`

## Global Constraints

- Opciones visibles:
  - **No** (por defecto).
  - **obfs4** (integrados).
  - **Snowflake** (integrado, con aviso).
  - **Mis puentes** (obfs4 o webtunnel pegados o por QR, máximo 10 líneas).
  - No hay «WebTunnel» integrado: `pt_config.json` de tor-browser-build no trae ninguna lista
    WebTunnel. WebTunnel solo existe vía «Mis puentes», y así se documenta en R30.
- Nunca se conecta sin puente si el ajuste no es «No». Un fallo de transporte o de arranque por
  puente da `EngineErrorKind.BRIDGE_FAILED`.
- Con «No», IPtProxy no se carga ni se inicia: ni `Controller` ni `System.loadLibrary`.
- Logs de IPtProxy desactivados: `enableLogging=false`, `unsafeLogging=false`, `logLevel="ERROR"`.
  No se queda ningún `IPtProxy.LogFileName` en `stateDir`. Nada de logs en GhostP2.
- Con modo ≠ «No», cada parada de Tor borra `torDir` entero y el `stateDir` de IPtProxy.
  - Paradas que cuentan: stop, «Desconectar», R29, `onDestroy`, cambio de ajuste y Tor atascado
    (lo que se pueda).
  - Un marcador `noBackupFilesDir/tor-bridged` se crea antes de arrancar con puente y se borra tras
    el borrado. Si al arrancar existe, se borra todo primero (cubre la muerte del proceso).
- Cambiar el ajuste de puentes borra el estado de Tor (también al pasar a «No»).
- Snowflake fija exactamente los valores de tor-browser-build de hoy (abajo): sin AMP cache, sin
  STUN de Google, frentes de CDN77/datapacket.
- Los puertos de IPtProxy solo en 127.0.0.1.
- Formato 2 (R21): campos nuevos con valor por defecto. Un modo desconocido se lee como «No».
- `CancellationException` se relanza siempre.
- Comprobación de CI de `releaseRuntimeClasspath` sin GMS/Firebase: sigue pasando.
- Textos en `strings.xml`, en español:
  - Sección «Puentes».
  - Ayuda: «Los puentes disfrazan tu conexión para que no se note que usas Tor. Lo más discreto
    son tus propios puentes obfs4 o WebTunnel de bridges.torproject.org.»
  - Aviso de Snowflake: «Snowflake contacta con un intermediario a través de una red de
    distribución de contenidos, con servidores STUN públicos y con voluntarios que hacen de puente.
    No ven tus mensajes, pero sí tu dirección IP. Úsalo solo si los otros no funcionan.»
  - Error: «No se puede conectar por el puente. Prueba otro tipo de puente o pega puentes nuevos en
    Ajustes › Puentes.»
  - Reconectar: «Para usar los nuevos puentes hay que reconectar. Se cerrarán las conversaciones
    abiertas.» [Reconectar] [Cancelar].
  - Inicio: «Conectando por puente… %d %%».

### Valores integrados (copiar tal cual, con la fecha 2026-11-02 en un comentario)

De `tor-browser-build/projects/tor-expert-bundle/pt_config.json`:

```
obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0
obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0
obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq/mtAjp1BIC9vHKJ2FAEpGssTPw iat-mode=0
obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0
obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0
obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1
obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1
snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn
snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn
```

Los parámetros de Snowflake van en la línea `Bridge` (Tor los pasa al transporte como argumentos
SOCKS). Además se fijan en el `Controller` con los mismos valores:
- `setSnowflakeIceServers` (la lista `ice=` de arriba);
- `setSnowflakeBrokerUrl("https://1098762253.rsc.cdn77.org/")`;
- `setSnowflakeFrontDomains("app.datapacket.com,www.datapacket.com")`;
- `setSnowflakeAmpCacheUrl("")`, `setSnowflakeSqsUrl("")`, `setSnowflakeSqsCreds("")`.

Así ningún valor interno de la librería, por ejemplo `stun.l.google.com`, se cuela.

## Review Focus

1. **Puente caído.** obfs4 con IPs inalcanzables: `BRIDGE_FAILED` antes del plazo, nunca `Online`
   por directo. → `bridgeTransportErrorIsBridgeFailed`, `bridgeBootstrapTimeoutIsBridgeFailed`.
2. **Cambio de ajuste mientras Tor arranca por puente.** Parada limpia: `Controller.stop`, borrado y
   arranque nuevo con el modo nuevo, sin dos `Controller` vivos. → `changeDuringStartRestartsWithNewMode`.
3. **Muerte del proceso con puente activo.** El siguiente arranque, con cualquier modo, borra antes
   el estado viejo. → `markerFromCrashWipesBeforeStart`.
4. **Pegar texto con basura.** Saltos `\r\n`, prefijo `Bridge`, espacios, líneas repetidas,
   transportes no admitidos y más de 10 líneas: mensaje claro por línea y nada guardado si hay
   error. → `parsesMessyPaste`, `rejectsUnsupportedAndTooMany`.
5. **«Mis puentes» vacío.** Elegir «Mis puentes» sin líneas válidas no se guarda (la interfaz y el
   ViewModel lo rechazan). Si llega así desde disco, sigue en «Mis puentes» (no se sanea a «No»):
   `bridgeSetupFor` da un `BridgeSetup` vacío, que falla cerrado como `BRIDGE_FAILED` sin arrancar
   Tor. → `customWithoutLinesStaysCustomAndFailsClosed`, `customWithoutLinesIsEmptySetup`,
   `emptySetupIsBridgeFailedWithoutDaemon`.

---

### Task 1: Modelo de puentes y ajustes (`:core:engine`, `:core:data`)

**Files:**
- Create:
  - `core/engine/src/main/kotlin/org/ghostp2/engine/bridges/BridgeLine.kt`
  - `core/engine/src/main/kotlin/org/ghostp2/engine/bridges/BuiltInBridges.kt`
  - `core/engine/src/main/kotlin/org/ghostp2/engine/bridges/BridgeSetup.kt`
- Modify:
  - `core/data/src/main/kotlin/org/ghostp2/data/Settings.kt`
  - `core/data/src/main/kotlin/org/ghostp2/data/StoredModels.kt`
- Test:
  - `core/engine/src/test/kotlin/org/ghostp2/engine/bridges/BridgeLineTest.kt`
  - `core/engine/src/test/kotlin/org/ghostp2/engine/bridges/BridgeSetupTest.kt`
  - `core/data/src/test/kotlin/org/ghostp2/data/SettingsRepositoryTest.kt`

**Interfaces (Produces):**
```kotlin
enum class BridgeMode { NONE, OBFS4, SNOWFLAKE, CUSTOM }
enum class PtKind(val torName: String) { OBFS4("obfs4"), WEBTUNNEL("webtunnel"), SNOWFLAKE("snowflake") }
data class BridgeLine(val kind: PtKind, val line: String)          // `line` sin prefijo "Bridge", normalizada
sealed interface ParseResult {
    data class Ok(val lines: List<BridgeLine>) : ParseResult
    data class Invalid(val lineNumbers: List<Int>, val tooMany: Boolean) : ParseResult // números 1-based
}
object BridgeLines {
    const val MAX = 10
    fun parse(text: String): ParseResult          // solo obfs4 y webtunnel (para "Mis puentes")
    fun parseQr(payload: String): ParseResult     // "['obfs4 …', 'obfs4 …']" o texto plano
}
object BuiltInBridges { val OBFS4: List<BridgeLine>; val SNOWFLAKE: List<BridgeLine>; val SNOWFLAKE_ICE: String; val SNOWFLAKE_BROKER: String; val SNOWFLAKE_FRONTS: String }
data class BridgeSetup(val lines: List<BridgeLine>) { val kinds: Set<PtKind> }
fun bridgeSetupFor(mode: BridgeMode, custom: List<String>): BridgeSetup?   // null = sin puentes
```
- `AppSettings.bridgeMode: BridgeMode = BridgeMode.NONE`
- `AppSettings.customBridges: List<String> = emptyList()`
- `StoredSettings` guarda el modo como `String` (nombre del enum) y las líneas como `List<String>`.

**Validación:**
- obfs4: `obfs4 <IPv4|[IPv6]>:<puerto> <40 hex> cert=<base64 sin relleno> iat-mode=<0|1|2>`.
- webtunnel: `webtunnel <IP|[IPv6]>:<puerto> <40 hex> url=https://… [ver=…]`.
- Puerto entre 1 y 65535.
- Se aceptan el prefijo `Bridge`, mayúsculas en la huella y espacios de más.
- Se quitan las líneas en blanco y los duplicados.

- [ ] **Step 1: Pruebas que fallan**
  - `BridgeLineTest`:
    - `parsesValidObfs4AndWebtunnel`: con y sin `Bridge`, y con IPv6.
    - `parsesMessyPaste`: `"Bridge obfs4 …\r\n\r\n  obfs4 …  \nobfs4 …(dup)"` da `Ok` con 2 líneas normalizadas.
    - `rejectsUnsupportedAndTooMany`:
      - `meek_lite …` y `snowflake …` dan `Invalid([n])`;
      - 11 líneas válidas dan `Invalid(tooMany = true)`;
      - un puerto 70000 o una huella de 39 caracteres dan `Invalid`.
    - `parsesQrArray`: `"['obfs4 A…', 'obfs4 B…']"` da 2 líneas; el QR basura da `Invalid`.
    - `builtInListsParse`: cada `BuiltInBridges.OBFS4` es obfs4 válido.
      - Hay 7 obfs4 y 2 snowflake.
      - `SNOWFLAKE_ICE` no contiene `google`.
      - Ninguna línea contiene `ampcache` ni `ampproject`.
  - `BridgeSetupTest`:
    - `noneIsNull`.
    - `obfs4UsesBuiltIn`.
    - `snowflakeUsesBuiltIn`.
    - `customMixesKinds`: obfs4 y webtunnel dan `kinds == {OBFS4, WEBTUNNEL}`.
    - `customWithoutLinesIsNull`.
  - `SettingsRepositoryTest`:
    - `bridgeDefaultsToNone`.
    - `format2FileWithoutBridgeFieldsReadsNone`: un archivo real de formato 2 sin los campos.
    - `unknownModeReadsNone`.
    - `customWithoutLinesSanitizesToNone`: también con líneas guardadas que ya no validan.
    - `customLinesRoundTrip`.
- [ ] **Step 2:** `./gradlew :core:engine:test :core:data:test` → FALLA.
- [ ] **Step 3:** Implementar según las interfaces. `sanitized()`:
  - `CUSTOM` con `customBridges` que no pasa `BridgeLines.parse` o vacío → `NONE` y lista vacía;
  - otro modo con líneas guardadas → las conserva, para no perderlas al cambiar de opción.
- [ ] **Step 4:** `./gradlew :core:engine:test :core:data:test` → PASA.
- [ ] **Step 5:** Commit `feat(engine,data): modelo de puentes de Tor y ajuste (R30)`.

---

### Task 2: Tor con transportes de IPtProxy (`:core:tor`, `:core:engine`)

**Files:**
- Modify:
  - `gradle/libs.versions.toml` (`iptproxy = "5.7.0"`)
  - `core/tor/build.gradle.kts`
  - `core/tor/src/main/kotlin/org/ghostp2/tor/KmpTorSupport.kt`
  - `TorDriver.kt`
  - `KmpTorTransport.kt`
  - `core/engine/src/main/kotlin/org/ghostp2/engine/TorTransport.kt`
  - `MessengerEngine.kt`
  - `app/proguard-rules.pro` (o `consumer-rules.pro` de `:core:tor`)
  - `.github/workflows/ci.yml` (si la comprobación del dex o del manifiesto lo necesita)
- Create:
  - `core/tor/src/main/kotlin/org/ghostp2/tor/PtController.kt`
  - `core/tor/src/main/kotlin/org/ghostp2/tor/IptProxyController.kt`
- Test:
  - `core/tor/src/test/kotlin/org/ghostp2/tor/BridgeConfigTest.kt`
  - `KmpTorTransportTest.kt` (el doble de driver que ya existe)
  - `core/engine/src/test/.../MessengerEngineTest.kt`

**Interfaces:**
- Consumes: `BridgeSetup`, `PtKind`, `BuiltInBridges` (Task 1).
- Produces:
```kotlin
internal interface PtController {                 // una instancia por arranque de Tor
    /** Arranca cada transporte y devuelve su puerto SOCKS local; lanza IOException si alguno falla. */
    suspend fun start(kinds: Set<PtKind>): Map<PtKind, Int>
    fun stop()                                    // idempotente; para todos
    val failures: Flow<PtKind>                    // OnTransportEvents.error/stopped inesperado
}
internal fun TorConfig.BuilderScope.applyBridgeConfig(setup: BridgeSetup, ports: Map<PtKind, Int>)
// TorTransport.kt
data class Error(val message: String, val stuck: Boolean = false, val bridge: Boolean = false) : TorState
// MessengerEngine.kt
enum class EngineErrorKind { TOR, STORAGE, TOR_STUCK, BRIDGE_FAILED }
// KmpTorTransport
constructor(context: Context, bridges: suspend () -> BridgeSetup?)
internal constructor(torDir: File, markerFile: File, bridges: suspend () -> BridgeSetup?,
                     ptFactory: (stateDir: File) -> PtController, driverFactory: …)
```
- `IptProxyController` lleva `stateDir = noBackupFilesDir/pt`. Crea el `Controller` con logs
  desactivados, fija los valores de Snowflake de Global Constraints y llama a
  `start(kind.torName, "")` y a `port(kind.torName)`.
- `IPtProxy` solo se toca dentro de esta clase, para que con «No» la librería ni se cargue.

**Puntos clave:**
- **Configuración de kmp-tor:** el runtime se guarda por entorno y la lambda `config {}` del
  primer `KmpTorDriver` se reutiliza para siempre (ver el KDoc de `TorDriver.kt`). Por eso la
  lambda lee la configuración de puentes de un contenedor mutable compartido por `torDir`
  (`@Volatile`), no capturado por driver. `start()` fija ese contenedor antes de `startDaemon()`.
- **Orden en `start()`:**
  1. Si existe el marcador, borrar `torDir` y `stateDir`.
  2. `setup = bridges()`.
  3. Si no es nulo: crear el marcador, `pt.start(setup.kinds)` y fijar la configuración.
  4. `startDaemon`.

  Si `pt.start` falla, el resultado es `Error(bridge = true)` sin arrancar Tor.
  `applyBridgeConfig` escribe `UseBridges 1`, una `Bridge` por línea y
  `ClientTransportPlugin <torName> socks5 127.0.0.1:<puerto>` por tipo.
- **Errores:**
  - Con puente, cualquier `Error` del run lleva `bridge = true`, salvo `stuck`.
  - Un evento en `pt.failures` con Tor en marcha también da `Error(bridge = true)`.
  - El motor mapea `bridge` a `BRIDGE_FAILED`. Su timeout de arranque, los 180 s actuales, también
    da `BRIDGE_FAILED` cuando hubo puente: `TorTransport` expone `val bridged: Boolean` del run
    actual.
- **`stopLocked()`:**
  1. Parar el demonio.
  2. `pt.stop()`.
  3. Si el run fue con puente, borrar `torDir` y `stateDir` y después el marcador, igual que
     `wipeState`, con `driver = null`.
  4. Si Tor está atascado, hacer `pt.stop()` y borrar lo que se pueda, y dejar el marcador para el
     siguiente arranque.
- **`TorTransport.forgetBridgeState()`:** método nuevo, borra el estado como `wipeState`. Lo usa la
  Task 3 al cambiar el ajuste.

- [ ] **Step 1: Pruebas que fallan**
  - `BridgeConfigTest`:
    - `obfs4ConfigLines`: genera `UseBridges 1`, 7 `Bridge obfs4 …` y
      `ClientTransportPlugin obfs4 socks5 127.0.0.1:4711`.
    - `mixedCustomConfig`.
    - `noSetupAddsNothing`.
  - `KmpTorTransportTest`, con un `PtController` falso y el doble de driver:
    - `noBridgesNeverCreatesController`.
    - `bridgeStartsPtBeforeDaemon`.
    - `bridgeTransportErrorIsBridgeFailed`: `pt.start` lanza, el resultado es
      `Error(bridge = true)` y `startDaemon` no se llama.
    - `ptFailureWhileRunningIsBridgeFailed`.
    - `stopWithBridgeWipesAndStopsPt`: `torDir`, `stateDir` y el marcador ya no existen, y `pt.stop`
      se llama una vez.
    - `stopWithoutBridgeKeepsState`.
    - `markerFromCrashWipesBeforeStart`: marcador y `torDir/work/state` presentes, `start()` con
      `NONE`, y la ruta ya no existe.
    - `changeDuringStartRestartsWithNewMode`: `start` con obfs4, `stop` a mitad, `start` con
      `NONE`; un único controlador creado y parado, y el segundo run sin puentes.
  - `MessengerEngineTest`:
    - `bridgeErrorMapsToBridgeFailed`.
    - `bridgeBootstrapTimeoutIsBridgeFailed`.
- [ ] **Step 2:** `./gradlew :core:tor:testDebugUnitTest :core:engine:test` → FALLA.
- [ ] **Step 3:** Implementar.
  - Dependencia IPtProxy en `:core:tor`.
  - Reglas R8: `-keep class go.** { *; }` y `-keep class IPtProxy.** { *; }`.
  - Verificar que cada APK por ABI (arm64-v8a, armeabi-v7a, x86_64) lleva `libgojni.so` y que la
    comprobación de CI de 3 APK y del dex sigue pasando.
- [ ] **Step 4:** Mismo comando → PASA. Después `./gradlew :app:assembleRelease` y comprobar con
  `unzip -l` que `lib/arm64-v8a/libgojni.so` está en el APK arm64.
- [ ] **Step 5:** Commit `feat(tor): transportes disfrazados con IPtProxy (R30)`.

---

### Task 3: Ajustes › Puentes, conexión con el motor y documentación (`:app`, docs)

**Files:**
- Modify:
  - `app/src/main/kotlin/org/ghostp2/app/GhostApp.kt` (`KmpTorTransport(context) { bridgeSetupFor(settings.get()…) }`, borrado al cambiar el ajuste)
  - `ui/settings/SettingsScreen.kt`
  - `SettingsViewModel.kt`
  - `ui/EngineBanner.kt` (texto de `BRIDGE_FAILED` y «Conectando por puente…»)
  - `res/values/strings.xml`
  - `docs/PROTOCOLO.md` (§14, R30; §15)
  - `docs/PRUEBA-MANUAL.md`
  - `README.md` (función, licencias GPL-3.0 de la APK)
  - avisos de licencias, si existen
- Create:
  - `app/src/main/kotlin/org/ghostp2/app/ui/settings/BridgesScreen.kt`, con su ruta en `Routes.kt`.
    Reutiliza `ui/pairing/QrScanner.kt` para escanear.
- Test:
  - `app/src/test/kotlin/org/ghostp2/app/ui/settings/BridgesScreenTest.kt`
  - `SettingsViewModelTest.kt`
  - `GhostServiceTest.kt` o una prueba del contenedor

**Interfaces:**
- Consumes: Task 1 (`BridgeMode`, `BridgeLines`, `bridgeSetupFor`) y Task 2 (`KmpTorTransport(context, bridges)`, `forgetBridgeState()`, `EngineErrorKind.BRIDGE_FAILED`).
- Produces:
  - `SettingsViewModel.setBridges(mode: BridgeMode, customText: String?): BridgeChange`
  - `enum class BridgeChange { SAVED, NEEDS_RECONNECT, INVALID }`
  - `SettingsViewModel.confirmReconnect()`

**Comportamiento:**
- Ajustes enseña la fila «Puentes» con el modo actual. Al tocarla se abre `BridgesScreen`:
  - radio No / obfs4 / Snowflake / Mis puentes;
  - con Mis puentes: un campo de texto multilínea (sin autocorrección ni sugerencias, sin guardarse
    en el portapapeles) y un botón «Escanear QR»;
  - errores por número de línea: «Línea 2 no válida»; «Máximo 10 puentes».
- Elegir Snowflake muestra antes el aviso, con [Usar Snowflake] [Cancelar].
- Guardar con un cambio real:
  - motor `Online` o `StartingTor`: diálogo de reconexión y, al confirmar, `stop()` →
    `forgetBridgeState()` → `start()`;
  - motor `Error`: lo mismo, sin diálogo;
  - motor `Stopped`: solo guardar y `forgetBridgeState()`.
- La notificación no cambia: no nombra el puente.

- [ ] **Step 1: Pruebas que fallan**
  - `BridgesScreenTest`:
    - `showsFourOptionsWithNoneSelected`.
    - `snowflakeShowsWarningAndCancelKeepsNone`.
    - `customInvalidShowsLineErrorsAndSavesNothing`.
    - `customValidSaves`.
    - `scanQrFillsField`: con el escáner falso que ya usan las pruebas de emparejamiento.
  - `SettingsViewModelTest`:
    - `changeWhileOnlineNeedsReconnect`.
    - `confirmReconnectStopsForgetsAndStarts`: orden verificado con un `TorTransport` falso.
    - `changeWhileErrorReconnectsWithoutDialog`.
    - `changeWhileStoppedOnlySavesAndForgets`.
    - `sameValueIsNoop`.
  - Integración:
    - `bridgeFailedShowsBanner`: un `TorTransport` falso en `Error(bridge = true)` muestra el texto
      de error.
    - `networkReturnRetriesSameBridgeNeverDirect`: `recoverOnNetworkReturn` vuelve a llamar a
      `start()` y el transporte falso recibe el mismo `BridgeSetup`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar. Para la documentación:
  - **PROTOCOLO R30:** opciones; nunca directo; DNS fuera de Tor para el host de WebTunnel y los
    frentes/STUN de Snowflake (aceptado); borrado al parar; IPtProxy sin logs; WebTunnel solo en
    «Mis puentes».
  - **§14.9:** `noBackupFilesDir/pt` y `tor-bridged`.
  - **PRUEBA-MANUAL**, pasos nuevos al final:
    - obfs4 integrado en WiFi y en 5G;
    - Snowflake con aviso;
    - Mis puentes pegados y por QR;
    - puente inválido o caído da el error, nunca conecta;
    - cambiar con el motor en marcha muestra el diálogo;
    - tras «Desconectar», `run-as org.ghostp2 ls no_backup` (debug) no muestra `tor` ni `pt`;
    - con «No», tráfico idéntico al de antes.
  - **README:** función; la APK incluye Lyrebird (GPL-3.0) y Snowflake (BSD-3), y el código de
    GhostP2 sigue en Apache-2.0.
- [ ] **Step 4:** Verificación completa:
  `./gradlew test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease`
  → PASA, 0 avisos de lint. Pruebas de puentes 3 veces seguidas.
- [ ] **Step 5:** Commit `feat(app): ajuste de puentes de Tor (R30)`.
