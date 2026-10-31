package org.ghostp2.core.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import org.ghostp2.core.crypto.AuthenticationException
import org.ghostp2.core.crypto.ChannelSecurityException
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.OnionAddress
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import org.ghostp2.core.crypto.SafetyNumber
import org.ghostp2.core.crypto.noise.HandshakePattern
import org.ghostp2.core.crypto.noise.HandshakeState
import java.io.IOException

/** Lo que yo entrego al emparejar: mi .onion y la clave privada x25519 con la que el otro entrará en ella. */
data class PairingOffer(val myOnion: String, val clientAuthPrivateForPeer: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PairingOffer && myOnion == other.myOnion &&
            clientAuthPrivateForPeer.contentEquals(other.clientAuthPrivateForPeer)

    override fun hashCode(): Int = 31 * myOnion.hashCode() + clientAuthPrivateForPeer.contentHashCode()

    override fun toString(): String = "PairingOffer(myOnion=$myOnion, clientAuthPrivateForPeer=***)"
}

/** Resultado de un emparejamiento correcto. [safetyNumber] solo depende de las dos identidades. */
data class PairingResult(
    val peerIdentity: ByteArray,
    val peerOnion: String,
    val clientAuthPrivateForPeerOnion: ByteArray,
    val pairSecret: ByteArray,
    val safetyNumber: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PairingResult && peerIdentity.contentEquals(other.peerIdentity) && peerOnion == other.peerOnion &&
            clientAuthPrivateForPeerOnion.contentEquals(other.clientAuthPrivateForPeerOnion) &&
            pairSecret.contentEquals(other.pairSecret) && safetyNumber == other.safetyNumber

    override fun hashCode(): Int {
        var h = peerIdentity.contentHashCode()
        h = 31 * h + peerOnion.hashCode()
        h = 31 * h + clientAuthPrivateForPeerOnion.contentHashCode()
        h = 31 * h + pairSecret.contentHashCode()
        return 31 * h + safetyNumber.hashCode()
    }

    override fun toString(): String =
        "PairingResult(peerIdentity=${peerIdentity.size} bytes, peerOnion=$peerOnion, " +
            "clientAuthPrivateForPeerOnion=***, pairSecret=***, safetyNumber=$safetyNumber)"
}

/** Carga CBOR que viaja cifrada por el canal tras el handshake. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class PairingPayload(val onion: String, @ByteString val clientAuthPrivate: ByteArray)

/**
 * Emparejamiento de una sola vez con Noise XXpsk3 sobre un [FrameStream].
 *
 * Flujo: el invitado (iniciador) escribe msg1; el invitador lo lee y escribe msg2; el invitado lo
 * lee y escribe msg3; el invitador lo lee. Después ambos crean un [RatchetChannel]; primero el
 * INVITADO envía su [PairingOffer], luego el invitador la suya y, por último, el invitado envía
 * la trama de cierre [PAIRING_OK] (orden fijo, sin bloqueos). El invitador solo devuelve su
 * resultado tras recibir y comprobar esa trama, así que nunca da por emparejado a alguien que no
 * recibió su oferta. Queda un caso asimétrico inevitable: si la trama "ok" se pierde, el invitado
 * termina bien y el invitador falla (la app del invitado verá después `UNKNOWN_CALLER` al llamar
 * y deberá ofrecer volver a emparejar).
 *
 * Errores ([HandshakeFailedException.reason]):
 * - `AUTH`: `AuthenticationException`/`ChannelSecurityException` (p. ej. el invitador lee msg3
 *   con un secreto distinto) o trama "ok" no válida.
 * - `CONNECTION`: EOF/IOException (el otro lado abortó: es lo que ve el lado que NO detectó el fallo).
 * - `TIMEOUT`: venció NUESTRO plazo (60 s). Un plazo exterior del llamador no se convierte: se
 *   propaga como cancelación.
 * - `STATE`: `IllegalStateException`, handshake incompleto o sin identidad remota.
 * - [ProtocolException]: el otro lado envió datos mal formados (tramas, CBOR, .onion o clave inválida).
 * - `IllegalArgumentException` (error de programación, antes de tocar el flujo): oferta local
 *   con .onion no válida o clave que no mide 32 bytes.
 *
 * Ante cualquier fallo se cierra el flujo (no sirve para nada más y así el otro lado no espera
 * indefinidamente). En todos los casos se borran la PSK, el handshake y el canal; si falla,
 * también el `pairSecret` y la clave de autorización recibida.
 *
 * Propiedad de los secretos: [secret], la identidad y la oferta local son de quien llama (no se
 * borran). El [PairingResult] devuelto también: quien llama debe guardar y después borrar
 * `pairSecret` y `clientAuthPrivateForPeerOnion` cuando ya no los necesite en memoria. El flujo
 * no se cierra al terminar bien: quien llama lo cierra (la conexión de emparejamiento no se
 * reutiliza). La caducidad y el uso único de la invitación los impone la app (plan 2), no el
 * núcleo; un intento fallido no debe consumir la invitación.
 */
@OptIn(ExperimentalSerializationApi::class)
object Pairing {
    internal const val TIMEOUT_MILLIS = 60_000L
    private const val KEY_LEN = 32

    private val PROLOGUE = "GhostP2 pairing v1".toByteArray(Charsets.UTF_8)
    private val PSK_INFO = "GhostP2 pairing psk v1".toByteArray(Charsets.UTF_8)
    private val PAIR_SECRET_INFO = "GhostP2 pair-secret v1".toByteArray(Charsets.UTF_8)

    /** Texto de la trama final que el invitado envía tras recibir la oferta del invitador. */
    private val PAIRING_OK = "GhostP2 pairing ok v1".toByteArray(Charsets.UTF_8)
    private val EMPTY = ByteArray(0)

    /** Quien generó la invitación: respondedor XXpsk3. */
    suspend fun asInviter(stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer): PairingResult =
        asInviter(stream, identity, secret, offer, TIMEOUT_MILLIS)

    /** Quien escaneó la invitación: iniciador XXpsk3. */
    suspend fun asInvitee(stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer): PairingResult =
        asInvitee(stream, identity, secret, offer, TIMEOUT_MILLIS)

    internal suspend fun asInviter(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer, timeoutMillis: Long,
    ): PairingResult = pair(stream, identity, secret, offer, initiator = false, timeoutMillis)

    internal suspend fun asInvitee(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer, timeoutMillis: Long,
    ): PairingResult = pair(stream, identity, secret, offer, initiator = true, timeoutMillis)

    /**
     * Aplica nuestro tiempo máximo. Solo NUESTRO plazo se convierte en `TIMEOUT`:
     * withTimeoutOrNull devuelve null únicamente cuando vence su propio plazo; uno exterior se
     * propaga tal cual como cancelación.
     */
    private suspend fun pair(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer,
        initiator: Boolean, timeoutMillis: Long,
    ): PairingResult {
        require(OnionAddress.isValid(OnionAddress.normalize(offer.myOnion))) { "La .onion de la oferta local no es válida" }
        require(offer.clientAuthPrivateForPeer.size == KEY_LEN) { "La clave de la oferta local debe tener $KEY_LEN bytes" }
        return withTimeoutOrNull(timeoutMillis) { exchange(stream, identity, secret, offer, initiator) } ?: run {
            stream.close()
            throw HandshakeFailedException(HandshakeFailedException.Reason.TIMEOUT, "Tiempo de emparejamiento agotado")
        }
    }

    private suspend fun exchange(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer, initiator: Boolean,
    ): PairingResult {
        val psk = Primitives.hkdf(ByteArray(KEY_LEN), secret, PSK_INFO, KEY_LEN)
        var hs: HandshakeState? = null
        var channel: RatchetChannel? = null
        var pairSecret: ByteArray? = null
        var peer: PeerOffer? = null
        var ok = false
        try {
            val state = HandshakeState(HandshakePattern.XXpsk3, initiator, PROLOGUE, identity, null, psk)
            hs = state
            if (initiator) {
                stream.send(state.writeMessage(EMPTY))
                state.readMessage(stream.receive())
                stream.send(state.writeMessage(EMPTY))
            } else {
                state.readMessage(stream.receive())
                stream.send(state.writeMessage(EMPTY))
                state.readMessage(stream.receive())
            }
            if (!state.isComplete) throw HandshakeFailedException(HandshakeFailedException.Reason.STATE, "Handshake incompleto")
            val peerIdentity = state.remoteStaticKey
                ?: throw HandshakeFailedException(HandshakeFailedException.Reason.STATE, "Falta la identidad del otro lado")

            // pairSecret se deriva del hash del handshake antes de split (no depende de él)
            val hash = state.handshakeHash()
            pairSecret = try {
                Primitives.hkdf(secret, hash, PAIR_SECRET_INFO, KEY_LEN)
            } finally {
                Primitives.wipe(hash)
            }

            val ch = RatchetChannel.fromHandshake(state, initiator)
            channel = ch
            if (initiator) {
                sendOffer(stream, ch, offer)
                peer = receiveOffer(stream, ch)
                stream.send(ch.encrypt(PAIRING_OK))
            } else {
                peer = receiveOffer(stream, ch)
                sendOffer(stream, ch, offer)
                expectOk(stream, ch)
            }
            val result = PairingResult(
                peerIdentity = peerIdentity,
                peerOnion = peer.onion,
                clientAuthPrivateForPeerOnion = peer.clientAuthPrivate,
                pairSecret = pairSecret,
                safetyNumber = SafetyNumber.compute(identity.publicKey, peerIdentity),
            )
            ok = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakeFailedException) {
            throw e
        } catch (e: ProtocolException) {
            throw e
        } catch (e: AuthenticationException) {
            throw HandshakeFailedException(HandshakeFailedException.Reason.AUTH, "Autenticación del emparejamiento fallida")
        } catch (e: ChannelSecurityException) {
            throw HandshakeFailedException(HandshakeFailedException.Reason.AUTH, "Canal de emparejamiento no válido")
        } catch (e: IllegalStateException) {
            throw HandshakeFailedException(HandshakeFailedException.Reason.STATE, "Estado de emparejamiento no válido")
        } catch (e: IOException) {
            // Incluye EOFException: el otro lado cerró o la conexión se cayó durante el emparejamiento
            throw HandshakeFailedException(HandshakeFailedException.Reason.CONNECTION, "Conexión de emparejamiento cerrada")
        } finally {
            Primitives.wipe(psk)
            hs?.wipe()
            channel?.destroy()
            if (!ok) {
                pairSecret?.let { Primitives.wipe(it) }
                peer?.let { Primitives.wipe(it.clientAuthPrivate) }
                stream.close()
            }
        }
    }

    private suspend fun sendOffer(stream: FrameStream, channel: RatchetChannel, offer: PairingOffer) {
        val encoded = Cbor.encodeToByteArray(
            PairingPayload.serializer(),
            PairingPayload(offer.myOnion, offer.clientAuthPrivateForPeer),
        )
        try {
            stream.send(channel.encrypt(encoded))
        } finally {
            Primitives.wipe(encoded)
        }
    }

    /** Recibe y verifica la trama final del invitado (prueba que recibió nuestra oferta). */
    private suspend fun expectOk(stream: FrameStream, channel: RatchetChannel) {
        val plaintext = channel.decrypt(stream.receive())
        try {
            if (!Primitives.constantTimeEquals(plaintext, PAIRING_OK)) {
                throw HandshakeFailedException(HandshakeFailedException.Reason.AUTH, "Confirmación de emparejamiento no válida")
            }
        } finally {
            Primitives.wipe(plaintext)
        }
    }

    private class PeerOffer(val onion: String, val clientAuthPrivate: ByteArray)

    private suspend fun receiveOffer(stream: FrameStream, channel: RatchetChannel): PeerOffer {
        val plaintext = channel.decrypt(stream.receive())
        val payload = try {
            Cbor.decodeFromByteArray(PairingPayload.serializer(), plaintext)
        } catch (e: Exception) {
            // Cualquier fallo del decodificador (no solo SerializationException), como en AppCodec
            throw ProtocolException("Oferta de emparejamiento mal formada")
        } finally {
            Primitives.wipe(plaintext)
        }
        val onion = OnionAddress.normalize(payload.onion)
        if (!OnionAddress.isValid(onion)) {
            Primitives.wipe(payload.clientAuthPrivate)
            throw ProtocolException("Dirección .onion del otro lado no válida")
        }
        if (payload.clientAuthPrivate.size != KEY_LEN) {
            Primitives.wipe(payload.clientAuthPrivate)
            throw ProtocolException("Clave de autorización del otro lado no válida")
        }
        return PeerOffer(onion, payload.clientAuthPrivate)
    }
}
