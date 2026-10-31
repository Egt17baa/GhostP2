package org.ghostp2.tor

import io.matthewnelson.kmp.tor.runtime.core.config.TorConfig
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.config.TorSetting
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.X25519
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.engine.TorState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import io.matthewnelson.kmp.tor.runtime.TorState as RuntimeState

/** Partes puras de [KmpTorTransport]: estado, configuración, órdenes de control y SOCKS (sin Tor real). */
class KmpTorSupportTest {
    private val servers = mutableListOf<ServerSocket>()

    @After
    fun cerrar() {
        servers.forEach { runCatching { it.close() } }
    }

    // --- Estado ---

    @Test
    fun stateFollowsBootstrapPercent() {
        val on = RuntimeState.Network.Enabled
        val off = RuntimeState.Network.Disabled
        assertEquals(TorState.Starting(0), mapRuntimeState(RuntimeState.Daemon.Starting, off, true))
        assertEquals(TorState.Starting(0), mapRuntimeState(RuntimeState.Daemon.On(0), off, true))
        assertEquals(TorState.Starting(37), mapRuntimeState(RuntimeState.Daemon.On(37), on, true))
        assertEquals(TorState.Starting(100), mapRuntimeState(RuntimeState.Daemon.On(100), off, true))
        assertEquals(TorState.Ready, mapRuntimeState(RuntimeState.Daemon.On(100), on, true))
    }

    @Test
    fun processDeathIsAnError() {
        val off = RuntimeState.Network.Disabled
        // El Off previo al arranque no cambia nada (seguimos en Starting(0) tras start()).
        assertNull(mapRuntimeState(RuntimeState.Daemon.Off, off, processSeen = false))
        assertNull(mapRuntimeState(RuntimeState.Daemon.Stopping, off, processSeen = false))
        assertEquals(TorState.Error(UNEXPECTED_STOP), mapRuntimeState(RuntimeState.Daemon.Off, off, processSeen = true))
        assertEquals(
            TorState.Error(UNEXPECTED_STOP),
            mapRuntimeState(RuntimeState.Daemon.Stopping, RuntimeState.Network.Enabled, processSeen = true),
        )
    }

    // --- Configuración ---

    private fun TorConfig.items(option: TorOption): List<TorSetting.LineItem> =
        flatMap { it.items }.filter { it.option == option }

    @Test
    fun configUsesAutoLoopbackOnionOnlySocksAndNoDnsOrHttpPorts() {
        val config = TorConfig.Builder { applyGhostConfig() }
        val socks = config.items(TorOption.__SocksPort).single()
        assertEquals("auto", socks.argument) // Tor elige el puerto, en 127.0.0.1 (sin dirección explícita)
        assertTrue(socks.optionals.containsAll(setOf("OnionTrafficOnly", "PreferSOCKSNoAuth")))
        assertTrue(config.items(TorOption.SocksPort).isEmpty())
        assertEquals("0", config.items(TorOption.DNSPort).single().argument)
        assertEquals("0", config.items(TorOption.HTTPTunnelPort).single().argument)
        for (absent in listOf(
            TorOption.ClientOnionAuthDir, TorOption.Log, TorOption.SafeLogging,
            TorOption.__DNSPort, TorOption.__HTTPTunnelPort, TorOption.TransPort, TorOption.NATDPort,
        )) {
            assertTrue("$absent no debe configurarse", config.items(absent).isEmpty())
        }
        // Ningún puerto con dirección: todo lo que abre Tor queda en 127.0.0.1.
        config.flatMap { it.items }.filter { it.option.attributes.contains(TorOption.Attribute.PORT) }.forEach {
            assertFalse("${it.option} con dirección", it.argument.contains(':'))
        }
    }

    // --- Órdenes de control ---

    @Test
    fun newOnionWithClientAuth() {
        val k1 = TorKeys.x25519PublicKey(Primitives.generateX25519().publicKey)
        val k2 = TorKeys.x25519PublicKey(Primitives.generateX25519().publicKey)
        val cmd = onionAddCommand(null, listOf(k1, k2), 7420, 5555)
        assertSame(ED25519_V3, cmd.keyType)
        assertNull(cmd.key)
        assertEquals(setOf(k1, k2), cmd.clientAuth)
        assertTrue("V3Auth" in cmd.flags)
        assertFalse("DiscardPK" in cmd.flags) // hay que recibir la clave nueva
        assertFalse("Detach" in cmd.flags) // el servicio muere con la conexión de control
        assertEquals(listOf("7420 5555"), cmd.ports.map { it.argument })
        assertEquals(TorOption.HiddenServicePort, cmd.ports.single().option)
    }

    @Test
    fun existingOnionDiscardsReturnedKeyAndDestroysOurCopy() {
        val key = ED25519_V3.PrivateKey.generate()
        val cmd = onionAddCommand(key, listOf(TorKeys.x25519PublicKey(Primitives.generateX25519().publicKey)), 7420, 6000)
        assertSame(key, cmd.key)
        assertTrue("DiscardPK" in cmd.flags)
        assertTrue("V3Auth" in cmd.flags)
        assertTrue(cmd.destroyKeyOnJobCompletion)
        assertEquals(listOf("7420 6000"), cmd.ports.map { it.argument })
    }

    @Test
    fun invitationOnionHasNoClientAuth() {
        val cmd = onionAddCommand(null, emptyList(), 7420, 7000)
        assertTrue(cmd.clientAuth.isEmpty())
        assertFalse("V3Auth" in cmd.flags)
    }

    @Test
    fun clientAuthIsInMemoryOnly() {
        val address = TorKeys.onionAddress(DUCKDUCKGO)
        val key: X25519.PrivateKey = TorKeys.x25519PrivateKey(Primitives.generateX25519().privateKey)
        val cmd = clientAuthAddCommand(address, key)
        assertEquals(address, cmd.address)
        assertSame(key, cmd.key)
        assertFalse("Permanent" in cmd.flags)
        assertTrue(cmd.flags.isEmpty())
        assertNull(cmd.clientName)
        assertTrue(cmd.destroyKeyOnJobCompletion)
    }

    // --- SOCKS ---

    @Test
    fun socksTargetIsUnresolvedOnionName() {
        val target = socksTarget("${DUCKDUCKGO.uppercase()}.onion", 7420)
        assertTrue(target.isUnresolved)
        assertEquals("$DUCKDUCKGO.onion", target.hostString)
        assertEquals(7420, target.port)
    }

    @Test
    fun connectSendsHostnameToProxyWithoutLocalDns() = runBlocking<Unit> {
        val seen = CompletableRequest()
        val proxy = fakeSocks5 { input, out ->
            seen.complete(readSocks5Request(input, out))
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)) // éxito
            out.write(42)
            out.flush()
        }
        val socket = connectThroughSocks(proxy, DUCKDUCKGO, 7420, 5_000)
        socket.use {
            assertEquals(42, it.getInputStream().read())
        }
        val (atyp, host, port) = seen.await()
        assertEquals(3, atyp) // DOMAINNAME: el nombre lo resuelve Tor
        assertEquals("$DUCKDUCKGO.onion", host)
        assertEquals(7420, port)
    }

    @Test
    fun socksRefusalIsIOException() = runBlocking<Unit> {
        val proxy = fakeSocks5 { input, out ->
            readSocks5Request(input, out)
            out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)) // host inalcanzable
            out.flush()
        }
        expectIOException { connectThroughSocks(proxy, DUCKDUCKGO, 7420, 5_000) }
    }

    @Test
    fun invalidOnionIsIOExceptionWithoutTouchingTheProxy() = runBlocking<Unit> {
        val contacted = CountDownLatch(1)
        val proxy = fakeSocks5 { _, _ -> contacted.countDown() }
        expectIOException { connectThroughSocks(proxy, "example.com", 80, 5_000) }
        assertFalse(contacted.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun connectTimeoutApplies() = runBlocking<Unit> {
        val proxy = silentProxy()
        val started = System.nanoTime()
        expectIOException { connectThroughSocks(proxy, DUCKDUCKGO, 7420, 300) }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("tardó $elapsed ms", elapsed < 3_000)
    }

    @Test
    fun cancellingClosesTheSocket() = runBlocking<Unit> {
        val accepted = CountDownLatch(1)
        val closedByClient = CountDownLatch(1)
        val proxy = fakeSocks5 { input, _ ->
            accepted.countDown()
            // Sin responder: el cliente queda bloqueado en la negociación SOCKS hasta que cierre.
            drain(input)
            closedByClient.countDown()
        }
        val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            connectThroughSocks(proxy, DUCKDUCKGO, 7420, 60_000)
        }
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        call.cancel()
        withTimeout(3_000) { call.join() }
        assertTrue(call.isCancelled)
        assertTrue("el socket no se cerró", closedByClient.await(3, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    // --- Utilidades: un proxy SOCKS5 mínimo ---

    private class CompletableRequest {
        private val latch = CountDownLatch(1)
        @Volatile private var value: Triple<Int, String, Int>? = null
        fun complete(v: Triple<Int, String, Int>) {
            value = v
            latch.countDown()
        }
        fun await(): Triple<Int, String, Int> {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            return value!!
        }
    }

    /** Proxy en 127.0.0.1 que atiende una conexión con `handler` en un hilo propio. */
    private fun fakeSocks5(handler: (DataInputStream, java.io.OutputStream) -> Unit): InetSocketAddress {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s -> handler(DataInputStream(s.getInputStream()), s.getOutputStream()) }
            }
        }
        return InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
    }

    /** Lee hasta que el otro lado cierre. */
    private fun drain(input: DataInputStream) {
        do {
            val b = input.read()
        } while (b != -1)
    }

    /** Acepta la conexión TCP y nunca contesta. */
    private fun silentProxy(): InetSocketAddress = fakeSocks5 { input, _ -> drain(input) }

    /** Saludo SOCKS5 (elige "sin autenticación") y petición CONNECT; devuelve (ATYP, host, puerto). */
    private fun readSocks5Request(input: DataInputStream, out: java.io.OutputStream): Triple<Int, String, Int> {
        assertEquals(5, input.readUnsignedByte())
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        assertTrue(0.toByte() in methods)
        out.write(byteArrayOf(5, 0))
        out.flush()
        assertEquals(5, input.readUnsignedByte())
        assertEquals(1, input.readUnsignedByte()) // CONNECT
        input.readUnsignedByte()
        val atyp = input.readUnsignedByte()
        val host = when (atyp) {
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
            1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
            else -> ByteArray(16).also { input.readFully(it) }.toString()
        }
        return Triple(atyp, host, input.readUnsignedShort())
    }

    private suspend fun expectIOException(block: suspend () -> Socket) {
        try {
            block().close()
            fail("se esperaba IOException")
        } catch (e: IOException) {
            assertFalse("el mensaje no debe incluir la dirección", e.message.orEmpty().contains(DUCKDUCKGO))
        }
    }

    private companion object {
        const val DUCKDUCKGO = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
    }
}
