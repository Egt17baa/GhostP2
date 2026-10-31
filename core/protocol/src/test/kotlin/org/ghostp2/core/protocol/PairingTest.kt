package org.ghostp2.core.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.ghostp2.core.crypto.Base32
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.OnionAddress
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.SafetyNumber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

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
        assertTrue(e1 is HandshakeFailedException, "invitador: $e1")
        assertTrue(e2 is HandshakeFailedException, "invitado: $e2")
    }

    @Test
    fun pairingRejectsInvalidOnion(): Unit = runBlocking {
        val secret = Primitives.randomBytes(16)
        val inviter = side(onion = "esto-no-es-una-onion")
        val invitee = side()
        val r1 = async { runCatching { Pairing.asInviter(transport.a, inviter.identity, secret.copyOf(), inviter.offer) } }
        val r2 = async { runCatching { Pairing.asInvitee(transport.b, invitee.identity, secret.copyOf(), invitee.offer) } }
        r1.await()
        val e2 = r2.await().exceptionOrNull()
        assertTrue(e2 is ProtocolException, "el receptor debe rechazar la .onion: $e2")
    }

    @Test
    fun pairingRejectsWrongClientAuthKeyLength(): Unit = runBlocking {
        val secret = Primitives.randomBytes(16)
        val inviter = side()
        val invitee = Side(Primitives.generateX25519(), PairingOffer(randomOnion(), ByteArray(31)))
        val r1 = async { runCatching { Pairing.asInviter(transport.a, inviter.identity, secret.copyOf(), inviter.offer) } }
        val r2 = async { runCatching { Pairing.asInvitee(transport.b, invitee.identity, secret.copyOf(), invitee.offer) } }
        val e1 = r1.await().exceptionOrNull()
        r2.await()
        assertTrue(e1 is ProtocolException, "el invitador debe rechazar la clave de 31 bytes: $e1")
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
        assertEquals("Tiempo de emparejamiento agotado", e.message)
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
