package org.ghostp2.core.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.crypto.AuthenticationException
import org.ghostp2.core.crypto.ChannelSecurityException
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import org.ghostp2.core.crypto.noise.HandshakePattern
import org.ghostp2.core.crypto.noise.HandshakeState
import java.io.IOException

/**
 * Lo que guardo de un contacto emparejado: su clave de identidad x25519 y el pairSecret común
 * (32 bytes cada uno). Son datos almacenados: el handshake nunca borra estos arrays.
 */
data class ContactKeys(val peerIdentity: ByteArray, val pairSecret: ByteArray) {
    init {
        require(peerIdentity.size == KEY_LEN) { "La identidad del contacto debe tener $KEY_LEN bytes" }
        require(pairSecret.size == KEY_LEN) { "El pairSecret debe tener $KEY_LEN bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is ContactKeys && peerIdentity.contentEquals(other.peerIdentity) &&
            pairSecret.contentEquals(other.pairSecret)

    override fun hashCode(): Int = 31 * peerIdentity.contentHashCode() + pairSecret.contentHashCode()

    override fun toString(): String = "ContactKeys(peerIdentity=${peerIdentity.size} bytes, pairSecret=***)"

    private companion object {
        const val KEY_LEN = 32
    }
}

/**
 * Handshake de sesión entre contactos ya emparejados con Noise KKpsk2 sobre un [FrameStream].
 *
 * Ambos lados conocen de antemano la identidad del otro y comparten `pairSecret` (la PSK), así
 * que cada conexión autentica a los dos y produce un [RatchetChannel] nuevo con claves efímeras
 * frescas (secreto hacia adelante).
 *
 * Flujo: el iniciador escribe msg1 (`e, es, ss`); el respondedor lo lee y escribe msg2
 * (`e, ee, se, psk`); el iniciador lo lee. Las cargas útiles van vacías.
 *
 * El respondedor no sabe quién llama: lee msg1 una vez y lo prueba con un [HandshakeState] nuevo
 * por cada candidato; msg1 lleva una etiqueta AEAD (carga vacía cifrada tras `es`/`ss`), así que
 * solo autentica el candidato cuya identidad es la del llamante. Se queda con el primero que
 * autentica (el tiempo de respuesta depende de su posición en la lista; aceptable en v1).
 *
 * Confirmación de clave: en KKpsk2 la PSK (`psk`) solo entra en msg2, así que msg1 autentica la
 * identidad pero NO está ligado al pairSecret; sin más, el respondedor terminaría el handshake
 * aunque los pairSecret no coincidan. Por eso, al completar el handshake, el iniciador envía por
 * el canal una trama cifrada con [CONFIRM] y el respondedor no devuelve su canal hasta
 * descifrarla y compararla en tiempo constante. Así AMBOS lados rechazan un pairSecret distinto
 * durante el handshake (dentro del mismo plazo de 30 s). La trama se consume aquí: la primera
 * trama de aplicación usa ya el contador siguiente en los dos lados.
 *
 * Errores ([HandshakeFailedException.reason]):
 * - `UNKNOWN_CALLER`: (respondedor) ningún candidato autentica msg1.
 * - `AUTH`: `AuthenticationException`/`ChannelSecurityException` (p. ej. el iniciador lee msg2
 *   con un pairSecret distinto) o confirmación de clave no válida (respondedor).
 * - `CONNECTION`: EOF/IOException; es lo que ve el lado que NO detectó el fallo, porque el otro
 *   cierra el flujo.
 * - `TIMEOUT`: venció nuestro plazo (30 s). Un tiempo máximo impuesto por el llamador (p. ej. su
 *   propio `withTimeout`) NO se convierte: se propaga como cancelación.
 * - `STATE`: `IllegalStateException` o handshake incompleto.
 * - [ProtocolException]: el otro lado envió datos mal formados.
 * - `IllegalArgumentException` (error de programación, antes de tocar el flujo): candidatos con
 *   identidades repetidas.
 *
 * Ante cualquier fallo se cierra el flujo (así el otro lado falla enseguida) y se borran los
 * estados del handshake.
 *
 * Propiedad de los secretos: los arrays de [ContactKeys] y de la identidad son de quien llama y
 * el handshake nunca los borra. El [RatchetChannel] devuelto también pasa a quien llama: si lo
 * entrega a una [PeerSession], ella lo destruye al cerrarse; si no (p. ej. pierde el desempate
 * de conexiones duplicadas), quien llama debe llamar a `channel.destroy()` y a `stream.close()`.
 */
object SessionHandshake {
    internal const val TIMEOUT_MILLIS = 30_000L

    private val PROLOGUE = "GhostP2 session v1".toByteArray(Charsets.UTF_8)
    private val EMPTY = ByteArray(0)

    /** Texto de la trama de confirmación de clave que envía el iniciador tras el handshake. */
    private val CONFIRM = "GhostP2 session confirm v1".toByteArray(Charsets.UTF_8)

    /** Quien llama: iniciador KKpsk2 hacia un contacto conocido. */
    suspend fun initiate(stream: FrameStream, identity: KeyPair25519, contact: ContactKeys): RatchetChannel =
        initiate(stream, identity, contact, TIMEOUT_MILLIS)

    /**
     * Quien recibe la llamada: respondedor KKpsk2. Devuelve la instancia de [candidates] que
     * corresponde al llamante (la misma, no una copia) y el canal.
     *
     * [candidates] debe tener identidades (`peerIdentity`) distintas: la capa de datos guarda un
     * único contacto por clave de identidad (al volver a emparejar con la misma identidad se
     * sustituye el anterior). Si no, lanza `IllegalArgumentException` sin tocar el flujo.
     */
    suspend fun respond(
        stream: FrameStream, identity: KeyPair25519, candidates: List<ContactKeys>,
    ): Pair<ContactKeys, RatchetChannel> = respond(stream, identity, candidates, TIMEOUT_MILLIS)

    internal suspend fun initiate(
        stream: FrameStream, identity: KeyPair25519, contact: ContactKeys, timeoutMillis: Long,
    ): RatchetChannel = withDeadline(stream, timeoutMillis) {
        guarded(stream) { track ->
            val hs = track(newState(initiator = true, identity, contact))
            stream.send(hs.writeMessage(EMPTY))
            expectEmpty(hs.readMessage(stream.receive()))
            val channel = finish(hs, initiator = true)
            destroyOnFailure(channel) {
                // Confirmación de clave: solo devolvemos el canal si se envió bien
                stream.send(channel.encrypt(CONFIRM))
            }
            channel
        }
    }

    internal suspend fun respond(
        stream: FrameStream, identity: KeyPair25519, candidates: List<ContactKeys>, timeoutMillis: Long,
    ): Pair<ContactKeys, RatchetChannel> {
        require(candidates.map { it.peerIdentity.asList() }.toSet().size == candidates.size) {
            "Hay candidatos con la misma identidad"
        }
        return respondChecked(stream, identity, candidates, timeoutMillis)
    }

    private suspend fun respondChecked(
        stream: FrameStream, identity: KeyPair25519, candidates: List<ContactKeys>, timeoutMillis: Long,
    ): Pair<ContactKeys, RatchetChannel> = withDeadline(stream, timeoutMillis) {
        guarded(stream) { track ->
            val msg1 = stream.receive()
            try {
                var match: Pair<ContactKeys, HandshakeState>? = null
                for (candidate in candidates) {
                    val hs = newState(initiator = false, identity, candidate)
                    val payload = try {
                        hs.readMessage(msg1)
                    } catch (e: AuthenticationException) {
                        // No es este contacto (readMessage ya abortó y borró el estado)
                        hs.wipe()
                        continue
                    }
                    track(hs)
                    expectEmpty(payload)
                    match = candidate to hs
                    break
                }
                val (contact, hs) = match
                    ?: throw HandshakeFailedException(HandshakeFailedException.Reason.UNKNOWN_CALLER, "Llamante desconocido")
                stream.send(hs.writeMessage(EMPTY))
                val channel = finish(hs, initiator = false)
                destroyOnFailure(channel) { expectConfirm(stream, channel) }
                contact to channel
            } finally {
                Primitives.wipe(msg1)
            }
        }
    }

    /** HandshakeState copia la PSK y la clave remota, así que los datos guardados no se borran. */
    private fun newState(initiator: Boolean, identity: KeyPair25519, contact: ContactKeys) =
        HandshakeState(
            HandshakePattern.KKpsk2, initiator, PROLOGUE, identity,
            remoteStatic = contact.peerIdentity, psk = contact.pairSecret,
        )

    private fun finish(hs: HandshakeState, initiator: Boolean): RatchetChannel {
        if (!hs.isComplete) throw HandshakeFailedException(HandshakeFailedException.Reason.STATE, "Handshake de sesión incompleto")
        return RatchetChannel.fromHandshake(hs, initiator)
    }

    /** Recibe y verifica la trama de confirmación del iniciador (prueba que tiene el mismo pairSecret). */
    private suspend fun expectConfirm(stream: FrameStream, channel: RatchetChannel) {
        val plaintext = channel.decrypt(stream.receive())
        try {
            if (!Primitives.constantTimeEquals(plaintext, CONFIRM)) {
                throw HandshakeFailedException(HandshakeFailedException.Reason.AUTH, "Confirmación de sesión no válida")
            }
        } finally {
            Primitives.wipe(plaintext)
        }
    }

    /** Si [block] falla (por lo que sea, también cancelación), destruye el canal antes de propagar. */
    private suspend inline fun destroyOnFailure(channel: RatchetChannel, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            channel.destroy()
            throw e
        }
    }

    private fun expectEmpty(payload: ByteArray) {
        if (payload.isNotEmpty()) {
            Primitives.wipe(payload)
            throw ProtocolException("Carga inesperada en el handshake de sesión")
        }
    }

    /**
     * Aplica nuestro tiempo máximo. Solo NUESTRO plazo se convierte en [HandshakeFailedException]:
     * withTimeoutOrNull devuelve null únicamente cuando vence su propio plazo; uno exterior se
     * propaga tal cual como cancelación.
     */
    private suspend fun <T : Any> withDeadline(stream: FrameStream, timeoutMillis: Long, block: suspend () -> T): T =
        withTimeoutOrNull(timeoutMillis) { block() } ?: run {
            stream.close()
            throw HandshakeFailedException(HandshakeFailedException.Reason.TIMEOUT, "Tiempo de sesión agotado")
        }

    /**
     * Ejecuta el handshake traduciendo errores y garantizando la limpieza: los estados registrados
     * con `track` se borran siempre y, si algo falla, se cierra el flujo.
     */
    private suspend fun <T> guarded(
        stream: FrameStream, block: suspend (track: (HandshakeState) -> HandshakeState) -> T,
    ): T {
        val states = mutableListOf<HandshakeState>()
        var ok = false
        try {
            return block { hs -> hs.also { states += it } }.also { ok = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakeFailedException) {
            throw e
        } catch (e: ProtocolException) {
            throw e
        } catch (e: AuthenticationException) {
            throw HandshakeFailedException(HandshakeFailedException.Reason.AUTH, "Autenticación de la sesión fallida")
        } catch (e: ChannelSecurityException) {
            throw HandshakeFailedException(HandshakeFailedException.Reason.AUTH, "Canal de sesión no válido")
        } catch (e: IllegalStateException) {
            throw HandshakeFailedException(HandshakeFailedException.Reason.STATE, "Estado de sesión no válido")
        } catch (e: IOException) {
            // Incluye EOFException: el otro lado cerró o la conexión se cayó durante el handshake
            throw HandshakeFailedException(HandshakeFailedException.Reason.CONNECTION, "Conexión de sesión cerrada")
        } finally {
            states.forEach { it.wipe() }
            if (!ok) stream.close()
        }
    }
}
