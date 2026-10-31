# GhostP2 · Plan 1: Núcleo criptográfico y de protocolo — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir y probar en la JVM todo el núcleo de GhostP2 (primitivas, Noise, ratchet, invitaciones, emparejamiento, sesión entre contactos, mensajes de texto y fotos con acuse/descarte), sin Android ni Tor.

**Architecture:** Proyecto Gradle en la raíz del repositorio con dos módulos Kotlin/JVM puros: `:core:crypto` (primitivas, Noise, ratchet, identidad, invitación) y `:core:protocol` (tramas sobre flujos, emparejamiento, sesión, mensajes de aplicación). El transporte es un par `InputStream`/`OutputStream` abstracto: en el plan 2 será un socket de Tor; aquí, sockets locales y tuberías en memoria.

**Tech Stack:** Kotlin 2.4.21 (JVM toolchain 17), Gradle 8.14.3 (wrapper), BouncyCastle `bcprov-jdk18on` 1.86, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.12.0 (CBOR), JUnit 5 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-31-ghostp2-android-design.md`

**Hoja de ruta (planes siguientes, se escriben al terminar este):**
- Plan 2 — Android base: proyecto `:app`, `:core:data` (Keystore + Tink), `:core:tor` (kmp-tor 2.6.0: servicio oculto permanente con autorización de cliente v3, servicio temporal de invitación, SOCKS), servicio en primer plano, presencia y reconexión.
- Plan 3 — Interfaz: Compose (contactos, chat, QR con ZXing, fotos con Photo Picker y recodificación, borrar/unpair, ajustes, bloqueo biométrico, `FLAG_SECURE`), CI de APK firmado y README.

## Global Constraints

- Licencia Apache-2.0; sin dependencias propietarias ni de Google Play Services.
- `:core:crypto` y `:core:protocol` no dependen de Android (deben compilar y probarse con `./gradlew test` en la JVM).
- Paquetes: `org.ghostp2.core.crypto` y `org.ghostp2.core.protocol`.
- Noise: `Noise_KKpsk2_25519_ChaChaPoly_SHA256` para sesiones; `Noise_XXpsk3_25519_ChaChaPoly_SHA256` para emparejamiento; conforme a la especificación Noise rev. 34 y validados con los vectores de cacophony.
- Ratchet por mensaje: `mk = HMAC-SHA256(ck, 0x01)`, `ck' = HMAC-SHA256(ck, 0x02)`; AEAD ChaCha20-Poly1305; contador estrictamente secuencial; relleno a bloques de 256 bytes.
- Trama en el flujo: prefijo de longitud big-endian de 4 bytes; tamaño máximo de trama 65 536 bytes.
- Latido cada 20 s; sin tráfico entrante 60 s ⇒ sesión cerrada. Acuse no recibido en 30 s ⇒ mensaje fallido.
- Texto máx. 8 000 caracteres. Foto máx. 3 MiB (3 145 728 bytes), trozos de 32 KiB (32 768 bytes).
- Invitación: secreto de 16 bytes; formato `ghostp2:<onion-v3-56-caracteres>.<secreto-base32-26-caracteres>`.
- Número de seguridad: 30 dígitos en 6 grupos de 5, idéntico en ambos lados, solo depende de las dos claves de identidad.
- Nada de mensajes ni claves de sesión se escribe en disco ni en logs.
- Textos visibles y comentarios en español.

## Review Focus

1. El contacto se desconecta a mitad de una foto ⇒ el receptor descarta los trozos y el emisor recibe `Failed` (nunca una foto parcial). → Task 8, `photoInterruptedIsDiscardedOnBothSides`.
2. El contacto reinstaló la app (identidad distinta) ⇒ el handshake de sesión falla con `HandshakeFailedException` y no se abre sesión. → Task 7, `sessionRejectsUnknownIdentity`.
3. Un par malicioso anuncia una longitud de trama enorme o negativa ⇒ se cierra sin reservar memoria. → Task 6, `rejectsOversizedLengthPrefix`.
4. Invitación pegada con espacios, saltos de línea o en mayúsculas/minúsculas mezcladas ⇒ se acepta; con un carácter cambiado ⇒ error claro (falla la suma de control de la .onion). → Task 5, `invitationToleratesWhitespaceAndCase`, `invitationRejectsBadChecksum`.
5. Varios envíos concurrentes (texto + latido + acuses) ⇒ ningún contador se reutiliza y el receptor los descifra todos en orden. → Task 8, `concurrentSendsKeepCounterOrder`.

---

### Task 1: Esqueleto Gradle y retirada del prototipo web

**Files:**
- Delete: `client/`, `server/`, `scripts/`, `test/`, `package.json`, `package-lock.json`, `Dockerfile`, `.dockerignore`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Create: `core/crypto/build.gradle.kts`, `core/protocol/build.gradle.kts`
- Create: `core/crypto/src/test/kotlin/org/ghostp2/core/crypto/SmokeTest.kt`
- Modify: `.gitignore` (añadir `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`; quitar `node_modules/`, `dist/`)
- Modify: `.github/workflows/ci.yml` (JDK 17 + `./gradlew test`)

**Interfaces:**
- Produces: módulos `:core:crypto` y `:core:protocol` (este último con `implementation(project(":core:crypto"))`), catálogo `libs` con alias `bouncycastle`, `coroutines-core`, `coroutines-test`, `serialization-cbor`, `junit-bom`, `junit-jupiter`, plugins `kotlin-jvm`, `kotlin-serialization`.

- [ ] **Step 1:** Borrar los archivos del prototipo listados arriba con `git rm -r`.
- [ ] **Step 2:** Generar el wrapper: `gradle wrapper --gradle-version 8.14.3` (Gradle está en `/opt/gradle/bin/gradle`).
- [ ] **Step 3:** Escribir `settings.gradle.kts` (`rootProject.name = "GhostP2"`, `include(":core:crypto", ":core:protocol")`, repositorios `mavenCentral()` y `google()`), catálogo de versiones con las del Tech Stack, y los `build.gradle.kts` de ambos módulos con `kotlin { jvmToolchain(17) }`, `tasks.test { useJUnitPlatform() }`.
- [ ] **Step 4:** Escribir `SmokeTest.smokeRuns()` que afirma `assertEquals(2, 1 + 1)`.
- [ ] **Step 5:** Ejecutar `./gradlew test` → `BUILD SUCCESSFUL`.
- [ ] **Step 6:** Actualizar `ci.yml` (checkout, `actions/setup-java@v4` temurin 17, `./gradlew test --no-daemon`).
- [ ] **Step 7:** Commit `build: proyecto Gradle del núcleo y retirada del prototipo web`.

### Task 2: Primitivas criptográficas

**Files:**
- Create: `core/crypto/src/main/kotlin/org/ghostp2/core/crypto/Primitives.kt`
- Test: `core/crypto/src/test/kotlin/org/ghostp2/core/crypto/PrimitivesTest.kt`

**Interfaces:**
- Produces:
  - `class KeyPair25519(val privateKey: ByteArray, val publicKey: ByteArray)` (32 + 32 bytes)
  - `class AuthenticationException(message: String) : Exception(message)`
  - `object Primitives` con:
    - `fun randomBytes(n: Int): ByteArray` (`SecureRandom`)
    - `fun generateX25519(): KeyPair25519`
    - `fun x25519PublicFromPrivate(privateKey: ByteArray): KeyPair25519`
    - `fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray` — lanza `AuthenticationException` si el resultado es todo ceros
    - `fun sha256(vararg parts: ByteArray): ByteArray`
    - `fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray`
    - `fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray` (RFC 5869)
    - `fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray`
    - `fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray` — lanza `AuthenticationException`
    - `fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean`
    - `fun wipe(vararg arrays: ByteArray)` (rellena con ceros)

- [ ] **Step 1: Pruebas que fallan** en `PrimitivesTest`:
  - `x25519MatchesRfc7748` — vector §6.1 de RFC 7748: privada de Alice `77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a`, pública de Bob `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f` ⇒ secreto `4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742`.
  - `x25519RejectsLowOrderPoint` — clave pública de 32 ceros ⇒ `AuthenticationException`.
  - `hkdfMatchesRfc5869Case1` — IKM `0b`×22, salt `000102…0c`, info `f0f1…f9`, L=42 ⇒ OKM `3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865`.
  - `chachaMatchesRfc8439Section2_8_2` — vector AEAD de RFC 8439 §2.8.2 (clave `808182…9f`, nonce `070000004041424344454647`, AAD `50515253c0c1c2c3c4c5c6c7`, texto "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.") ⇒ etiqueta `1ae10b594f09e26a7e902ecbd0600691`.
  - `chachaOpenRejectsTampering` — cambiar un bit del cifrado ⇒ `AuthenticationException`.
- [ ] **Step 2:** `./gradlew :core:crypto:test` → FALLA (no compila).
- [ ] **Step 3:** Implementar `Primitives.kt` con BouncyCastle ligero (`X25519`, `ChaCha20Poly1305`, `HKDFBytesGenerator`, `HMac(SHA256Digest)`), sin el proveedor JCA.
- [ ] **Step 4:** `./gradlew :core:crypto:test` → PASA.
- [ ] **Step 5:** Commit `feat(crypto): primitivas X25519, HKDF, HMAC y ChaCha20-Poly1305`.

### Task 3: Noise (KKpsk2 y XXpsk3) validado con vectores oficiales

**Files:**
- Create: `core/crypto/src/main/kotlin/org/ghostp2/core/crypto/noise/CipherState.kt`, `SymmetricState.kt`, `HandshakeState.kt`, `HandshakePattern.kt`
- Create: `core/crypto/src/test/resources/noise-vectors.json`
- Test: `core/crypto/src/test/kotlin/org/ghostp2/core/crypto/noise/NoiseVectorsTest.kt`

**Interfaces:**
- Consumes: `Primitives`, `KeyPair25519`, `AuthenticationException` (Task 2).
- Produces:
  - `enum class HandshakePattern(val protocolName: String) { KKpsk2("Noise_KKpsk2_25519_ChaChaPoly_SHA256"), XXpsk3("Noise_XXpsk3_25519_ChaChaPoly_SHA256") }`
  - `class CipherState` con `fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray`, `fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray`, `val key: ByteArray?` (para derivar el ratchet), `fun wipe()`
  - `class HandshakeState(pattern: HandshakePattern, initiator: Boolean, prologue: ByteArray, localStatic: KeyPair25519, remoteStatic: ByteArray? = null, psk: ByteArray, fixedEphemeral: KeyPair25519? = null)` con:
    - `fun writeMessage(payload: ByteArray): ByteArray`
    - `fun readMessage(message: ByteArray): ByteArray` — lanza `AuthenticationException`
    - `val isComplete: Boolean`
    - `val remoteStaticKey: ByteArray?`
    - `fun handshakeHash(): ByteArray`
    - `fun split(): Pair<CipherState, CipherState>` — (enviar, recibir) ya orientados según `initiator`
  - `fixedEphemeral` solo se usa en pruebas.

- [ ] **Step 1:** Extraer los vectores:
  `curl -s https://raw.githubusercontent.com/haskell-cryptography/cacophony/master/vectors/cacophony.txt | python3 -c "import json,sys; d=json.load(sys.stdin); json.dump({'vectors':[v for v in d['vectors'] if v['protocol_name'] in ('Noise_KKpsk2_25519_ChaChaPoly_SHA256','Noise_XXpsk3_25519_ChaChaPoly_SHA256')]}, open('core/crypto/src/test/resources/noise-vectors.json','w'), indent=1)"` → el archivo contiene 2 vectores.
- [ ] **Step 2: Prueba que falla** `NoiseVectorsTest.vectorsMatch(protocol)` (parametrizada por los 2 nombres): construye iniciador y respondedor con `init_*`/`resp_*` (prologue, `psks[0]`, static, ephemeral fijo, `remote_static` si existe), alterna `writeMessage`/`readMessage` para cada entrada de `messages` hasta completar el handshake, y después usa los `CipherState` de `split()` para el resto; en cada mensaje afirma `ciphertext` exacto y `payload` recuperado; al final afirma `handshake_hash`. Añade la dependencia de prueba `kotlinx-serialization-json` para leer el JSON.
- [ ] **Step 3:** `./gradlew :core:crypto:test --tests '*NoiseVectorsTest*'` → FALLA.
- [ ] **Step 4:** Implementar según Noise rev. 34 (§5 y §9 de modificadores PSK): `MixKey`, `MixHash`, `MixKeyAndHash`; nonce ChaChaPoly = 4 bytes cero + contador de 64 bits little-endian; `psk` aplicado en el token correspondiente y `e` mezclado también con `MixKey` en patrones PSK. Patrones:
  - KKpsk2: pre-mensajes `-> s`, `<- s`; mensajes `-> e, es, ss` · `<- e, ee, se, psk`.
  - XXpsk3: `-> e` · `<- e, ee, s, es` · `-> s, se, psk`.
- [ ] **Step 5:** `./gradlew :core:crypto:test` → PASA.
- [ ] **Step 6:** Commit `feat(crypto): handshakes Noise KKpsk2 y XXpsk3 con vectores oficiales`.

### Task 4: Canal con ratchet por mensaje

**Files:**
- Create: `core/crypto/src/main/kotlin/org/ghostp2/core/crypto/RatchetChannel.kt`
- Test: `core/crypto/src/test/kotlin/org/ghostp2/core/crypto/RatchetChannelTest.kt`

**Interfaces:**
- Consumes: `Primitives`, `HandshakeState.split()`, `handshakeHash()` (Task 3).
- Produces:
  - `class SecurityException(message: String) : Exception(message)` (en `org.ghostp2.core.crypto`)
  - `class RatchetChannel private constructor(...)` con:
    - `companion fun fromHandshake(hs: HandshakeState, initiator: Boolean): RatchetChannel` — cadenas iniciales: `HKDF(salt = handshakeHash, ikm = clave de envío de split, info = "GhostP2 chain v1", 32)` (y lo mismo para recepción); después borra los `CipherState`.
    - `@Synchronized fun encrypt(plaintext: ByteArray): ByteArray`
    - `@Synchronized fun decrypt(frame: ByteArray): ByteArray` — lanza `SecurityException` ante contador inesperado, manipulación o tamaño inválido; solo avanza la cadena si autentica.
    - `fun destroy()`
    - `const val MAX_PLAINTEXT = 49_152`
  - Formato de trama: `[versión=1 (1 byte)][contador u32 BE (4 bytes)][ChaCha20-Poly1305(mk, nonce = 8 ceros + contador BE, aad = 5 bytes de cabecera, relleno(plaintext))]`; relleno: `u32 BE longitud || datos || ceros` hasta múltiplo de 256.

- [ ] **Step 1: Pruebas que fallan** (cada una crea dos canales con un handshake KKpsk2 real entre identidades aleatorias):
  - `roundTripBothDirections` — 5 mensajes en cada sentido, incluido texto con "ñ 👻".
  - `rejectsReplay` — descifrar dos veces la misma trama ⇒ la segunda lanza `SecurityException`.
  - `rejectsReorder` — descifrar la trama 2 antes que la 1 ⇒ `SecurityException`; luego la 1 y la 2 en orden funcionan.
  - `rejectsTamperingWithoutAdvancing` — un bit cambiado en cabecera o cuerpo ⇒ excepción; la trama original sigue siendo aceptada después.
  - `paddingHidesShortLengths` — `encrypt("sí")` y `encrypt("x"×150)` tienen el mismo tamaño.
  - `rejectsOwnReflectedFrame` — un canal no acepta sus propias tramas.
  - `destroyedChannelRefusesWork` — tras `destroy()`, `encrypt` y `decrypt` lanzan `SecurityException`.
  - `rejectsPlaintextOverMax` — `MAX_PLAINTEXT + 1` bytes ⇒ `IllegalArgumentException`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar `RatchetChannel.kt`.
- [ ] **Step 4:** `./gradlew :core:crypto:test` → PASA.
- [ ] **Step 5:** Commit `feat(crypto): canal con ratchet simétrico por mensaje y relleno`.

### Task 5: Identidad, número de seguridad e invitación

**Files:**
- Create: `core/crypto/src/main/kotlin/org/ghostp2/core/crypto/SafetyNumber.kt`, `Base32.kt`, `OnionAddress.kt`, `Invitation.kt`
- Test: `core/crypto/src/test/kotlin/org/ghostp2/core/crypto/InvitationTest.kt`, `SafetyNumberTest.kt`

**Interfaces:**
- Consumes: `Primitives` (Task 2).
- Produces:
  - `object Base32 { fun encode(bytes: ByteArray): String /* RFC 4648, minúsculas, sin relleno */; fun decode(text: String): ByteArray }`
  - `object OnionAddress { fun isValid(address: String): Boolean; fun normalize(address: String): String /* sin ".onion", minúsculas */ }` — v3: 56 caracteres base32 = clave(32) || checksum(2) || versión(1)=3, checksum = `SHA3-256(".onion checksum" || clave || 0x03)[0..2]`.
  - `object SafetyNumber { fun compute(identityA: ByteArray, identityB: ByteArray): String }` — ordena las dos claves, `h = SHA-256("GhostP2 safety v1" || menor || mayor)` iterado 5 200 veces (`h = SHA-256(h || menor || mayor)`), 6 grupos: `u32 BE de h[4i..4i+4] % 100000` con 5 dígitos, separados por espacio.
  - `data class Invitation(val onion: String, val secret: ByteArray)` con `fun encode(): String` y `companion fun parse(text: String): Invitation` — lanza `IllegalArgumentException("Invitación no válida…")`; `secret` 16 bytes.

- [ ] **Step 1: Pruebas que fallan:**
  - `knownOnionIsValid` — `duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad` y la misma con `.onion` ⇒ válidas.
  - `invitationRoundTrip` — `Invitation(onion, secret).encode()` empieza por `ghostp2:` y `parse` devuelve lo mismo.
  - `invitationToleratesWhitespaceAndCase` — la cadena con saltos de línea, espacios y en MAYÚSCULAS ⇒ se parsea igual.
  - `invitationRejectsBadChecksum` — cambiar un carácter de la .onion ⇒ `IllegalArgumentException`.
  - `invitationRejectsWrongSecretLength` y `invitationRejectsMissingPrefix`.
  - `safetyNumberIsSymmetricAndFormatted` — `compute(a,b) == compute(b,a)`, coincide con `^(\d{5} ){5}\d{5}$`, y es distinto para otro par.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar (SHA3-256 con `org.bouncycastle.crypto.digests.SHA3Digest(256)`).
- [ ] **Step 4:** `./gradlew :core:crypto:test` → PASA.
- [ ] **Step 5:** Commit `feat(crypto): invitaciones, direcciones onion v3 y número de seguridad`.

### Task 6: Tramas sobre flujos y handshake de emparejamiento

**Files:**
- Create: `core/protocol/src/main/kotlin/org/ghostp2/core/protocol/FrameStream.kt`, `Pairing.kt`
- Test: `core/protocol/src/test/kotlin/org/ghostp2/core/protocol/FrameStreamTest.kt`, `PairingTest.kt`

**Interfaces:**
- Consumes: `HandshakeState`, `HandshakePattern.XXpsk3`, `RatchetChannel`, `Primitives`, `SafetyNumber`, `OnionAddress` (Tasks 2–5).
- Produces:
  - `class FrameStream(input: InputStream, output: OutputStream, closeable: Closeable)` con `suspend fun send(frame: ByteArray)`, `suspend fun receive(): ByteArray` (en `Dispatchers.IO`; lanza `ProtocolException` si la longitud es ≤ 0 o > 65 536, `EOFException` al cerrar), `fun close()`, `const val MAX_FRAME = 65_536`.
  - `class ProtocolException(message: String) : Exception(message)`
  - `class HandshakeFailedException(message: String) : Exception(message)`
  - `data class PairingOffer(val myOnion: String, val clientAuthPrivateForPeer: ByteArray)` — lo que yo entrego: mi .onion y la clave privada x25519 con la que el otro entrará en ella.
  - `data class PairingResult(val peerIdentity: ByteArray, val peerOnion: String, val clientAuthPrivateForPeerOnion: ByteArray, val pairSecret: ByteArray, val safetyNumber: String)`
  - `object Pairing`:
    - `suspend fun asInviter(stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer): PairingResult` (respondedor XXpsk3)
    - `suspend fun asInvitee(stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer): PairingResult` (iniciador XXpsk3)
  - Parámetros: `psk = HKDF(salt = 32 ceros, ikm = secret, info = "GhostP2 pairing psk v1", 32)`, prólogo `"GhostP2 pairing v1"`, payloads de handshake vacíos; tras el handshake cada lado envía por `RatchetChannel` su `PairingOffer` en CBOR (`@Serializable` interno) y recibe el del otro; `pairSecret = HKDF(salt = secret, ikm = handshakeHash, info = "GhostP2 pair-secret v1", 32)`; validar `OnionAddress.isValid` y longitud 32 de la clave; tiempo máximo total 60 s.

- [ ] **Step 1: Pruebas que fallan:**
  - `framesRoundTrip` — sobre `PipedInputStream`/`PipedOutputStream`, tramas de 1 byte y de 65 536 bytes.
  - `rejectsOversizedLengthPrefix` — escribir el prefijo `0x7fffffff` ⇒ `ProtocolException` sin `OutOfMemoryError`; prefijo `0` y negativo ⇒ `ProtocolException`.
  - `pairingExchangesInfoAndAgrees` — dos corrutinas sobre tuberías cruzadas: cada lado obtiene la identidad, .onion y clave de autorización del otro; `pairSecret` y `safetyNumber` iguales en ambos lados.
  - `pairingFailsWithWrongSecret` — secretos distintos ⇒ ambos lados lanzan `HandshakeFailedException`.
  - `pairingRejectsInvalidOnion` — oferta con .onion inválida ⇒ el receptor lanza `ProtocolException`.
- [ ] **Step 2:** `./gradlew :core:protocol:test` → FALLA.
- [ ] **Step 3:** Implementar; envolver `AuthenticationException`/`SecurityException` del handshake en `HandshakeFailedException`.
- [ ] **Step 4:** `./gradlew :core:protocol:test` → PASA.
- [ ] **Step 5:** Commit `feat(protocol): tramas sobre flujo y emparejamiento Noise XXpsk3`.

### Task 7: Handshake de sesión entre contactos

**Files:**
- Create: `core/protocol/src/main/kotlin/org/ghostp2/core/protocol/SessionHandshake.kt`
- Test: `core/protocol/src/test/kotlin/org/ghostp2/core/protocol/SessionHandshakeTest.kt`

**Interfaces:**
- Consumes: `FrameStream`, `HandshakeFailedException` (Task 6); `HandshakeState`, `HandshakePattern.KKpsk2`, `RatchetChannel` (Tasks 3–4).
- Produces:
  - `data class ContactKeys(val peerIdentity: ByteArray, val pairSecret: ByteArray)`
  - `object SessionHandshake`:
    - `suspend fun initiate(stream: FrameStream, identity: KeyPair25519, contact: ContactKeys): RatchetChannel`
    - `suspend fun respond(stream: FrameStream, identity: KeyPair25519, candidates: List<ContactKeys>): Pair<ContactKeys, RatchetChannel>` — el respondedor no sabe quién llama: prueba cada contacto con un `HandshakeState` nuevo sobre el mismo primer mensaje y se queda con el que autentica.
  - Prólogo `"GhostP2 session v1"`, `psk = pairSecret`, tiempo máximo 30 s.

- [ ] **Step 1: Pruebas que fallan:**
  - `sessionOpensBetweenPairedContacts` — tras un handshake, un mensaje cifrado con el canal del iniciador se descifra en el del respondedor y viceversa.
  - `responderIdentifiesCallerAmongMany` — el respondedor tiene 5 contactos y reconoce al correcto.
  - `sessionRejectsUnknownIdentity` — el iniciador usa una identidad nueva (reinstalación) ⇒ el respondedor lanza `HandshakeFailedException`.
  - `sessionRejectsWrongPairSecret` ⇒ `HandshakeFailedException`.
  - `sessionKeysAreFreshEachTime` — dos sesiones entre los mismos contactos producen tramas distintas para el mismo texto.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:protocol:test` → PASA.
- [ ] **Step 5:** Commit `feat(protocol): handshake de sesión Noise KKpsk2 con identificación del llamante`.

### Task 8: Mensajes de aplicación y sesión (texto, fotos, acuses, descarte)

**Files:**
- Create: `core/protocol/src/main/kotlin/org/ghostp2/core/protocol/AppMessage.kt`, `PeerSession.kt`
- Test: `core/protocol/src/test/kotlin/org/ghostp2/core/protocol/PeerSessionTest.kt`

**Interfaces:**
- Consumes: `FrameStream`, `RatchetChannel` (Tasks 4, 6).
- Produces:
  - `@Serializable sealed interface AppMessage` (CBOR) con: `Text(id: String, text: String)`, `PhotoStart(id: String, totalBytes: Int, chunkCount: Int)`, `PhotoChunk(id: String, index: Int, data: ByteArray)`, `PhotoEnd(id: String)`, `Ack(id: String)`, `Ping`, `Bye`, `Unpair`.
  - `sealed interface SessionEvent`: `TextReceived(id: String, text: String)`, `PhotoReceived(id: String, jpeg: ByteArray)`, `PhotoProgress(id: String, sentBytes: Int, totalBytes: Int)`, `Delivered(id: String)`, `Failed(id: String, reason: FailReason)`, `PeerUnpaired`, `Closed(reason: CloseReason)`.
  - `enum class FailReason { NOT_CONNECTED, CONNECTION_LOST, NO_ACK }`, `enum class CloseReason { PEER_BYE, PEER_UNPAIRED, TIMEOUT, SECURITY, IO, LOCAL }`
  - `class PeerSession(stream: FrameStream, channel: RatchetChannel, scope: CoroutineScope, clock: TimeSource = TimeSource.Monotonic)` con:
    - `val events: SharedFlow<SessionEvent>` (con búfer; no pierde eventos)
    - `val isOpen: StateFlow<Boolean>`
    - `fun start()`
    - `suspend fun sendText(text: String): String` (id hex de 12 bytes aleatorios; lanza `IllegalArgumentException` si > 8 000 caracteres)
    - `suspend fun sendPhoto(jpeg: ByteArray): String` (> 3 145 728 bytes ⇒ `IllegalArgumentException`)
    - `suspend fun sendUnpair()`
    - `suspend fun close(reason: CloseReason = CloseReason.LOCAL)` (envía `Bye` si puede)
  - Reglas: un único bucle de lectura; los envíos pasan por un `Mutex` (cifrar + escribir atómico); latido `Ping` cada 20 s; sin tramas entrantes en 60 s ⇒ `Closed(TIMEOUT)`; acuse en 30 s o `Failed(NO_ACK)`; al cerrarse, todo pendiente ⇒ `Failed(CONNECTION_LOST)` y las fotos entrantes incompletas se descartan sin evento; cualquier `SecurityException` ⇒ `Closed(SECURITY)`; `Unpair` recibido ⇒ `PeerUnpaired` y luego `Closed(PEER_UNPAIRED)`; `sendText`/`sendPhoto` con la sesión cerrada devuelven el id y emiten `Failed(id, NOT_CONNECTED)` sin enviar nada; el receptor acusa `Text` y `PhotoEnd` (solo si llegaron todos los trozos y suman `totalBytes`; si no, cierra con `SECURITY`).

- [ ] **Step 1: Pruebas que fallan** (dos `PeerSession` sobre tuberías cruzadas con canales reales; tiempos con `runTest` y tiempo virtual):
  - `textIsDeliveredAndAcked` — A envía; B emite `TextReceived`; A emite `Delivered` con el mismo id.
  - `photoArrivesWholeAndAcked` — foto de 200 000 bytes aleatorios ⇒ B recibe bytes idénticos; A ve `PhotoProgress` hasta 200 000 y `Delivered`.
  - `photoInterruptedIsDiscardedOnBothSides` — cerrar el transporte de B tras 3 trozos ⇒ A emite `Failed(id, CONNECTION_LOST)`, B nunca emite `PhotoReceived`.
  - `pendingFailsWhenConnectionDrops` — texto enviado y transporte cortado antes del acuse ⇒ `Failed(CONNECTION_LOST)`.
  - `missingAckFailsAfter30s` — el receptor no acusa (sesión B sin `start()`) ⇒ a los 30 s virtuales `Failed(NO_ACK)`.
  - `silentPeerTimesOutAfter60s` ⇒ `Closed(TIMEOUT)`.
  - `unpairIsDelivered` — A `sendUnpair()` ⇒ B emite `PeerUnpaired` y `Closed(PEER_UNPAIRED)`.
  - `concurrentSendsKeepCounterOrder` — 50 `sendText` lanzados en paralelo ⇒ B recibe los 50, todos `Delivered`, ningún `Closed(SECURITY)`.
  - `sendAfterCloseFailsNotConnected` — tras `close()`, `sendText` emite `Failed(id, NOT_CONNECTED)`.
  - `oversizeInputsRejected` — texto de 8 001 caracteres y foto de 3 145 729 bytes ⇒ `IllegalArgumentException`.
- [ ] **Step 2:** Ejecutar → FALLA.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** `./gradlew :core:protocol:test` → PASA.
- [ ] **Step 5:** Commit `feat(protocol): sesión con texto, fotos troceadas, acuses, latido y descarte`.

### Task 9: Integración con sockets reales y documento del protocolo

**Files:**
- Create: `core/protocol/src/test/kotlin/org/ghostp2/core/protocol/EndToEndSocketTest.kt`
- Create: `docs/PROTOCOLO.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `Pairing`, `SessionHandshake`, `PeerSession`, `FrameStream`, `Invitation`, `SafetyNumber`.

- [ ] **Step 1: Prueba** `pairThenChatOverLocalhost` — A abre `ServerSocket(0, 50, InetAddress.getLoopbackAddress())`, genera `Invitation` (con una .onion válida ficticia); B la parsea, conecta, ambos emparejan; nueva conexión: B inicia sesión KKpsk2, A responde reconociéndolo; intercambian un texto y una foto de 1 MiB; A cierra con `close()` ⇒ B emite `Closed(PEER_BYE)` y un envío posterior de B emite `Failed(id, NOT_CONNECTED)`.
- [ ] **Step 2:** `./gradlew test` → PASA (todos los módulos).
- [ ] **Step 3:** Escribir `docs/PROTOCOLO.md`: nombres Noise, prólogos, derivaciones HKDF (todas las cadenas `info` de este plan), formato de trama y de ratchet, mensajes CBOR, tiempos y límites, y el modelo de amenazas resumido del spec §7.
- [ ] **Step 4:** Reescribir `README.md`: qué es GhostP2, estado (núcleo listo; Android en planes 2 y 3), cómo ejecutar `./gradlew test`, enlace a `docs/PROTOCOLO.md`.
- [ ] **Step 5:** Commit `test(protocol): prueba de extremo a extremo con sockets y documentación del protocolo`.

## Decisiones tomadas durante la ejecución (registro)

- Ruling R1: rename crypto `SecurityException` → `ChannelSecurityException` (org.ghostp2.core.crypto) — avoids shadowing java.lang.SecurityException — cost if wrong: trivial rename.
- Ruling R2: tests needing a duplex stream use a shared test helper of connected loopback `Socket` pairs (or java.nio Pipe-backed streams), not PipedInput/OutputStream — Piped streams break across thread pools — cost: none.
- Ruling R3: PeerSession timers (heartbeat 20s, idle 60s, ack 30s) run via `delay` on the injected scope so `runTest` virtual time drives them; blocking reads stay on Dispatchers.IO. `clock` param may be dropped if unused — cost: minor API diff.
- Ruling R4: SafetyNumber = h0=SHA256(label||low||high) then 5200 further iterations h=SHA256(h||low||high) — matches plan text literally; Task 9 PROTOCOLO.md must state it — cost: none (no deployed peers).
- Ruling R5: Invitation as plain class with contentEquals/hashCode and secret-hiding toString instead of data class — data class would leak secret in toString — cost: none.
- Ruling R6: KKpsk2 responder cannot verify pairSecret from msg1 (psk only in msg2) — add key confirmation: after handshake the initiator immediately sends one RatchetChannel-encrypted confirm frame (fixed plaintext "GhostP2 session confirm v1"); respond() decrypts and checks it (constant-time) before returning, all inside the 30 s timeout; failure → HandshakeFailedException. Restores the brief's sessionRejectsWrongPairSecret on both sides — cost: one extra frame per session; PeerSession counters start after it.
- Ruling R7: plan 2 must run PeerSession with a service-owned SupervisorJob scope on Dispatchers.Default (never Main/Main.immediate/Unconfined) and collect events there — residual minor "immediate dispatchers break id-before-event" — cost: none if plan 2 follows it.
- Ruling R8: ack-queue overflow closes with SECURITY but may happen to an honest peer on a very slow link; plan 2 must not treat Closed(SECURITY) alone as proof of attack (no auto-block) — cost: misleading label only.
