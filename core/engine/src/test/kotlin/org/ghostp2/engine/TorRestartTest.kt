package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lo que el motor debe hacer porque un Tor real no conserva nada entre reinicios: volver a registrar las
 * autorizaciones de cliente, no confiar en que el array de clave devuelto sea distinto del suyo, y
 * borrar el estado de Tor con "Borrar todo".
 */
class TorRestartTest {
    private val config = EngineConfig(backoffMinMillis = 50, backoffMaxMillis = 400)
    private var harness: EngineHarness? = null

    @AfterEach
    fun cerrar() {
        harness?.close()
    }

    private suspend fun pairedHarness(): Triple<EngineHarness, String, String> {
        val h = EngineHarness(2, config, forgetClientAuthOnStop = true).also { harness = it }
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(h.engine(1).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        return Triple(h, h.contacts(0).all().single().id, h.contacts(1).all().single().id)
    }

    private suspend fun awaitOnline(engine: MessengerEngine, id: String) {
        withTimeout(TIMEOUT) { engine.presence.first { it[id] == Presence.ONLINE } }
    }

    @Test
    fun clientAuthReRegisteredOnEveryStart() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitOnline(h.engine(0), bInA)
        awaitOnline(h.engine(1), aInB)
        val bOnion = h.contacts(0).all().single().peerOnion
        val aOnion = h.contacts(1).all().single().peerOnion

        // Los dos se detienen: su Tor olvida las autorizaciones. Al volver, nadie podría conectar sin
        // que el motor las registre de nuevo.
        h.engine(0).stop()
        h.engine(1).stop()
        assertTrue(h.tor(0).clientAuthOnions().isEmpty())
        assertTrue(h.tor(1).clientAuthOnions().isEmpty())

        h.startAll()
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        assertEquals(setOf(bOnion), h.tor(0).clientAuthOnions())
        assertEquals(setOf(aOnion), h.tor(1).clientAuthOnions())
        awaitOnline(h.engine(0), bInA)
        awaitOnline(h.engine(1), aInB)
    }

    @Test
    fun identityChangedContactIsNotReRegistered() = runBlocking<Unit> {
        val h = EngineHarness(1, config, forgetClientAuthOnStop = true).also { harness = it }
        val active = contact(ContactStatus.ACTIVE)
        val changed = contact(ContactStatus.IDENTITY_CHANGED)
        h.contacts(0).upsert(active)
        h.contacts(0).upsert(changed)
        h.startAll()
        assertEquals(setOf(active.peerOnion), h.tor(0).clientAuthOnions())
    }

    @Test
    fun failedClientAuthRegistrationIsBoundedAndNotified() = runBlocking<Unit> {
        val failing = contact(ContactStatus.ACTIVE)
        val hanging = contact(ContactStatus.ACTIVE)
        val good = contact(ContactStatus.ACTIVE)
        val fake = FakeTor(FakeTorNetwork())
        val tor = object : TorTransport by fake {
            override suspend fun addClientAuth(onion: String, privateKey: ByteArray) {
                when (onion) {
                    failing.peerOnion -> throw java.io.IOException("rechazada")
                    hanging.peerOnion -> kotlinx.coroutines.awaitCancellation()
                    else -> fake.addClientAuth(onion, privateKey)
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MessengerEngine(
            tor, InMemoryIdentityRepository(), InMemoryContactRepository(listOf(failing, hanging, good)), scope,
            config.copy(clientAuthRegistrationTimeoutMillis = 300),
        )
        try {
            val notice = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                engine.notices.filterIsInstance<Notice.ClientAuthFailed>().first()
            }
            withTimeout(5_000) { engine.start() } // el registro está acotado: no se queda esperando al que cuelga
            assertEquals(EngineStatus.Online, engine.status.value)
            assertEquals(setOf(good.peerOnion), fake.clientAuthOnions())
            val ids = withTimeout(TIMEOUT) { notice.await() }.contactIds
            assertEquals(listOf(failing.id, hanging.id).sorted(), ids)
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun keyKeptWhenTorEchoesTheInputArray() = runBlocking<Unit> {
        val network = FakeTorNetwork()
        val tor = EchoKeyTor(FakeTor(network))
        val identities = InMemoryIdentityRepository()
        val contacts = InMemoryContactRepository(listOf(contact(ContactStatus.ACTIVE)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MessengerEngine(tor, identities, contacts, scope, config)
        try {
            engine.start()
            val first = checkNotNull(engine.myOnion.value)
            engine.stop()
            // Segundo arranque: se republica con la clave guardada y el Tor devuelve ese mismo array.
            engine.start()
            assertEquals(first, engine.myOnion.value)
            val key = checkNotNull(identities.load()?.onionPrivateKey)
            assertTrue(key.any { it != 0.toByte() }) { "la clave de la identidad se rellenó con ceros" }
            engine.stop()
            engine.start()
            assertEquals(first, engine.myOnion.value)
            assertTrue(tor.echoed > 0)
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun wipeAllPurgesTorState() = runBlocking<Unit> {
        val h = EngineHarness(2, config).also { harness = it }
        h.engine(0).start()
        h.engine(0).wipeAll()
        assertEquals(1, h.tor(0).wipeStateCalls.get())
        assertEquals(TorState.Off, h.tor(0).state.value)
        // También con el motor parado.
        h.engine(1).wipeAll()
        assertEquals(1, h.tor(1).wipeStateCalls.get())
    }

    /** Devuelve como `privateKey` el mismo array recibido (lo que el contrato prohíbe): el motor no debe borrarlo. */
    private class EchoKeyTor(private val delegate: FakeTor) : TorTransport by delegate {
        @Volatile var echoed = 0

        override suspend fun publishService(
            privateKey: ByteArray?,
            clientAuthPublicKeys: List<ByteArray>,
            virtualPort: Int,
            localPort: Int,
        ): PublishedService {
            val published = delegate.publishService(privateKey, clientAuthPublicKeys, virtualPort, localPort)
            if (privateKey == null) return published
            echoed++
            return PublishedService(published.onion, privateKey)
        }
    }

    private fun contact(status: ContactStatus): Contact {
        val peerIdentity = Primitives.generateX25519().publicKey
        val onion = FakeTor.onionFromPublicKey(Primitives.randomBytes(32))
        return Contact(
            id = contactIdFor(peerIdentity),
            name = "c",
            peerIdentity = peerIdentity,
            peerOnion = onion,
            clientAuthPrivateForPeerOnion = Primitives.generateX25519().privateKey,
            myClientAuthPublic = Primitives.generateX25519().publicKey,
            pairSecret = Primitives.randomBytes(32),
            verified = false,
            status = status,
        )
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
