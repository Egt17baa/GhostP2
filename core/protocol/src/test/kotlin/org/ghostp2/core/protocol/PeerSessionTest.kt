package org.ghostp2.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Pruebas de [PeerSession] con dos sesiones reales (handshake KKpsk2 + ratchet) sobre sockets de
 * bucle local. Los temporizadores usan tiempo real con duraciones cortas (vía [SessionTimings]):
 * el tiempo virtual de runTest no sirve con E/S real, porque el planificador de pruebas se
 * considera ocioso mientras espera al socket y adelantaría los plazos.
 */
class PeerSessionTest {
    private val transport = TestTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        transport.close()
    }

    /** Temporizadores largos: ninguno debe vencer durante una prueba normal. */
    private val calm = SessionTimings(heartbeat = 10.seconds, idleTimeout = 30.seconds, ackTimeout = 10.seconds, linger = 500.milliseconds)

    /** Registro de eventos de una sesión; se suscribe de inmediato (antes de start()). */
    private class EventLog(session: PeerSession, scope: CoroutineScope) {
        private val seen = MutableStateFlow<List<SessionEvent>>(emptyList())

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { e -> seen.update { it + e } } }
        }

        val all: List<SessionEvent> get() = seen.value

        suspend fun await(timeout: Duration = 5.seconds, predicate: (SessionEvent) -> Boolean): SessionEvent =
            withTimeout(timeout) { seen.first { list -> list.any(predicate) }.first(predicate) }

        /** Espera a que el registro completo cumpla [condition]. */
        suspend fun awaitLog(timeout: Duration = 5.seconds, condition: (List<SessionEvent>) -> Boolean) {
            withTimeout(timeout) { seen.first(condition) }
        }
    }

    private class Peers(val a: PeerSession, val b: PeerSession, val logA: EventLog, val logB: EventLog)

    private fun peers(
        timingsA: SessionTimings = calm, timingsB: SessionTimings = calm,
        streamA: FrameStream = transport.a, streamB: FrameStream = transport.b,
    ): Peers {
        val (ca, cb) = pairedChannels(streamA, streamB)
        val a = PeerSession(streamA, ca, scope, TimeSource.Monotonic, timingsA)
        val b = PeerSession(streamB, cb, scope, TimeSource.Monotonic, timingsB)
        return Peers(a, b, EventLog(a, scope), EventLog(b, scope))
    }

    @Test
    fun textIsDeliveredAndAcked(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        val id = p.a.sendText("hola Bob")
        assertTrue(id.matches(Regex("[0-9a-f]{24}")), "id: $id")
        val received = p.logB.await { it is SessionEvent.TextReceived } as SessionEvent.TextReceived
        assertEquals(id, received.id)
        assertEquals("hola Bob", received.text)
        p.logA.await { it == SessionEvent.Delivered(id) }
        assertTrue(p.a.isOpen.value && p.b.isOpen.value)
    }

    @Test
    fun repliesFlowBothWays(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        val idA = p.a.sendText("¿qué tal?")
        val idB = p.b.sendText("bien 👍")
        assertEquals("bien 👍", (p.logA.await { it is SessionEvent.TextReceived } as SessionEvent.TextReceived).text)
        assertEquals("¿qué tal?", (p.logB.await { it is SessionEvent.TextReceived } as SessionEvent.TextReceived).text)
        p.logA.await { it == SessionEvent.Delivered(idA) }
        p.logB.await { it == SessionEvent.Delivered(idB) }
    }

    @Test
    fun photoArrivesWholeAndAcked(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        val jpeg = Primitives.randomBytes(200_000)
        val id = p.a.sendPhoto(jpeg)
        val received = p.logB.await { it is SessionEvent.PhotoReceived } as SessionEvent.PhotoReceived
        assertEquals(id, received.id)
        assertArrayEquals(jpeg, received.jpeg)
        p.logA.await { it == SessionEvent.Delivered(id) }
        val progress = p.logA.all.filterIsInstance<SessionEvent.PhotoProgress>()
        assertEquals(7, progress.size) // ceil(200 000 / 32 768)
        assertEquals((1..6).map { it * 32_768 } + 200_000, progress.map { it.sentBytes })
        assertTrue(progress.all { it.id == id && it.totalBytes == 200_000 })
        // El progreso llega antes que el acuse
        assertTrue(p.logA.all.indexOf(progress.last()) < p.logA.all.indexOf(SessionEvent.Delivered(id)))
    }

    @Test
    fun maxSizePhotoAndExactChunkMultipleArrive(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        for (size in listOf(PeerSession.MAX_PHOTO_BYTES, 2 * PeerSession.CHUNK_BYTES, 1)) {
            val jpeg = Primitives.randomBytes(size)
            val id = p.a.sendPhoto(jpeg)
            val received = p.logB.await { it is SessionEvent.PhotoReceived && it.id == id } as SessionEvent.PhotoReceived
            assertArrayEquals(jpeg, received.jpeg)
            p.logA.await { it == SessionEvent.Delivered(id) }
        }
    }

    /** Deja pasar [allowed] escrituras (una por trama) y bloquea las siguientes hasta [open]. */
    private class GateOutputStream(private val delegate: OutputStream) : OutputStream() {
        private val remaining = AtomicInteger(Int.MAX_VALUE)
        private val opened = CountDownLatch(1)
        val blocked = CountDownLatch(1)

        fun allow(frames: Int) = remaining.set(frames)
        fun open() = opened.countDown()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (remaining.getAndDecrement() <= 0) {
                blocked.countDown()
                opened.await(10, TimeUnit.SECONDS)
            }
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    @Test
    fun photoInterruptedIsDiscardedOnBothSides(): Unit = runBlocking {
        val gate = GateOutputStream(transport.rawA.getOutputStream())
        val streamA = FrameStream(transport.rawA.getInputStream(), gate, transport.rawA)
        val p = peers(streamA = streamA)
        gate.allow(4) // PhotoStart + 3 trozos; el cuarto trozo se queda bloqueado
        p.a.start(); p.b.start()
        val sending = scope.async { p.a.sendPhoto(Primitives.randomBytes(200_000)) }
        try {
            assertTrue(gate.blocked.await(5, TimeUnit.SECONDS))
            transport.b.close() // se corta el transporte de B a mitad de la foto
            assertEquals(SessionEvent.Closed(CloseReason.IO), p.logB.await { it is SessionEvent.Closed })
            val failed = p.logA.await { it is SessionEvent.Failed } as SessionEvent.Failed
            assertEquals(FailReason.CONNECTION_LOST, failed.reason)
            p.logA.await { it is SessionEvent.Closed }
            gate.open()
            assertEquals(failed.id, sending.await())
        } finally {
            gate.open()
        }
        assertEquals(3 * 32_768, p.logA.all.filterIsInstance<SessionEvent.PhotoProgress>().maxOf { it.sentBytes })
        assertFalse(p.logB.all.any { it is SessionEvent.PhotoReceived })
        assertFalse(p.logA.all.any { it is SessionEvent.Delivered })
        assertFalse(p.a.isOpen.value || p.b.isOpen.value)
    }

    @Test
    fun pendingFailsWhenConnectionDrops(): Unit = runBlocking {
        val p = peers()
        p.a.start() // B no lee: nunca acusará
        val id = p.a.sendText("¿sigues ahí?")
        transport.b.close()
        assertEquals(SessionEvent.Failed(id, FailReason.CONNECTION_LOST), p.logA.await { it is SessionEvent.Failed })
        assertEquals(SessionEvent.Closed(CloseReason.IO), p.logA.await { it is SessionEvent.Closed })
        assertFalse(p.a.isOpen.value)
    }

    @Test
    fun missingAckFailsAfterAckTimeout(): Unit = runBlocking {
        val p = peers(timingsA = calm.copy(ackTimeout = 300.milliseconds))
        p.a.start() // B sin start(): no acusa
        val t0 = TimeSource.Monotonic.markNow()
        val id = p.a.sendText("hola")
        assertEquals(SessionEvent.Failed(id, FailReason.NO_ACK), p.logA.await { it is SessionEvent.Failed })
        assertTrue(t0.elapsedNow() >= 300.milliseconds)
        assertTrue(p.a.isOpen.value) // un acuse perdido no cierra la sesión
    }

    @Test
    fun lateAckAfterNoAckIsIgnored(): Unit = runBlocking {
        val p = peers(timingsA = calm.copy(ackTimeout = 300.milliseconds))
        p.a.start()
        val late = p.a.sendText("tarde")
        p.logA.await { it == SessionEvent.Failed(late, FailReason.NO_ACK) }
        p.b.start() // ahora B lee el texto y lo acusa tarde
        p.logB.await { it is SessionEvent.TextReceived && it.id == late }
        val onTime = p.a.sendText("a tiempo")
        p.logA.await { it == SessionEvent.Delivered(onTime) } // el acuse tardío ya llegó antes que este
        assertFalse(p.logA.all.contains(SessionEvent.Delivered(late)))
    }

    @Test
    fun silentPeerTimesOut(): Unit = runBlocking {
        val p = peers(timingsA = calm.copy(heartbeat = 100.milliseconds, idleTimeout = 500.milliseconds))
        p.a.start() // B no arranca: no envía latidos
        val t0 = TimeSource.Monotonic.markNow()
        assertEquals(SessionEvent.Closed(CloseReason.TIMEOUT), p.logA.await { it is SessionEvent.Closed })
        assertTrue(t0.elapsedNow() >= 500.milliseconds)
        assertFalse(p.a.isOpen.value)
    }

    @Test
    fun heartbeatKeepsIdleSessionAlive(): Unit = runBlocking {
        val short = calm.copy(heartbeat = 100.milliseconds, idleTimeout = 500.milliseconds)
        val p = peers(timingsA = short, timingsB = short)
        p.a.start(); p.b.start()
        delay(1_500)
        assertTrue(p.a.isOpen.value && p.b.isOpen.value)
        assertTrue(p.logA.all.isEmpty() && p.logB.all.isEmpty())
    }

    @Test
    fun unpairIsDelivered(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        p.a.sendUnpair()
        p.logB.await { it is SessionEvent.Closed }
        assertEquals(listOf(SessionEvent.PeerUnpaired, SessionEvent.Closed(CloseReason.PEER_UNPAIRED)), p.logB.all)
        assertEquals(listOf<SessionEvent>(SessionEvent.Closed(CloseReason.LOCAL)), listOf(p.logA.await { it is SessionEvent.Closed }))
        assertFalse(p.a.isOpen.value || p.b.isOpen.value)
    }

    @Test
    fun closeSendsByeAndIsIdempotent(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        p.a.close()
        p.a.close()
        assertEquals(SessionEvent.Closed(CloseReason.PEER_BYE), p.logB.await { it is SessionEvent.Closed })
        p.logA.await { it is SessionEvent.Closed }
        delay(100)
        assertEquals(listOf<SessionEvent>(SessionEvent.Closed(CloseReason.LOCAL)), p.logA.all)
        assertEquals(1, p.logB.all.count { it is SessionEvent.Closed })
    }

    @Test
    fun localCloseFailsPendingMessages(): Unit = runBlocking {
        val p = peers()
        p.a.start() // B no acusa
        val id = p.a.sendText("pendiente")
        p.a.close()
        p.logA.await { it is SessionEvent.Closed }
        assertEquals(
            listOf(SessionEvent.Failed(id, FailReason.CONNECTION_LOST), SessionEvent.Closed(CloseReason.LOCAL)),
            p.logA.all,
        )
    }

    @Test
    fun concurrentSendsKeepCounterOrder(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        val texts = (1..50).map { "mensaje $it" }
        val ids = texts.map { t -> scope.async { p.a.sendText(t) } }.awaitAll()
        for (id in ids) p.logA.await { it == SessionEvent.Delivered(id) }
        // El registro de B se recolecta aparte: esperar a que vea los 50
        p.logB.awaitLog { log -> log.count { it is SessionEvent.TextReceived } == 50 }
        val received = p.logB.all.filterIsInstance<SessionEvent.TextReceived>()
        assertEquals(50, received.size)
        assertEquals(texts.toSet(), received.map { it.text }.toSet())
        assertEquals(ids.toSet(), received.map { it.id }.toSet())
        assertFalse((p.logA.all + p.logB.all).any { it is SessionEvent.Closed || it is SessionEvent.Failed })
    }

    @Test
    fun sendAfterCloseFailsNotConnected(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        p.a.close()
        p.logB.await { it is SessionEvent.Closed }
        val textId = p.a.sendText("demasiado tarde")
        val photoId = p.a.sendPhoto(Primitives.randomBytes(10))
        assertEquals(SessionEvent.Failed(textId, FailReason.NOT_CONNECTED), p.logA.await { it is SessionEvent.Failed && it.id == textId })
        assertEquals(SessionEvent.Failed(photoId, FailReason.NOT_CONNECTED), p.logA.await { it is SessionEvent.Failed && it.id == photoId })
        assertTrue(textId.matches(Regex("[0-9a-f]{24}")))
        assertFalse(p.logB.all.any { it is SessionEvent.TextReceived || it is SessionEvent.PhotoReceived })
    }

    @Test
    fun oversizeInputsRejected(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        assertThrowsSuspend<IllegalArgumentException> { p.a.sendText("a".repeat(8_001)) }
        assertThrowsSuspend<IllegalArgumentException> { p.a.sendPhoto(ByteArray(3_145_729)) }
        assertThrowsSuspend<IllegalArgumentException> { p.a.sendPhoto(ByteArray(0)) }
        // Los límites exactos sí se aceptan (8 000 caracteres, también fuera del BMP)
        val max = p.a.sendText("😀".repeat(8_000))
        p.logA.await { it == SessionEvent.Delivered(max) }
        p.logB.await { it is SessionEvent.TextReceived }
        assertEquals(1, p.logB.all.count { it is SessionEvent.TextReceived })
        assertTrue(p.logA.all.none { it is SessionEvent.Failed })
    }

    @Test
    fun defaultTimingsMatchSpec() {
        val t = SessionTimings()
        assertEquals(20.seconds, t.heartbeat)
        assertEquals(60.seconds, t.idleTimeout)
        assertEquals(30.seconds, t.ackTimeout)
        assertEquals(8_000, PeerSession.MAX_TEXT_CHARS)
        assertEquals(3_145_728, PeerSession.MAX_PHOTO_BYTES)
        assertEquals(32_768, PeerSession.CHUNK_BYTES)
    }

    @Test
    fun eventsAndMessagesHideContentInToString() {
        assertFalse(SessionEvent.TextReceived("ab".repeat(12), "secreto").toString().contains("secreto"))
        assertFalse(AppMessage.Text("ab".repeat(12), "secreto").toString().contains("secreto"))
    }

    // --- Violaciones del protocolo: el otro lado envía tramas a mano con su canal real ---

    /** B arranca; A envía [frames] (mensajes o bytes ya cifrados) y B debe cerrar con SECURITY. */
    private fun assertSecurityClose(build: (RatchetChannel) -> List<ByteArray>): Unit = runBlocking {
        TestTransport().use { t ->
            val (ca, cb) = pairedChannels(t.a, t.b)
            val b = PeerSession(t.b, cb, scope, TimeSource.Monotonic, calm)
            val log = EventLog(b, scope)
            b.start()
            for (frame in build(ca)) t.a.send(frame)
            assertEquals(SessionEvent.Closed(CloseReason.SECURITY), log.await { it is SessionEvent.Closed })
            assertFalse(log.all.any { it is SessionEvent.PhotoReceived })
            assertFalse(b.isOpen.value)
            // B cerró el transporte: A ve el final del flujo (quizá tras un acuse o un latido)
            withTimeout(5_000) {
                runCatching { while (true) t.a.receive() }
            }
        }
    }

    private fun RatchetChannel.msg(m: AppMessage) = encrypt(AppCodec.encode(m))
    private val pid = "0123456789abcdef01234567"
    private fun chunk(i: Int, size: Int = PeerSession.CHUNK_BYTES) = AppMessage.PhotoChunk(pid, i, ByteArray(size))

    @Test
    fun tamperedFrameClosesWithSecurity() = assertSecurityClose { ch ->
        listOf(ch.msg(AppMessage.Ping).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
    }

    @Test
    fun undecodableMessageClosesWithSecurity() = assertSecurityClose { ch -> listOf(ch.encrypt(byteArrayOf(1, 2, 3))) }

    @Test
    fun malformedIdClosesWithSecurity() = assertSecurityClose { ch -> listOf(ch.msg(AppMessage.Text("XYZ", "hola"))) }

    @Test
    fun oversizeIncomingTextClosesWithSecurity() =
        assertSecurityClose { ch -> listOf(ch.msg(AppMessage.Text(pid, "a".repeat(8_001)))) }

    @Test
    fun chunkWithoutStartClosesWithSecurity() = assertSecurityClose { ch -> listOf(ch.msg(chunk(0))) }

    @Test
    fun endWithoutStartClosesWithSecurity() = assertSecurityClose { ch -> listOf(ch.msg(AppMessage.PhotoEnd(pid))) }

    @Test
    fun oversizePhotoStartClosesWithSecurity() =
        assertSecurityClose { ch -> listOf(ch.msg(AppMessage.PhotoStart(pid, PeerSession.MAX_PHOTO_BYTES + 1, 97))) }

    @Test
    fun inconsistentChunkCountClosesWithSecurity() =
        assertSecurityClose { ch -> listOf(ch.msg(AppMessage.PhotoStart(pid, 40_000, 3))) }

    @Test
    fun outOfOrderChunkClosesWithSecurity() = assertSecurityClose { ch ->
        listOf(ch.msg(AppMessage.PhotoStart(pid, 70_000, 3)), ch.msg(chunk(0)), ch.msg(chunk(2, 70_000 - 65_536)))
    }

    @Test
    fun chunkIndexOutOfRangeClosesWithSecurity() = assertSecurityClose { ch ->
        listOf(ch.msg(AppMessage.PhotoStart(pid, 10, 1)), ch.msg(chunk(0, 10)), ch.msg(chunk(1, 10)))
    }

    @Test
    fun wrongChunkSizeClosesWithSecurity() = assertSecurityClose { ch ->
        listOf(ch.msg(AppMessage.PhotoStart(pid, 70_000, 3)), ch.msg(chunk(0, 1_000)))
    }

    @Test
    fun endBeforeAllChunksClosesWithSecurity() = assertSecurityClose { ch ->
        listOf(ch.msg(AppMessage.PhotoStart(pid, 70_000, 3)), ch.msg(chunk(0)), ch.msg(AppMessage.PhotoEnd(pid)))
    }

    @Test
    fun secondConcurrentPhotoClosesWithSecurity() = assertSecurityClose { ch ->
        listOf(ch.msg(AppMessage.PhotoStart(pid, 10, 1)), ch.msg(AppMessage.PhotoStart("ff".repeat(12), 10, 1)))
    }

    @Test
    fun wellFormedManualPhotoIsAccepted(): Unit = runBlocking {
        // Control positivo del arnés anterior: la misma secuencia bien formada sí produce PhotoReceived
        TestTransport().use { t ->
            val (ca, cb) = pairedChannels(t.a, t.b)
            val b = PeerSession(t.b, cb, scope, TimeSource.Monotonic, calm)
            val log = EventLog(b, scope)
            b.start()
            for (m in listOf(AppMessage.PhotoStart(pid, 70_000, 3), chunk(0), chunk(1), chunk(2, 70_000 - 65_536), AppMessage.PhotoEnd(pid))) {
                t.a.send(ca.msg(m))
            }
            val photo = log.await { it is SessionEvent.PhotoReceived } as SessionEvent.PhotoReceived
            assertEquals(70_000, photo.jpeg.size)
            assertEquals(AppMessage.Ack(pid), AppCodec.decode(ca.decrypt(t.a.receive())))
            assertTrue(b.isOpen.value)
        }
    }
}
