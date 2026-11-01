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
    private val tor = KmpTorTransport(File(dir, "tor"), STOP_TIMEOUT_MILLIS) { onState ->
        driver.also { it.onState = onState }
    }

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
    fun permanentAddressIsNeverPublishedWithoutClientAuth() = runBlocking<Unit> {
        startReady()
        val key = ED25519_V3.PrivateKey.generate()
        // Con clave (la .onion permanente) y sin claves de autorización: se rechaza sin enviar nada a Tor.
        expectIOException { tor.publishService(key.encoded(), emptyList(), 7420, 5000) }
        assertTrue(driver.commands.isEmpty())
        // Sin clave y sin autorización sigue permitido: es el servicio temporal de la invitación.
        tor.publishService(null, emptyList(), 7420, 5000)
        assertTrue(driver.commands.single() is TorCmd.Onion.Add)
    }

    @Test
    fun ambiguousAddFailureRetiresTheAddress() = runBlocking<Unit> {
        startReady()
        val key = ED25519_V3.PrivateKey.generate()
        val expected = key.generatePublicKey().address()
        driver.addFailure = IllegalStateException("conexión de control caída")
        val auth = Primitives.generateX25519().publicKey
        expectIOException { tor.publishService(key.encoded(), listOf(auth), 7420, 5000) }
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
    fun removalsAfterReadyStillActWhenBackToStarting() = runBlocking<Unit> {
        startReady()
        // Bootstrap completo pero red desactivada: el estado vuelve a Starting(100) y Tor conserva los servicios.
        driver.emit(RuntimeState(RuntimeState.Daemon.On(100), RuntimeState.Network.Disabled))
        assertEquals(TorState.Starting(100), tor.state.value)
        withTimeout(1_000) {
            tor.unpublishService(DUCKDUCKGO)
            tor.removeClientAuth(DUCKDUCKGO)
        }
        assertTrue(driver.commands[0] is TorCmd.Onion.Delete)
        assertTrue(driver.commands[1] is TorCmd.OnionClientAuth.Remove)
        // Tras stop() y un arranque nuevo, sin Ready todavía, vuelven a ser no-ops.
        tor.stop()
        tor.start()
        driver.emit(bootstrapping(40))
        tor.unpublishService(DUCKDUCKGO)
        assertEquals(2, driver.commands.size)
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

    @Test
    fun startAfterWipeStateUsesAFreshDriver() = runBlocking<Unit> {
        val drivers = mutableListOf<FakeDriver>()
        val counted = KmpTorTransport(File(dir, "tor2"), STOP_TIMEOUT_MILLIS) { onState ->
            FakeDriver().also { it.onState = onState; drivers += it }
        }
        try {
            counted.start()
            drivers[0].emit(ready)
            assertEquals(TorState.Ready, counted.state.value)
            counted.wipeState()
            assertEquals(TorState.Off, counted.state.value)
            counted.start()
            assertNotEquals(TorState.Off, counted.state.value) // R13
            assertEquals("la fábrica se llama otra vez tras borrar el estado", 2, drivers.size)
            withTimeout(5_000) { drivers[1].startEntered.await() }
            assertEquals(1, drivers[1].starts)
            drivers[1].emit(ready)
            assertEquals(TorState.Ready, counted.state.value)
        } finally {
            counted.stop()
        }
    }

    @Test
    fun wipeStateDuringStartingStopsTheRun() = runBlocking<Unit> {
        tor.start()
        driver.emit(bootstrapping(40))
        assertEquals(TorState.Starting(40), tor.state.value)
        File(tor.torDir, "work/data").mkdirs()
        tor.wipeState()
        assertEquals(TorState.Off, tor.state.value)
        assertFalse(tor.torDir.exists())
        assertEquals(1, driver.stops)
        driver.emit(ready) // eventos tardíos del arranque interrumpido no cambian nada
        assertEquals(TorState.Off, tor.state.value)
    }

    @Test
    fun poisonedRecoversWhenTheRetriedStopFinishes() = runBlocking<Unit> {
        startReady()
        driver.hangOnStop = true
        withTimeout(5_000) { tor.stop() } // acotado: no se queda colgado
        assertEquals(TorState.Off, tor.state.value)
        assertEquals(1, driver.stops)
        // Envenenado: borrar el estado informa del fallo (borra lo que puede) sin volver a esperar.
        File(tor.torDir, "work").mkdirs()
        try {
            withTimeout(5_000) { tor.wipeState() }
            fail("se esperaba IOException")
        } catch (e: IOException) {
            // esperado
        }
        assertFalse(tor.torDir.exists())
        assertEquals(1, driver.stops)
        // El Tor colgado termina por fin: start() reintenta detenerlo una vez y arranca con normalidad.
        driver.hangOnStop = false
        tor.start()
        assertEquals(2, driver.stops)
        assertEquals(TorState.Starting(0), tor.state.value) // R13
        withTimeout(5_000) { while (driver.starts < 2) delay(10) }
        driver.emit(ready)
        assertEquals(TorState.Ready, tor.state.value)
        // Ya no está envenenado: borrar el estado vuelve a funcionar.
        tor.wipeState()
    }

    @Test
    fun poisonedTwiceStaysStuck() = runBlocking<Unit> {
        startReady()
        driver.hangOnStop = true
        withTimeout(5_000) { tor.stop() }
        // El reintento también agota el plazo: Error "atascado", sin arrancar otro proceso.
        withTimeout(5_000) { tor.start() }
        assertEquals(TorState.Error("Tor no se detuvo a tiempo; hay que reiniciar la app", stuck = true), tor.state.value)
        assertEquals(2, driver.stops)
        assertEquals("no se volvió a arrancar el proceso", 1, driver.starts)
        // Cada start() reintenta una sola vez.
        withTimeout(5_000) { tor.start() }
        assertTrue((tor.state.value as TorState.Error).stuck)
        assertEquals(3, driver.stops)
        assertEquals(1, driver.starts)
        driver.hangOnStop = false
    }

    @Test
    fun stopTimeoutDuringWipeStateReportsFailureAndPoisons() = runBlocking<Unit> {
        startReady()
        driver.hangOnStop = true
        try {
            withTimeout(5_000) { tor.wipeState() }
            fail("se esperaba IOException")
        } catch (e: IOException) {
            // esperado
        }
        assertEquals(TorState.Off, tor.state.value)
        // Sigue colgado: el reintento de start() también agota el plazo.
        withTimeout(5_000) { tor.start() }
        assertTrue((tor.state.value as TorState.Error).stuck)
        driver.hangOnStop = false
    }

    @Test
    fun stopFailureWithoutTimeoutDoesNotPoison() = runBlocking<Unit> {
        startReady()
        driver.stopFailure = IllegalStateException("control caído")
        tor.stop()
        tor.wipeState()
        tor.start()
        assertNotEquals(TorState.Off, tor.state.value)
        assertFalse(tor.state.value is TorState.Error)
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
        @Volatile var hangOnStop = false
        @Volatile var stopFailure: Exception? = null
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
            stopFailure?.let { throw it }
            if (hangOnStop) awaitCancellation()
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
        const val STOP_TIMEOUT_MILLIS = 300L
        const val DUCKDUCKGO = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
    }
}
