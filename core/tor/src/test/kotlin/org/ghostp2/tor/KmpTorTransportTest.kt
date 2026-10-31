package org.ghostp2.tor

import android.content.Context
import io.matthewnelson.kmp.tor.runtime.core.ctrl.HiddenServiceEntry
import io.matthewnelson.kmp.tor.runtime.core.ctrl.Reply
import io.matthewnelson.kmp.tor.runtime.core.ctrl.Reply.Error.Companion.toError
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.engine.TorState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import io.matthewnelson.kmp.tor.runtime.TorState as RuntimeState

/**
 * Lógica de [KmpTorTransport] sobre un [TorDriver] falso (kmp-tor no arranca en la JVM: el arranque real
 * con Tor solo se prueba en un dispositivo, Task 11). La prueba mueve el estado del runtime a mano.
 */
@RunWith(RobolectricTestRunner::class)
class KmpTorTransportTest {
    private val dir: File = Files.createTempDirectory("tor-test").toFile()
    private val driver = FakeDriver()
    private val tor = KmpTorTransport(File(dir, "tor")) { onState -> driver.also { it.onState = onState } }

    @After
    fun cerrar() {
        runBlocking { tor.stop() }
        dir.deleteRecursively()
    }

    private val off = RuntimeState(RuntimeState.Daemon.Off, RuntimeState.Network.Disabled)
    private val ready = RuntimeState(RuntimeState.Daemon.On(100), RuntimeState.Network.Enabled)
    private fun bootstrapping(p: Int) = RuntimeState(RuntimeState.Daemon.On(p.toByte()), RuntimeState.Network.Enabled)

    private suspend fun startReady() {
        tor.start()
        driver.emit(RuntimeState(RuntimeState.Daemon.Starting, RuntimeState.Network.Disabled))
        driver.emit(ready)
        assertEquals(TorState.Ready, tor.state.value)
    }

    @Test
    fun usesNoBackupFilesDir() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertEquals(File(context.noBackupFilesDir, "tor"), KmpTorTransport(context).torDir)
    }

    @Test
    fun startNeverReturnsOffAndFollowsBootstrap() = runBlocking<Unit> {
        tor.start()
        assertEquals(TorState.Starting(0), tor.state.value) // R13
        driver.emit(off) // el Off previo al proceso no cuenta
        assertEquals(TorState.Starting(0), tor.state.value)
        driver.emit(bootstrapping(45))
        assertEquals(TorState.Starting(45), tor.state.value)
        driver.emit(ready)
        assertEquals(TorState.Ready, tor.state.value)
        tor.start() // idempotente
        withTimeout(5_000) { driver.startEntered.await() }
        delay(50)
        assertEquals(1, driver.starts)
    }

    @Test
    fun processDeathIsErrorAndRestartWorks() = runBlocking<Unit> {
        startReady()
        driver.emit(off) // el proceso murió sin stop()
        assertTrue(tor.state.value is TorState.Error)
        driver.emit(ready) // no "revive" solo
        assertTrue(tor.state.value is TorState.Error)
        tor.start()
        assertEquals(TorState.Starting(0), tor.state.value)
        assertEquals(1, driver.stops) // limpió el intento anterior
        driver.emit(bootstrapping(10))
        driver.emit(ready)
        assertEquals(TorState.Ready, tor.state.value)
        tor.stop()
        assertEquals(TorState.Off, tor.state.value)
        driver.emit(bootstrapping(50)) // eventos tardíos tras stop() no cambian nada
        assertEquals(TorState.Off, tor.state.value)
        tor.start()
        assertNotEquals(TorState.Off, tor.state.value)
    }

    @Test
    fun startFailureIsError() = runBlocking<Unit> {
        driver.startFailure = IllegalStateException("sin binario")
        tor.start()
        assertNotEquals(TorState.Off, tor.state.value)
        val settled = withTimeout(5_000) { tor.state.first { it !is TorState.Starting } }
        assertEquals(TorState.Error("No se pudo arrancar Tor"), settled)
    }

    @Test
    fun stopInterruptsAHangingStartWithoutLeaks() = runBlocking<Unit> {
        driver.hangOnStart = true
        tor.start()
        withTimeout(5_000) { driver.startEntered.await() }
        withTimeout(5_000) { tor.stop() }
        assertEquals(TorState.Off, tor.state.value)
        assertTrue("el arranque no se canceló", driver.startCancelled)
    }

    @Test
    fun publishWaitsForReadyAndReturnsFreshKey() = runBlocking<Unit> {
        tor.start()
        driver.emit(bootstrapping(30))
        val auth = Primitives.generateX25519().publicKey
        val pending = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            tor.publishService(null, listOf(auth), 7420, 5000)
        }
        delay(100)
        assertTrue("publicó durante el arranque", driver.commands.isEmpty())
        driver.emit(ready)
        val first = withTimeout(5_000) { pending.await() }
        assertEquals(64, first.privateKey.size)
        assertEquals(56, first.onion.length)

        // Republicar con la clave: devuelve un array NUEVO con el mismo contenido y la misma .onion.
        val input = first.privateKey.copyOf()
        val again = tor.publishService(input, listOf(auth), 7420, 5000)
        assertNotSame(input, again.privateKey)
        assertArrayEquals(input, again.privateKey)
        assertEquals(first.onion, again.onion)
        input.fill(0)
        assertTrue(again.privateKey.any { it != 0.toByte() })
        val add = driver.commands.filterIsInstance<TorCmd.Onion.Add>().last()
        assertTrue("la clave de kmp-tor no se destruyó", add.key!!.isDestroyed())
    }

    @Test
    fun publishFailsWhenStartingEndsInError() = runBlocking<Unit> {
        tor.start()
        driver.emit(bootstrapping(30))
        val pending = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching { tor.publishService(null, emptyList(), 7420, 5000) }
        }
        driver.emit(off)
        assertTrue(withTimeout(5_000) { pending.await() }.exceptionOrNull() is IOException)
        assertTrue(driver.commands.isEmpty())
    }

    @Test
    fun nothingRunsWhenOff() = runBlocking<Unit> {
        val auth = Primitives.generateX25519()
        expectIOException { tor.publishService(null, listOf(auth.publicKey), 7420, 5000) }
        expectIOException { tor.addClientAuth(DUCKDUCKGO, auth.privateKey) }
        expectIOException { tor.connect(DUCKDUCKGO, 7420, 1_000) }
        // Retirar con Tor parado no hace nada: un Tor parado no tiene servicios ni autorizaciones.
        tor.unpublishService(DUCKDUCKGO)
        tor.removeClientAuth(DUCKDUCKGO)
        assertTrue(driver.commands.isEmpty())
    }

    @Test
    fun connectFailsFastWhileStarting() = runBlocking<Unit> {
        tor.start()
        driver.emit(bootstrapping(50))
        expectIOException { tor.connect(DUCKDUCKGO, 7420, 60_000) }
    }

    @Test
    fun connectRefusesNonLoopbackSocks() = runBlocking<Unit> {
        startReady()
        driver.socks = InetSocketAddress("10.0.0.1", 9050)
        expectIOException { tor.connect(DUCKDUCKGO, 7420, 1_000) }
        driver.socks = null
        expectIOException { tor.connect(DUCKDUCKGO, 7420, 1_000) }
    }

    @Test
    fun clientAuthIsSentAndKeyDestroyed() = runBlocking<Unit> {
        startReady()
        val priv = Primitives.generateX25519().privateKey
        tor.addClientAuth("$DUCKDUCKGO.onion", priv)
        val add = driver.commands.single() as TorCmd.OnionClientAuth.Add
        assertEquals(DUCKDUCKGO, add.address.value)
        assertFalse("Permanent" in add.flags)
        assertTrue(add.key.isDestroyed())
        assertTrue("la clave del motor no se toca", priv.any { it != 0.toByte() })
        tor.removeClientAuth(DUCKDUCKGO)
        assertTrue(driver.commands.last() is TorCmd.OnionClientAuth.Remove)
        expectIOException { tor.addClientAuth("no-es-una-onion", priv) }
    }

    @Test
    fun torRejectionIsIOExceptionWithoutDetails() = runBlocking<Unit> {
        startReady()
        driver.commandFailure = IllegalStateException("552 Unknown Onion Service id: $DUCKDUCKGO")
        expectIOException { tor.unpublishService(DUCKDUCKGO) }
    }

    @Test
    fun malformedKeysAreIOException() = runBlocking<Unit> {
        startReady()
        val auth = Primitives.generateX25519().publicKey
        expectIOException { tor.publishService(ByteArray(10) { 1 }, listOf(auth), 7420, 5000) }
        expectIOException { tor.publishService(null, listOf(ByteArray(31) { 1 }), 7420, 5000) }
        expectIOException { tor.addClientAuth(DUCKDUCKGO, ByteArray(5)) }
        assertTrue(driver.commands.isEmpty())
    }

    @Test
    fun ambiguousAddFailureRetiresTheAddress() = runBlocking<Unit> {
        startReady()
        val key = ED25519_V3.PrivateKey.generate()
        val expected = key.generatePublicKey().address()
        driver.addFailure = IllegalStateException("conexión de control caída")
        expectIOException { tor.publishService(key.encoded(), emptyList(), 7420, 5000) }
        val delete = driver.commands.last() as TorCmd.Onion.Delete
        assertEquals(expected, delete.address)

        // Dirección nueva: también sabemos cuál retirar (la clave se genera aquí).
        expectIOException { tor.publishService(null, emptyList(), 7420, 5000) }
        assertTrue(driver.commands.last() is TorCmd.Onion.Delete)
    }

    @Test
    fun definitiveRejectionDoesNotRetire() = runBlocking<Unit> {
        startReady()
        driver.addFailure = listOf(Reply.of("550", "Onion address collision")).toError("ADD_ONION")
        expectIOException { tor.publishService(null, emptyList(), 7420, 5000) }
        assertTrue(driver.commands.none { it is TorCmd.Onion.Delete })
    }

    @Test
    fun removalsWhileStartingAreNoOps() = runBlocking<Unit> {
        tor.start()
        driver.emit(bootstrapping(40))
        withTimeout(1_000) {
            tor.unpublishService(DUCKDUCKGO)
            tor.removeClientAuth(DUCKDUCKGO)
        }
        assertTrue(driver.commands.isEmpty())
    }

    @Test
    fun wipeStateStopsAndDeletesTheTorDirectory() = runBlocking<Unit> {
        startReady()
        File(tor.torDir, "work/data").mkdirs()
        File(tor.torDir, "work/data/state").writeText("guardas")
        tor.wipeState()
        assertEquals(TorState.Off, tor.state.value)
        assertEquals(1, driver.stops)
        assertFalse(tor.torDir.exists())
        tor.wipeState() // sin directorio: no falla
        tor.start()
        assertNotEquals(TorState.Off, tor.state.value)
    }

    private suspend fun expectIOException(block: suspend () -> Any) {
        try {
            block()
            fail("se esperaba IOException")
        } catch (e: IOException) {
            assertFalse("el mensaje no debe incluir la dirección", e.message.orEmpty().contains(DUCKDUCKGO))
        }
    }

    /** Runtime falso: la prueba emite los estados; las órdenes se registran y responden como Tor. */
    private class FakeDriver : TorDriver {
        var onState: (RuntimeState) -> Unit = {}
        val commands: MutableList<TorCmd.Unprivileged<*>> = Collections.synchronizedList(mutableListOf())
        @Volatile var starts = 0
        @Volatile var stops = 0
        @Volatile var startFailure: Exception? = null
        @Volatile var commandFailure: Exception? = null
        @Volatile var addFailure: Exception? = null
        @Volatile var hangOnStart = false
        @Volatile var startCancelled = false
        val startEntered = CompletableDeferred<Unit>()
        @Volatile var socks: InetSocketAddress? = InetSocketAddress("127.0.0.1", 9050)

        fun emit(s: RuntimeState) = onState(s)

        override suspend fun startDaemon() {
            starts++
            startEntered.complete(Unit)
            startFailure?.let { throw it }
            if (hangOnStart) {
                try {
                    awaitCancellation()
                } finally {
                    startCancelled = true
                }
            }
        }

        override suspend fun stopDaemon() {
            stops++
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> execute(cmd: TorCmd.Unprivileged<T>): T {
            commands += cmd
            commandFailure?.let { throw it }
            return when (cmd) {
                is TorCmd.Onion.Add -> {
                    addFailure?.let { throw it }
                    val existing = cmd.key as ED25519_V3.PrivateKey?
                    val result = if (existing == null) {
                        val generated = ED25519_V3.PrivateKey.generate()
                        HiddenServiceEntry.of(generated.generatePublicKey(), generated, cmd.clientAuth)
                    } else {
                        val entry = HiddenServiceEntry.of(existing.generatePublicKey(), null, cmd.clientAuth)
                        if (cmd.destroyKeyOnJobCompletion) existing.destroy() // como kmp-tor al terminar
                        entry
                    }
                    result as T
                }
                else -> Reply.Success.OK as T
            }
        }

        override fun socksAddress(): InetSocketAddress? = socks
    }

    private companion object {
        const val DUCKDUCKGO = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
    }
}
