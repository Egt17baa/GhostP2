package org.ghostp2.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.io.IOException

class InvitationTest {
    private var harness: EngineHarness? = null
    private val extraEngines = mutableListOf<Pair<MessengerEngine, CoroutineScope>>()

    @AfterEach
    fun cerrar() {
        runBlocking { extraEngines.forEach { runCatching { it.first.stop() } } }
        extraEngines.forEach { it.second.cancel() }
        harness?.close()
    }

    /** Motor adicional sobre la red del banco con dobles a medida (Tor o repositorio de contactos). */
    private fun customEngine(
        h: EngineHarness,
        tor: TorTransport = FakeTor(h.network),
        contacts: ContactRepository = InMemoryContactRepository(),
        identities: InMemoryIdentityRepository = InMemoryIdentityRepository(),
    ): MessengerEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return MessengerEngine(tor, identities, contacts, scope, h.config).also { extraEngines += it to scope }
    }

    /** Tor cuyo `addClientAuth` falla (para probar que el emparejamiento se deshace entero). */
    private class FailingAuthTor(inner: FakeTor) : TorTransport by inner {
        override suspend fun addClientAuth(onion: String, privateKey: ByteArray) {
            throw IOException("fallo simulado de addClientAuth")
        }
    }

    /** Contactos cuyo `upsert` se detiene hasta `release`: congela al ganador mientras guarda el contacto. */
    private class GatedContacts(
        private val inner: InMemoryContactRepository = InMemoryContactRepository(),
    ) : ContactRepository by inner {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun upsert(contact: Contact) {
            entered.complete(Unit)
            release.await()
            inner.upsert(contact)
        }
    }

    private suspend fun connectError(tor: TorTransport, onion: String): Throwable? =
        runCatching { tor.connect(onion, 7420, 5_000).close() }.exceptionOrNull()

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

    @Test
    fun racingSecretHolderGetsNoValidKey() = runBlocking<Unit> {
        val h = harness(2)
        val aContacts = GatedContacts()
        val aIdentities = InMemoryIdentityRepository()
        val a = customEngine(h, contacts = aContacts, identities = aIdentities)
        h.startAll()
        a.start()
        val handle = a.createInvitation()
        val text = awaitWaiting(handle).text
        // El ganador completa el emparejamiento y A se queda guardando su contacto…
        val winner = async { h.engine(0).acceptInvitation(text) }
        withTimeout(TIMEOUT) { aContacts.entered.await() }
        // …mientras otro que también conoce el secreto completa XXpsk3 y recibe una oferta de A.
        assertInstanceOf(AcceptResult.Paired::class.java, h.engine(1).acceptInvitation(text))
        aContacts.release.complete(Unit)
        assertInstanceOf(AcceptResult.Paired::class.java, winner.await())
        assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))

        val aOnion = a.myOnion.value!!
        val stored = aContacts.all().single()
        assertEquals(contactIdFor(h.identities(0).load()!!.identityPublic), stored.id)
        // Solo la clave del ganador está registrada en la .onion permanente de A.
        val service = h.network.published.value.getValue(aOnion)
        assertEquals(listOf(stored.myClientAuthPublic.hex()), service.clientAuthPublicKeys.map { it.hex() })
        // El perdedor guardó la clave recibida para la .onion de A, pero no le sirve.
        assertEquals(aOnion, h.contacts(1).all().single().peerOnion)
        assertInstanceOf(IOException::class.java, connectError(h.tor(1), aOnion))
        assertNull(connectError(h.tor(0), aOnion))
    }

    @Test
    fun overlappingInvitationsKeepOnionPublished() = runBlocking<Unit> {
        val h = harness(1)
        h.startAll()
        val engine = h.engine(0)
        val first = engine.createInvitation()
        awaitWaiting(first)
        val onion = engine.myOnion.value!!
        // Sin contactos, la .onion se publica con una clave de reserva (nunca sin autorización).
        assertTrue(h.network.published.value.getValue(onion).requiresClientAuth)

        val second = engine.createInvitation()
        assertEquals(InvitationState.Cancelled, awaitEnd(first))
        awaitWaiting(second)
        // Cancelar la primera no retira la .onion de la que sigue en curso.
        assertEquals(onion, engine.myOnion.value)
        assertTrue(h.network.published.value.getValue(onion).requiresClientAuth)

        // Un intento de aceptar (fallido) a la vez tampoco la retira al terminar.
        val other = customEngine(h)
        other.start()
        val foreign = other.createInvitation()
        val foreignText = awaitWaiting(foreign).text
        assertInstanceOf(AcceptResult.Failed::class.java, engine.acceptInvitation(withWrongSecret(foreignText)))
        assertEquals(onion, engine.myOnion.value)
        foreign.cancel()

        second.cancel()
        assertEquals(InvitationState.Cancelled, awaitEnd(second))
        assertNull(engine.myOnion.value)
        assertNull(h.network.published.value[onion])
    }

    @Test
    fun inviteeFailureAfterPairingRollsBack() = runBlocking<Unit> {
        val h = harness(1)
        val bTor = FailingAuthTor(FakeTor(h.network))
        val bContacts = InMemoryContactRepository()
        val b = customEngine(h, tor = bTor, contacts = bContacts)
        h.startAll()
        b.start()
        val handle = h.engine(0).createInvitation()
        val text = awaitWaiting(handle).text
        assertEquals(
            AcceptResult.Failed("No se pudo completar el emparejamiento"),
            b.acceptInvitation(text),
        )
        assertTrue(bContacts.all().isEmpty())
        assertNull(b.myOnion.value)
        // Solo queda la .onion de A (que sí dio por bueno el emparejamiento: caso asimétrico).
        assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))
        assertEquals(setOf(h.engine(0).myOnion.value), h.network.published.value.keys)
    }

    @Test
    fun inviterFailureAfterPairingRollsBack() = runBlocking<Unit> {
        val h = harness(1)
        val aContacts = InMemoryContactRepository()
        val a = customEngine(h, tor = FailingAuthTor(FakeTor(h.network)), contacts = aContacts)
        h.startAll()
        a.start()
        val handle = a.createInvitation()
        val text = awaitWaiting(handle).text
        assertInstanceOf(AcceptResult.Paired::class.java, h.engine(0).acceptInvitation(text))
        assertEquals(InvitationState.Failed("No se pudo completar el emparejamiento"), awaitEnd(handle))
        assertTrue(aContacts.all().isEmpty())
        assertNull(a.myOnion.value)
        assertEquals(setOf(h.engine(0).myOnion.value), h.network.published.value.keys)
    }

    @Test
    fun cannotAcceptOwnInvitation() = runBlocking<Unit> {
        val h = harness(1)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        assertEquals(
            AcceptResult.Failed("No puedes aceptar tu propia invitación"),
            h.engine(0).acceptInvitation(waiting.text),
        )
        assertEquals(waiting, handle.state.value)
        assertTrue(h.contacts(0).all().isEmpty())
    }

    @Test
    fun concurrentAttemptsAreCapped() = runBlocking<Unit> {
        val h = harness(2)
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = awaitWaiting(handle)
        val temp = onionOf(waiting.text)
        // 32 conexiones que no envían nada ocupan todos los cupos; la 33.ª se cierra enseguida.
        val idle = List(32) { h.tor(1).connect(temp, 7420, 5_000) }
        val extra = h.tor(1).connect(temp, 7420, 5_000)
        val read = withContext(Dispatchers.IO) {
            extra.soTimeout = 5_000
            extra.getInputStream().read()
        }
        assertEquals(-1, read)
        extra.close()
        idle.forEach { it.close() }
        assertEquals(waiting, handle.state.value)
        // Al liberarse los cupos (de forma asíncrona), el invitado legítimo empareja.
        val result = withTimeout(TIMEOUT) {
            var r: AcceptResult
            do {
                r = h.engine(1).acceptInvitation(waiting.text)
            } while (r is AcceptResult.Failed)
            r
        }
        assertInstanceOf(AcceptResult.Paired::class.java, result)
        assertInstanceOf(InvitationState.Paired::class.java, awaitEnd(handle))
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
