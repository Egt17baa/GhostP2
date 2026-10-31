package org.ghostp2.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Invitation
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvitationTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun cerrar() {
        harness?.close()
    }

    private fun harness(n: Int, config: EngineConfig = EngineConfig()): EngineHarness =
        EngineHarness(n, config).also { harness = it }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Espera a que la invitación salga de `Preparing` y devuelve su estado `Waiting`. */
    private suspend fun awaitWaiting(handle: InvitationHandle): InvitationState.Waiting =
        withTimeout(TIMEOUT) { handle.state.first { it !is InvitationState.Preparing } }
            .let { assertInstanceOf(InvitationState.Waiting::class.java, it) }

    /** Espera a un estado final (ni `Preparing` ni `Waiting`). */
    private suspend fun awaitEnd(handle: InvitationHandle): InvitationState =
        withTimeout(TIMEOUT) {
            handle.state.first { it !is InvitationState.Preparing && it !is InvitationState.Waiting }
        }

    private fun onionOf(text: String): String = Invitation.parse(text).onion

    /** Mismo .onion temporal, secreto distinto. */
    private fun withWrongSecret(text: String): String = Invitation(onionOf(text), Primitives.randomBytes(16)).encode()

    @Test
    fun inviteAndAcceptPairsBothSides() = runBlocking<Unit> {
        val h = harness(2)
        h.startAll()
        val a = h.engine(0)
        val b = h.engine(1)
        val handle = a.createInvitation()
        val waiting = awaitWaiting(handle)
        assertTrue(waiting.text.startsWith("ghostp2:"))
        assertTrue(waiting.expiresAtMillis > System.currentTimeMillis())
        // Servicio temporal publicado sin autorización de cliente.
        val temp = h.network.published.value.getValue(onionOf(waiting.text))
        assertFalse(temp.requiresClientAuth)
        assertEquals(7420, temp.virtualPort)

        val accepted = b.acceptInvitation(waiting.text)
        val bPaired = assertInstanceOf(AcceptResult.Paired::class.java, accepted)
        val aPaired = assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))

        val aContact = h.contacts(0).all().single()
        val bContact = h.contacts(1).all().single()
        val aId = h.identities(0).load()!!
        val bId = h.identities(1).load()!!
        // Cada uno tiene al otro, con el identificador y el número de seguridad comunes.
        assertEquals(bId.identityPublic.hex(), aContact.peerIdentity.hex())
        assertEquals(aId.identityPublic.hex(), bContact.peerIdentity.hex())
        assertEquals(contactIdFor(bId.identityPublic), aContact.id)
        assertEquals(aContact.id, aPaired.contactId)
        assertEquals(bContact.id, bPaired.contactId)
        assertEquals(aPaired.safetyNumber, bPaired.safetyNumber)
        for (c in listOf(aContact, bContact)) {
            assertEquals("Contacto nuevo", c.name)
            assertFalse(c.verified)
            assertEquals(ContactStatus.ACTIVE, c.status)
            assertEquals(32, c.pairSecret.size)
        }
        assertEquals(aContact.pairSecret.hex(), bContact.pairSecret.hex())
        // .onion permanentes intercambiadas.
        assertEquals(aId.onion, bContact.peerOnion)
        assertEquals(bId.onion, aContact.peerOnion)
        assertEquals(aId.onion, a.myOnion.value)
        assertEquals(bId.onion, b.myOnion.value)

        // La clave que A dio a B entra en la .onion de A (y viceversa), publicadas con autorización.
        val published = h.network.published.value
        assertNull(published[onionOf(waiting.text)], "el servicio temporal debe retirarse")
        assertEquals(2, published.size)
        val aService = published.getValue(aId.onion!!)
        val bService = published.getValue(bId.onion!!)
        assertEquals(listOf(aContact.myClientAuthPublic.hex()), aService.clientAuthPublicKeys.map { it.hex() })
        assertEquals(listOf(bContact.myClientAuthPublic.hex()), bService.clientAuthPublicKeys.map { it.hex() })
        assertEquals(
            aContact.myClientAuthPublic.hex(),
            Primitives.x25519PublicFromPrivate(bContact.clientAuthPrivateForPeerOnion).publicKey.hex(),
        )
        assertEquals(
            bContact.myClientAuthPublic.hex(),
            Primitives.x25519PublicFromPrivate(aContact.clientAuthPrivateForPeerOnion).publicKey.hex(),
        )
        // addClientAuth registrado: FakeTor solo deja conectar con la autorización correcta.
        h.tor(1).connect(aId.onion!!, 7420, 5_000).close()
        h.tor(0).connect(bId.onion!!, 7420, 5_000).close()
    }

    @Test
    fun failedAttemptsDoNotConsumeInvitation() = runBlocking<Unit> {
        val h = harness(3)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        repeat(3) {
            val r = h.engine(2).acceptInvitation(withWrongSecret(waiting.text))
            assertInstanceOf(AcceptResult.Failed::class.java, r)
        }
        assertEquals(waiting, handle.state.value)
        assertTrue(h.contacts(0).all().isEmpty())
        assertTrue(h.contacts(2).all().isEmpty())
        // El intruso no deja publicada su .onion permanente (cero contactos).
        assertNull(h.engine(2).myOnion.value)

        assertInstanceOf(AcceptResult.Paired::class.java, h.engine(1).acceptInvitation(waiting.text))
        assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))
        assertEquals(1, h.contacts(0).all().size)
        assertEquals(contactIdFor(h.identities(1).load()!!.identityPublic), h.contacts(0).all().single().id)
    }

    @Test
    fun invitationExpiresAfterTtl() = runBlocking<Unit> {
        val h = harness(2, EngineConfig(invitationTtlMillis = 300))
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        assertEquals(InvitationState.Expired, awaitEnd(handle))
        assertNull(h.network.published.value[onionOf(waiting.text)])
        // Sin contactos, la .onion permanente reservada para el emparejamiento se retira.
        assertTrue(h.network.published.value.isEmpty())
        assertNull(h.engine(0).myOnion.value)

        val r = h.engine(1).acceptInvitation(waiting.text)
        assertInstanceOf(AcceptResult.Failed::class.java, r)
        assertTrue(h.contacts(0).all().isEmpty())
        assertTrue(h.contacts(1).all().isEmpty())
        assertTrue(h.network.published.value.isEmpty())
    }

    @Test
    fun invitationIsSingleUse() = runBlocking<Unit> {
        val h = harness(3)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        assertInstanceOf(AcceptResult.Paired::class.java, h.engine(1).acceptInvitation(waiting.text))
        assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))

        // Ni el mismo invitado ni un tercero pueden volver a usarla.
        val again = assertInstanceOf(AcceptResult.Failed::class.java, h.engine(1).acceptInvitation(waiting.text))
        assertEquals("No se pudo conectar con la invitación (¿caducada o ya usada?)", again.message)
        assertInstanceOf(AcceptResult.Failed::class.java, h.engine(2).acceptInvitation(waiting.text))
        assertEquals(1, h.contacts(0).all().size)
        assertEquals(1, h.contacts(1).all().size)
        assertTrue(h.contacts(2).all().isEmpty())
    }

    @Test
    fun malformedInvitationFailsClearly() = runBlocking<Unit> {
        val h = harness(1)
        h.startAll()
        for (text in listOf("", "hola", "ghostp2:abc.def", "ghostp2:" + "a".repeat(56) + "." + "b".repeat(26))) {
            val r = assertInstanceOf(AcceptResult.Failed::class.java, h.engine(0).acceptInvitation(text))
            assertTrue(r.message.startsWith("Invitación no válida"), r.message)
        }
        assertTrue(h.network.published.value.isEmpty())
    }

    @Test
    fun cancelRetiresTemporaryService() = runBlocking<Unit> {
        val h = harness(2)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        handle.cancel()
        assertEquals(InvitationState.Cancelled, awaitEnd(handle))
        assertTrue(h.network.published.value.isEmpty())
        assertInstanceOf(AcceptResult.Failed::class.java, h.engine(1).acceptInvitation(waiting.text))
    }

    @Test
    fun newInvitationCancelsPrevious() = runBlocking<Unit> {
        val h = harness(2)
        h.startAll()
        val first = h.engine(0).createInvitation()
        val firstWaiting = awaitWaiting(first)
        val second = h.engine(0).createInvitation()
        assertEquals(InvitationState.Cancelled, awaitEnd(first))
        val secondWaiting = awaitWaiting(second)
        assertNull(h.network.published.value[onionOf(firstWaiting.text)])
        assertInstanceOf(AcceptResult.Failed::class.java, h.engine(1).acceptInvitation(firstWaiting.text))
        assertInstanceOf(AcceptResult.Paired::class.java, h.engine(1).acceptInvitation(secondWaiting.text))
        assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(second))
    }

    @Test
    fun requiresOnlineEngine() = runBlocking<Unit> {
        val h = harness(1)
        val handle = h.engine(0).createInvitation()
        assertEquals(InvitationState.Failed("GhostP2 no está activo"), handle.state.value)
        assertEquals(AcceptResult.Failed("GhostP2 no está activo"), h.engine(0).acceptInvitation("ghostp2:x.y"))
    }

    @Test
    fun concurrentAcceptsCreateOnlyOneContact() = runBlocking<Unit> {
        val h = harness(3)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        val r1 = async { h.engine(1).acceptInvitation(waiting.text) }
        val r2 = async { h.engine(2).acceptInvitation(waiting.text) }
        val results = listOf(r1.await(), r2.await())
        val aPaired = assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))
        assertTrue(results.any { it is AcceptResult.Paired })
        // El invitador guarda un único contacto: el de quien ganó.
        val aContacts = h.contacts(0).all()
        assertEquals(1, aContacts.size)
        assertEquals(aPaired.contactId, aContacts.single().id)
        assertEquals(1, h.network.published.value.getValue(h.engine(0).myOnion.value!!).clientAuthPublicKeys.size)
    }

    @Test
    fun repairingSameContactReplacesIt() = runBlocking<Unit> {
        val h = harness(2)
        h.startAll()
        repeat(2) {
            val handle = h.engine(0).createInvitation()
            val waiting = awaitWaiting(handle)
            assertInstanceOf(AcceptResult.Paired::class.java, h.engine(1).acceptInvitation(waiting.text))
            assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))
        }
        val aContact = h.contacts(0).all().single()
        val bContact = h.contacts(1).all().single()
        // Solo la clave del último emparejamiento sigue autorizada.
        val aService = h.network.published.value.getValue(h.engine(0).myOnion.value!!)
        assertEquals(listOf(aContact.myClientAuthPublic.hex()), aService.clientAuthPublicKeys.map { it.hex() })
        val bService = h.network.published.value.getValue(h.engine(1).myOnion.value!!)
        assertEquals(listOf(bContact.myClientAuthPublic.hex()), bService.clientAuthPublicKeys.map { it.hex() })
        h.tor(1).connect(h.engine(0).myOnion.value!!, 7420, 5_000).close()
    }

    @Test
    fun stopEndsWaitingInvitation() = runBlocking<Unit> {
        val h = harness(1)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        awaitWaiting(handle)
        h.engine(0).stop()
        assertEquals(InvitationState.Cancelled, awaitEnd(handle))
        assertTrue(h.network.published.value.isEmpty())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
