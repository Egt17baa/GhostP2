package org.ghostp2.tor

import kotlinx.coroutines.runBlocking
import org.ghostp2.engine.bridges.PtKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections

/**
 * Contabilidad de [IptProxyController] sobre un IPtProxy falso ([GoTransports]) y una redirección de la salida
 * estándar falsa: en la JVM no hay `libgojni.so` ni se puede tocar el fd 1/2 del proceso de Gradle.
 */
class IptProxyControllerTest {
    private val dir: File = Files.createTempDirectory("pt-test").toFile()
    private val stateDir = File(dir, "pt")

    /** Llamadas en orden: "newController", "stdio", "snowflake", "start obfs4", "stop obfs4"… */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val go = FakeGo(events)

    @Volatile private var created: () -> GoTransports? = { go }
    @Volatile private var stdioFailure: Throwable? = null

    private val process = GoProcess(
        create = { events += "newController"; created() },
        silenceStdio = { events += "stdio"; stdioFailure?.let { throw it } },
    )

    private fun controller() = IptProxyController(stateDir, process)

    @After
    fun cerrar() {
        dir.deleteRecursively()
    }

    @Test
    fun stdioGoesToDevNullOnceRightAfterNewControllerBeforeAnyTransport() = runBlocking<Unit> {
        val first = controller()
        assertEquals(mapOf(PtKind.OBFS4 to 4711), first.start(setOf(PtKind.OBFS4)))
        assertEquals("newController", events[0])
        assertEquals("la salida de Go se silencia justo después de crear el Controller", "stdio", events[1])
        assertTrue(events.indexOf("start obfs4") > 1)
        first.stop()

        // Un arranque nuevo en el mismo proceso: ni otro Controller ni otra redirección.
        val second = controller()
        second.start(setOf(PtKind.WEBTUNNEL))
        assertEquals(1, events.count { it == "newController" })
        assertEquals(1, events.count { it == "stdio" })
        second.stop()
    }

    @Test
    fun stdioFailureFailsClosedWithoutStartingTransports() = runBlocking<Unit> {
        stdioFailure = RuntimeException("EBADF")
        expectIOException { controller().start(setOf(PtKind.OBFS4)) }
        assertFalse("sin silenciar la salida de Go no arranca ningún transporte", events.any { it.startsWith("start ") })

        // Se reintenta en el siguiente arranque, sobre el mismo Controller (IPtProxy no permite otro).
        stdioFailure = null
        controller().start(setOf(PtKind.OBFS4))
        assertEquals(1, events.count { it == "newController" })
        assertEquals(2, events.count { it == "stdio" })
        assertTrue("start obfs4" in events)
    }

    @Test
    fun nullOrMissingLibraryFailsClosed() = runBlocking<Unit> {
        created = { null } // error de Go en NewController
        expectIOException { controller().start(setOf(PtKind.OBFS4)) }
        created = { throw UnsatisfiedLinkError("sin libgojni.so") }
        expectIOException { controller().start(setOf(PtKind.OBFS4)) }
        created = { throw ExceptionInInitializerError() }
        expectIOException { controller().start(setOf(PtKind.OBFS4)) }
        assertFalse(events.any { it == "stdio" || it.startsWith("start ") })
    }

    @Test
    fun linkageErrorInANativeCallStopsWhatWasStarted() = runBlocking<Unit> {
        go.failures["webtunnel"] = UnsatisfiedLinkError("start")
        val pt = controller()
        expectIOException { pt.start(setOf(PtKind.OBFS4, PtKind.WEBTUNNEL)) }
        assertTrue("obfs4 ya estaba arrancado y se para", events.lastIndexOf("stop obfs4") > events.indexOf("start obfs4"))
        assertTrue(events.lastIndexOf("stop webtunnel") > events.indexOf("start webtunnel"))

        // Nada queda apuntado: la misma instancia puede volver a arrancar.
        go.failures.clear()
        assertEquals(mapOf(PtKind.OBFS4 to 4711), pt.start(setOf(PtKind.OBFS4)))
    }

    @Test
    fun linkageErrorInSnowflakeSettersFailsClosed() = runBlocking<Unit> {
        go.snowflakeFailure = NoSuchMethodError("setSnowflakeIceServers")
        expectIOException { controller().start(setOf(PtKind.SNOWFLAKE)) }
        assertFalse(events.any { it.startsWith("start ") })
    }

    @Test
    fun listenerOutsideLoopbackFailsClosed() = runBlocking<Unit> {
        go.address = "0.0.0.0"
        expectIOException { controller().start(setOf(PtKind.OBFS4)) }
        assertTrue(events.lastIndexOf("stop obfs4") > events.indexOf("start obfs4"))
    }

    @Test
    fun stopNeverThrows() = runBlocking<Unit> {
        val pt = controller()
        pt.start(setOf(PtKind.OBFS4, PtKind.SNOWFLAKE))
        go.stopFailure = UnsatisfiedLinkError("stop")
        pt.stop()
        assertTrue("se intentan parar todos", "stop snowflake" in events)
        pt.stop() // idempotente
    }

    private suspend fun expectIOException(block: suspend () -> Any) {
        try {
            block()
            fail("se esperaba IOException")
        } catch (e: IOException) {
            // esperado
        }
    }

    /** IPtProxy falso: puertos fijos (obfs4 4711, webtunnel 4712, snowflake 4713). */
    private class FakeGo(private val events: MutableList<String>) : GoTransports {
        val failures: MutableMap<String, Throwable> = Collections.synchronizedMap(mutableMapOf())
        @Volatile var snowflakeFailure: Throwable? = null
        @Volatile var stopFailure: Throwable? = null
        @Volatile var address = "127.0.0.1"
        private val ports = mapOf("obfs4" to 4711L, "webtunnel" to 4712L, "snowflake" to 4713L)

        override fun configureSnowflake(iceServers: String, brokerUrl: String, frontDomains: String) {
            snowflakeFailure?.let { throw it }
            events += "snowflake"
        }

        override fun start(transport: String) {
            events += "start $transport"
            failures[transport]?.let { throw it }
        }

        override fun stop(transport: String) {
            events += "stop $transport"
            stopFailure?.let { throw it }
        }

        override fun port(transport: String): Long = ports.getValue(transport)

        override fun localAddress(transport: String): String = "$address:${port(transport)}"
    }
}
