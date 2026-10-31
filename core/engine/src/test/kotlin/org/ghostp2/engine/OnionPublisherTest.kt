package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

class OnionPublisherTest {
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun contact(): Contact {
        val peer = Primitives.generateX25519().publicKey
        return Contact(
            contactIdFor(peer), "C", peer, "x".repeat(56), Primitives.generateX25519().privateKey,
            Primitives.generateX25519().publicKey, Primitives.randomBytes(32), false, ContactStatus.ACTIVE,
        )
    }

    private fun identity() = Primitives.generateX25519().let { Identity(it.privateKey, it.publicKey, null, null) }

    private fun setup(): Triple<FakeTorNetwork, OnionPublisher, InMemoryIdentityRepository> = runBlocking {
        val net = FakeTorNetwork()
        val tor = FakeTor(net).also { it.start() }
        val repo = InMemoryIdentityRepository()
        Triple(net, OnionPublisher(tor, repo, virtualPort = 7420, localPort = 5555), repo)
    }

    @Test
    fun resyncWithChangedKeysKeepsSameOnion() = runBlocking {
        val (net, publisher, repo) = setup()
        val c1 = contact()
        val c2 = contact()
        val first = publisher.sync(identity(), listOf(c1))
        val onion = first.onion!!
        assertEquals(first, repo.load())
        assertEquals(listOf(c1.myClientAuthPublic.hex()), net.published.value[onion]!!.clientAuthPublicKeys.map { it.hex() })

        val second = publisher.sync(first, listOf(c1, c2))
        assertEquals(onion, second.onion)
        assertArrayEquals(first.onionPrivateKey, second.onionPrivateKey)
        assertEquals(setOf(onion), net.published.value.keys)
        assertEquals(
            setOf(c1.myClientAuthPublic.hex(), c2.myClientAuthPublic.hex()),
            net.published.value[onion]!!.clientAuthPublicKeys.map { it.hex() }.toSet(),
        )
        assertEquals(onion, publisher.publishedOnion)
    }

    @Test
    fun unchangedKeysDoNotRepublish() = runBlocking {
        val (net, publisher, _) = setup()
        val c1 = contact()
        val first = publisher.sync(identity(), listOf(c1))
        val service = net.published.value.values.single()
        val again = publisher.sync(first, listOf(c1))
        assertEquals(first, again)
        assertSame(service, net.published.value.values.single())
    }

    @Test
    fun emptyContactsUnpublishButKeepReservedOnion() = runBlocking {
        val (net, publisher, repo) = setup()
        val first = publisher.sync(identity(), listOf(contact()))
        val after = publisher.sync(first, emptyList())
        assertTrue(net.published.value.isEmpty())
        assertNull(publisher.publishedOnion)
        assertEquals(first.onion, after.onion)
        assertArrayEquals(first.onionPrivateKey, after.onionPrivateKey)
        assertEquals(first, repo.load())
        // Volver a tener contactos ⇒ la misma dirección.
        assertEquals(first.onion, publisher.sync(after, listOf(contact())).onion)
    }

    @Test
    fun neverPublishesWithoutClientAuth() = runBlocking {
        val (net, publisher, repo) = setup()
        val id = identity()
        assertEquals(id, publisher.sync(id, emptyList()))
        assertTrue(net.published.value.isEmpty())
        assertNull(repo.load()) // nada que guardar
    }

    @Test
    fun extraKeysArePublishedToo() = runBlocking {
        val (net, publisher, _) = setup()
        val pending = Primitives.generateX25519().publicKey
        val id = publisher.sync(identity(), emptyList(), extraClientAuthKeys = listOf(pending))
        assertEquals(listOf(pending.hex()), net.published.value[id.onion]!!.clientAuthPublicKeys.map { it.hex() })
    }

    @Test
    fun concurrentSyncsAreSerialized() = runBlocking {
        val (net, publisher, repo) = setup()
        val base = identity()
        val pool = List(6) { contact() }
        // Muchas sincronizaciones simultáneas con la identidad SIN .onion: no debe haber publicaciones
        // dobles ("ya publicado") ni direcciones distintas.
        val results = (1..40).map { i ->
            async(Dispatchers.Default) { publisher.sync(base, pool.take(1 + i % pool.size)) }
        }.awaitAll()
        assertEquals(1, results.map { it.onion }.toSet().size)
        assertEquals(1, net.published.value.size)
        assertEquals(results.first().onion, repo.load()!!.onion)
    }

    /** Identidades en memoria cuyo `save` puede fallar a voluntad. */
    private class FlakySaveIdentities : IdentityRepository {
        private val inner = InMemoryIdentityRepository()
        @Volatile var failSaves = false

        override suspend fun load(): Identity? = inner.load()

        override suspend fun save(identity: Identity) {
            if (failSaves) throw IOException("almacenamiento no disponible (simulado)")
            inner.save(identity)
        }

        override suspend fun wipe() = inner.wipe()
    }

    @Test
    fun staleIdentityCompletedWithTheKnownKeyIsPersisted() = runBlocking {
        val net = FakeTorNetwork()
        val tor = FakeTor(net).also { it.start() }
        val repo = FlakySaveIdentities()
        val publisher = OnionPublisher(tor, repo, virtualPort = 7420, localPort = 5555)
        val c1 = contact()
        val base = identity()
        val first = publisher.sync(base, listOf(c1))
        // Se pierde la identidad guardada con la .onion (p. ej. una escritura anterior falló).
        repo.wipe()
        repo.save(base)

        // Mismas claves con la identidad antigua (sin .onion): se completa con la clave conocida...
        repo.failSaves = true
        assertThrows<IOException> { publisher.sync(base, listOf(c1)) } // sin poder guardarla, falla
        assertNull(repo.load()!!.onion)
        assertEquals(first.onion, publisher.publishedOnion) // la publicación sigue como estaba

        // ...y en cuanto se puede guardar, queda persistida (dirección estable tras reiniciar).
        repo.failSaves = false
        val completed = publisher.sync(base, listOf(c1))
        assertEquals(first.onion, completed.onion)
        assertEquals(completed, repo.load())
        assertArrayEquals(first.onionPrivateKey, repo.load()!!.onionPrivateKey)
        assertEquals(setOf(first.onion), net.published.value.keys)
    }

    @Test
    fun unpublishRetiresService() = runBlocking {
        val (net, publisher, _) = setup()
        publisher.sync(identity(), listOf(contact()))
        publisher.unpublish()
        assertTrue(net.published.value.isEmpty())
        assertNull(publisher.publishedOnion)
        publisher.unpublish() // idempotente
    }

    /** Tor cuyo `unpublishService` puede fallar a voluntad. */
    private class FlakyUnpublishTor(private val delegate: FakeTor) : TorTransport by delegate {
        @Volatile var failUnpublish = false

        override suspend fun unpublishService(onion: String) {
            if (failUnpublish) throw IOException("fallo al retirar")
            delegate.unpublishService(onion)
        }
    }

    @Test
    fun failedUnpublishKeepsPublishedState() = runBlocking {
        val net = FakeTorNetwork()
        val tor = FlakyUnpublishTor(FakeTor(net).also { it.start() })
        val publisher = OnionPublisher(tor, InMemoryIdentityRepository(), 7420, 5555)
        val c1 = contact()
        val id = publisher.sync(identity(), listOf(c1))
        tor.failUnpublish = true
        assertThrows<IOException> { publisher.unpublish() }
        assertEquals(id.onion, publisher.publishedOnion)
        assertThrows<IOException> { publisher.sync(id, emptyList()) }
        assertEquals(id.onion, publisher.publishedOnion)
        // Cambio de claves con retirada fallida: tampoco se da por retirada ni se republica.
        assertThrows<IOException> { publisher.sync(id, listOf(c1, contact())) }
        assertEquals(id.onion, publisher.publishedOnion)
        assertEquals(listOf(c1.myClientAuthPublic.hex()), net.published.value[id.onion]!!.clientAuthPublicKeys.map { it.hex() })
        // Cuando Tor se recupera, la retirada funciona.
        tor.failUnpublish = false
        publisher.unpublish()
        assertNull(publisher.publishedOnion)
        assertTrue(net.published.value.isEmpty())
    }

    @Test
    fun listenerDeliversSocketsAndClosesCleanly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val accepted = Channel<Socket>(Channel.UNLIMITED)
            val listener = Listener(scope) { accepted.trySend(it) }
            assertTrue(listener.port > 0)
            Socket(InetAddress.getByName("127.0.0.1"), listener.port).use { client ->
                val server = withTimeout(5_000) { accepted.receive() }
                server.use {
                    assertEquals(InetAddress.getByName("127.0.0.1"), it.localAddress)
                    client.getOutputStream().write(7)
                    client.getOutputStream().flush()
                    assertEquals(7, it.getInputStream().read())
                }
            }
            withTimeout(5_000) { listener.closeAndJoin() }
            assertTrue(listener.job.isCompleted)
            assertThrows<IOException> { Socket(InetAddress.getByName("127.0.0.1"), listener.port).close() }
            listener.close() // idempotente
            listener.closeAndJoin()
        } finally {
            scope.cancel()
        }
    }
}
