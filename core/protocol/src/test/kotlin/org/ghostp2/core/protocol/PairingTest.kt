package org.ghostp2.core.protocol

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import org.ghostp2.core.crypto.Base32
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.OnionAddress
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import org.ghostp2.core.crypto.SafetyNumber
import org.ghostp2.core.crypto.noise.HandshakePattern
import org.ghostp2.core.crypto.noise.HandshakeState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

@OptIn(ExperimentalSerializationApi::class)
class PairingTest {
    private val transport = TestTransport()

    @AfterEach
    fun tearDown() = transport.close()

    /** Genera una dirección onion v3 válida (con suma de comprobación) a partir de una clave al azar. */
    private fun randomOnion(): String {
        val pub = Primitives.randomBytes(32)
        val sha3 = MessageDigest.getInstance("SHA3-256")
        sha3.update(".onion checksum".toByteArray(Charsets.US_ASCII))
        sha3.update(pub)
        sha3.update(byteArrayOf(3))
        val checksum = sha3.digest().copyOfRange(0, 2)
        return Base32.encode(pub + checksum + byteArrayOf(3)).lowercase().also { check(OnionAddress.isValid(it)) }
    }

    private class Side(val identity: KeyPair25519, val offer: PairingOffer)

    private fun side(onion: String = randomOnion()) =
        Side(Primitives.generateX25519(), PairingOffer(onion, Primitives.randomBytes(32)))

    @Test
    fun pairingExchangesInfoAndAgrees(): Unit = runBlocking {
        val secret = Primitives.randomBytes(16)
        val inviter = side()
        val invitee = side(randomOnion().uppercase() + ".onion") // se normaliza al recibirla
        val r1 = async { Pairing.asInviter(transport.a, inviter.identity, secret.copyOf(), inviter.offer) }
        val r2 = async { Pairing.asInvitee(transport.b, invitee.identity, secret.copyOf(), invitee.offer) }
        val inviterResult = r1.await()
        val inviteeResult = r2.await()

        assertArrayEquals(invitee.identity.publicKey, inviterResult.peerIdentity)
        assertArrayEquals(inviter.identity.publicKey, inviteeResult.peerIdentity)
        assertEquals(OnionAddress.normalize(invitee.offer.myOnion), inviterResult.peerOnion)
        assertEquals(inviter.offer.myOnion, inviteeResult.peerOnion)
        assertArrayEquals(invitee.offer.clientAuthPrivateForPeer, inviterResult.clientAuthPrivateForPeerOnion)
        assertArrayEquals(inviter.offer.clientAuthPrivateForPeer, inviteeResult.clientAuthPrivateForPeerOnion)
        assertEquals(32, inviterResult.pairSecret.size)
        assertArrayEquals(inviterResult.pairSecret, inviteeResult.pairSecret)
        assertEquals(inviterResult.safetyNumber, inviteeResult.safetyNumber)
        assertEquals(
            SafetyNumber.compute(inviter.identity.publicKey, invitee.identity.publicKey),
            inviterResult.safetyNumber,
        )
        // La clave de pareja no es el secreto de la invitación
        assertFalse(inviterResult.pairSecret.contentEquals(secret))
    }

    @Test
    fun pairingFailsWithWrongSecret(): Unit = runBlocking {
        val inviter = side()
        val invitee = side()
        val r1 = async {
            runCatching { Pairing.asInviter(transport.a, inviter.identity, Primitives.randomBytes(16), inviter.offer) }
        }
        val r2 = async {
            runCatching { Pairing.asInvitee(transport.b, invitee.identity, Primitives.randomBytes(16), invitee.offer) }
        }
        val e1 = r1.await().exceptionOrNull()
        val e2 = r2.await().exceptionOrNull()
        // msg3 lleva la PSK: el invitador lo detecta (AUTH); el invitado ve cerrarse la conexión
        assertEquals(HandshakeFailedException.Reason.AUTH, (e1 as HandshakeFailedException).reason, "invitador: $e1")
        assertEquals(HandshakeFailedException.Reason.CONNECTION, (e2 as HandshakeFailedException).reason, "invitado: $e2")
    }

    /** Codifica una oferta tal como viaja por el cable. */
    private fun encodeOffer(onion: String, key: ByteArray): ByteArray =
        Cbor.encodeToByteArray(PairingPayload.serializer(), PairingPayload(onion, key))

    /**
     * Invitado hecho a mano (XXpsk3 iniciador) sobre [stream]: completa el handshake, envía
     * [offerPlaintext] por el canal y después hace [afterOffer] (en vez del resto del protocolo).
     */
    private suspend fun manualInvitee(
        stream: FrameStream, secret: ByteArray, offerPlaintext: ByteArray,
        afterOffer: suspend (RatchetChannel) -> Unit = {},
    ) {
        val psk = Primitives.hkdf(ByteArray(32), secret, "GhostP2 pairing psk v1".toByteArray(), 32)
        val hs = HandshakeState(
            HandshakePattern.XXpsk3, true, "GhostP2 pairing v1".toByteArray(), Primitives.generateX25519(), null, psk,
        )
        stream.send(hs.writeMessage(ByteArray(0)))
        hs.readMessage(stream.receive())
        stream.send(hs.writeMessage(ByteArray(0)))
        val ch = RatchetChannel.fromHandshake(hs, true)
        stream.send(ch.encrypt(offerPlaintext))
        afterOffer(ch)
    }

    /** El invitador frente a un invitado manual que envía [offerPlaintext]; devuelve el resultado del invitador. */
    private fun inviterAgainstManual(
        offerPlaintext: ByteArray, t: TestTransport = transport, afterOffer: suspend (RatchetChannel) -> Unit = {},
    ): Result<PairingResult> = runBlocking {
        val secret = Primitives.randomBytes(16)
        val inviter = side()
        val r = async { runCatching { Pairing.asInviter(t.a, inviter.identity, secret.copyOf(), inviter.offer) } }
        runCatching { manualInvitee(t.b, secret.copyOf(), offerPlaintext, afterOffer) }
        r.await()
    }

    @Test
    fun pairingRejectsInvalidOnion() {
        val e = inviterAgainstManual(encodeOffer("esto-no-es-una-onion", ByteArray(32))).exceptionOrNull()
        assertTrue(e is ProtocolException, "el receptor debe rechazar la .onion: $e")
    }

    @Test
    fun pairingRejectsWrongClientAuthKeyLength() {
        val e = inviterAgainstManual(encodeOffer(randomOnion(), ByteArray(31))).exceptionOrNull()
        assertTrue(e is ProtocolException, "el invitador debe rechazar la clave de 31 bytes: $e")
    }

    /** Cualquier fallo al decodificar la oferta (no solo SerializationException) es ProtocolException. */
    @Test
    fun malformedOfferIsProtocolException() {
        val cases = listOf(
            byteArrayOf(0xbf.toByte(), 0x65), // mapa truncado
            byteArrayOf(0x9f.toByte(), 0x01, 0xff.toByte()), // array en vez de mapa
            byteArrayOf(0xbf.toByte(), 0x65) + "onion".toByteArray() + byteArrayOf(0x01, 0xff.toByte()), // onion entero
            byteArrayOf(0x00), // un entero suelto
        )
        for (bytes in cases) {
            TestTransport().use { t ->
                val e = inviterAgainstManual(bytes, t).exceptionOrNull()
                assertTrue(e is ProtocolException, "${bytes.joinToString("") { "%02x".format(it) }}: $e")
            }
        }
    }

    @Test
    fun localOfferIsValidatedAtEntry(): Unit = runBlocking {
        val secret = Primitives.randomBytes(16)
        val id = Primitives.generateX25519()
        val badOnion = PairingOffer("esto-no-es-una-onion", ByteArray(32))
        val badKey = PairingOffer(randomOnion(), ByteArray(31))
        for (offer in listOf(badOnion, badKey)) {
            assertThrowsSuspend<IllegalArgumentException> { Pairing.asInviter(transport.a, id, secret, offer) }
            assertThrowsSuspend<IllegalArgumentException> { Pairing.asInvitee(transport.b, id, secret, offer) }
        }
    }

    /** El invitador solo termina bien si recibe la trama final "ok" del invitado. */
    @Test
    fun inviterFailsIfInviteeDropsBeforeOk() {
        val e = inviterAgainstManual(encodeOffer(randomOnion(), ByteArray(32))) { ch ->
            ch.decrypt(transport.b.receive()) // la oferta del invitador
            transport.b.close() // ... y se va sin enviar "ok"
        }.exceptionOrNull()
        assertEquals(HandshakeFailedException.Reason.CONNECTION, (e as? HandshakeFailedException)?.reason, "invitador: $e")
    }

    @Test
    fun inviterRejectsWrongOkText() {
        val e = inviterAgainstManual(encodeOffer(randomOnion(), ByteArray(32))) { ch ->
            ch.decrypt(transport.b.receive())
            transport.b.send(ch.encrypt("GhostP2 pairing ko v1".toByteArray()))
        }.exceptionOrNull()
        assertEquals(HandshakeFailedException.Reason.AUTH, (e as? HandshakeFailedException)?.reason, "invitador: $e")
    }

    @Test
    fun manualInviteeWithOkPairs() {
        // Control positivo del arnés anterior: con la trama "ok" correcta el invitador termina bien
        val result = inviterAgainstManual(encodeOffer(randomOnion(), ByteArray(32) { 5 })) { ch ->
            ch.decrypt(transport.b.receive())
            transport.b.send(ch.encrypt("GhostP2 pairing ok v1".toByteArray()))
        }
        assertArrayEquals(ByteArray(32) { 5 }, result.getOrThrow().clientAuthPrivateForPeerOnion)
    }

    /** La clave de autorización viaja como bstr CBOR (`@ByteString`); ejemplo de PROTOCOLO.md §6. */
    @Test
    fun offerWireFormatUsesByteString() {
        val hex = encodeOffer("ab", byteArrayOf(1, 2)).joinToString("") { "%02x".format(it) }
        assertEquals("bf656f6e696f6e626162" + "71" + "clientAuthPrivate".toByteArray().joinToString("") { "%02x".format(it) } + "420102ff", hex)
    }

    @Test
    fun callerTimeoutIsNotSwallowed(): Unit = runBlocking {
        val inviter = side()
        assertThrowsSuspend<TimeoutCancellationException> {
            withTimeout(200) { Pairing.asInviter(transport.a, inviter.identity, Primitives.randomBytes(16), inviter.offer) }
        }
    }

    @Test
    fun pairingTimesOutWhenPeerIsSilent(): Unit = runBlocking {
        val inviter = side()
        // Nadie habla por el otro extremo: el invitador espera el primer mensaje hasta agotar el plazo
        val start = System.nanoTime()
        val e = assertThrowsSuspend<HandshakeFailedException> {
            Pairing.asInviter(transport.a, inviter.identity, Primitives.randomBytes(16), inviter.offer, timeoutMillis = 300)
        }
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "el plazo debe desbloquear la lectura")
        assertEquals(HandshakeFailedException.Reason.TIMEOUT, e.reason)
        assertEquals("Tiempo de emparejamiento agotado", e.message)
    }

    @Test
    fun pairingFailsWithConnectionWhenPeerCloses(): Unit = runBlocking {
        val inviter = side()
        transport.b.close()
        val e = assertThrowsSuspend<HandshakeFailedException> {
            Pairing.asInviter(transport.a, inviter.identity, Primitives.randomBytes(16), inviter.offer)
        }
        assertEquals(HandshakeFailedException.Reason.CONNECTION, e.reason)
    }

    @Test
    fun resultsHideSecretsInToString() {
        val offer = PairingOffer(randomOnion(), ByteArray(32) { 7 })
        assertFalse(offer.toString().contains(offer.clientAuthPrivateForPeer.contentToString()))
        val result = PairingResult(ByteArray(32), randomOnion(), ByteArray(32) { 7 }, ByteArray(32) { 9 }, "12345")
        assertFalse(result.toString().contains("7, 7"))
        assertFalse(result.toString().contains("9, 9"))
    }
}
