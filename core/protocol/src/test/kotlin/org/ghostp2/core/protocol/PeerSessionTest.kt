package org.ghostp2.core.protocol

import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        val a = PeerSession(streamA, ca, scope, timingsA)
        val b = PeerSession(streamB, cb, scope, timingsB)
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

    /**
     * Flujo cuyas escrituras se bloquean según [gate]; cerrarlo abre la puerta (como un socket
     * real, que al cerrarse desbloquea una escritura en curso) y cierra el socket.
     */
    private fun gatedStream(socket: java.net.Socket, gate: GateOutputStream) =
        FrameStream(socket.getInputStream(), gate) { gate.open(); socket.close() }

    /** Id de mensaje al que se refiere un evento (null si no se refiere a ninguno). */
    private fun idOf(e: SessionEvent): String? = when (e) {
        is SessionEvent.TextReceived -> e.id
        is SessionEvent.PhotoReceived -> e.id
        is SessionEvent.PhotoProgress -> e.id
        is SessionEvent.Delivered -> e.id
        is SessionEvent.Failed -> e.id
        else -> null
    }

    /**
     * Con un recolector confinado en el mismo hilo que quien llama, ningún evento de un envío
     * puede registrarse antes de que sendText/sendPhoto devuelvan su id (no suspenden).
     */
    @Test
    fun idIsKnownBeforeAnyEventForIt(): Unit = runBlocking {
        val confined = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val confinedScope = CoroutineScope(scope.coroutineContext + confined)
            val (ca, cb) = pairedChannels(transport.a, transport.b)
            val a = PeerSession(transport.a, ca, scope, calm)
            val b = PeerSession(transport.b, cb, scope, calm)
            val logA = EventLog(a, confinedScope)
            EventLog(b, scope)
            a.start(); b.start()

            val jpeg = Primitives.randomBytes(100_000)
            val (photoId, beforePhoto) = withContext(confined) {
                val id = a.sendPhoto(jpeg)
                id to logA.all.filter { idOf(it) == id }
            }
            assertEquals(emptyList<SessionEvent>(), beforePhoto)
            logA.await { it == SessionEvent.Delivered(photoId) }
            val progress = logA.all.filterIsInstance<SessionEvent.PhotoProgress>()
            assertEquals(4, progress.size)
            assertTrue(progress.all { it.id == photoId })

            val (textId, beforeText) = withContext(confined) {
                val id = a.sendText("hola")
                id to logA.all.filter { idOf(it) == id }
            }
            assertEquals(emptyList<SessionEvent>(), beforeText)
            logA.await { it == SessionEvent.Delivered(textId) }

            a.close()
            logA.await { it is SessionEvent.Closed }
            val (lateId, beforeLate) = withContext(confined) {
                val id = a.sendText("tarde")
                id to logA.all.filter { idOf(it) == id }
            }
            assertEquals(emptyList<SessionEvent>(), beforeLate)
            logA.await { it == SessionEvent.Failed(lateId, FailReason.NOT_CONNECTED) }
        } finally {
            confined.close()
        }
    }

    /** Los textos enviados uno tras otro desde la misma corrutina llegan en ese orden. */
    @Test
    fun sequentialTextsArriveInOrder(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        val texts = (1..40).map { "mensaje $it" }
        val ids = texts.map { p.a.sendText(it) }
        p.logB.awaitLog { log -> log.count { it is SessionEvent.TextReceived } == 40 }
        val received = p.logB.all.filterIsInstance<SessionEvent.TextReceived>()
        assertEquals(texts, received.map { it.text })
        assertEquals(ids, received.map { it.id })
    }

    /**
     * El otro lado deja de leer: la escritura se queda bloqueada. El plazo de acuse del texto
     * corre desde que se registra (no desde que termina la escritura) y el vigilante de escritura
     * cierra la sesión con IO.
     */
    @Test
    fun stalledWriterFailsPendingTextAndClosesIo(): Unit = runBlocking {
        val gate = GateOutputStream(transport.rawA.getOutputStream())
        val streamA = gatedStream(transport.rawA, gate)
        val p = peers(timingsA = calm.copy(ackTimeout = 500.milliseconds), streamA = streamA)
        gate.allow(0) // ninguna escritura de A llega a salir
        p.a.start()
        val t0 = TimeSource.Monotonic.markNow()
        val id = p.a.sendText("¿me lees?")
        val failed = p.logA.await(3.seconds) { it is SessionEvent.Failed } as SessionEvent.Failed
        assertEquals(id, failed.id)
        assertTrue(failed.reason == FailReason.NO_ACK || failed.reason == FailReason.CONNECTION_LOST, "$failed")
        assertEquals(SessionEvent.Closed(CloseReason.IO), p.logA.await(3.seconds) { it is SessionEvent.Closed })
        assertTrue(t0.elapsedNow() < 2.seconds, "tardó ${t0.elapsedNow()}")
        assertFalse(p.a.isOpen.value)
    }

    /** Un lado que no lee pero sigue enviando textos no puede hacer crecer sin límite la cola de acuses. */
    @Test
    fun ackQueueOverflowClosesWithSecurity(): Unit = runBlocking {
        TestTransport().use { t ->
            val gate = GateOutputStream(t.rawA.getOutputStream())
            val streamA = gatedStream(t.rawA, gate)
            val (ca, cb) = pairedChannels(streamA, t.b)
            val a = PeerSession(streamA, ca, scope, calm)
            val log = EventLog(a, scope)
            gate.allow(0) // los acuses de A se quedan bloqueados en la escritura
            a.start()
            for (i in 1..300) {
                if (!a.isOpen.value) break
                // A cierra el socket al desbordarse la cola (antes de poner isOpen a false) con datos
                // sin leer: el kernel responde con RST y la siguiente escritura de B falla («Broken
                // pipe» / «Connection reset»). Es el final esperado del envío; el motivo se comprueba abajo.
                try {
                    t.b.send(cb.encrypt(AppCodec.encode(AppMessage.Text("%024x".format(i), "hola $i"))))
                } catch (e: java.io.IOException) {
                    break
                }
            }
            assertEquals(SessionEvent.Closed(CloseReason.SECURITY), log.await { it is SessionEvent.Closed })
            assertTrue(log.all.count { it is SessionEvent.TextReceived } >= 256)
        }
    }

    @Test
    fun startReturnsWhenClosedBeforeAnySubscription(): Unit = runBlocking {
        TestTransport().use { t ->
            val (ca, _) = pairedChannels(t.a, t.b)
            val a = PeerSession(t.a, ca, scope, calm)
            val starting = scope.async { a.start() }
            delay(100)
            assertFalse(starting.isCompleted) // sin suscriptor y abierta: start() espera
            a.close()
            withTimeout(2_000) { starting.await() }
            assertFalse(a.isOpen.value)
        }
    }

    @Test
    fun photoInterruptedIsDiscardedOnBothSides(): Unit = runBlocking {
        val gate = GateOutputStream(transport.rawA.getOutputStream())
        val streamA = FrameStream(transport.rawA.getInputStream(), gate, transport.rawA)
        val p = peers(streamA = streamA)
        gate.allow(4) // PhotoStart + 3 trozos; el cuarto trozo se queda bloqueado
        p.a.start(); p.b.start()
        val id = p.a.sendPhoto(Primitives.randomBytes(200_000)) // vuelve enseguida con el id
        try {
            assertTrue(gate.blocked.await(5, TimeUnit.SECONDS))
            transport.b.close() // se corta el transporte de B a mitad de la foto
            assertEquals(SessionEvent.Closed(CloseReason.IO), p.logB.await { it is SessionEvent.Closed })
            val failed = p.logA.await { it is SessionEvent.Failed } as SessionEvent.Failed
            assertEquals(SessionEvent.Failed(id, FailReason.CONNECTION_LOST), failed)
            p.logA.await { it is SessionEvent.Closed }
        } finally {
            gate.open()
        }
        assertEquals(3 * 32_768, p.logA.all.filterIsInstance<SessionEvent.PhotoProgress>().maxOf { it.sentBytes })
        assertFalse(p.logB.all.any { it is SessionEvent.PhotoReceived })
        assertFalse(p.logA.all.any { it is SessionEvent.Delivered })
        assertFalse(p.a.isOpen.value || p.b.isOpen.value)
    }

    @Test
    fun cancelledPhotoSendStillCompletesAndSessionStaysUsable(): Unit = runBlocking {
        val gate = GateOutputStream(transport.rawA.getOutputStream())
        val streamA = FrameStream(transport.rawA.getInputStream(), gate, transport.rawA)
        val p = peers(streamA = streamA)
        gate.allow(3) // PhotoStart + 2 trozos; el tercero se queda bloqueado
        p.a.start(); p.b.start()
        val jpeg = Primitives.randomBytes(200_000)
        val idFromCaller = CompletableDeferred<String>()
        val caller = scope.launch {
            idFromCaller.complete(p.a.sendPhoto(jpeg))
            awaitCancellation() // la pantalla sigue abierta hasta que el usuario sale
        }
        val id = withTimeout(5_000) { idFromCaller.await() }
        try {
            assertTrue(gate.blocked.await(5, TimeUnit.SECONDS))
            caller.cancel() // p. ej. el usuario sale del chat a mitad de la subida
            caller.join()
        } finally {
            gate.open()
        }
        p.logA.await { it == SessionEvent.Delivered(id) }
        val received = p.logB.await { it is SessionEvent.PhotoReceived } as SessionEvent.PhotoReceived
        assertArrayEquals(jpeg, received.jpeg)
        // La sesión sigue sirviendo: otra foto llega entera
        val second = Primitives.randomBytes(50_000)
        val id2 = p.a.sendPhoto(second)
        p.logA.await { it == SessionEvent.Delivered(id2) }
        assertArrayEquals(second, (p.logB.await { it is SessionEvent.PhotoReceived && it.id == id2 } as SessionEvent.PhotoReceived).jpeg)
        val terminal = p.logA.all.filter {
            (it is SessionEvent.Delivered && it.id == id) || (it is SessionEvent.Failed && it.id == id)
        }
        assertEquals(1, terminal.size)
        assertTrue(p.a.isOpen.value && p.b.isOpen.value)
    }

    @Test
    fun startWaitsForSubscriberSoNoEventIsLost(): Unit = runBlocking {
        TestTransport().use { t ->
            val (ca, cb) = pairedChannels(t.a, t.b)
            val a = PeerSession(t.a, ca, scope, calm)
            val b = PeerSession(t.b, cb, scope, calm)
            EventLog(b, scope)
            b.start()
            val starting = scope.async { a.start() }
            delay(200)
            assertFalse(starting.isCompleted) // sin suscriptor, start() no vuelve
            val delivered = CompletableDeferred<SessionEvent>()
            // Recolector lanzado de forma normal (no UNDISPATCHED) y después start()
            scope.launch { a.events.collect { if (it is SessionEvent.Delivered) delivered.complete(it) } }
            withTimeout(5_000) { starting.await() }
            val id = a.sendText("hola")
            assertEquals(SessionEvent.Delivered(id), withTimeout(5_000) { delivered.await() })
        }
    }

    @Test
    fun unpairReceivedDuringCloseLingerIsReported(): Unit = runBlocking {
        TestTransport().use { t ->
            val (ca, cb) = pairedChannels(t.a, t.b)
            val b = PeerSession(t.b, cb, scope, calm.copy(linger = 3.seconds))
            val log = EventLog(b, scope)
            b.start()
            val closing = scope.launch { b.close() }
            assertEquals(AppMessage.Bye, AppCodec.decode(ca.decrypt(t.a.receive())))
            assertFalse(b.isOpen.value) // isOpen cae en cuanto empieza el cierre, no tras la espera
            t.a.send(ca.msg(AppMessage.Unpair)) // B está esperando a que A cierre
            log.await { it is SessionEvent.Closed }
            assertEquals(listOf(SessionEvent.PeerUnpaired, SessionEvent.Closed(CloseReason.LOCAL)), log.all)
            withTimeout(1_000) { closing.join() }
        }
    }

    @Test
    fun simultaneousClosesDoNotWaitForLinger(): Unit = runBlocking {
        val slow = calm.copy(linger = 2.seconds)
        val p = peers(timingsA = slow, timingsB = slow)
        p.a.start(); p.b.start()
        val t0 = TimeSource.Monotonic.markNow()
        listOf(scope.async { p.a.close() }, scope.async { p.b.close() }).awaitAll()
        assertTrue(t0.elapsedNow() < 1.5.seconds, "tardó ${t0.elapsedNow()}")
        p.logA.await { it is SessionEvent.Closed }
        p.logB.await { it is SessionEvent.Closed }
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
        assertTrue(p.a.sendUnpair()) // la trama Unpair se escribió
        p.logB.await { it is SessionEvent.Closed }
        assertEquals(listOf(SessionEvent.PeerUnpaired, SessionEvent.Closed(CloseReason.PEER_UNPAIRED)), p.logB.all)
        assertEquals(listOf<SessionEvent>(SessionEvent.Closed(CloseReason.LOCAL)), listOf(p.logA.await { it is SessionEvent.Closed }))
        assertFalse(p.a.isOpen.value || p.b.isOpen.value)
    }

    @Test
    fun unpairOnClosedSessionReturnsFalse(): Unit = runBlocking {
        val p = peers()
        p.a.start(); p.b.start()
        p.a.close()
        assertFalse(p.a.sendUnpair())
        p.logB.await { it is SessionEvent.Closed }
        assertFalse(p.logB.all.contains(SessionEvent.PeerUnpaired))
    }

    /** Escrituras que fallan con IOException a partir de [broken] (p. ej. conexión rota). */
    private class BreakableOutputStream(private val delegate: OutputStream) : OutputStream() {
        @Volatile var broken = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (broken) throw java.io.IOException("Conexión rota")
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    @Test
    fun unpairWriteFailureReturnsFalse(): Unit = runBlocking {
        val out = BreakableOutputStream(transport.rawA.getOutputStream())
        val streamA = FrameStream(transport.rawA.getInputStream(), out, transport.rawA)
        val p = peers(streamA = streamA)
        p.a.start(); p.b.start()
        out.broken = true
        assertFalse(p.a.sendUnpair())
        assertEquals(SessionEvent.Closed(CloseReason.LOCAL), p.logA.await { it is SessionEvent.Closed })
        assertFalse(p.logB.all.contains(SessionEvent.PeerUnpaired))
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
        assertThrows<IllegalArgumentException> { p.a.sendText("a".repeat(8_001)) }
        assertThrows<IllegalArgumentException> { p.a.sendPhoto(ByteArray(3_145_729)) }
        assertThrows<IllegalArgumentException> { p.a.sendPhoto(ByteArray(0)) }
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
            val b = PeerSession(t.b, cb, scope, calm)
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
            val b = PeerSession(t.b, cb, scope, calm)
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
