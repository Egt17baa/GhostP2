package org.ghostp2.core.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
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
@Serializable
internal class PairingPayload(val onion: String, val clientAuthPrivate: ByteArray)

/**
 * Emparejamiento de una sola vez con Noise XXpsk3 sobre un [FrameStream].
 *
 * Flujo: el invitado (iniciador) escribe msg1; el invitador lo lee y escribe msg2; el invitado lo
 * lee y escribe msg3; el invitador lo lee. Después ambos crean un [RatchetChannel]; primero el
 * INVITADO envía su [PairingOffer] y luego el invitador la suya (orden fijo, sin bloqueos).
 *
 * Errores:
 * - [HandshakeFailedException]: autenticación fallida (p. ej. secreto distinto), error del canal,
 *   cierre o caída de la conexión (EOF/IOException: el otro lado abortó) y tiempo agotado (60 s).
 * - [ProtocolException]: el otro lado envió datos mal formados (tramas, CBOR, .onion o clave inválida).
 *
 * Ante cualquier fallo se cierra el flujo (no sirve para nada más y así el otro lado no espera
 * indefinidamente). En todos los casos se borran la PSK, el handshake y el canal.
 */
@OptIn(ExperimentalSerializationApi::class)
object Pairing {
    internal const val TIMEOUT_MILLIS = 60_000L
    private const val KEY_LEN = 32

    private val PROLOGUE = "GhostP2 pairing v1".toByteArray(Charsets.UTF_8)
    private val PSK_INFO = "GhostP2 pairing psk v1".toByteArray(Charsets.UTF_8)
    private val PAIR_SECRET_INFO = "GhostP2 pair-secret v1".toByteArray(Charsets.UTF_8)
    private val EMPTY = ByteArray(0)

    /** Quien generó la invitación: respondedor XXpsk3. */
    suspend fun asInviter(stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer): PairingResult =
        asInviter(stream, identity, secret, offer, TIMEOUT_MILLIS)

    /** Quien escaneó la invitación: iniciador XXpsk3. */
    suspend fun asInvitee(stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer): PairingResult =
        asInvitee(stream, identity, secret, offer, TIMEOUT_MILLIS)

    internal suspend fun asInviter(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer, timeoutMillis: Long,
    ): PairingResult = run(stream, identity, secret, offer, initiator = false, timeoutMillis)

    internal suspend fun asInvitee(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer, timeoutMillis: Long,
    ): PairingResult = run(stream, identity, secret, offer, initiator = true, timeoutMillis)

    private suspend fun run(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer,
        initiator: Boolean, timeoutMillis: Long,
    ): PairingResult = try {
        withTimeout(timeoutMillis) { exchange(stream, identity, secret, offer, initiator) }
    } catch (e: TimeoutCancellationException) {
        stream.close()
        throw HandshakeFailedException("Tiempo de emparejamiento agotado")
    }

    private suspend fun exchange(
        stream: FrameStream, identity: KeyPair25519, secret: ByteArray, offer: PairingOffer, initiator: Boolean,
    ): PairingResult {
        val psk = Primitives.hkdf(ByteArray(KEY_LEN), secret, PSK_INFO, KEY_LEN)
        var hs: HandshakeState? = null
        var channel: RatchetChannel? = null
        var pairSecret: ByteArray? = null
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
            if (!state.isComplete) throw HandshakeFailedException("Handshake incompleto")
            val peerIdentity = state.remoteStaticKey ?: throw HandshakeFailedException("Falta la identidad del otro lado")

            // pairSecret se deriva del hash del handshake antes de split (no depende de él)
            val hash = state.handshakeHash()
            pairSecret = try {
                Primitives.hkdf(secret, hash, PAIR_SECRET_INFO, KEY_LEN)
            } finally {
                Primitives.wipe(hash)
            }

            val ch = RatchetChannel.fromHandshake(state, initiator)
            channel = ch
            val peer = if (initiator) {
                sendOffer(stream, ch, offer)
                receiveOffer(stream, ch)
            } else {
                val received = receiveOffer(stream, ch)
                sendOffer(stream, ch, offer)
                received
            }
            ok = true
            return PairingResult(
                peerIdentity = peerIdentity,
                peerOnion = peer.onion,
                clientAuthPrivateForPeerOnion = peer.clientAuthPrivate,
                pairSecret = pairSecret,
                safetyNumber = SafetyNumber.compute(identity.publicKey, peerIdentity),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakeFailedException) {
            throw e
        } catch (e: ProtocolException) {
            throw e
        } catch (e: AuthenticationException) {
            throw HandshakeFailedException("Autenticación del emparejamiento fallida")
        } catch (e: ChannelSecurityException) {
            throw HandshakeFailedException("Canal de emparejamiento no válido")
        } catch (e: IllegalStateException) {
            throw HandshakeFailedException("Estado de emparejamiento no válido")
        } catch (e: IOException) {
            // Incluye EOFException: el otro lado cerró o la conexión se cayó durante el emparejamiento
            throw HandshakeFailedException("Conexión de emparejamiento cerrada")
        } finally {
            Primitives.wipe(psk)
            hs?.wipe()
            channel?.destroy()
            if (!ok) {
                pairSecret?.let { Primitives.wipe(it) }
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

    private class PeerOffer(val onion: String, val clientAuthPrivate: ByteArray)

    private suspend fun receiveOffer(stream: FrameStream, channel: RatchetChannel): PeerOffer {
        val plaintext = channel.decrypt(stream.receive())
        val payload = try {
            Cbor.decodeFromByteArray(PairingPayload.serializer(), plaintext)
        } catch (e: SerializationException) {
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
