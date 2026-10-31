package org.ghostp2.core.protocol

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionHandshakeTest {
    private val transport = TestTransport()

    @AfterEach
    fun tearDown() = transport.close()

    /** Dos contactos ya emparejados: cada uno guarda la identidad del otro y el pairSecret común. */
    private class Pair2(val alice: KeyPair25519, val bob: KeyPair25519, val pairSecret: ByteArray) {
        /** Lo que Alice guarda de Bob. */
        val bobAsSeenByAlice get() = ContactKeys(bob.publicKey.copyOf(), pairSecret.copyOf())
        /** Lo que Bob guarda de Alice. */
        val aliceAsSeenByBob get() = ContactKeys(alice.publicKey.copyOf(), pairSecret.copyOf())
    }

    private fun paired() = Pair2(Primitives.generateX25519(), Primitives.generateX25519(), Primitives.randomBytes(32))

    private fun randomContact() = ContactKeys(Primitives.generateX25519().publicKey, Primitives.randomBytes(32))

    /** Alice (A) inicia; Bob (B) responde con la lista de candidatos dada. */
    private fun session(
        alice: KeyPair25519, contactForAlice: ContactKeys, bob: KeyPair25519, candidates: List<ContactKeys>,
        t: TestTransport = transport,
    ): Triple<RatchetChannel, ContactKeys, RatchetChannel> = runBlocking {
        val i = async { SessionHandshake.initiate(t.a, alice, contactForAlice) }
        val r = async { SessionHandshake.respond(t.b, bob, candidates) }
        val initiatorChannel = i.await()
        val (who, responderChannel) = r.await()
        Triple(initiatorChannel, who, responderChannel)
    }

    @Test
    fun sessionOpensBetweenPairedContacts() {
        val p = paired()
        val contact = p.aliceAsSeenByBob
        val (alice, who, bob) = session(p.alice, p.bobAsSeenByAlice, p.bob, listOf(contact))
        assertSame(contact, who)
        val hola = "hola Bob".toByteArray()
        assertArrayEquals(hola, bob.decrypt(alice.encrypt(hola)))
        val adios = "hola Alice".toByteArray()
        assertArrayEquals(adios, alice.decrypt(bob.encrypt(adios)))
    }

    @Test
    fun responderIdentifiesCallerAmongMany() {
        val p = paired()
        val real = p.aliceAsSeenByBob
        val candidates = listOf(randomContact(), randomContact(), real, randomContact(), randomContact())
        val (alice, who, bob) = session(p.alice, p.bobAsSeenByAlice, p.bob, candidates)
        assertSame(real, who)
        val msg = "¿me reconoces?".toByteArray()
        assertArrayEquals(msg, bob.decrypt(alice.encrypt(msg)))
    }

    @Test
    fun sessionRejectsUnknownIdentity(): Unit = runBlocking {
        val p = paired()
        val reinstalled = Primitives.generateX25519() // Alice reinstaló: identidad nueva
        val i = async { runCatching { SessionHandshake.initiate(transport.a, reinstalled, p.bobAsSeenByAlice) } }
        val r = async { runCatching { SessionHandshake.respond(transport.b, p.bob, listOf(p.aliceAsSeenByBob)) } }
        val eR = r.await().exceptionOrNull()
        val eI = i.await().exceptionOrNull()
        assertTrue(eR is HandshakeFailedException, "respondedor: $eR")
        assertTrue(eI is HandshakeFailedException, "iniciador: $eI")
    }

    /**
     * En KKpsk2 la PSK solo entra en msg2 (`psk` al final), así que msg1 autentica la identidad
     * pero no el pairSecret: quien lo detecta es el iniciador al leer msg2. Este lanza
     * [HandshakeFailedException] y cierra el flujo, de modo que el canal del respondedor no
     * llega a servir: su primera lectura falla.
     */
    @Test
    fun sessionRejectsWrongPairSecret(): Unit = runBlocking {
        val p = paired()
        val wrong = ContactKeys(p.bob.publicKey.copyOf(), Primitives.randomBytes(32))
        val i = async { runCatching { SessionHandshake.initiate(transport.a, p.alice, wrong) } }
        val r = async { runCatching { SessionHandshake.respond(transport.b, p.bob, listOf(p.aliceAsSeenByBob)) } }
        val eI = i.await().exceptionOrNull()
        assertTrue(eI is HandshakeFailedException, "iniciador: $eI")
        r.await().onSuccess { (_, bobChannel) ->
            // El respondedor no puede saberlo aún; el iniciador cerró, así que no llega nada
            runCatching { bobChannel.decrypt(transport.b.receive()) }
                .onSuccess { throw AssertionError("El canal del respondedor no debería funcionar") }
        }.onFailure { assertTrue(it is HandshakeFailedException, "respondedor: $it") }
    }

    /** Al revés: el respondedor guarda un pairSecret distinto; también falla el iniciador. */
    @Test
    fun responderWithWrongPairSecretIsRejectedByInitiator(): Unit = runBlocking {
        val p = paired()
        val wrongAtBob = ContactKeys(p.alice.publicKey.copyOf(), Primitives.randomBytes(32))
        val i = async { runCatching { SessionHandshake.initiate(transport.a, p.alice, p.bobAsSeenByAlice) } }
        val r = async { runCatching { SessionHandshake.respond(transport.b, p.bob, listOf(wrongAtBob)) } }
        val eI = i.await().exceptionOrNull()
        assertTrue(eI is HandshakeFailedException, "iniciador: $eI")
        r.await()
    }

    @Test
    fun sessionKeysAreFreshEachTime() {
        val p = paired()
        val text = "mismo texto".toByteArray()
        val (a1, _, _) = session(p.alice, p.bobAsSeenByAlice, p.bob, listOf(p.aliceAsSeenByBob))
        val frame1 = a1.encrypt(text)
        TestTransport().use { t2 ->
            val (a2, _, b2) = session(p.alice, p.bobAsSeenByAlice, p.bob, listOf(p.aliceAsSeenByBob), t2)
            val frame2 = a2.encrypt(text)
            assertFalse(frame1.contentEquals(frame2))
            assertArrayEquals(text, b2.decrypt(frame2))
        }
    }

    @Test
    fun responderWithoutCandidatesFails(): Unit = runBlocking {
        val p = paired()
        val i = async { runCatching { SessionHandshake.initiate(transport.a, p.alice, p.bobAsSeenByAlice) } }
        val r = async { runCatching { SessionHandshake.respond(transport.b, p.bob, emptyList()) } }
        assertTrue(r.await().exceptionOrNull() is HandshakeFailedException)
        assertTrue(i.await().exceptionOrNull() is HandshakeFailedException)
    }

    @Test
    fun handshakeDoesNotWipeStoredContactKeys() {
        val p = paired()
        val forAlice = p.bobAsSeenByAlice
        val forBob = p.aliceAsSeenByBob
        val decoy = randomContact()
        val decoySecret = decoy.pairSecret.copyOf()
        session(p.alice, forAlice, p.bob, listOf(decoy, forBob))
        assertArrayEquals(p.pairSecret, forAlice.pairSecret)
        assertArrayEquals(p.pairSecret, forBob.pairSecret)
        assertArrayEquals(p.bob.publicKey, forAlice.peerIdentity)
        assertArrayEquals(p.alice.publicKey, forBob.peerIdentity)
        assertArrayEquals(decoySecret, decoy.pairSecret)
    }

    @Test
    fun initiatorTimesOutWhenPeerIsSilent(): Unit = runBlocking {
        val p = paired()
        val e = assertThrowsSuspend<HandshakeFailedException> {
            SessionHandshake.initiate(transport.a, p.alice, p.bobAsSeenByAlice, timeoutMillis = 200)
        }
        assertEquals("Tiempo de sesión agotado", e.message)
    }

    @Test
    fun responderTimesOutWhenPeerIsSilent(): Unit = runBlocking {
        val p = paired()
        assertThrowsSuspend<HandshakeFailedException> {
            SessionHandshake.respond(transport.b, p.bob, listOf(p.aliceAsSeenByBob), timeoutMillis = 200)
        }
    }

    @Test
    fun callerTimeoutIsNotSwallowed(): Unit = runBlocking {
        val p = paired()
        assertThrowsSuspend<TimeoutCancellationException> {
            withTimeout(200) { SessionHandshake.initiate(transport.a, p.alice, p.bobAsSeenByAlice) }
        }
    }

    @Test
    fun garbageFirstMessageIsRejected(): Unit = runBlocking {
        val p = paired()
        transport.a.send(Primitives.randomBytes(48))
        assertThrowsSuspend<HandshakeFailedException> {
            SessionHandshake.respond(transport.b, p.bob, listOf(p.aliceAsSeenByBob))
        }
    }

    @Test
    fun contactKeysComparesContentAndHidesSecret() {
        val id = Primitives.randomBytes(32)
        val secret = Primitives.randomBytes(32)
        val a = ContactKeys(id, secret)
        val b = ContactKeys(id.copyOf(), secret.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ContactKeys(id.copyOf(), Primitives.randomBytes(32)))
        val hex = secret.joinToString("") { "%02x".format(it) }
        assertFalse(a.toString().contains(hex))
        assertTrue(a.toString().contains("***"))
    }
}
