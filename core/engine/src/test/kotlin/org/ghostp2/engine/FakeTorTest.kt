package org.ghostp2.engine

import kotlinx.coroutines.runBlocking
import org.ghostp2.core.crypto.OnionAddress
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class FakeTorTest {
    private val servers = mutableListOf<ServerSocket>()
    private val port = 7420

    @AfterEach
    fun cerrar() {
        servers.forEach { it.close() }
    }

    /** Servidor local que responde con un byte (eco) a cada conexión. */
    private fun echoServer(): ServerSocket {
        val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) {
                    s.use {
                        try {
                            while (true) {
                                val b = it.getInputStream().read()
                                if (b < 0) break
                                it.getOutputStream().write(b)
                                it.getOutputStream().flush()
                            }
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        }
        return server
    }

    private fun Socket.ping(): Int {
        soTimeout = 5_000
        getOutputStream().write(42)
        getOutputStream().flush()
        return getInputStream().read()
    }

    private fun pair(): Triple<FakeTorNetwork, FakeTor, FakeTor> = runBlocking {
        val net = FakeTorNetwork()
        val a = FakeTor(net).also { it.start() }
        val b = FakeTor(net).also { it.start() }
        Triple(net, a, b)
    }

    @Test
    fun publishedServiceIsReachable() = runBlocking {
        val (net, a, b) = pair()
        assertEquals(TorState.Ready, a.state.value)
        val server = echoServer()
        val svc = a.publishService(null, emptyList(), port, server.localPort)
        assertTrue(net.published.value.containsKey(svc.onion))
        b.connect(svc.onion, port, 5_000).use { assertEquals(42, it.ping()) }
        // También con sufijo ".onion" y mayúsculas.
        b.connect(svc.onion.uppercase() + ".onion", port, 5_000).use { assertEquals(42, it.ping()) }
        // Puerto virtual distinto ⇒ IOException.
        assertThrows<IOException> { b.connect(svc.onion, port + 1, 5_000) }
        Unit
    }

    @Test
    fun clientAuthRequiredWhenConfigured() = runBlocking {
        val (_, a, b) = pair()
        val server = echoServer()
        val auth = Primitives.generateX25519()
        val svc = a.publishService(null, listOf(auth.publicKey), port, server.localPort)
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        // Clave privada incorrecta ⇒ sigue sin conectar.
        b.addClientAuth(svc.onion, Primitives.generateX25519().privateKey)
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        b.addClientAuth(svc.onion, auth.privateKey)
        b.connect(svc.onion, port, 5_000).use { assertEquals(42, it.ping()) }
        b.removeClientAuth(svc.onion)
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        Unit
    }

    @Test
    fun unpublishedServiceIsUnreachable() = runBlocking {
        val (net, a, b) = pair()
        val server = echoServer()
        val svc = a.publishService(null, emptyList(), port, server.localPort)
        val open = b.connect(svc.onion, port, 5_000)
        a.unpublishService(svc.onion)
        assertFalse(net.published.value.containsKey(svc.onion))
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        // Como en Tor, el socket ya abierto sigue funcionando.
        open.use { assertEquals(42, it.ping()) }
        // Onion desconocida ⇒ IOException.
        val otra = a.publishService(null, emptyList(), port, server.localPort).onion
        a.unpublishService(otra)
        assertThrows<IOException> { b.connect(otra, port, 5_000) }
        Unit
    }

    @Test
    fun offlineNetworkRefusesConnections() = runBlocking {
        val (net, a, b) = pair()
        val server = echoServer()
        val svc = a.publishService(null, emptyList(), port, server.localPort)
        val open = b.connect(svc.onion, port, 5_000)
        assertEquals(42, open.ping())
        net.setOnline(false)
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        // El corte de red cierra las conexiones existentes.
        assertTrue(open.isClosed)
        net.setOnline(true)
        b.connect(svc.onion, port, 5_000).use { assertEquals(42, it.ping()) }
        // failNextConnects(n): fallan exactamente n intentos.
        net.failNextConnects(2)
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        b.connect(svc.onion, port, 5_000).use { assertEquals(42, it.ping()) }
        Unit
    }

    @Test
    fun generatedOnionsAreValid() = runBlocking {
        val (_, a, _) = pair()
        val server = echoServer()
        val s1 = a.publishService(null, emptyList(), port, server.localPort)
        val s2 = a.publishService(null, emptyList(), port, server.localPort)
        assertTrue(OnionAddress.isValid(s1.onion))
        assertTrue(OnionAddress.isValid(s2.onion))
        assertEquals(56, s1.onion.length)
        assertEquals(s1.onion.lowercase(), s1.onion)
        assertNotEquals(s1.onion, s2.onion)
        assertEquals(64, s1.privateKey.size)
        // Misma clave privada ⇒ misma dirección.
        a.unpublishService(s1.onion)
        val again = a.publishService(s1.privateKey, emptyList(), port, server.localPort)
        assertEquals(s1.onion, again.onion)
        assertArrayEquals(s1.privateKey, again.privateKey)
        assertFalse(again.toString().contains(s1.privateKey.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun stoppedTransportCannotConnect() = runBlocking {
        val (_, a, b) = pair()
        val server = echoServer()
        val svc = a.publishService(null, emptyList(), port, server.localPort)
        b.stop()
        assertEquals(TorState.Off, b.state.value)
        assertThrows<IOException> { b.connect(svc.onion, port, 5_000) }
        Unit
    }

    @Test
    fun publishingAnAlreadyPublishedOnionFails() = runBlocking {
        val (net, a, b) = pair()
        val server = echoServer()
        val svc = a.publishService(null, emptyList(), port, server.localPort)
        // Ni el mismo dueño ni otro FakeTor pueden publicar dos veces la misma dirección.
        val e1 = assertThrows<IOException> { a.publishService(svc.privateKey, emptyList(), port, server.localPort) }
        assertEquals("ya publicado", e1.message)
        assertThrows<IOException> { b.publishService(svc.privateKey, emptyList(), port, server.localPort) }
        assertEquals(1, net.published.value.size)
        // Una vez retirada, se puede volver a publicar.
        a.unpublishService(svc.onion)
        assertEquals(svc.onion, b.publishService(svc.privateKey, emptyList(), port, server.localPort).onion)
        Unit
    }

    @Test
    fun unpublishOnlyRemovesOwnServices() = runBlocking {
        val (net, a, b) = pair()
        val server = echoServer()
        val svc = a.publishService(null, emptyList(), port, server.localPort)
        b.unpublishService(svc.onion)
        assertTrue(net.published.value.containsKey(svc.onion))
        b.connect(svc.onion, port, 5_000).use { assertEquals(42, it.ping()) }
        a.unpublishService(svc.onion)
        assertFalse(net.published.value.containsKey(svc.onion))
        Unit
    }

    @Test
    fun stopRemovesOnlyThatInstancesServices() = runBlocking {
        val (net, a, b) = pair()
        val server = echoServer()
        val a1 = a.publishService(null, emptyList(), port, server.localPort)
        val a2 = a.publishService(null, listOf(Primitives.generateX25519().publicKey), port, server.localPort)
        val b1 = b.publishService(null, emptyList(), port, server.localPort)
        assertEquals(setOf(a1.onion, a2.onion, b1.onion), net.published.value.keys)
        a.stop()
        assertEquals(setOf(b1.onion), net.published.value.keys)
        Unit
    }

    @Test
    fun modelsUseContentEqualityAndHideSecrets() {
        val id = ByteArray(32) { it.toByte() }
        val c1 = Contact(
            id = contactIdFor(id), name = "Ana", peerIdentity = id, peerOnion = "x",
            clientAuthPrivateForPeerOnion = ByteArray(32) { 7 }, myClientAuthPublic = ByteArray(32) { 8 },
            pairSecret = ByteArray(32) { 9 }, verified = false, status = ContactStatus.ACTIVE,
        )
        val c2 = c1.copy(peerIdentity = id.copyOf())
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
        assertNotEquals(c1, c1.copy(verified = true))
        assertEquals(32, c1.id.length)
        assertEquals(Primitives.sha256(id).copyOfRange(0, 16).joinToString("") { "%02x".format(it) }, c1.id)
        assertFalse(c1.toString().contains("0707"))
        assertFalse(c1.toString().contains("0909"))

        val i1 = Identity(ByteArray(32) { 5 }, ByteArray(32) { 6 }, null, null)
        val i2 = Identity(ByteArray(32) { 5 }, ByteArray(32) { 6 }, null, null)
        assertEquals(i1, i2)
        assertEquals(i1.hashCode(), i2.hashCode())
        assertFalse(i1.toString().contains("0505"))
    }

    @Test
    fun inMemoryRepositoriesWork() = runBlocking {
        val ids = InMemoryIdentityRepository()
        val identity = Identity(ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(64) { 3 }, "abc")
        ids.save(identity)
        assertEquals(identity, ids.load())
        ids.wipe()
        assertEquals(null, ids.load())

        val repo = InMemoryContactRepository()
        val c = Contact("a", "Ana", ByteArray(1), "o", ByteArray(1), ByteArray(1), ByteArray(1), false, ContactStatus.ACTIVE)
        repo.upsert(c)
        repo.upsert(c.copy(name = "Ana B"))
        assertEquals(listOf(c.copy(name = "Ana B")), repo.all())
        assertEquals(repo.all(), repo.contacts.value)
        repo.delete("a")
        assertTrue(repo.all().isEmpty())
        repo.upsert(c)
        repo.wipe()
        assertTrue(repo.all().isEmpty())
    }
}
