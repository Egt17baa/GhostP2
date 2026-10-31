# Protocolo de GhostP2 (v1)

Este documento describe el protocolo tal como está implementado en los módulos `:core:crypto`
(`org.ghostp2.core.crypto`) y `:core:protocol` (`org.ghostp2.core.protocol`). Si algo de aquí
contradice el código, manda el código: cada etiqueta, tamaño y plazo se ha copiado de él.

Diseño general: [`docs/superpowers/specs/2026-10-31-ghostp2-android-design.md`](superpowers/specs/2026-10-31-ghostp2-android-design.md).

## 1. Visión general

Dos personas hablan directamente entre sus móviles. Cada móvil publica un servicio oculto de
Tor (.onion v3) con autorización de cliente; el núcleo solo ve un flujo de bytes fiable y
ordenado (en la app, un socket Tor; en las pruebas, un socket TCP de bucle local).

1. **Invitación**: A muestra `ghostp2:<onion>.<secreto>` (QR o texto).
2. **Emparejamiento** (una sola vez, Noise `XXpsk3`): B conecta a la .onion temporal de A; se
   autentican con el secreto de la invitación, intercambian sus identidades X25519, sus .onion
   permanentes y las claves de autorización de cliente, y derivan un `pairSecret` común.
3. **Sesión** (en cada conexión, Noise `KKpsk2`): cualquiera de los dos llama al otro; ambos
   conocen ya la identidad del otro y usan `pairSecret` como PSK. Claves efímeras nuevas en
   cada sesión.
4. **Canal**: tras cada handshake, un `RatchetChannel` con una clave por mensaje, contador
   estrictamente secuencial y relleno a 256 bytes.
5. **Aplicación**: `PeerSession` intercambia mensajes CBOR (texto, foto troceada, acuses,
   latido, adiós, desemparejar). Nada se encola: si no hay sesión, el mensaje se descarta.

Pila en el cable: `TCP/Tor → FrameStream (u32 BE longitud || trama) → handshake Noise o trama
RatchetChannel → CBOR (AppMessage / PairingPayload)`.

## 2. Primitivas (`Primitives`, BouncyCastle API ligera, sin proveedor JCA)

| Primitiva | Detalle |
|---|---|
| X25519 | Claves de 32 bytes. `x25519()` rechaza un secreto compartido todo ceros (punto de orden bajo) con `AuthenticationException`. |
| SHA-256 | `sha256(partes...)` = SHA-256 de la concatenación. |
| HMAC-SHA256 | `hmacSha256(clave, partes...)`. |
| HKDF | RFC 5869 con HMAC-SHA256: `hkdf(salt, ikm, info, longitud)`, longitud 0..8160. |
| AEAD | ChaCha20-Poly1305 (RFC 8439): clave 32, nonce 12, etiqueta 16; salida `cifrado || etiqueta`. Fallo de etiqueta ⇒ `AuthenticationException`. |
| Comparación | `constantTimeEquals` = `MessageDigest.isEqual` (tiempo constante). |
| Azar | `SecureRandom`. |
| SHA3-256 | Solo para la suma de control de las .onion v3. |

Excepciones: `AuthenticationException` (fallo AEAD o DH inválido) y `ChannelSecurityException`
(violación del canal con ratchet; se llama así para no ocultar `java.lang.SecurityException`).

## 3. Noise

Implementación propia de Noise rev. 34 (`noise/`), validada con los vectores de cacophony.

| Uso | Nombre del protocolo | Patrón |
|---|---|---|
| Emparejamiento | `Noise_XXpsk3_25519_ChaChaPoly_SHA256` | `-> e` · `<- e, ee, s, es` · `-> s, se, psk` |
| Sesión | `Noise_KKpsk2_25519_ChaChaPoly_SHA256` | pre-mensajes `-> s`, `<- s`; `-> e, es, ss` · `<- e, ee, se, psk` |

- Nombre del protocolo > 32 bytes ⇒ `h = SHA-256(nombre)`, `ck = h`.
- Al ser patrones PSK, cada token `e` hace `MixHash(e.pub)` y además `MixKey(e.pub)`; el token
  `psk` hace `MixKeyAndHash(psk)`. PSK de 32 bytes.
- KK: los pre-mensajes se mezclan en el orden estática del iniciador, estática del respondedor.
- HKDF de Noise (§4.3, distinto de RFC 5869): `temp = HMAC(ck, ikm)`, `out_i = HMAC(temp, out_{i-1} || i)`.
- CipherState: nonce = 4 bytes a cero || contador de 64 bits **little-endian**; 2^64-1
  reservado. Mensaje Noise máximo 65 535 bytes.
- Cualquier error en `readMessage`/`writeMessage` aborta el handshake y borra sus secretos;
  toda llamada posterior lanza `IllegalStateException`.
- `split()` devuelve `(enviar, recibir)` ya orientados según el rol, solo una vez, y borra
  efímeras, PSK y `ck`; `handshakeHash()` sigue disponible tras `split()`.
- Todas las cargas útiles de los handshakes van **vacías**. Tamaños resultantes (medidos):
  XXpsk3 48 / 96 / 64 bytes; KKpsk2 48 / 48 bytes.

## 4. Invitación, .onion y número de seguridad

### 4.1 Invitación (`Invitation`)

```
ghostp2:<onion v3, 56 caracteres>.<secreto base32, 26 caracteres>
```

- Secreto: 16 bytes aleatorios (`SECRET_SIZE = 16`), en base32 RFC 4648 minúsculas sin relleno
  (26 caracteres; los 2 bits finales deben ser cero: decodificación estricta y canónica).
- Longitud total: 8 + 56 + 1 + 26 = 91 caracteres.
- `parse` elimina todos los espacios en blanco y pasa a minúsculas; exige el prefijo
  `ghostp2:`, exactamente dos partes separadas por `.`, una .onion válida de 56 caracteres
  (sin sufijo `.onion`) y un secreto de 26 caracteres.
- `toString()` nunca muestra el secreto.

### 4.2 Dirección .onion v3 (`OnionAddress`)

`base32(clave(32) || checksum(2) || versión(1) = 0x03)` = 56 caracteres, con
`checksum = SHA3-256(".onion checksum" || clave || 0x03)[0..2]`. `normalize` recorta
espacios, pasa a minúsculas y quita el sufijo `.onion`.

### 4.3 Número de seguridad (`SafetyNumber`)

Solo depende de las dos claves de identidad X25519 (32 bytes cada una):

```
(low, high) = ordenar(idA, idB)               // comparación lexicográfica sin signo
h = SHA-256("GhostP2 safety v1" || low || high)
repetir 5200 veces: h = SHA-256(h || low || high)
grupo_i = (u32 big-endian de h[4i..4i+3]) mod 100000, con 5 dígitos, i = 0..5
resultado = 6 grupos separados por un espacio (30 dígitos)
```

Es idéntico en ambos lados. Vector conocido (calculado con Python 3 + `hashlib`, fijado en
`SafetyNumberTest.knownAnswerVector`): claves `0x01×32` y `0x02×32` ⇒
`11384 69342 18126 46878 36715 63359`.

## 5. Tramas sobre el flujo (`FrameStream`)

```
[longitud: u32 big-endian][carga: longitud bytes]      1 ≤ longitud ≤ 65 536 (MAX_FRAME)
```

- La longitud se valida antes de reservar memoria: ≤ 0 o > 65 536 ⇒ `ProtocolException`.
- Cierre del flujo antes de una trama o a mitad ⇒ `EOFException`.
- Enviar una trama vacía o > 65 536 bytes ⇒ `IllegalArgumentException`.
- Envíos y recepciones se serializan con un `Mutex` por sentido; la E/S bloqueante va en
  `Dispatchers.IO`. Si la corrutina se cancela con E/S en curso, el flujo se **cierra** (única
  forma de desbloquear un socket; la trama quedaría a medias).

## 6. Emparejamiento (`Pairing`, Noise XXpsk3)

- Roles: el **invitador** (quien generó la invitación, escucha) es el **respondedor** XXpsk3;
  el **invitado** (quien la escaneó, conecta) es el **iniciador**.
- Prólogo: `"GhostP2 pairing v1"` (UTF-8).
- PSK: `HKDF(salt = 32 bytes a cero, ikm = secreto de la invitación (16 bytes), info = "GhostP2 pairing psk v1", 32)`.
- Clave estática Noise = la `identityKey` X25519 de cada lado (viaja cifrada en msg2 y msg3).
- Secuencia: invitado → msg1; invitador → msg2; invitado → msg3.
- `pairSecret = HKDF(salt = secreto de la invitación, ikm = handshakeHash, info = "GhostP2 pair-secret v1", 32)`,
  calculado antes de `split()`.
- Después se crea un `RatchetChannel` (§8) y se intercambian tres tramas del canal en orden fijo:
  1. el **invitado** envía su `PairingOffer`;
  2. el **invitador** envía la suya;
  3. el **invitado** envía la trama de cierre con el texto `"GhostP2 pairing ok v1"` (UTF-8,
     21 bytes; trama de 5 + 256 + 16 = 277 bytes).

  El invitador descifra la trama 3 y la compara en tiempo constante; solo entonces devuelve su
  resultado. Así el invitador nunca da por emparejado a quien no recibió su oferta. El invitado
  termina en cuanto escribe la trama 3: si esta se pierde, el invitado queda emparejado y el
  invitador no (caso residual, §13).
- Oferta (`PairingPayload`, CBOR, mapa de longitud indefinida con claves de texto):

  | Campo | Tipo CBOR | Contenido |
  |---|---|---|
  | `onion` | texto | .onion permanente de quien la envía |
  | `clientAuthPrivate` | **bstr** (`@ByteString`) | clave privada x25519 (32 bytes) con la que el otro entrará en mi .onion |

  Ejemplo (onion `"ab"`, clave `01 02`; fijado en `PairingTest.offerWireFormatUsesByteString`):
  `bf 65 6f6e696f6e 62 6162 71 636c69656e744175746850726976617465 42 0102 ff`.
- Oferta local: debe tener una .onion v3 válida (tras normalizar) y una clave de 32 bytes; si no
  ⇒ `IllegalArgumentException` al entrar, sin tocar el flujo (error de programación).
- Validación de la oferta recibida: CBOR decodificable (cualquier fallo del decodificador, no solo
  `SerializationException`), .onion v3 válida (se normaliza) y clave de 32 bytes; si no ⇒
  `ProtocolException`.
- Resultado (`PairingResult`): identidad del otro, su .onion, la clave de autorización para su
  .onion, `pairSecret` y número de seguridad (§4.3). Es de quien llama: debe guardar y luego
  borrar `pairSecret` y `clientAuthPrivateForPeerOnion`. Al terminar bien el flujo no se cierra:
  lo cierra quien llama.
- Plazo total: **60 s** (`Pairing.TIMEOUT_MILLIS = 60_000`), con `withTimeoutOrNull`: un plazo
  exterior del llamador no se convierte, se propaga como cancelación.
- Errores: `HandshakeFailedException` con motivo (§7.1) y `ProtocolException` (datos mal
  formados). Secreto distinto: el invitador lo detecta al leer msg3 (`AUTH`) y el invitado ve
  cerrarse la conexión (`CONNECTION`). Ante cualquier fallo se cierra el flujo. Siempre se
  borran PSK, handshake y canal; si falla, también `pairSecret` y la clave de autorización
  recibida.

## 7. Sesión (`SessionHandshake`, Noise KKpsk2)

- Prólogo: `"GhostP2 session v1"` (UTF-8).
- PSK = `pairSecret` (32 bytes). Clave estática remota = identidad guardada del contacto
  (`ContactKeys(peerIdentity, pairSecret)`); el handshake copia estos arrays y nunca los borra.
- Secuencia: iniciador → msg1 (`e, es, ss`); respondedor → msg2 (`e, ee, se, psk`).
- **Prueba de candidatos en el respondedor**: no sabe quién llama; lee msg1 una vez y lo prueba
  con un `HandshakeState` nuevo por cada contacto de la lista `candidates`. msg1 lleva una
  etiqueta AEAD (carga vacía cifrada tras `es`/`ss`), así que solo autentica el contacto cuya
  identidad es la del llamante. Se queda con el primero que autentica y devuelve **la misma
  instancia** de `ContactKeys`. Ninguno ⇒ `HandshakeFailedException(UNKNOWN_CALLER, "Llamante desconocido")`.
  Los candidatos deben tener identidades distintas (si no ⇒ `IllegalArgumentException` al
  entrar, sin tocar el flujo): la capa de datos guarda un solo contacto por clave de identidad.
- **Confirmación de clave**: en KKpsk2 la PSK solo entra en msg2, así que msg1 autentica la
  identidad pero no está ligado al `pairSecret`. Por eso, tras completar el handshake, el
  **iniciador** envía como primera trama del canal (contador 0) el texto
  `"GhostP2 session confirm v1"` (UTF-8, 26 bytes; trama de 5 + 256 + 16 = 277 bytes). El
  **respondedor** la descifra y la compara en tiempo constante antes de devolver su canal. Así
  **ambos** lados rechazan un `pairSecret` distinto durante el handshake. La trama se consume
  aquí: la primera trama de aplicación del iniciador usa el contador 1 y la del respondedor el 0.
- Plazo: **30 s** (`SessionHandshake.TIMEOUT_MILLIS = 30_000`) para todo el handshake, incluida
  la confirmación. Un plazo exterior del llamador no se convierte: se propaga como cancelación.
- Errores: `HandshakeFailedException` con motivo (§7.1) y `ProtocolException`; una carga no
  vacía en un mensaje del handshake ⇒ `ProtocolException`. `pairSecret` distinto: el iniciador
  lo detecta al leer msg2 (`AUTH`) y el respondedor ve cerrarse la conexión (`CONNECTION`).
  Ante cualquier fallo se cierra el flujo y se borran todos los estados probados.
- Propiedad: `ContactKeys` y la identidad son de quien llama (nunca se borran). El
  `RatchetChannel` devuelto también: si se entrega a una `PeerSession`, ella lo destruye; si no
  (p. ej. pierde el desempate de conexiones duplicadas, §10.5), quien llama debe hacer
  `channel.destroy()` y `stream.close()`.

### 7.1 Motivos de `HandshakeFailedException` (`reason`)

| Motivo | Cuándo |
|---|---|
| `AUTH` | `AuthenticationException` o `ChannelSecurityException` (secreto o `pairSecret` distinto, trama no auténtica), o trama de confirmación (`session confirm` / `pairing ok`) con otro texto |
| `UNKNOWN_CALLER` | Sesión, respondedor: ningún candidato autentica msg1 (identidad desconocida o cambiada, o msg1 basura) |
| `CONNECTION` | `IOException`/EOF: el otro lado cerró o la conexión cayó. Es lo que ve el lado que **no** detectó el fallo |
| `TIMEOUT` | Venció nuestro plazo (60 s emparejamiento, 30 s sesión); mensajes `"Tiempo de emparejamiento agotado"` / `"Tiempo de sesión agotado"` |
| `STATE` | `IllegalStateException`, handshake incompleto o sin identidad remota |

## 8. Canal con ratchet (`RatchetChannel`)

### 8.1 Derivación desde el handshake

```
(k_enviar, k_recibir) = split()           // orientado según el rol
h = handshakeHash()
ck_enviar  = HKDF(salt = h, ikm = k_enviar,  info = "GhostP2 chain v1", 32)
ck_recibir = HKDF(salt = h, ikm = k_recibir, info = "GhostP2 chain v1", 32)
```

Se borran `h`, las claves de `split()` y los `CipherState` resultantes. Contadores iniciales 0.

### 8.2 Ratchet por mensaje (una cadena por sentido)

```
mk  = HMAC-SHA256(ck, 0x01)     // clave del mensaje, se usa una vez y se borra
ck' = HMAC-SHA256(ck, 0x02)     // la cadena anterior se borra
```

### 8.3 Formato de la trama

```
cabecera (5 bytes) = [versión = 0x01][contador u32 big-endian]
nonce   (12 bytes) = 8 bytes a cero || contador u32 big-endian
aad                = los 5 bytes de cabecera
relleno(texto)     = u32 BE longitud || texto || ceros, hasta múltiplo de 256 (mínimo de bloques)
trama              = cabecera || ChaCha20-Poly1305(mk, nonce, aad, relleno(texto))
```

| Límite | Valor |
|---|---|
| Texto máximo por trama (`MAX_PLAINTEXT`) | 49 152 bytes |
| Trama mínima | 5 + 256 + 16 = 277 bytes |
| Trama máxima | 5 + 49 408 + 16 = 49 429 bytes |
| Tamaño válido | `(tamaño − 21) mod 256 = 0` |
| Contador | 0 .. 2^32−2 (2^32−1 reservado); agotado ⇒ `ChannelSecurityException` |

### 8.4 Rigidez

- Antes de cualquier operación criptográfica se validan tamaño, versión y contador.
- El contador debe ser **exactamente** el esperado: repetición, salto o desorden ⇒
  `ChannelSecurityException`.
- La cadena de recepción solo avanza si la trama autentica **y** el relleno es canónico
  (longitud ≤ 49 152, número mínimo de bloques, ceros). Una trama auténtica con relleno no
  canónico se rechaza sin avanzar; la sesión lo trata como fatal.
- Las cadenas de envío y recepción son distintas: una trama propia reflejada no descifra.
- `destroy()` borra ambas cadenas; después todo uso lanza `ChannelSecurityException`.

## 9. Mensajes de aplicación (`AppMessage`, CBOR)

Cada trama del canal lleva exactamente un mensaje. Codificación kotlinx-serialization CBOR:
polimorfismo como **array de longitud indefinida** `[etiqueta, objeto]`, con el objeto como
**mapa de longitud indefinida** cuyas claves son los nombres de campo en texto
(`9f <etiqueta> bf ... ff ff`).

| Etiqueta | Mensaje | Campos |
|---|---|---|
| `t` | `Text` | `id`: texto, `text`: texto |
| `ps` | `PhotoStart` | `id`: texto, `totalBytes`: entero, `chunkCount`: entero |
| `pc` | `PhotoChunk` | `id`: texto, `index`: entero, `data`: **bstr** (`@ByteString`) |
| `pe` | `PhotoEnd` | `id`: texto |
| `a` | `Ack` | `id`: texto |
| `p` | `Ping` | — |
| `b` | `Bye` | — |
| `u` | `Unpair` | — |

- `id`: 24 caracteres hexadecimales en minúscula (12 bytes aleatorios), regex `[0-9a-f]{24}`.
- Ejemplos: `Ping` = `9f 61 70 bf ff ff`; `Ack("00112233445566778899aabb")` =
  `9f 61 61 bf 62 6964 78 18 <24 bytes ASCII> ff ff`.
- Cualquier fallo al decodificar ⇒ `ProtocolException`.

## 10. Comportamiento de la sesión (`PeerSession`)

### 10.1 Límites y tiempos

| Parámetro | Valor |
|---|---|
| Texto | máx. 8 000 caracteres Unicode (puntos de código) |
| Foto | 1 .. 3 145 728 bytes (3 MiB) |
| Trozo de foto | 32 768 bytes (el último, el resto) |
| Latido (`Ping`) | cada 20 s, haya o no otro tráfico |
| Inactividad | sin ninguna trama entrante en 60 s ⇒ `Closed(TIMEOUT)` |
| Acuse | no recibido en 30 s ⇒ `Failed(id, NO_ACK)` (texto: desde que se registra el envío; foto: desde `PhotoEnd`) |
| Vigilante de escritura | una sola escritura de trama tarda más de 30 s (el plazo de acuse) ⇒ `Closed(IO)` |
| Cola de acuses salientes (`ACK_QUEUE_CAPACITY`) | 256; si se llena ⇒ `Closed(SECURITY)` |
| Espera tras `Bye`/`Unpair` (`linger`) | hasta 2 s a que el otro cierre la conexión |

### 10.2 Arranque y eventos

- Construcción: `PeerSession(stream, channel, scope)`. `scope` debe ser un scope con
  `SupervisorJob` que viva más que la sesión; cancelarlo cierra la sesión (`LOCAL`). Desde ese
  momento la sesión es dueña del flujo y del canal.
- `events` es un `SharedFlow` sin reproducción y con búfer ilimitado. `start()` es `suspend`:
  **espera a que haya al menos un suscriptor o a que la sesión se cierre** y entonces (si sigue
  abierta) lanza el lector, el latido, el vigilante de inactividad y el emisor de acuses. Es
  idempotente y nunca se queda colgada.
- Contrato: un único recolector de larga vida (repositorio/servicio, no la interfaz), lanzado
  antes de `start()`. Los eventos emitidos sin recolector activo **se pierden**, aunque el `Ack`
  ya se haya enviado (el otro lado verá "entregado"). No usar `Dispatchers.Unconfined` (los
  eventos se emiten con un cerrojo interno tomado).
- Eventos: `TextReceived`, `PhotoReceived`, `PhotoProgress` (tras cada trozo enviado),
  `Delivered`, `Failed(id, NOT_CONNECTED | CONNECTION_LOST | NO_ACK)`, `PeerUnpaired`,
  `Closed(PEER_BYE | PEER_UNPAIRED | TIMEOUT | SECURITY | IO | LOCAL)`. Ningún `toString`
  muestra contenido.

### 10.3 Envío y descarte

- `sendText`/`sendPhoto` **no suspenden**: validan (`IllegalArgumentException`), generan el id,
  registran el envío, lo lanzan en el `scope` de la sesión y devuelven el id enseguida. Ningún
  evento de ese id se emite mientras la llamada está en curso; con un recolector confinado en el
  mismo hilo que quien llama, los eventos de un id llegan siempre después de que este lo guarde.
- Si la sesión no está abierta (o se está cerrando) el mensaje **se descarta**: se devuelve el
  id y `Failed(id, NOT_CONNECTED)` se emite después, de forma asíncrona. No hay cola ni reintentos.
- Cada mensaje enviado termina exactamente en un `Delivered` o un `Failed`.
- El envío se arranca sin despachar (hasta su primera suspensión corre en el hilo de quien
  llama), así toma su turno en los `Mutex` (justos, FIFO) en el orden de las llamadas: envíos
  sucesivos desde un mismo hilo salen en ese orden. La cancelación de quien llama no le afecta;
  solo cancelar el `scope` interrumpe el envío, y entonces la sesión se cierra (`LOCAL`).
- Cifrar y escribir es atómico (un `Mutex`): el orden de contadores coincide con el del cable.
- Vigilante de escritura: si una escritura de trama tarda más de 30 s, el otro lado no lee ⇒
  `Closed(IO)` (y los pendientes `Failed(CONNECTION_LOST)`).
- Foto: `PhotoStart`, `chunkCount = ⌈total / 32 768⌉` trozos en orden, `PhotoEnd`. **Una sola
  foto saliente a la vez** por sentido (otro `Mutex`). El plazo de acuse empieza tras
  `PhotoEnd`; un `Ack` que llega mientras la foto aún se envía se ignora.
- Texto: el plazo de acuse empieza al registrar el envío (no al terminar de escribirlo), así un
  texto atascado en una escritura bloqueada también falla a los 30 s.
- Los acuses los envía una corrutina aparte (cola de 256), no el lector. Si la cola se llena
  (el otro lado envía sin leer nuestros acuses) ⇒ `ProtocolException` ⇒ `Closed(SECURITY)`.

### 10.4 Recepción

- Cada trama entrante (cualquiera) reinicia el plazo de inactividad.
- `Text`: id válido y ≤ 8 000 caracteres; se emite `TextReceived` y se envía `Ack`.
- Reglas de la foto entrante (cualquier violación ⇒ `ProtocolException` ⇒ `Closed(SECURITY)`):
  - `PhotoStart`: id válido; `1 ≤ totalBytes ≤ 3 145 728`; `chunkCount = ⌈totalBytes / 32 768⌉`;
    no puede haber otra foto entrante en curso.
  - `PhotoChunk`: debe ser de la foto en curso; `0 ≤ index < chunkCount`; `index` = el siguiente
    esperado (en orden); tamaño exacto `min(32 768, totalBytes − index·32 768)`.
  - `PhotoEnd`: de la foto en curso y con todos los trozos recibidos; solo entonces se emite
    `PhotoReceived` y se envía `Ack`. Una foto llega entera o no llega.
- `Ack`: id válido; si corresponde a un mensaje pendiente que espera acuse ⇒ `Delivered`. Un
  acuse desconocido, tardío (ya `NO_ACK`) o de una foto aún enviándose se ignora.
- `Ping`: nada. `Bye` ⇒ `Closed(PEER_BYE)`. `Unpair` ⇒ `PeerUnpaired` y `Closed(PEER_UNPAIRED)`.
- Mientras la sesión se está cerrando se descartan textos y fotos entrantes (ni se muestran ni
  se acusan); `Ack`, `Bye` y `Unpair` se procesan siempre (un `Unpair` recibido durante la
  espera de cierre se notifica).
- Errores del lector: `IOException`/EOF ⇒ `Closed(IO)`; `ChannelSecurityException`,
  `ProtocolException` u otro ⇒ `Closed(SECURITY)`.

### 10.5 Cierre

- `close()` envía `Bye` y `sendUnpair()` envía `Unpair` y esperan como mucho 2 s a que el otro
  cierre; luego `Closed(LOCAL)`. Ambos son idempotentes. `sendUnpair(): Boolean` devuelve
  `true` solo si la trama `Unpair` se escribió en el flujo; `false` si la sesión ya se estaba
  cerrando o estaba cerrada, o si la escritura falló (la app debe reintentarlo en otra conexión,
  §6.7 bis del diseño).
- Conexiones duplicadas (ambos llaman a la vez): la app elige la ganadora **antes** de crear la
  `PeerSession` (la iniciada por la `identityKey` menor) y, para la perdedora, cierra su flujo y
  destruye su canal **sin enviar `Bye`**.
- Desmontaje: cancela lector y temporizadores, emite `Failed(id, CONNECTION_LOST)` por cada
  pendiente, descarta en silencio (y borra) la foto entrante incompleta, destruye el canal,
  cierra el flujo, pone `isOpen` a `false` y emite `Closed(motivo)` una sola vez. Gana el
  primer motivo registrado. Tras `Closed` solo pueden llegar `Failed(NOT_CONNECTED)` de envíos
  posteriores.

## 11. Vectores conocidos y pruebas

| Prueba | Vector |
|---|---|
| `PrimitivesTest` | X25519 RFC 7748 §6.1 (acuerdo de Alice y Bob), HKDF RFC 5869 caso 1, HMAC RFC 4231 caso 1, ChaCha20-Poly1305 RFC 8439 §2.8.2 |
| `NoiseVectorsTest` | Vectores de cacophony para `Noise_KKpsk2_25519_ChaChaPoly_SHA256` y `Noise_XXpsk3_25519_ChaChaPoly_SHA256` (`noise-vectors.json`) |
| `RatchetChannelTest.knownAnswerVector` | Python + `cryptography`: `ck = 00..1f`, contador inicial `0x01020304`, mensajes `"hola ñ"` y `"adios"` |
| `SafetyNumberTest.knownAnswerVector` | Python + `hashlib`: `0x01×32`, `0x02×32` ⇒ `11384 69342 18126 46878 36715 63359` |
| `InvitationTest` | Base32 `"foobar"` ⇒ `mzxw6ytboi`; .onion v3 real `duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad` |
| `EndToEndSocketTest` | Sockets TCP reales de bucle local: invitación, emparejamiento, sesión nueva, texto en ambos sentidos, foto de 1 MiB, `Bye` y descarte posterior |

Ejecutar: `./gradlew test`.

## 12. Modelo de amenazas (resumen del diseño, §7)

| Adversario | Qué puede | Qué no puede |
|---|---|---|
| Red / operadora / wifi | Ver que usas Tor | Ver con quién hablas ni qué |
| Nodos de Tor | Ver tráfico cifrado | Leerlo; saber IP y destino a la vez |
| Quien conozca tu .onion | Nada (autorización de cliente) | Conectar, sondear presencia |
| Quien robe una invitación antes de usarla | Emparejarse en tu lugar | — (mitigado: 10 min y un uso, impuestos por la app en el plan 2, no por el núcleo; número de seguridad) |
| Robo del móvil bloqueado | — | Leer mensajes (no existen en disco); contactos cifrados con Keystore |
| Contacto malicioso | Ver lo que le envías | Ver tu IP |

Riesgos aceptados: el dispositivo comprometido (malware/root) queda fuera de alcance; el uso de
Tor puede ser visible o estar bloqueado en algunas redes (puentes fuera de la v1).

Propiedades que aporta el núcleo: autenticación mutua por identidad + `pairSecret` en cada
sesión; secreto hacia adelante por sesión (efímeras nuevas) y por mensaje (ratchet con borrado
de claves); rechazo de repetición, reordenación y manipulación (cierre de la sesión); relleno a
256 bytes; nada de mensajes ni claves de sesión en disco ni en logs.

## 13. Limitaciones conocidas

- **Fuga temporal por posición del candidato**: el respondedor prueba msg1 contra sus contactos
  en orden y se detiene en el primero que autentica; el tiempo de respuesta revela
  aproximadamente la posición del llamante en la lista (2 DH por candidato probado). Aceptado
  en v1; la autorización de cliente de Tor limita quién puede llegar a conectar.
- **El emparejamiento no liga la .onion temporal al handshake**: que el invitador es quien
  sirve esa .onion lo garantiza Tor, no el núcleo (el prólogo no incluye la dirección).
- **msg2 de XXpsk3 revela la identidad del invitador**: la PSK solo entra en msg3, así que
  cualquiera que conozca la .onion temporal (aunque no el secreto) puede enviar msg1 y descifrar
  en msg2 la clave pública de identidad del invitador (vinculación entre invitaciones y
  contactos). No revela mensajes ni permite emparejarse.
- **Invitación de un solo uso y caducidad**: las impone la app (plan 2), no el núcleo; `Pairing`
  no sabe nada de la invitación. Un intento fallido (secreto erróneo, conexión cortada, sondeo
  de msg2) **no debe** consumir la invitación; solo un emparejamiento terminado bien en el
  invitador.
- **Emparejamiento asimétrico residual**: si se pierde la trama final `"GhostP2 pairing ok v1"`,
  el invitado queda emparejado y el invitador no; al llamar, el invitado obtendrá
  `UNKNOWN_CALLER` y la app debe proponer volver a emparejar.
- **Borrado de memoria incompleto**: BouncyCastle copia internamente claves
  (`X25519PrivateKeyParameters`, `KeyParameter`, estado HMAC) que no se pueden borrar; la JVM
  puede copiar arrays al compactar; los textos (`String`) son inmutables y no se borran. Las
  fotos recibidas se entregan a la app, que es quien debe borrarlas.
- **Búfer de eventos ilimitado**: si el recolector se atasca, los eventos (incluidas fotos de
  hasta 3 MiB) se acumulan en memoria. (La cola de acuses salientes sí está acotada: 256.)
- **Sin confirmación de lectura**: `Delivered` significa que la `PeerSession` del otro lado
  procesó el mensaje, no que la persona lo haya visto.
- **Número de seguridad**: 6 grupos de `u32 mod 100000` (≈ 99,7 bits, con un sesgo de módulo
  despreciable).
- **Límite de contador**: 2^32−1 tramas por sentido y sesión; agotado, la sesión se cierra.
- **Aún no implementado en el núcleo** (planes 2 y 3): Tor y servicios ocultos, reglas de
  conexiones simultáneas (conservar la iniciada por la `identityKey` menor, elegida antes de
  crear la `PeerSession`, §10.5), reintentos de presencia, almacenamiento cifrado con Keystore,
  invitación de 10 minutos y un solo uso.
