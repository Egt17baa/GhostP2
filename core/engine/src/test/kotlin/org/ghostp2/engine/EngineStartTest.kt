package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

class EngineStartTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun cerrar() {
        harness?.close()
    }

    private fun harness(n: Int = 1): EngineHarness = EngineHarness(n).also { harness = it }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Contacto de prueba con claves x25519 reales; `myClientAuthPublic` es lo que se registra en nuestra .onion. */
    private fun contact(name: String, status: ContactStatus = ContactStatus.ACTIVE): Contact {
        val peerIdentity = Primitives.generateX25519().publicKey
        return Contact(
            id = contactIdFor(peerIdentity),
            name = name,
            peerIdentity = peerIdentity,
            peerOnion = "x".repeat(56),
            clientAuthPrivateForPeerOnion = Primitives.generateX25519().privateKey,
            myClientAuthPublic = Primitives.generateX25519().publicKey,
            pairSecret = Primitives.randomBytes(32),
            verified = false,
            status = status,
        )
    }

    private fun assertPortClosed(port: Int) {
        assertThrows<IOException> { Socket(InetAddress.getByName("127.0.0.1"), port).close() }
    }

    @Test
    fun firstStartCreatesIdentity() = runBlocking {
        val h = harness()
        assertNull(h.identities(0).load())
        h.engine(0).start()
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        val identity = checkNotNull(h.identities(0).load())
        assertEquals(32, identity.identityPrivate.size)
        assertEquals(32, identity.identityPublic.size)
        assertEquals(Primitives.x25519PublicFromPrivate(identity.identityPrivate).publicKey.hex(), identity.identityPublic.hex())
        // Sin contactos no se reserva .onion.
        assertNull(identity.onionPrivateKey)
        assertNull(identity.onion)
        // Al reiniciar se conserva la misma identidad.
        h.engine(0).stop()
        h.engine(0).start()
        assertEquals(identity, h.identities(0).load())
    }

    @Test
    fun noPermanentOnionWithoutContacts() = runBlocking {
        val h = harness()
        h.engine(0).start()
        assertTrue(h.network.published.value.isEmpty())
        assertNull(h.engine(0).myOnion.value)
        // El listener local sí está abierto y solo en 127.0.0.1.
        val port = checkNotNull(h.engine(0).localPort)
        Socket(InetAddress.getByName("127.0.0.1"), port).close()
    }

    @Test
    fun permanentOnionPublishedWithClientAuthForEachContact() = runBlocking {
        val h = harness()
        val ana = contact("Ana")
        // Un contacto IDENTITY_CHANGED conserva su clave registrada hasta que el usuario lo borre.
        val bea = contact("Bea", ContactStatus.IDENTITY_CHANGED)
        h.contacts(0).upsert(ana)
        h.contacts(0).upsert(bea)
        h.engine(0).start()

        val services = h.network.published.value
        assertEquals(1, services.size)
        val service = services.values.single()
        assertTrue(service.requiresClientAuth)
        assertEquals(
            setOf(ana.myClientAuthPublic.hex(), bea.myClientAuthPublic.hex()),
            service.clientAuthPublicKeys.map { it.hex() }.toSet(),
        )
        assertEquals(2, service.clientAuthPublicKeys.size)
        assertEquals(7420, service.virtualPort)
        assertEquals(h.engine(0).localPort, service.localPort)
        assertEquals(service.onion, h.engine(0).myOnion.value)

        val identity = h.identities(0).load()!!
        assertEquals(service.onion, identity.onion)
        assertEquals(64, identity.onionPrivateKey!!.size)

        // Tras detener y arrancar, la misma .onion gracias a onionPrivateKey guardada.
        h.engine(0).stop()
        h.engine(0).start()
        assertEquals(setOf(service.onion), h.network.published.value.keys)
        assertEquals(service.onion, h.engine(0).myOnion.value)
        assertEquals(identity, h.identities(0).load())
    }

    @Test
    fun stopUnpublishesAndStopsTor() = runBlocking {
        val h = harness()
        h.contacts(0).upsert(contact("Ana"))
        h.engine(0).start()
        val port = h.engine(0).localPort!!
        assertEquals(1, h.network.published.value.size)
        assertEquals(TorState.Ready, h.tor(0).state.value)

        h.engine(0).stop()
        assertTrue(h.network.published.value.isEmpty())
        assertEquals(TorState.Off, h.tor(0).state.value)
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        assertNull(h.engine(0).myOnion.value)
        assertNull(h.engine(0).localPort)
        assertPortClosed(port)
        // No quedan corrutinas del motor vivas en el scope inyectado.
        assertEquals(0, h.scope(0).coroutineContext[Job]!!.children.count())
        // La .onion sigue reservada en la identidad.
        assertNotNull(h.identities(0).load()!!.onion)
    }

    @Test
    fun startAndStopAreIdempotent() = runBlocking {
        val h = harness()
        h.contacts(0).upsert(contact("Ana"))
        h.engine(0).stop() // detener sin haber arrancado: no falla
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        h.engine(0).start()
        val port = h.engine(0).localPort
        val onion = h.engine(0).myOnion.value
        h.engine(0).start() // segunda llamada: no hace nada
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        assertEquals(port, h.engine(0).localPort)
        assertEquals(onion, h.engine(0).myOnion.value)
        assertEquals(1, h.network.published.value.size)
        h.engine(0).stop()
        h.engine(0).stop()
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        assertTrue(h.network.published.value.isEmpty())
    }

    @Test
    fun enginesOnSharedNetworkPublishDistinctOnions() = runBlocking {
        val h = harness(2)
        h.contacts(0).upsert(contact("Ana"))
        h.contacts(1).upsert(contact("Bea"))
        h.startAll()
        val a = h.engine(0).myOnion.value
        val b = h.engine(1).myOnion.value
        assertNotNull(a)
        assertNotNull(b)
        assertTrue(a != b)
        assertEquals(setOf(a, b), h.network.published.value.keys)
        // Detener uno solo retira únicamente su servicio.
        h.engine(0).stop()
        assertEquals(setOf(b), h.network.published.value.keys)
    }

    @Test
    fun statusReportsTorProgressThenOnline() = runBlocking {
        val tor = ControlledTor(FakeTorNetwork())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = MessengerEngine(tor, InMemoryIdentityRepository(), InMemoryContactRepository(), scope)
            val starting = scope.launch { engine.start() }
            tor.awaitStarted()
            tor.mutableState.value = TorState.Starting(55)
            withTimeout(5_000) { engine.status.first { it == EngineStatus.StartingTor(55) } }
            tor.mutableState.value = TorState.Ready
            withTimeout(5_000) { starting.join() }
            assertEquals(EngineStatus.Online, engine.status.value)
            engine.stop()
            assertEquals(EngineStatus.Stopped, engine.status.value)
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun torErrorSetsErrorStatusAndReleasesResources() = runBlocking {
        val tor = ControlledTor(FakeTorNetwork())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val contacts = InMemoryContactRepository(listOf(contact("Ana")))
            val engine = MessengerEngine(tor, InMemoryIdentityRepository(), contacts, scope)
            val starting = scope.launch { engine.start() }
            tor.awaitStarted()
            tor.mutableState.value = TorState.Error("sin red")
            withTimeout(5_000) { starting.join() }
            assertEquals(EngineStatus.Error("sin red"), engine.status.value)
            assertTrue(tor.stopped)
            assertNull(engine.localPort)
            assertNull(engine.myOnion.value)
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
            // Se puede reintentar: ahora Tor arranca bien.
            tor.autoReady = true
            engine.start()
            assertEquals(EngineStatus.Online, engine.status.value)
            assertNotNull(engine.myOnion.value)
            engine.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun torStartExceptionSetsErrorStatus() = runBlocking {
        val tor = ControlledTor(FakeTorNetwork()).apply { failOnStart = IOException("binario ausente") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = MessengerEngine(tor, InMemoryIdentityRepository(), InMemoryContactRepository(), scope)
            engine.start()
            assertEquals(EngineStatus.Error("binario ausente"), engine.status.value)
            assertNull(engine.localPort)
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopInterruptsStalledStart() = runBlocking {
        val tor = ControlledTor(FakeTorNetwork())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = MessengerEngine(tor, InMemoryIdentityRepository(), InMemoryContactRepository(), scope)
            val starting = scope.launch { engine.start() }
            tor.awaitStarted()
            tor.mutableState.value = TorState.Starting(5) // Tor atascado
            withTimeout(5_000) { engine.status.first { it == EngineStatus.StartingTor(5) } }
            withTimeout(2_000) { engine.stop() }
            assertEquals(EngineStatus.Stopped, engine.status.value)
            assertTrue(tor.stopped)
            assertNull(engine.localPort)
            withTimeout(2_000) { starting.join() }
            // start() termina normalmente (no lanza al llamante, que no fue cancelado).
            assertTrue(!starting.isCancelled)
            assertEquals(EngineStatus.Stopped, engine.status.value)
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
            // Se puede volver a arrancar.
            tor.autoReady = true
            engine.start()
            assertEquals(EngineStatus.Online, engine.status.value)
            engine.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun torGoingOffDuringStartIsAnError() = runBlocking {
        val tor = ControlledTor(FakeTorNetwork())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = MessengerEngine(tor, InMemoryIdentityRepository(), InMemoryContactRepository(), scope)
            val starting = scope.launch { engine.start() }
            tor.awaitStarted()
            tor.mutableState.value = TorState.Off
            withTimeout(5_000) { starting.join() }
            assertEquals(EngineStatus.Error("Tor se detuvo durante el arranque"), engine.status.value)
            assertTrue(tor.stopped)
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun torBootstrapTimeoutIsAnError() = runBlocking {
        val tor = ControlledTor(FakeTorNetwork())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val contacts = InMemoryContactRepository(listOf(contact("Ana")))
            val engine = MessengerEngine(
                tor, InMemoryIdentityRepository(), contacts, scope, EngineConfig(torBootstrapTimeoutMillis = 200),
            )
            withTimeout(5_000) { engine.start() }
            assertEquals(EngineStatus.Error("Tor no terminó de arrancar"), engine.status.value)
            assertTrue(tor.stopped)
            assertNull(engine.localPort)
            assertNull(engine.myOnion.value)
            assertEquals(0, scope.coroutineContext[Job]!!.children.count())
        } finally {
            scope.cancel()
        }
    }
}

/**
 * Tor controlado por la prueba: `start()` solo emite `Starting(0)` y vuelve (como un Tor real que arranca
 * en segundo plano); la prueba mueve el estado. Publicación y conexiones se delegan en un [FakeTor].
 */
private class ControlledTor(network: FakeTorNetwork) : TorTransport {
    private val delegate = FakeTor(network)
    val mutableState = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = mutableState

    @Volatile var stopped = false
    @Volatile var autoReady = false
    @Volatile var failOnStart: Exception? = null

    override suspend fun start() {
        failOnStart?.let { throw it }
        stopped = false
        delegate.start()
        mutableState.value = if (autoReady) TorState.Ready else TorState.Starting(0)
    }

    /** Espera a que el motor haya llamado a `start()` (antes de mover el estado desde la prueba). */
    suspend fun awaitStarted() {
        withTimeout(5_000) { mutableState.first { it is TorState.Starting } }
    }

    override suspend fun stop() {
        stopped = true
        delegate.stop()
        mutableState.value = TorState.Off
    }

    override suspend fun publishService(
        privateKey: ByteArray?,
        clientAuthPublicKeys: List<ByteArray>,
        virtualPort: Int,
        localPort: Int,
    ) = delegate.publishService(privateKey, clientAuthPublicKeys, virtualPort, localPort)

    override suspend fun unpublishService(onion: String) = delegate.unpublishService(onion)

    override suspend fun addClientAuth(onion: String, privateKey: ByteArray) = delegate.addClientAuth(onion, privateKey)

    override suspend fun removeClientAuth(onion: String) = delegate.removeClientAuth(onion)

    override suspend fun connect(onion: String, port: Int, timeoutMillis: Long) =
        delegate.connect(onion, port, timeoutMillis)

    override suspend fun wipeState() {
        stopped = true
        delegate.wipeState()
        mutableState.value = TorState.Off
    }
}
