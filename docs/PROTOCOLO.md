# Protocolo de GhostP2 (v1)

Este documento describe el protocolo tal como está implementado en los módulos `:core:crypto`
(`org.ghostp2.core.crypto`) y `:core:protocol` (`org.ghostp2.core.protocol`) (§1–13), y las
reglas del nivel de aplicación que imponen `:core:engine` (`org.ghostp2.engine`), `:core:data`,
`:core:tor` y `:app` (§14–15). Si algo de aquí contradice el código, manda el código: cada
etiqueta, tamaño y plazo se ha copiado de él.

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
| Quien robe una invitación antes de usarla | Emparejarse en tu lugar | — (mitigado: 10 min y un uso, impuestos por el motor, no por el núcleo, §14.2; número de seguridad) |
| Robo del móvil bloqueado | — | Leer mensajes (no existen en disco); contactos cifrados con Keystore |
| Contacto malicioso | Ver lo que le envías | Ver tu IP |

Riesgos aceptados: el dispositivo comprometido (malware/root) queda fuera de alcance; el uso de
Tor puede ser visible o estar bloqueado en algunas redes (puentes fuera de la v1). Las
limitaciones del nivel de aplicación están en §15.

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
- **Invitación de un solo uso y caducidad**: las impone el motor (§14.2), no el núcleo; `Pairing`
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
- **Fuera del núcleo**: Tor y servicios ocultos, desempate de conexiones simultáneas,
  reintentos de presencia, almacenamiento cifrado con Keystore e invitación de 10 minutos y un
  solo uso no están en `:core:crypto` ni en `:core:protocol`: los implementa el nivel de
  aplicación (§14).

## 14. Reglas del nivel de aplicación (`:core:engine`, `:core:data`, `:core:tor`, `:app`)

El motor (`MessengerEngine`) usa el núcleo sobre Tor real (`KmpTorTransport`, kmp-tor). Los
valores por defecto están en `EngineConfig`.

| Parámetro | Valor |
|---|---|
| Puerto virtual de los servicios ocultos | 7420 → `127.0.0.1:<puerto local aleatorio>` |
| Validez de la invitación | 10 min (600 000 ms) |
| Intentos de emparejamiento simultáneos por invitación | 32 (los demás se cierran al aceptarlos) |
| Reintentos por contacto | 15 s → 5 min, ×2 por fallo, ±20 % de variación aleatoria |
| Conexión SOCKS a una .onion | 60 s |
| Ventana para entregar `Unpair` al borrar | 2 min (120 000 ms) |
| `Unpair` a todos al "Borrar todo" | 2,5 s en total, en paralelo |
| Arranque de Tor hasta `Ready` | 180 s |
| Registro de autorizaciones de cliente al arrancar | 30 s en total, en paralelo |
| Historial en memoria | 500 elementos por contacto (se descartan los más antiguos) |
| Fotos en el historial, sumando todas las conversaciones | 64 MiB (`maxPhotoBytesInMemory`; se descartan las más antiguas) |
| Handshakes de sesión entrantes simultáneos | 32 (las conexiones de más se cierran al aceptarlas) |
| Fotos salientes en la cola de la interfaz | 3 en total, contando la que está en vuelo (R25, §14.10) |
| Lado mayor de una foto enviada | 2048 px por defecto (ajustes: 1280, 2048 o 3072) |
| Decodificación de una foto antes de recodificarla | ≈ 24 Mpx como mucho (R26); archivo de origen de hasta 64 MiB |

### 14.1 Publicación de la .onion permanente

- Se publica **solo si hay al menos una clave de autorización de cliente**, y **siempre con
  autorización de cliente v3**: nunca se llama a `ADD_ONION` con la lista vacía para la .onion
  permanente. Las claves son la `myClientAuthPublic` de **cada** contacto guardado (también los
  que están en `IDENTITY_CHANGED`, hasta que se borren) y, solo si no hay ningún contacto, las
  claves pendientes de los emparejamientos en curso (regla R10, abajo).
- La primera publicación genera la dirección; su clave ED25519 y la .onion se guardan con la
  identidad, así que la dirección es estable. Si cambia el conjunto de claves se retira
  (`DEL_ONION`) y se vuelve a publicar con la misma clave: corte de unos segundos para los demás
  contactos. Si no queda ninguna clave, se retira, pero la dirección queda reservada.
- Los servicios son efímeros (`ADD_ONION` sin guardar en disco); al detener Tor desaparecen y se
  publican de nuevo en cada arranque.
- **Regla R10.** Con cero contactos, cada emparejamiento en curso reserva la publicación con su
  propia clave pendiente: el invitado, con la pública del par que genera para el contacto nuevo;
  el invitador, mientras espera, con una clave de reserva cuya privada se borra al crearla (nadie
  puede usarla). Al emparejar, la clave del contacto sustituye a la de reserva; si el
  emparejamiento no termina, la clave pendiente se retira y, sin contactos, la .onion deja de
  publicarse.
- Defensa en profundidad: `TorTransport.publishService` rechaza (`IOException`, sin enviar nada a
  Tor) una publicación con clave de servicio (la .onion permanente) y sin claves de autorización.
  Sin clave y sin autorización solo se publica el servicio temporal de la invitación (§14.2).
- **Autorizaciones para las .onion de los contactos.** Tor las guarda solo en memoria
  (`ONION_CLIENT_AUTH_ADD` sin `Permanent`, sin `ClientOnionAuthDir`). En cada arranque, con Tor
  `Ready` y antes de los bucles de conexión, se registran de nuevo las de todos los contactos
  `ACTIVE`. Los que fallen se avisan (diagnóstico "no se pudo registrar en Tor la autorización…")
  y no se les puede llamar hasta el siguiente arranque; ellos sí pueden llamarnos.
- Si una re-publicación falla (p. ej. tras borrar un contacto), se reintenta en segundo plano con
  la misma espera creciente que los contactos, y antes si cambia la red. Tras 3 fallos seguidos
  se muestra un aviso de diagnóstico.

### 14.2 Ciclo de vida de la invitación

- Solo con el motor `Online`. Crear una invitación abre un `ServerSocket` en
  `127.0.0.1:<puerto aleatorio>` y publica un servicio oculto **temporal, con dirección nueva y
  sin autorización de cliente**, con un secreto nuevo de 16 bytes. Estados:
  `Preparing → Waiting(texto, caducidad)` y, al final, `Paired`, `Expired` (10 min),
  `Cancelled` (botón, invitación nueva o motor parado) o `Failed`. En todos se retira el
  servicio temporal, se cierra su socket y se borra el secreto.
- **Una invitación activa por móvil**: crear otra cancela la anterior.
- **Un solo emparejamiento con éxito**: el primer intento que termina bien toma un guardián
  atómico y guarda el contacto; los que terminen bien a la vez se descartan (se borran sus
  secretos). Ese otro lado cree estar emparejado, pero no tiene ninguna clave válida para mi
  .onion (caso residual de §6).
- **Los intentos fallidos no consumen la invitación** (secreto erróneo, conexión cortada, sondeo
  de msg2, plazo agotado): se cierra su conexión y se sigue esperando hasta la caducidad.
- Cada intento genera su propio par de autorización de cliente: la privada va en la oferta de ese
  intento y se borra al terminarlo; solo la pública del ganador se publica en mi .onion.
- Aceptar la propia invitación se rechaza ("No puedes aceptar tu propia invitación"), tanto
  antes de conectar (misma .onion temporal) como si la identidad recibida es la propia.
- El contacto se guarda como `ACTIVE`, sin verificar y con el nombre "Contacto nuevo". Su id es
  el hex de los 16 primeros bytes de `SHA-256(identidad)`: volver a emparejar con la misma
  identidad sustituye al contacto (mismo id); si su .onion cambió, se retira la autorización de
  la antigua. Si algo falla tras guardarlo (Tor, publicación), se deshace todo.
- Errores del invitado: conexión o plazo ⇒ "No se pudo conectar con la invitación (¿caducada o
  ya usada?)"; `AUTH` ⇒ "La invitación no coincide"; otros ⇒ "No se pudo completar el
  emparejamiento". Con un secreto erróneo el invitado ve `CONNECTION` (§6), así que recibe el
  primer mensaje.

### 14.3 Presencia, reintentos y desempate

- Cada contacto `ACTIVE` tiene un bucle que, mientras no haya sesión, conecta a su .onion (SOCKS,
  60 s) y hace el handshake de sesión (§7, 30 s). Tras un fallo espera 15 s, y la espera se
  dobla con cada fallo hasta 5 min, con ±20 % de variación aleatoria. Cuando una sesión se cierra
  (por cualquier motivo) el contacto pasa a `OFFLINE` y el bucle vuelve a llamar tras la espera
  mínima (con variación).
- Un cambio de la red por defecto de Android (`onAvailable`/`onLost` del `NetworkCallback` del
  servicio) **o abrir la app** (cada `onResume` de la actividad, con el motor `Online`) corta las
  esperas, vuelve a la mínima y reintenta enseguida.
- Presencia: `OFFLINE`, `CONNECTING`, `ONLINE` o `IDENTITY_CHANGED`. Con el motor parado no hay
  presencia (mapa vacío). `ONLINE` solo con la sesión ya arrancada (acepta envíos); mientras arranca
  (también al sustituir una sesión duplicada) el contacto está `CONNECTING`.
- Conexiones entrantes: handshake de respondedor (§7) con los contactos `ACTIVE` como candidatos.
  Cualquier fallo (llamante desconocido, conexión, plazo…) cierra en silencio, sin cambiar el
  estado de nadie. Como mucho 32 handshakes entrantes a la vez: las conexiones de más se cierran
  nada más aceptarlas (como los intentos de una invitación, §14.2).
- Si al recibir `AUTH` el almacenamiento falla y no se puede guardar `IDENTITY_CHANGED`, el
  contacto no se marca: el bucle sigue con la espera creciente y lo vuelve a intentar.
- **Desempate** (§10.5): por contacto puede haber dos conexiones autenticadas a la vez, la
  iniciada por cada lado. Se conserva siempre la **iniciada por el lado cuya `identityKey`
  pública es menor** (orden de bytes sin signo). La decisión se toma antes de crear la
  `PeerSession`; la perdedora se cierra **sin `Bye`** (`stream.close()` y `channel.destroy()`).
  Si la llamada propia del lado menor está en pleno handshake, la entrante espera su resultado.
  Limitación: si solo el lado mayor pierde la red y el menor aún cree viva su sesión, el menor
  rechaza las llamadas del mayor hasta que esa sesión caduca (60 s sin tráfico) y llama él.

### 14.4 Identidad cambiada (reglas R8 y R14)

- `HandshakeFailedException.reason == AUTH` **al iniciar** hacia un contacto (su `pairSecret`
  ya no coincide: el otro volvió a emparejar con la misma identidad solo por su lado, §13) ⇒ el
  contacto se guarda como `IDENTITY_CHANGED`, se avisa ("La identidad de <nombre> ha cambiado ·
  Vuelve a emparejar para seguir hablando") y su bucle termina: no se le vuelve a llamar ni se le
  acepta como candidato entrante. Su clave sigue en mi publicación hasta que se le borre. Volver
  a emparejar con él lo deja otra vez `ACTIVE`.
- **R8**: `Closed(SECURITY)` (violación del protocolo dentro de una sesión) por sí solo **no**
  bloquea al contacto: el bucle vuelve a llamar como tras cualquier cierre. `UNKNOWN_CALLER` en
  el respondedor tampoco marca nada.
- **R14**: quien reinstala la app genera identidad **y** .onion nuevas. Para los demás, el
  contacto antiguo simplemente queda `OFFLINE` (su .onion ya no existe); no hay ninguna señal en
  el protocolo. Hay que volver a emparejar, lo que crea un contacto nuevo (otro id).

### 14.5 Historial en memoria y envío

- Las conversaciones viven en la memoria del motor, por contacto (hasta 500 elementos), no
  ligadas a la sesión: **sobreviven a los cortes de conexión**. Se borran con "Borrar chat"
  (`clearConversation`), al borrar el contacto (al empezar el borrado), al detener el motor
  ("Desconectar"), cuando el motor pasa a `Error` (también se libera todo) y, por supuesto, si
  muere el proceso. Nunca se escriben en disco. Las fotos se rellenan con ceros al salir del
  historial.
- Solo hay conversaciones de contactos guardados: los mensajes de ids desconocidos no se guardan.
- **Presupuesto global de fotos**: sumando todas las conversaciones, las fotos del historial ocupan
  como mucho 64 MiB (`EngineConfig.maxPhotoBytesInMemory`; cada foto puede tener hasta 3 MiB, §10.1).
  Al superarlo, tras guardar una foto nueva (enviada o recibida), se descartan primero las **más
  antiguas**, de cualquier contacto: sus bytes se rellenan con ceros y el mensaje sigue en el
  historial (mismo id, estado y hora) como foto descartada (`ChatContent.PhotoDiscarded`, solo con
  su tamaño; la interfaz muestra "Foto descartada para liberar memoria"). Una foto que se está
  enviando no se descarta hasta que termina su envío.
- Enviar sin sesión abierta ⇒ el elemento queda en el historial como `Failed(NOT_CONNECTED)` al
  momento (la interfaz lo tacha con "No entregado: tu contacto no está conectado. Descartado"). No
  hay cola ni reintentos (§10.3); la interfaz deja enviar igual y avisa antes "Tu contacto no está
  conectado: lo que envíes se descartará".
- **R15**: `sendText`/`sendPhoto` con el motor parado, o hacia un id que no es un contacto
  guardado (o que se está borrando), devuelven un id y **no guardan nada**: un motor parado no
  tiene mensajes ni conversaciones fantasma. La interfaz solo debe enviar con el motor en línea y
  a un contacto conocido.

### 14.6 Borrar un contacto (aviso `Unpair`)

1. El contacto se olvida en el acto: su conversación se borra y ningún envío ni recepción para
   ese id se guarda ya. Su bucle de reconexión se detiene y no vuelve.
2. Si hay sesión abierta se le envía `Unpair` por ella. Si no, se intenta conectar (y se aceptan
   sus llamadas) **durante como mucho 2 min**, con la espera creciente de siempre, solo para
   entregarlo. Con el contacto en `IDENTITY_CHANGED` no se intenta (la autenticación fallaría).
3. Después, **siempre**, aunque no se haya entregado o el motor se detenga a mitad: se borra del
   repositorio, se quita la autorización de su .onion y se vuelve a publicar la mía sin su clave
   (se retira si no quedan contactos ni emparejamientos en curso). El resultado es `DELIVERED`
   solo si la trama `Unpair` se escribió en el flujo (el núcleo no tiene acuse para ella).

Si mientras tanto se volvió a emparejar con el mismo contacto, el emparejamiento nuevo se
conserva. Al **recibir** `Unpair` se hace el mismo borrado local y se avisa "<nombre> te ha
eliminado de sus contactos" (con el nombre que tenía).

**Borrados pendientes persistidos.** Mientras dura un borrado, el motor expone su id en `deleting`
(la interfaz muestra "Borrando…" y no deja abrir el contacto) y, además, lo apunta en un diario
(`DeletionJournal`) que se guarda en `ghostp2.bin` (campo `pendingDeletions`, solo ids; formato 2,
R21). La entrada se apunta tras el paso 1 (si no se puede guardar, el borrado sigue solo en
memoria) y se quita tras el paso 3; si el borrado del paso 3 falla, se queda. Si el proceso muere a mitad,
en el siguiente arranque del motor (justo después de arrancar los bucles de conexión) cada id del
diario que sigue siendo un contacto guardado **se vuelve a borrar** desde el principio, con una
ventana nueva de 2 min para entregar `Unpair`; los ids que ya no son contactos se quitan del
diario. Si el diario no se puede leer, no se reanuda ninguno. Volver a emparejar con ese contacto
quita su entrada, y "Borrar todo" vacía el diario.

**"Borrar todo"** (Ajustes › Datos, con doble confirmación) envía `Unpair` en paralelo a las
sesiones abiertas (2,5 s en total, mejor esfuerzo), detiene el motor y borra identidad, contactos,
autorizaciones, borrados pendientes y el estado de Tor en disco. Después la app vuelve los ajustes a
los de por defecto (con ellos, a la bienvenida), detiene el servicio, quita todas sus
notificaciones y vacía el portapapeles si contiene una invitación de GhostP2. El detalle del
contacto ofrece "Borrar contacto" (con confirmación; al terminar dice si el aviso se entregó) y
"Borrar chat".

### 14.7 Ciclo de vida del motor y Tor (reglas R13 y R18)

- Arranque: almacenamiento comprobado (§14.9) → identidad (se crea si no hay) → Tor hasta `Ready`
  (como mucho 180 s; si no, `Error("Tor no terminó de arrancar")`) → autorizaciones de cliente →
  escucha local → publicación → bucles de los contactos. Estado visible: `Stopped`,
  `StartingTor(%)`, `Online` o `Error(mensaje)`. Un fallo de arranque libera todo y deja
  `Error`; nunca lanza.
- **R13**: al volver de `TorTransport.start()` el estado de Tor ya es `Starting`, `Ready` o
  `Error`, nunca `Off`.
- **R18 (modificada)**: con el motor `Online` se vigila el estado de Tor. Si pasa a `Error` (p. ej.
  su proceso muere) o a `Off` sin pedirlo, el motor libera la ejecución como al detenerse
  (sesiones cerradas, presencia vacía, historial borrado) y queda en `Error`. La app muestra
  "Reintentar" (y "Desconectar"), y la notificación fija pasa a "GhostP2 sin conexión · error".
  **Un único reinicio acotado al volver la red**: si el servicio recibe `onAvailable` de la red por
  defecto con el motor en `Error` (también si el error fue al arrancar) y el usuario no ha pulsado
  "Desconectar", vuelve a arrancar el motor una vez; como mucho un reinicio por aviso de red y con
  al menos 30 s entre dos reinicios, para no entrar en bucle si Tor sigue fallando (entonces queda
  "Reintentar").
- Una excepción inesperada en una corrutina del proceso (motor, sesiones, servicio) no cierra la
  app: los scopes llevan un `CoroutineExceptionHandler` que la descarta sin registrarla.

### 14.8 Servicio en primer plano (reglas R19 y R20)

- "Activar" inicia un servicio en primer plano de tipo `specialUse` con la notificación fija
  "GhostP2 activo · cifrado" y la acción "Desconectar". En Android 13+ se pide antes el permiso
  de notificaciones; el servicio arranca se conceda o no.
- **R19**: el servicio devuelve `START_STICKY`. Si el sistema mata el proceso con GhostP2
  activo, lo recrea con un intent nulo, que se trata como "Activar" (primer plano y motor). En
  Android 12+ el sistema puede no permitir volver al primer plano desde segundo plano (sobre
  todo sin la exención de batería): entonces se detiene y deja la notificación "GhostP2 se ha
  detenido · Abre la app para volver a activarlo". Lo mismo si al reiniciarse el almacenamiento
  falla.
- "Desconectar" (en la barra de la lista de contactos con el motor `Online`, en el aviso del estado
  o en la notificación) detiene
  motor y servicio **para siempre**: el sistema no vuelve a crearlo.
- **R20 (modifica R19)**: opción "Activar GhostP2 al encender el móvil" (Ajustes › Segundo plano),
  **desactivada por defecto**. Un receptor de `BOOT_COMPLETED` (difusión protegida: solo la envía el
  sistema) comprueba el almacenamiento y lee los ajustes en como mucho 8 s; solo si la opción está
  activada y la bienvenida terminada, arranca el servicio en primer plano como el reinicio
  `START_STICKY`. Si el almacenamiento o la lectura fallan, no hace nada (GhostP2 queda apagado). Si
  el sistema no deja pasar a primer plano, deja la notificación "GhostP2 se ha detenido · Abre la
  app para volver a activarlo". Sin la opción, nada arranca GhostP2 al encender el móvil.
- Exención de la optimización de batería: la bienvenida la pide ("Quitar el ahorro de batería") y
  Ajustes la muestra con "Permitir en segundo plano" (diálogo del sistema; si el fabricante lo
  quitó, la lista del sistema). Las dos muestran la ruta del ahorro propio del fabricante (Xiaomi,
  Samsung, Huawei/Honor, OnePlus/OPPO/realme o un texto genérico), que la exención de Android no
  desactiva.
- Notificaciones de contacto (canal "Mensajes y avisos", visibilidad privada): "Nuevo mensaje de
  <nombre>" **sin contenido**; su versión pública (pantalla de bloqueo con el contenido sensible
  oculto) es "Nuevo mensaje", sin nombre. Con "Ocultar el nombre del contacto" (leído en cada aviso;
  si no se puede leer, se oculta) tampoco la privada lleva nombre: "Nuevo mensaje", "Un contacto te
  ha eliminado de sus contactos", "La identidad de un contacto ha cambiado"; al activarlo se quitan
  las notificaciones de contacto ya publicadas. No se notifican los mensajes del chat que está
  abierto en primer plano. Los avisos de diagnóstico no se notifican: el último se ve en Ajustes ›
  Acerca de.

### 14.9 Almacenamiento (reglas R16, R17 y R21)

- En disco solo `noBackupFilesDir/ghostp2.bin` (identidad, clave de la .onion permanente y
  contactos con sus claves, `pairSecret` y nombres, en un objeto CBOR) cifrado con un keyset Tink
  AES-256-GCM, a su vez cifrado por la clave maestra de Android Keystore `ghostp2_master` (en
  StrongBox si existe y supera una prueba). Escritura atómica (archivo temporal y renombrado).
  **R17**: almacén propio (`EncryptedStore`), sin DataStore.
- **R16**: si Android Keystore no se puede usar, el almacenamiento **falla cerrado**: nunca se
  acepta el keyset en claro con el que Tink se conformaría, y el motor no arranca. Un fallo
  pasajero ofrece "Reintentar" (y, tras 3 seguidos, "Empezar de cero" con doble confirmación);
  datos ilegibles ofrecen "Empezar de cero" y también "Reintentar", que solo vuelve a leer (por si
  el fallo era pasajero). Nunca se borra nada sin que el usuario lo pida.
- Compatibilidad hacia delante: los campos CBOR desconocidos (escritos por una versión más nueva)
  se ignoran al leer; un valor desconocido de un enum o un campo obligatorio ausente siguen siendo
  "datos ilegibles".
- **R21**: `ghostp2.bin` pasa al formato 2. El primer byte del archivo es la versión de formato y
  va en los datos asociados del AEAD (byte ‖ etiqueta `ghostp2-store-v1`, que no cambia). Se leen
  los formatos 1 y 2 y se escribe siempre el 2, así que un archivo de formato 1 pasa al 2 en la
  primera escritura; cualquier otro primer byte es "datos ilegibles" sin intentar descifrar. El
  formato 2 añade, con valor por defecto, los ajustes de la app (bienvenida hecha, bloqueo y
  "Bloquear tras", ocultar nombres, lado mayor de las fotos, activar al encender; sin secretos ni
  contenido) y los borrados pendientes (§14.6). Un archivo de formato 1 se lee con ajustes por
  defecto y sin borrados pendientes.
- Estado de Tor en `noBackupFilesDir/tor` (consenso, guardas, cookie de control); sin claves de
  servicios ni de autorización.
- **Tor vuelve a arrancar tras borrar su estado** ("Borrar todo", "Empezar de cero"):
  `KmpTorTransport.wipeState()` detiene Tor, suelta su driver y borra el directorio; el siguiente
  `start()` crea un driver nuevo. kmp-tor 2.6.0 (comprobado en su bytecode) guarda en caché para
  todo el proceso, sin expulsión, el `Environment` por directorio y el `TorRuntime` de cada
  `Environment`, así que el driver nuevo recibe el mismo runtime. Por eso los observadores de
  `ERROR` y `STATE` se suscriben **una sola vez por runtime** (suscribir otra vez los duplicaría:
  se comparan por identidad) y cada driver nuevo solo cambia el destino de esos avisos. En cada
  `startDaemon` kmp-tor vuelve a extraer los recursos que falten (geoip, geoip6), genera la
  configuración de nuevo (por la entrada estándar) y crea los directorios con permisos 700; el
  binario de Tor sale del directorio de bibliotecas nativas, que el borrado no toca. No hace falta
  ninguna API de reinstalación.
- Sin copias de seguridad: `allowBackup="false"` y reglas de extracción que excluyen todo.

### 14.10 Interfaz (reglas R22–R28)

- **Sin estado guardable con contenido**: ni mensajes, ni fotos, ni borradores, ni la invitación,
  ni el texto pegado van en el `Bundle` que Android escribe en disco (`rememberSaveable`,
  `SavedStateHandle` y `rememberTextFieldState` están prohibidos); las rutas de navegación solo
  llevan ids. Todos los campos de texto piden al teclado no aprender
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`) y no autocorregir. `FLAG_SECURE` en la ventana.
- **R24**: el texto de la invitación no se puede seleccionar; solo se copia con "Copiar", que lo
  marca como sensible (`EXTRA_IS_SENSITIVE`, Android 13+). "Compartir" lo envía a otra app con el
  selector del sistema. El portapapeles se vacía si tiene una invitación de GhostP2 cuando la
  propia deja de esperar (emparejada, caducada o cancelada), al salir de "Añadir contacto" y con
  "Borrar todo".
- **Fotos salientes**: selector de fotos del sistema (`PickVisualMedia`, solo imágenes, sin
  permiso de galería) y recodificación a JPEG sin metadatos (orientación aplicada, sRGB,
  transparencia sobre blanco), con el lado mayor del ajuste "Tamaño máximo" (nunca se amplía) y
  como mucho `PeerSession.MAX_PHOTO_BYTES` (3 MiB): si no cabe, se baja la calidad y después el
  tamaño; si aun así no cabe, "La foto es demasiado grande para enviarla". Nada toca el disco.
  - **R25**: la cola de fotos salientes admite **como mucho 3 fotos en total**, contando la que
    está en vuelo (codificándose, enviándose o esperando su acuse) y las que esperan; con 3, la
    siguiente se rechaza ("Espera a que terminen de enviarse las fotos"). Una sola foto en vuelo.
  - **R26**: la decodificación previa se submuestrea (`inSampleSize`) para no pasar de unos
    24 Mpx; con fuentes de 100 Mpx o más y "3072 px", el lado mayor puede quedar algo por debajo
    del ajuste.
- **Fotos recibidas**: la interfaz decodifica cada foto una vez a su propio bitmap (miniatura y
  visor, fuera del hilo principal) y tolera que el presupuesto de 64 MiB (§14.5) ponga sus bytes a
  cero en cualquier momento: entonces muestra "Foto descartada para liberar memoria", nunca una
  imagen corrupta.
  - **R27**: "Guardar" del visor (destino elegido por el usuario con el selector de documentos del
    sistema) es la única vía por la que una foto llega al disco. Quita los segmentos APP1–APP15 y
    COM (conserva el APP0 de JFIF) y comprueba las marcas SOI y EOI, así que el EXIF de una foto
    recibida nunca llega al disco. Si la foto ya está a cero o no tiene la estructura esperada, no
    se guarda y se borra el documento creado.
- **Bloqueo** ("Bloqueo con huella o PIN", "Bloquear tras": inmediatamente, 1, 5 o 15 min; 1 min
  por defecto): ver R22 en §15. Se bloquea al arrancar en frío y al volver tras el tiempo elegido
  (reloj `elapsedRealtime`; girar el móvil no cuenta). Hasta leer los ajustes la raíz no muestra
  nada sensible (falla cerrado). Activarlo exige bloqueo de pantalla en el móvil; si el usuario lo
  quita después, al volver GhostP2 se desbloquea, desactiva su bloqueo y lo avisa.
  - **R28**: en Android 8.0–8.1 (API 26–27) el bloqueo solo ofrece la credencial del dispositivo
    (PIN, patrón o contraseña): `FingerprintManagerCompat` de androidx.core 1.18 no detecta hardware
    ahí, y androidx.biometric 1.1.0 pasa directamente a la credencial.

## 15. Limitaciones del nivel de aplicación

Complementan las del núcleo (§13). GhostP2 no promete más de lo que hay aquí.

- **Claves en `String` dentro de kmp-tor**: las claves de los servicios ocultos y de
  autorización de cliente viajan a Tor como texto por el puerto de control; kmp-tor las convierte
  en `String`, que no se pueden rellenar con ceros. Además el proceso de Tor (aparte) las tiene
  en su memoria mientras está en marcha.
- **Material de claves en memoria**: identidad, `pairSecret` y claves de autorización están en
  memoria mientras el motor funciona. El motor rellena con ceros **algunas** de sus copias al dejar
  de usarlas (las del contacto borrado, las del emparejamiento descartado, todo lo vivo al "Borrar
  todo"), pero **no todas**: el repositorio entrega copias nuevas en cada lectura y muchas de esas
  copias transitorias (y las de `PairingResult` tras guardar el contacto) se sueltan sin rellenar y
  quedan en la memoria hasta que el recolector de basura las reutiliza. Tampoco se borran las copias
  internas de la JVM, BouncyCastle o Tink (§13). No hay garantía de borrado en memoria.
- **Mensajes solo en memoria**: se descartan si el destinatario no está conectado (no hay cola) y
  el historial se pierde al desconectar, si Tor falla, si el sistema mata la app o al reiniciar
  el móvil. "✓ entregado" significa recibido por su app, no leído. Con muchas fotos, las más
  antiguas se descartan de la memoria (64 MiB en total, §14.5).
- **Android Keystore obligatorio** (R16): en dispositivos con Keystore roto la app no funciona.
  La protección en reposo es la de Keystore: con el móvil desbloqueado (o con root), quien
  ejecute código como la app puede descifrar `ghostp2.bin`. La clave maestra no exige
  autenticación del usuario.
- **R22: el bloqueo de la app es una puerta de la interfaz**: `BiometricPrompt` con huella (clase
  débil) o credencial del dispositivo, sin botón negativo; mientras está bloqueada solo se compone
  la pantalla "GhostP2 está bloqueado". No cifra nada: la clave de Keystore no exige autenticación,
  así que no protege `ghostp2.bin` frente a quien ejecute código como la app, y el motor sigue
  funcionando (recibe y notifica) con la app bloqueada. Es un bloqueo en memoria del proceso; el
  arranque en frío vuelve a bloquear. En Android 8.0–8.1 solo hay credencial, no huella (R28).
- **Errores del almacenamiento antes del bloqueo**: si `ghostp2.bin` no se puede leer, las pantallas
  de error ("Reintentar", "Empezar de cero") se muestran **antes** del bloqueo de la app (R22): sin
  datos legibles no hay ajustes de los que saber si el bloqueo está activado. No muestran nada de los
  datos; lo único que permiten es destructivo: quien tenga el móvil desbloqueado puede borrarlo todo
  con "Empezar de cero" (con confirmación), igual que podría desde los ajustes del sistema.
- **R23: sin cámara para fotos**: `TakePicture` exige que la cámara escriba la foto en un archivo
  (en disco) y `TakePicturePreview` solo devuelve una miniatura; por eso solo se envían fotos
  elegidas con el selector del sistema. La cámara (CameraX, opcional en el manifiesto) solo se usa
  para escanear el QR de la invitación, y los fotogramas no se guardan.
- **Estado de Tor en disco**: guardas y caché de consenso revelan, a quien lea el
  almacenamiento de la app, que se ha usado Tor (no con quién).
- **Tor colgado al detener o borrar**: detener Tor espera como mucho 15 s. Si el proceso no para en
  ese plazo, `KmpTorTransport` queda "envenenado": no lanza otro proceso y `wipeState` informa de
  fallo (sin volver a esperar). Cada arranque posterior ("Activar", "Reintentar") reintenta una vez
  detener el Tor colgado con el mismo plazo: si termina, arranca con normalidad; si no, Tor queda en
  `Error` con `stuck` y el motor en `Error` de tipo `TOR_STUCK`, con el aviso "Tor no responde. Cierra
  GhostP2 desde Recientes y vuelve a abrirlo." (siempre distinto de `Off`, R13). Solo reiniciar el
  proceso de la app lo arregla seguro. "Borrar todo" y "Empezar de cero" borran igualmente su
  directorio (ese fallo se ignora, como el resto de Tor), y un Tor aún vivo podría volver a escribir
  ahí parte de su estado (guardas, consenso; nunca claves, que no se escriben) hasta que muera.
- **Eventos atrasados de Tor**: el estado de Tor se deriva de los eventos del runtime de kmp-tor, que no
  dicen de qué arranque son. Tras detener y volver a arrancar enseguida, un evento atrasado del
  arranque anterior puede dar `Ready` antes de tiempo (las operaciones fallan con `IOException` y se
  reintentan como cualquier fallo de conexión) o, si es el de su parada, dejar Tor en `Error` (el
  motor muestra el error con "Reintentar") hasta el siguiente arranque. No se ha visto en pruebas.
- **Metadatos visibles**: la red local y la operadora ven que usas Tor (sin puentes, puede estar
  bloqueado); tus contactos ven cuándo estás en línea; un adversario que observe gran parte de la
  red puede correlacionar horarios y volumen (latido cada 20 s, ráfagas de fotos). Los nodos de
  Tor no ven el contenido.
- **Otras apps del mismo móvil**: el puerto SOCKS de Tor, la escucha local del servicio oculto y
  la de la invitación están en `127.0.0.1`, al alcance de cualquier app con permiso de red.
  Sin el secreto ni las claves no pueden completar ningún handshake, pero una app maliciosa
  que conozca la .onion de uno de tus contactos podría usar el SOCKS de GhostP2 (que ya tiene
  registrada la autorización para ella) para comprobar si ese contacto está en línea.
- **Dispositivo comprometido**: malware con root, un depurador o una captura de la memoria
  quedan fuera de alcance. Las notificaciones muestran el nombre del contacto con el móvil
  desbloqueado, salvo con "Ocultar el nombre del contacto".
- **Fotos guardadas**: lo que el usuario guarda con "Guardar" queda en el disco, fuera del control
  de GhostP2 (sin metadatos, R27).
- **Segundo plano**: "Activar GhostP2 al encender el móvil" está desactivado por defecto (R20) y,
  aun activado, el sistema puede no dejarlo arrancar. Sin la exención de batería, Android puede
  cortar la conexión con Tor o no recrear el servicio (R19). Para el ahorro de energía propio de
  cada fabricante solo hay una ayuda con la ruta de sus ajustes (Xiaomi, Samsung, Huawei/Honor,
  OnePlus/OPPO/realme); GhostP2 no puede desactivarlo.
- **Reinstalación** (R14): no se avisa; el contacto antiguo queda desconectado y hay que volver a
  emparejar.
