# GhostP2 · Plan 2: Android base (motor, Tor, almacenamiento cifrado, servicio) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un APK instalable que arranca Tor en un servicio en primer plano, publica la .onion privada del usuario, empareja contactos por invitación, los conecta automáticamente cuando ambos están activos, intercambia texto y fotos cifrados con descarte, y guarda solo identidad y contactos cifrados con Android Keystore. Con una pantalla mínima de diagnóstico; la interfaz completa es el plan 3.

**Architecture:** Toda la lógica de orquestación vive en un módulo Kotlin/JVM puro `:core:engine` (`MessengerEngine`) que depende de interfaces (`TorTransport`, `IdentityRepository`, `ContactRepository`); se prueba en la JVM con un Tor falso que mapea direcciones .onion a sockets locales. Las implementaciones Android son finas: `:core:tor` (kmp-tor), `:core:data` (DataStore + Tink AEAD con clave en Android Keystore) y `:app` (servicio en primer plano, notificaciones, DI manual, pantalla de diagnóstico).

**Tech Stack:** AGP 8.13.2, compileSdk/targetSdk 36, minSdk 26, Kotlin 2.4.21 (+ plugin Compose), Jetpack Compose BOM 2026.09.00 + Material 3, activity-compose 1.13.0, lifecycle 2.11.0, core-ktx 1.19.1, DataStore 1.3.0, Tink-android 1.24.0, kmp-tor `runtime` 2.6.0 + `resource-exec-tor` 409.5.0, Robolectric (última 4.x estable), kotlinx-coroutines 1.11.0, kotlinx-serialization 1.12.0 (CBOR).

**Spec:** `docs/superpowers/specs/2026-10-31-ghostp2-android-design.md` (§6.2, §6.3, §6.4, §6.5, §6.6, §6.7, §6.7 bis, §6.9). Protocolo del núcleo: `docs/PROTOCOLO.md`.

## Global Constraints

- Paquetes: `org.ghostp2.engine`, `org.ghostp2.data`, `org.ghostp2.tor`, `org.ghostp2.app`; `applicationId = "org.ghostp2.app"`.
- Sin Google Play Services, Firebase ni librerías propietarias. Licencia Apache-2.0.
- Mensajes, fotos y claves de sesión **nunca** en disco ni en logs. En disco solo: identidad, contactos y ajustes, cifrados (AEAD) con clave envuelta por Android Keystore; y el estado interno de Tor.
- `android:allowBackup="false"`, `dataExtractionRules`/`fullBackupContent` que excluyen todo.
- La .onion permanente **solo se publica si hay al menos un contacto**, y **siempre** con autorización de cliente v3 (una clave pública x25519 por contacto). Nunca se publica sin autorización de cliente.
- Puerto virtual del servicio oculto: `7420`; destino `127.0.0.1:<puerto local aleatorio>`.
- Invitación: secreto 16 bytes, servicio oculto temporal sin autorización de cliente, válida 10 min, un solo emparejamiento con éxito; los intentos fallidos **no** la consumen.
- Reintentos de conexión por contacto: espera creciente 15 s → 5 min (×2 por fallo, ±20 % de variación aleatoria), reinicio al cambiar la red o al abrir la app. Tiempo de conexión SOCKS: 60 s.
- Desempate de conexiones simultáneas: se conserva la conexión **iniciada** por el lado cuya `identityKey` pública es menor (orden de bytes sin signo); la perdedora se cierra sin `Bye` y se destruye su canal antes de crear `PeerSession` (PROTOCOLO §10.5).
- `HandshakeFailedException.reason == AUTH` al iniciar hacia un contacto ⇒ contacto en estado `IDENTITY_CHANGED`: se deja de reintentar y se avisa. `Closed(SECURITY)` por sí solo **no** bloquea al contacto (regla R8).
- `PeerSession` siempre con el scope del motor: `SupervisorJob() + Dispatchers.Default`, nunca Main/`immediate`/Unconfined (regla R7); un único colector por sesión, lanzado antes de `start()`.
- Historial de conversación en memoria del motor: sobrevive a cortes; se borra con "Borrar", al borrar el contacto o al detener el motor.
- Borrar contacto: enviar `Unpair` si hay sesión; si no, intentar conectar durante 2 min solo para entregarlo; después borrar igualmente. Al recibir `Unpair`: borrar el contacto y avisar "<nombre> te ha eliminado de sus contactos".
- Servicio en primer plano tipo `specialUse`, notificación fija "GhostP2 activo · cifrado" con acción "Desconectar"; notificación de mensaje **sin contenido**: "Nuevo mensaje de <nombre>".
- Textos visibles y comentarios en español.

## Review Focus

1. Con cero contactos, el motor arranca sin publicar ninguna .onion permanente; al borrar el último contacto, la .onion se retira (nunca queda pública sin autorización). → Task 3 `noPermanentOnionWithoutContacts`, Task 7 `deletingLastContactUnpublishesOnion`.
2. Alguien que conoce la .onion temporal pero no el secreto intenta emparejarse varias veces ⇒ la invitación sigue válida para el contacto legítimo. → Task 4 `failedAttemptsDoNotConsumeInvitation`.
3. Ambos móviles se conectan a la vez ⇒ queda exactamente una sesión, sin `Closed(PEER_BYE)` espurio ni mensajes duplicados. → Task 5 `simultaneousConnectKeepsExactlyOneSession`.
4. Corte de red a mitad de conversación ⇒ el historial se conserva, lo enviado durante el corte se marca descartado y la sesión se recupera sola al volver la red. → Task 6 `historySurvivesReconnect`.
5. El almacenamiento cifrado se corrompe o la clave del Keystore desaparece (restauración de copia, cambio de dispositivo) ⇒ la app no se cuelga: informa y ofrece empezar de cero. → Task 8 `corruptedStoreReportsAndAllowsReset`.

---

### Task 1: Toolchain Android y esqueleto `:app`

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/org/ghostp2/app/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/proguard-rules.pro`
- Modify: `settings.gradle.kts` (incluir `:app`; `pluginManagement` con `google()`), `gradle/libs.versions.toml` (AGP, Compose BOM, androidx, Robolectric), `build.gradle.kts` raíz (plugins `apply false`), `.github/workflows/ci.yml`
- Create: `docs/DESARROLLO.md` (cómo instalar el SDK y compilar)

**Interfaces:**
- Produces: `:app` compila con `./gradlew :app:assembleDebug`; `MainActivity` (Compose, Material 3) muestra "GhostP2".

- [ ] **Step 1:** Instalar el SDK en el contenedor (no se versiona): descargar `commandlinetools-linux-16111833_latest.zip` en `$HOME/android-sdk/cmdline-tools/latest`, aceptar licencias y `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"`; crear `local.properties` con `sdk.dir` (ya ignorado por git). Documentar los mismos pasos en `docs/DESARROLLO.md`.
- [ ] **Step 2:** Añadir al catálogo AGP 8.13.2 y las versiones del Tech Stack; plugins `android-application`, `android-library`, `kotlin-android`, `kotlin-compose`. Si Kotlin 2.4.21 no es compatible con AGP 8.13.2, usar la Kotlin estable más reciente compatible y anotarlo.
- [ ] **Step 3:** `app/build.gradle.kts`: `namespace`/`applicationId` `org.ghostp2.app`, minSdk 26, target/compileSdk 36, Java/Kotlin 17, Compose activado, `buildTypes.release` con `isMinifyEnabled = true` y `proguard-rules.pro`; `splits.abi` para `arm64-v8a`, `armeabi-v7a`, `x86_64` con `isUniversalApk = false`.
- [ ] **Step 4:** Manifest: `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"` (ambos excluyen todos los dominios), `MainActivity` exportada con `MAIN/LAUNCHER`.
- [ ] **Step 5:** `MainActivity` con `setContent { MaterialTheme { Text("GhostP2") } }` y `window.setFlags(FLAG_SECURE, FLAG_SECURE)` antes de `setContent`.
- [ ] **Step 6:** Ejecutar `./gradlew :app:assembleDebug test` → `BUILD SUCCESSFUL` y existe `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- [ ] **Step 7:** CI: añadir `android-actions/setup-android@v3` y `./gradlew test :app:assembleDebug --no-daemon`.
- [ ] **Step 8:** Commit `build: proyecto Android con esqueleto de la app`.

### Task 2: Módulo `:core:engine` — modelos, interfaces y dobles de prueba

**Files:**
- Create: `core/engine/build.gradle.kts` (Kotlin/JVM, `api(project(":core:protocol"))`, `java-test-fixtures`)
- Create: `core/engine/src/main/kotlin/org/ghostp2/engine/Model.kt`, `TorTransport.kt`, `Repositories.kt`, `EngineConfig.kt`
- Create (testFixtures): `core/engine/src/testFixtures/kotlin/org/ghostp2/engine/FakeTor.kt`, `InMemoryRepositories.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/FakeTorTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Produces (en `org.ghostp2.engine`):
  - `sealed interface TorState { data object Off; data class Starting(val percent: Int); data object Ready; data class Error(val message: String) }`
  - `class PublishedService(val onion: String, val privateKey: ByteArray)` — `onion` sin ".onion", minúsculas; `privateKey` = clave ED25519-V3 de Tor (64 bytes) para volver a publicar la misma dirección.
  - `interface TorTransport { val state: StateFlow<TorState>; suspend fun start(); suspend fun stop(); suspend fun publishService(privateKey: ByteArray?, clientAuthPublicKeys: List<ByteArray>, virtualPort: Int, localPort: Int): PublishedService; suspend fun unpublishService(onion: String); suspend fun addClientAuth(onion: String, privateKey: ByteArray); suspend fun removeClientAuth(onion: String); suspend fun connect(onion: String, port: Int, timeoutMillis: Long): java.net.Socket }` — `publishService` con `clientAuthPublicKeys` vacía y `privateKey == null` solo se usa para el servicio temporal de invitación; `connect` lanza `java.io.IOException` si no conecta.
  - `class Identity(val identityPrivate: ByteArray, val identityPublic: ByteArray, val onionPrivateKey: ByteArray?, val onion: String?)` (equals por contenido, `toString` oculta claves privadas)
  - `enum class ContactStatus { ACTIVE, IDENTITY_CHANGED }`
  - `class Contact(val id: String, val name: String, val peerIdentity: ByteArray, val peerOnion: String, val clientAuthPrivateForPeerOnion: ByteArray, val myClientAuthPublic: ByteArray, val pairSecret: ByteArray, val verified: Boolean, val status: ContactStatus)` con `copy(...)`, equals por contenido, `toString` sin secretos. `id` = hex en minúsculas de los 16 primeros bytes de `SHA-256(peerIdentity)`.
  - `interface IdentityRepository { suspend fun load(): Identity?; suspend fun save(identity: Identity); suspend fun wipe() }`
  - `interface ContactRepository { val contacts: Flow<List<Contact>>; suspend fun all(): List<Contact>; suspend fun upsert(contact: Contact); suspend fun delete(id: String); suspend fun wipe() }`
  - `data class EngineConfig(val virtualPort: Int = 7420, val invitationTtlMillis: Long = 600_000, val backoffMinMillis: Long = 15_000, val backoffMaxMillis: Long = 300_000, val unpairDeliveryWindowMillis: Long = 120_000, val connectTimeoutMillis: Long = 60_000)`
  - testFixtures: `class FakeTor(network: FakeTorNetwork) : TorTransport` y `class FakeTorNetwork` — registra servicios publicados (onion → puerto local, claves de autorización), `connect` abre un `Socket` a `127.0.0.1:<puerto>` solo si el servicio existe y (si tiene autorización) el llamante añadió una clave privada cuya pública está registrada; `published: StateFlow<Map<String, FakeService>>` para inspección; `failNextConnects(n)` y `setOnline(Boolean)` para simular cortes. Las .onion generadas por `FakeTor` son direcciones v3 válidas (generar clave ed25519 aleatoria con BouncyCastle y calcular checksum como `OnionAddress`).
  - testFixtures: `InMemoryIdentityRepository`, `InMemoryContactRepository`.

- [ ] **Step 1: Pruebas que fallan** en `FakeTorTest`: `publishedServiceIsReachable`, `clientAuthRequiredWhenConfigured` (sin `addClientAuth` ⇒ `IOException`; con la privada correcta ⇒ conecta), `unpublishedServiceIsUnreachable`, `offlineNetworkRefusesConnections`, `generatedOnionsAreValid` (`OnionAddress.isValid`).
- [ ] **Step 2:** `./gradlew :core:engine:test` → FALLA.
- [ ] **Step 3:** Implementar modelos, interfaces y dobles.
- [ ] **Step 4:** `./gradlew :core:engine:test` → PASA.
- [ ] **Step 5:** Commit `feat(engine): modelos, interfaces de Tor y almacenamiento, y dobles de prueba`.

### Task 3: Motor — arranque, identidad y servicio oculto permanente

**Files:**
- Create: `core/engine/src/main/kotlin/org/ghostp2/engine/MessengerEngine.kt`, `OnionPublisher.kt`, `Listener.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/EngineStartTest.kt`, `core/engine/src/testFixtures/kotlin/org/ghostp2/engine/EngineHarness.kt`

**Interfaces:**
- Consumes: Task 2; `Primitives.generateX25519()` (núcleo).
- Produces:
  - `sealed interface EngineStatus { data object Stopped; data class StartingTor(val percent: Int); data object Online; data class Error(val message: String) }`
  - `class MessengerEngine(tor: TorTransport, identities: IdentityRepository, contacts: ContactRepository, scope: CoroutineScope, config: EngineConfig = EngineConfig(), random: java.util.Random = java.security.SecureRandom())` con, en esta tarea: `val status: StateFlow<EngineStatus>`, `val myOnion: StateFlow<String?>`, `suspend fun start()`, `suspend fun stop()`.
  - `internal class OnionPublisher` — `suspend fun sync(identity, contacts): Identity` publica/retira/re-publica la .onion permanente según la regla global; `internal class Listener` — `ServerSocket` en `127.0.0.1:0`, bucle de aceptación en el scope que entrega cada `Socket` a un callback.
  - testFixtures: `class EngineHarness` que crea N motores sobre un `FakeTorNetwork` compartido con repositorios en memoria y `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`, con `close()`.
- Reglas: `start()` crea la identidad si no existe (X25519 nueva; sin .onion), arranca Tor, espera `Ready`, abre el `Listener`, llama a `OnionPublisher.sync`; `stop()` cierra sesiones y listener, retira servicios, para Tor, borra el historial en memoria, `status = Stopped`.

- [ ] **Step 1: Pruebas que fallan:** `firstStartCreatesIdentity`, `noPermanentOnionWithoutContacts` (tras `start()` sin contactos, `FakeTorNetwork.published` vacío y `myOnion == null`), `permanentOnionPublishedWithClientAuthForEachContact` (insertar 2 contactos en el repositorio antes de `start()` ⇒ un servicio con exactamente esas 2 claves públicas, y la misma .onion tras `stop()`/`start()` gracias a `onionPrivateKey` guardada), `stopUnpublishesAndStopsTor`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:engine:test` → PASA.
- [ ] **Step 5:** Commit `feat(engine): arranque, identidad y publicación de la .onion permanente`.

### Task 4: Motor — invitaciones y emparejamiento

**Files:**
- Create: `core/engine/src/main/kotlin/org/ghostp2/engine/Invitations.kt`
- Modify: `MessengerEngine.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/InvitationTest.kt`

**Interfaces:**
- Consumes: `Invitation`, `Pairing.asInviter/asInvitee`, `PairingOffer`, `PairingResult`, `HandshakeFailedException` (núcleo); Tasks 2–3.
- Produces (en `MessengerEngine`):
  - `sealed interface InvitationState { data class Waiting(val text: String, val expiresAtMillis: Long); data class Paired(val contactId: String, val safetyNumber: String); data object Expired; data object Cancelled; data class Failed(val message: String) }`
  - `fun createInvitation(): InvitationHandle` con `class InvitationHandle(val state: StateFlow<InvitationState>, val cancel: () -> Unit)`. Solo una invitación activa a la vez (crear otra cancela la anterior).
  - `sealed interface AcceptResult { data class Paired(val contactId: String, val safetyNumber: String); data class Failed(val message: String) }`
  - `suspend fun acceptInvitation(text: String): AcceptResult`
- Reglas: por cada emparejamiento se genera un par x25519 de autorización de cliente para el otro (la privada va en mi `PairingOffer`, la pública queda en `Contact.myClientAuthPublic`). El invitador acepta conexiones en un `ServerSocket` propio detrás del servicio temporal; cada intento fallido se descarta y se sigue esperando hasta éxito, cancelación o caducidad (10 min); en éxito se retira el servicio temporal. Tras emparejar: `contacts.upsert` (si ya existía un contacto con la misma `peerIdentity`, se reemplaza), `tor.addClientAuth(peerOnion, clientAuthPrivateForPeerOnion)`, `OnionPublisher.sync` (re-publica con la nueva clave), nombre provisional `"Contacto nuevo"`, `verified = false`. Si el invitado aún no tenía .onion permanente (cero contactos), se publica antes de enviar su oferta, porque la oferta incluye su .onion.

- [ ] **Step 1: Pruebas que fallan** (dos motores en `EngineHarness`):
  - `inviteAndAcceptPairsBothSides` — ambos tienen el contacto del otro, mismo número de seguridad, .onion permanentes publicadas con la clave del otro, y `addClientAuth` registrado.
  - `failedAttemptsDoNotConsumeInvitation` — un tercero conecta 3 veces a la .onion temporal con secretos incorrectos; después el invitado legítimo empareja con éxito.
  - `invitationExpiresAfterTtl` — con `invitationTtlMillis = 300` ⇒ `Expired` y servicio temporal retirado; aceptar después ⇒ `AcceptResult.Failed`.
  - `invitationIsSingleUse` — tras un emparejamiento correcto, un segundo `acceptInvitation` con el mismo texto ⇒ `Failed`.
  - `malformedInvitationFailsClearly` — texto inválido ⇒ `Failed` con mensaje que empieza por "Invitación no válida".
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:engine:test` → PASA.
- [ ] **Step 5:** Commit `feat(engine): invitaciones de un solo uso y emparejamiento`.

### Task 5: Motor — presencia, conexión automática y desempate

**Files:**
- Create: `core/engine/src/main/kotlin/org/ghostp2/engine/Connections.kt`
- Modify: `MessengerEngine.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/ConnectionsTest.kt`

**Interfaces:**
- Consumes: `SessionHandshake.initiate/respond`, `ContactKeys`, `PeerSession`, `SessionEvent`, `CloseReason`, `HandshakeFailedException.reason` (núcleo); Tasks 2–4.
- Produces (en `MessengerEngine`):
  - `enum class Presence { OFFLINE, CONNECTING, ONLINE, IDENTITY_CHANGED }`
  - `val presence: StateFlow<Map<String, Presence>>` (por id de contacto)
  - `fun onNetworkChanged()` — reinicia esperas y reintenta ya.
  - `sealed interface Notice { data class MessageArrived(val contactId: String); data class ContactUnpairedYou(val contactId: String, val name: String); data class IdentityChanged(val contactId: String) }` y `val notices: SharedFlow<Notice>` (con búfer; en esta tarea se emite `IdentityChanged`, las demás en las tareas 6–7)
  - `internal` acceso a la sesión activa por contacto para las tareas 6–7.
- Reglas: por cada contacto `ACTIVE` sin sesión, un bucle intenta `tor.connect(peerOnion, virtualPort, connectTimeoutMillis)` + `SessionHandshake.initiate` con espera creciente 15 s → 5 min (×2, ±20 %); conexiones entrantes del `Listener` ⇒ `SessionHandshake.respond(candidatos = contactos ACTIVE)`; `AUTH` al iniciar ⇒ contacto `IDENTITY_CHANGED` (persistido), sin más reintentos; `UNKNOWN_CALLER`/`CONNECTION`/`TIMEOUT` ⇒ seguir reintentando. Desempate (regla global) antes de crear `PeerSession`; la perdedora: `stream.close()` + `channel.destroy()`, sin `Bye`. Cada `PeerSession` con el scope del motor y su colector lanzado antes de `start()`. Al cerrarse una sesión: `OFFLINE` y vuelta al bucle de reintentos (excepto `PEER_UNPAIRED`, tratado en Task 7).

- [ ] **Step 1: Pruebas que fallan** (con `backoffMinMillis = 50`, `backoffMaxMillis = 400` en pruebas):
  - `pairedContactsConnectAutomatically` — tras emparejar, ambos pasan a `ONLINE`.
  - `reconnectsAfterNetworkDrop` — `setOnline(false)` cierra la sesión (`OFFLINE`), `setOnline(true)` + `onNetworkChanged()` ⇒ `ONLINE` de nuevo.
  - `simultaneousConnectKeepsExactlyOneSession` — forzar que ambos inicien a la vez (reiniciar ambos bucles simultáneamente) 20 veces ⇒ siempre una sola sesión activa por lado, que puede intercambiar un texto, y ningún `Closed(PEER_BYE)`.
  - `identityChangedStopsRetrying` — reemplazar en B la identidad (simula reinstalación con la misma .onion) ⇒ A marca `IDENTITY_CHANGED`, persiste el estado y no vuelve a intentar.
  - `stoppedPeerIsOffline` — `stop()` en B ⇒ A ve `OFFLINE` en < 2 s.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:engine:test` (y `ConnectionsTest` 3 veces) → PASA.
- [ ] **Step 5:** Commit `feat(engine): presencia, reconexión con espera creciente y desempate`.

### Task 6: Motor — conversaciones en memoria (texto y fotos)

**Files:**
- Create: `core/engine/src/main/kotlin/org/ghostp2/engine/Conversations.kt`
- Modify: `MessengerEngine.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/ConversationsTest.kt`

**Interfaces:**
- Consumes: `PeerSession.sendText/sendPhoto/events` (núcleo; ids devueltos antes de cualquier evento); Task 5.
- Produces (en `MessengerEngine`):
  - `enum class Direction { IN, OUT }`; `sealed interface ChatContent { data class Text(val text: String); class Photo(val jpeg: ByteArray) }`
  - `sealed interface DeliveryStatus { data object Received; data class Sending(val sentBytes: Int, val totalBytes: Int); data object Delivered; data class Failed(val reason: FailReason) }`
  - `data class ChatItem(val id: String, val direction: Direction, val content: ChatContent, val status: DeliveryStatus, val timestampMillis: Long)`
  - `val conversations: StateFlow<Map<String, List<ChatItem>>>` (por id de contacto; máximo 500 elementos por conversación, se descartan los más antiguos)
  - `fun sendText(contactId: String, text: String): String` y `fun sendPhoto(contactId: String, jpeg: ByteArray): String` — si no hay sesión: añade el elemento con `Failed(NOT_CONNECTED)` y devuelve su id; nunca encola.
  - `fun clearConversation(contactId: String)`
- Reglas: el elemento saliente se añade con el id devuelto por `PeerSession` y se actualiza con sus eventos (`PhotoProgress` ⇒ `Sending`, `Delivered`, `Failed`); entrantes `TextReceived`/`PhotoReceived` ⇒ `Received` + `Notice.MessageArrived`; el historial no se borra al cerrarse una sesión; `stop()` borra todo (rellenando con ceros los `ByteArray` de fotos).

- [ ] **Step 1: Pruebas que fallan:** `textRoundTripUpdatesStatuses` (A ve `Delivered`, B ve `Received` y un `MessageArrived`), `photoRoundTripWithProgress` (200 000 bytes idénticos; A ve `Sending` creciente y `Delivered`), `sendingWhileOfflineIsDiscardedImmediately` (`Failed(NOT_CONNECTED)` y nada llega a B al reconectar), `historySurvivesReconnect`, `clearConversationEmptiesOnlyThatContact`, `stopWipesHistory`, `conversationKeepsAtMost500Items`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:engine:test` → PASA.
- [ ] **Step 5:** Commit `feat(engine): conversaciones en memoria con estados de entrega`.

### Task 7: Motor — borrar contacto (aviso), recibir aviso y borrar todo

**Files:**
- Modify: `MessengerEngine.kt`, `Connections.kt`
- Test: `core/engine/src/test/kotlin/org/ghostp2/engine/UnpairTest.kt`

**Interfaces:**
- Consumes: `PeerSession.sendUnpair(): Boolean`, `SessionEvent.PeerUnpaired` (núcleo); Tasks 3–6.
- Produces (en `MessengerEngine`):
  - `enum class UnpairDelivery { DELIVERED, NOT_DELIVERED }`
  - `suspend fun deleteContact(contactId: String): UnpairDelivery`
  - `suspend fun renameContact(contactId: String, name: String)`; `suspend fun setVerified(contactId: String, verified: Boolean)`
  - `suspend fun wipeAll()` — envía `Unpair` (sin esperas largas) a los contactos con sesión abierta, para todo, borra repositorios e identidad; el siguiente `start()` crea una identidad nueva.
- Reglas (spec §6.7 bis y Global Constraints): tras entregar o agotar la ventana: borrar historial, `contacts.delete`, `tor.removeClientAuth(peerOnion)`, `OnionPublisher.sync` (si no quedan contactos se retira la .onion). Al recibir `PeerUnpaired`: lo mismo + `Notice.ContactUnpairedYou(id, name)`.

- [ ] **Step 1: Pruebas que fallan:** `deleteWithOpenSessionNotifiesPeer` (A borra ⇒ `DELIVERED`; B emite `ContactUnpairedYou` y ya no tiene a A), `deleteWhileOfflineRetriesWithinWindow` (B desconectado, se reconecta dentro de la ventana ⇒ `DELIVERED`), `deleteWhileOfflineGivesUpAfterWindow` (`unpairDeliveryWindowMillis = 300` ⇒ `NOT_DELIVERED` y A borra igualmente), `deletingLastContactUnpublishesOnion`, `wipeAllResetsIdentity`, `renameAndVerifyPersist`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:engine:test` → PASA.
- [ ] **Step 5:** Commit `feat(engine): borrar contacto con aviso, recibir aviso y borrar todo`.

### Task 8: `:core:data` — almacenamiento cifrado

**Files:**
- Create: `core/data/build.gradle.kts` (Android library, namespace `org.ghostp2.data`, dependencias: `:core:engine`, DataStore, Tink-android, serialization-cbor; tests: Robolectric, JUnit 4 vintage o JUnit 5 según soporte de Robolectric)
- Create: `core/data/src/main/kotlin/org/ghostp2/data/EncryptedStore.kt`, `StoredModels.kt`, `DataStoreRepositories.kt`, `KeystoreAead.kt`
- Test: `core/data/src/test/kotlin/org/ghostp2/data/EncryptedStoreTest.kt`, `DataStoreRepositoriesTest.kt`

**Interfaces:**
- Consumes: `IdentityRepository`, `ContactRepository`, `Identity`, `Contact` (Task 2).
- Produces:
  - `class EncryptedStore<T>(file: java.io.File, aead: com.google.crypto.tink.Aead, serializer: KSerializer<T>, default: T, associatedData: ByteArray)` — lee/escribe el objeto completo como `AEAD(CBOR(T))` con escritura atómica (archivo temporal + `rename`); `suspend fun read(): T`, `suspend fun update(transform: (T) -> T)`; si no se puede descifrar lanza `StoreCorruptedException`.
  - `object KeystoreAead { fun create(context: Context): Aead }` — Tink `AndroidKeysetManager` con clave maestra `android-keystore://ghostp2_master` (StrongBox si está disponible), keyset en preferencias privadas `ghostp2_keyset`.
  - `class StoredIdentityRepository(store) : IdentityRepository`, `class StoredContactRepository(store) : ContactRepository` (un único archivo `ghostp2.bin` con identidad + contactos).
  - `class StoreCorruptedException(cause: Throwable) : Exception("Almacenamiento ilegible", cause)`
- Reglas: en pruebas se usa un `Aead` de Tink en memoria (`AesGcmKeyManager`), no el Keystore. El archivo vive en `context.noBackupFilesDir`.

- [ ] **Step 1: Pruebas que fallan:** `roundTripIdentityAndContacts`, `fileContentIsNotPlaintext` (el archivo no contiene la .onion ni el nombre en claro), `wrongKeyThrowsStoreCorrupted`, `corruptedStoreReportsAndAllowsReset` (bytes alterados ⇒ `StoreCorruptedException`; `wipe()` lo deja usable), `atomicWriteSurvivesCrashMidWrite` (si existe un `.tmp` huérfano se ignora), `contactsFlowEmitsUpdates`.
- [ ] **Step 2:** `./gradlew :core:data:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** → PASA.
- [ ] **Step 5:** Commit `feat(data): almacenamiento cifrado con Tink y Android Keystore`.

### Task 9: `:core:tor` — implementación con kmp-tor

**Files:**
- Create: `core/tor/build.gradle.kts` (Android library, namespace `org.ghostp2.tor`, dependencias `:core:engine`, `io.matthewnelson.kmp-tor:runtime:2.6.0`, `io.matthewnelson.kmp-tor:resource-exec-tor:409.5.0`)
- Create: `core/tor/src/main/kotlin/org/ghostp2/tor/KmpTorTransport.kt`, `TorKeys.kt`
- Test: `core/tor/src/test/kotlin/org/ghostp2/tor/TorKeysTest.kt`

**Interfaces:**
- Consumes: `TorTransport`, `TorState`, `PublishedService` (Task 2).
- Produces: `class KmpTorTransport(context: Context) : TorTransport`; `internal object TorKeys` con conversiones entre `ByteArray` y los tipos de kmp-tor (`ED25519_V3.PrivateKey`, `X25519.PublicKey`, `X25519.PrivateKey`, `OnionAddress`).
- Reglas: entorno de Tor en `context.noBackupFilesDir/tor`; puerto SOCKS y de control automáticos; `state` derivado de los eventos de arranque (porcentaje de bootstrap); `publishService` con `TorCmd.Onion.Add` (`new(ED25519_V3)` o `existing(clave)`, `port(virtual = virtualPort) { target(port = localPort) }` en `127.0.0.1`, `clientAuth(...)` por cada clave, `destroyKeyOnJobCompletion(false)` para conservar la privada y devolverla); `unpublishService` con `TorCmd.Onion.Delete`; `addClientAuth`/`removeClientAuth` con `TorCmd.OnionClientAuth.Add/Remove`; `connect` con `java.net.Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))` y `InetSocketAddress.createUnresolved("$onion.onion", port)`. Antes de implementar, leer el código fuente de kmp-tor 2.6.0 (`TorRuntime.Builder`, `TorRuntime.Environment.Builder` para Android, `TorCmd.Onion.Add`, `TorListeners`) y ajustar los nombres exactos; documentar en el informe cualquier diferencia con esta descripción.

- [ ] **Step 1: Pruebas que fallan** (JVM): `privateKeyRoundTrip` (bytes ↔ `ED25519_V3.PrivateKey`), `x25519PublicRoundTrip`, `onionAddressRoundTrip` (con la dirección de DuckDuckGo).
- [ ] **Step 2:** `./gradlew :core:tor:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar `TorKeys` y `KmpTorTransport`.
- [ ] **Step 4:** `./gradlew :core:tor:testDebugUnitTest :core:tor:assembleDebug` → PASA.
- [ ] **Step 5:** Commit `feat(tor): transporte Tor con kmp-tor (servicios ocultos con autorización de cliente)`.

### Task 10: `:app` — servicio en primer plano, notificaciones y pantalla de diagnóstico

**Files:**
- Create: `app/src/main/kotlin/org/ghostp2/app/GhostApp.kt` (Application + `AppContainer` con DI manual), `GhostService.kt`, `Notifications.kt`, `BatteryOptimization.kt`, `DiagnosticScreen.kt`
- Modify: `app/build.gradle.kts` (dependencias `:core:engine`, `:core:data`, `:core:tor`; tests Robolectric), `AndroidManifest.xml`, `MainActivity.kt`, `strings.xml`
- Test: `app/src/test/kotlin/org/ghostp2/app/NotificationsTest.kt`, `GhostServiceTest.kt`

**Interfaces:**
- Consumes: `MessengerEngine` y su API (Tasks 3–7), `KmpTorTransport` (Task 9), `KeystoreAead`, `EncryptedStore`, repositorios (Task 8).
- Produces:
  - `class AppContainer(context)` con `val engine: MessengerEngine` creado una vez con `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
  - `class GhostService : Service` — `ACTION_START`/`ACTION_STOP`; en `START` llama a `startForeground` con la notificación fija y `engine.start()`; en `STOP` `engine.stop()` y `stopSelf()`; registra un `ConnectivityManager.NetworkCallback` que llama a `engine.onNetworkChanged()`; reenvía `engine.notices` a notificaciones.
  - `object Notifications` — canales `ghostp2_status` (importancia baja) y `ghostp2_messages` (alta); `fun status(context): Notification` ("GhostP2 activo · cifrado", acción "Desconectar" → `ACTION_STOP`, `setOngoing(true)`); `fun message(context, contactName: String): Notification` ("Nuevo mensaje de <nombre>", sin texto del mensaje, `VISIBILITY_PRIVATE` con versión pública "Nuevo mensaje"); `fun unpaired(context, name)`; `fun identityChanged(context, name)`.
  - `object BatteryOptimization { fun isIgnoring(context): Boolean; fun requestIntent(context): Intent }`
  - `DiagnosticScreen` (Compose): botón Activar/Desconectar, estado del motor, mi .onion abreviada, nº de contactos y presencia; botones "Crear invitación" (muestra el texto copiable) y "Pegar invitación" (campo de texto) y un chat mínimo de texto por contacto para la prueba manual. Es temporal: el plan 3 la sustituye.
- Manifest: permisos `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_NETWORK_STATE`; `<service android:name=".GhostService" android:exported="false" android:foregroundServiceType="specialUse">` con `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Mantener la conexión cifrada P2P sobre Tor para recibir mensajes"/>`.
- Reglas: pedir `POST_NOTIFICATIONS` (API 33+) al pulsar Activar; si `StoreCorruptedException` al arrancar ⇒ la pantalla muestra "No se pudieron leer tus datos cifrados" con botón "Empezar de cero" (`wipeAll`).

- [ ] **Step 1: Pruebas que fallan** (Robolectric): `statusNotificationIsOngoingWithStopAction`, `messageNotificationHasNoMessageText` (el texto de la notificación no contiene el mensaje; solo el nombre), `serviceStartsInForeground` (`ShadowService` indica primer plano tras `ACTION_START`, con `FakeTor` inyectado mediante el `AppContainer`), `stopActionStopsService`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASA sin errores de lint.
- [ ] **Step 5:** Commit `feat(app): servicio en primer plano, notificaciones y pantalla de diagnóstico`.

### Task 11: Documentación y prueba manual con dos móviles

**Files:**
- Modify: `README.md`, `docs/PROTOCOLO.md` (reglas del nivel de app: .onion solo con contactos y siempre con autorización, ciclo de vida de la invitación, desempate, reintentos, borrado con aviso)
- Create: `docs/PRUEBA-MANUAL.md`

- [ ] **Step 1:** `docs/PRUEBA-MANUAL.md`: instalación del APK de depuración en dos móviles (`adb install app-arm64-v8a-debug.apk`), lista de comprobación numerada con resultado esperado: activar y ver "GhostP2 activo"; Tor llega a 100 %; crear invitación en A y aceptarla en B; mismo número de seguridad; ambos "en línea"; texto en ambos sentidos con "✓ entregado"; modo avión en B ⇒ A ve desconectado y un envío se marca descartado; quitar modo avión ⇒ reconexión sola; borrar el contacto en A ⇒ B recibe el aviso; Desconectar ⇒ la notificación desaparece.
- [ ] **Step 2:** Actualizar README (estado: plan 2 completo, cómo compilar el APK, enlace a la prueba manual) y PROTOCOLO.
- [ ] **Step 3:** `./gradlew test :app:assembleDebug` → PASA.
- [ ] **Step 4:** Commit `docs: prueba manual con dos móviles y reglas del nivel de aplicación`.
