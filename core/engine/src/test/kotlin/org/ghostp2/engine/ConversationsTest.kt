package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.protocol.CloseReason
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.core.protocol.SessionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ConversationsTest {
    private val config = EngineConfig(backoffMinMillis = 50, backoffMaxMillis = 400)
    private var harness: EngineHarness? = null
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cerrar() {
        observers.cancel()
        harness?.close()
    }

    // --- Ayudas (como en ConnectionsTest) ---

    /** Dos motores arrancados, emparejados y con sesión común; devuelve (id de B en A, id de A en B). */
    private suspend fun pairedOnline(): Triple<EngineHarness, String, String> {
        val h = EngineHarness(2, config).also { harness = it }
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(h.engine(1).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        val bInA = h.contacts(0).all().single().id
        val aInB = h.contacts(1).all().single().id
        awaitSameSession(h, bInA, aInB)
        return Triple(h, bInA, aInB)
    }

    /** Ambos lados con sesión arrancada sobre la misma conexión (ver ConnectionsTest). */
    private suspend fun awaitSameSession(h: EngineHarness, bInA: String, aInB: String) {
        val settled = withTimeoutOrNull(TIMEOUT) {
            while (true) {
                val a = h.engine(0).sessionInitiatedByMe(bInA)
                val b = h.engine(1).sessionInitiatedByMe(aInB)
                if (a != null && b != null && a != b) return@withTimeoutOrNull true
                delay(POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        assertTrue(settled == true) { "sin sesión común" }
    }

    private suspend fun awaitPresence(engine: MessengerEngine, id: String, expected: Presence) {
        withTimeout(TIMEOUT) { engine.presence.first { it[id] == expected } }
    }

    private fun item(engine: MessengerEngine, contactId: String, id: String): ChatItem? =
        engine.conversations.value[contactId]?.firstOrNull { it.id == id }

    private suspend fun awaitStatus(engine: MessengerEngine, contactId: String, id: String, expected: DeliveryStatus): ChatItem =
        withTimeout(TIMEOUT) {
            engine.conversations.first { map -> map[contactId]?.any { it.id == id && it.status == expected } == true }
        }.getValue(contactId).first { it.id == id }

    private suspend fun awaitIncoming(engine: MessengerEngine, contactId: String, id: String): ChatItem =
        withTimeout(TIMEOUT) {
            engine.conversations.first { map -> map[contactId]?.any { it.id == id && it.direction == Direction.IN } == true }
        }.getValue(contactId).first { it.id == id && it.direction == Direction.IN }

    /** Empareja `invitee` con `inviter`; devuelve (id del invitado en el invitador, id del invitador en el invitado). */
    private suspend fun pair(h: EngineHarness, inviter: Int, invitee: Int): Pair<String, String> {
        val before = h.contacts(inviter).all().map { it.id }.toSet()
        val handle = h.engine(inviter).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(h.engine(invitee).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        val inviteeId = h.contacts(inviter).all().map { it.id }.single { it !in before }
        return inviteeId to h.contacts(invitee).all().single().id
    }

    /** Motor 0 emparejado con los motores 1..n-1 y toda la red caída (contactos reales, sin conexión). */
    private suspend fun pairedOffline(n: Int): Pair<EngineHarness, List<String>> {
        val h = EngineHarness(n, config).also { harness = it }
        h.startAll()
        val ids = (1 until n).map { pair(h, 0, it).first }
        h.network.setOnline(false)
        ids.forEach { awaitPresence(h.engine(0), it, Presence.OFFLINE) }
        return h to ids
    }

    // --- Pruebas del motor ---

    @Test
    fun textRoundTripUpdatesStatuses() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val notice = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(1).notices.filterIsInstance<Notice.MessageArrived>().first()
        }
        val id = h.engine(0).sendText(bInA, "hola")
        val sent = item(h.engine(0), bInA, id)!!
        assertEquals(Direction.OUT, sent.direction)
        assertEquals(ChatContent.Text("hola"), sent.content)
        assertTrue(sent.status is DeliveryStatus.Sending || sent.status == DeliveryStatus.Delivered)
        awaitStatus(h.engine(0), bInA, id, DeliveryStatus.Delivered)
        val got = awaitIncoming(h.engine(1), aInB, id)
        assertEquals(ChatContent.Text("hola"), got.content)
        assertEquals(DeliveryStatus.Received, got.status)
        assertEquals(Notice.MessageArrived(aInB), withTimeout(TIMEOUT) { notice.await() })
        // Solo un elemento por lado.
        assertEquals(1, h.engine(0).conversations.value.getValue(bInA).size)
        assertEquals(1, h.engine(1).conversations.value.getValue(aInB).size)
    }

    @Test
    fun photoRoundTripWithProgress() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val jpeg = Primitives.randomBytes(200_000)
        val original = jpeg.copyOf()
        val seen = Collections.synchronizedList(mutableListOf<DeliveryStatus>())
        val idHolder = java.util.concurrent.atomic.AtomicReference<String?>(null)
        observers.launch(start = CoroutineStart.UNDISPATCHED) {
            h.engine(0).conversations.collect { map ->
                val id = idHolder.get() ?: return@collect
                map[bInA]?.firstOrNull { it.id == id }?.let { s -> if (seen.lastOrNull() != s.status) seen += s.status }
            }
        }
        val id = h.engine(0).sendPhoto(bInA, jpeg)
        idHolder.set(id)
        // Copia defensiva: cambiar el array del llamante no altera lo enviado ni el historial.
        jpeg.fill(0)
        awaitStatus(h.engine(0), bInA, id, DeliveryStatus.Delivered)
        val got = awaitIncoming(h.engine(1), aInB, id)
        assertArrayEquals(original, (got.content as ChatContent.Photo).jpeg)
        assertEquals(DeliveryStatus.Received, got.status)
        assertArrayEquals(original, (item(h.engine(0), bInA, id)!!.content as ChatContent.Photo).jpeg)
        withTimeout(TIMEOUT) { while (seen.lastOrNull() != DeliveryStatus.Delivered) delay(POLL_MILLIS) }
        val snapshot = seen.toList()
        val sending = snapshot.dropLast(1).map { it as DeliveryStatus.Sending }
        assertTrue(sending.isNotEmpty()) { "A no vio ningún Sending: $snapshot" }
        assertTrue(sending.all { it.totalBytes == 200_000 })
        assertTrue(sending.zipWithNext().all { (a, b) -> b.sentBytes > a.sentBytes }) { "Sending no creciente: $snapshot" }
        assertEquals(DeliveryStatus.Delivered, snapshot.last())
    }

    @Test
    fun sendingWhileOfflineIsDiscardedImmediately() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        h.network.setOnline(false)
        awaitPresence(h.engine(0), bInA, Presence.OFFLINE)
        assertNull(h.engine(0).sessionFor(bInA))
        val lost = h.engine(0).sendText(bInA, "perdido")
        // Inmediato: ya está en el historial como fallido al volver la llamada.
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), item(h.engine(0), bInA, lost)!!.status)
        val lostPhoto = h.engine(0).sendPhoto(bInA, byteArrayOf(1, 2, 3))
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), item(h.engine(0), bInA, lostPhoto)!!.status)
        h.network.setOnline(true)
        h.engine(0).onNetworkChanged()
        h.engine(1).onNetworkChanged()
        awaitSameSession(h, bInA, aInB)
        val after = h.engine(0).sendText(bInA, "después")
        awaitStatus(h.engine(0), bInA, after, DeliveryStatus.Delivered)
        awaitIncoming(h.engine(1), aInB, after)
        // Nunca se reintenta: B solo tiene el mensaje enviado con conexión y A sigue en Failed.
        delay(200)
        assertEquals(listOf(after), h.engine(1).conversations.value.getValue(aInB).map { it.id })
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), item(h.engine(0), bInA, lost)!!.status)
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), item(h.engine(0), bInA, lostPhoto)!!.status)
    }

    @Test
    fun historySurvivesReconnect() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val first = h.engine(0).sendText(bInA, "uno")
        awaitStatus(h.engine(0), bInA, first, DeliveryStatus.Delivered)
        awaitIncoming(h.engine(1), aInB, first)
        h.network.setOnline(false)
        awaitPresence(h.engine(0), bInA, Presence.OFFLINE)
        awaitPresence(h.engine(1), aInB, Presence.OFFLINE)
        assertEquals(listOf(first), h.engine(0).conversations.value.getValue(bInA).map { it.id })
        assertEquals(listOf(first), h.engine(1).conversations.value.getValue(aInB).map { it.id })
        h.network.setOnline(true)
        h.engine(0).onNetworkChanged()
        h.engine(1).onNetworkChanged()
        awaitSameSession(h, bInA, aInB)
        val second = h.engine(1).sendText(aInB, "dos")
        awaitStatus(h.engine(1), aInB, second, DeliveryStatus.Delivered)
        awaitIncoming(h.engine(0), bInA, second)
        assertEquals(listOf(first, second), h.engine(0).conversations.value.getValue(bInA).map { it.id })
        assertEquals(listOf(Direction.OUT, Direction.IN), h.engine(0).conversations.value.getValue(bInA).map { it.direction })
        assertEquals(listOf(first, second), h.engine(1).conversations.value.getValue(aInB).map { it.id })
        assertEquals(DeliveryStatus.Delivered, item(h.engine(0), bInA, first)!!.status)
    }

    @Test
    fun clearConversationEmptiesOnlyThatContact() = runBlocking<Unit> {
        val h = EngineHarness(3, config).also { harness = it }
        h.startAll()
        val (c1, aInC1) = pair(h, 0, 1)
        val (c2, _) = pair(h, 0, 2)
        awaitPresence(h.engine(0), c1, Presence.ONLINE)
        awaitPresence(h.engine(1), aInC1, Presence.ONLINE)
        awaitSameSession(h, c1, aInC1)
        // Una foto recibida de verdad en la conversación que se borrará.
        val incoming = h.engine(1).sendPhoto(aInC1, Primitives.randomBytes(40_000))
        val received = (awaitIncoming(h.engine(0), c1, incoming).content as ChatContent.Photo).jpeg
        h.network.setOnline(false)
        awaitPresence(h.engine(0), c1, Presence.OFFLINE)
        awaitPresence(h.engine(0), c2, Presence.OFFLINE)
        val engine = h.engine(0)
        val photo = byteArrayOf(9, 9, 9)
        val p = engine.sendPhoto(c1, photo)
        engine.sendText(c1, "a c1")
        val t2 = engine.sendText(c2, "a c2")
        val held = (item(engine, c1, p)!!.content as ChatContent.Photo).jpeg
        engine.clearConversation(c1)
        assertNull(engine.conversations.value[c1])
        assertEquals(listOf(t2), engine.conversations.value.getValue(c2).map { it.id })
        assertTrue(held.all { it == 0.toByte() }) { "la foto enviada borrada no se rellenó con ceros" }
        assertTrue(received.all { it == 0.toByte() }) { "la foto recibida borrada no se rellenó con ceros" }
        assertArrayEquals(byteArrayOf(9, 9, 9), photo) // el array del llamante no se toca
        engine.clearConversation("desconocido") // sin efecto
        assertEquals(setOf(c2), engine.conversations.value.keys)
    }

    @Test
    fun sendToUnknownContactStoresNothing() = runBlocking<Unit> {
        val (h, ids) = pairedOffline(2)
        val engine = h.engine(0)
        val photo = byteArrayOf(4, 4)
        val t = engine.sendText("desconocido", "hola")
        val p = engine.sendPhoto("desconocido", photo)
        assertTrue(Regex("[0-9a-f]{24}").matches(t))
        assertTrue(Regex("[0-9a-f]{24}").matches(p))
        assertTrue(engine.conversations.value.isEmpty()) { "conversación fantasma: ${engine.conversations.value.keys}" }
        assertArrayEquals(byteArrayOf(4, 4), photo) // la copia es la que se borra, no el array del llamante
        // Al contacto real sí se guarda (como fallido: no hay red).
        val ok = engine.sendText(ids.single(), "hola")
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), item(engine, ids.single(), ok)!!.status)
        assertEquals(setOf(ids.single()), engine.conversations.value.keys)
    }

    @Test
    fun sendWhileTorIsStartingIsRecordedAsNotConnected() = runBlocking<Unit> {
        val network = FakeTorNetwork()
        val tor = StuckTor(FakeTor(network))
        val contact = Contact(
            id = "c", name = "C", peerIdentity = ByteArray(32), peerOnion = "x".repeat(56),
            clientAuthPrivateForPeerOnion = ByteArray(32), myClientAuthPublic = ByteArray(32),
            pairSecret = ByteArray(32), verified = false, status = ContactStatus.ACTIVE,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = MessengerEngine(tor, InMemoryIdentityRepository(), InMemoryContactRepository(listOf(contact)), scope, config)
            val starting = scope.launch { engine.start() }
            withTimeout(TIMEOUT) { tor.state.first { it is TorState.Starting } }
            assertTrue(engine.status.value is EngineStatus.StartingTor)
            val id = engine.sendText("c", "pronto")
            assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), item(engine, "c", id)!!.status)
            engine.sendText("otro", "no") // desconocido: nada
            assertEquals(setOf("c"), engine.conversations.value.keys)
            engine.stop()
            starting.join()
            assertTrue(engine.conversations.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopDuringPhotoSendWipesHistoryAndNoPartialPhotoArrives() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val jpeg = Primitives.randomBytes(PeerSession.MAX_PHOTO_BYTES)
        val original = jpeg.copyOf()
        val id = h.engine(0).sendPhoto(bInA, jpeg)
        // En pleno envío: ya salió algún trozo, pero aún no se ha entregado.
        withTimeout(TIMEOUT) {
            h.engine(0).conversations.first { map ->
                val s = map[bInA]?.firstOrNull { it.id == id }?.status
                s is DeliveryStatus.Sending && s.sentBytes > 0 || s !is DeliveryStatus.Sending
            }
        }
        h.engine(0).stop()
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        assertTrue(h.engine(0).conversations.value.isEmpty())
        assertArrayEquals(original, jpeg)
        awaitPresence(h.engine(1), aInB, Presence.OFFLINE)
        delay(200)
        // B nunca muestra una foto incompleta o alterada: o no llegó, o llegó entera e idéntica.
        h.engine(1).conversations.value[aInB].orEmpty().forEach { got ->
            assertEquals(id, got.id)
            assertEquals(DeliveryStatus.Received, got.status)
            assertArrayEquals(original, (got.content as ChatContent.Photo).jpeg)
        }
    }

    @Test
    fun stopWipesHistory() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val jpeg = Primitives.randomBytes(50_000)
        val original = jpeg.copyOf()
        val id = h.engine(0).sendPhoto(bInA, jpeg)
        awaitStatus(h.engine(0), bInA, id, DeliveryStatus.Delivered)
        val received = (awaitIncoming(h.engine(1), aInB, id).content as ChatContent.Photo).jpeg
        val sentHeld = (item(h.engine(0), bInA, id)!!.content as ChatContent.Photo).jpeg
        h.engine(0).sendText(bInA, "texto")
        h.engine(0).stop()
        h.engine(1).stop()
        assertTrue(h.engine(0).conversations.value.isEmpty())
        assertTrue(h.engine(1).conversations.value.isEmpty())
        assertTrue(sentHeld.all { it == 0.toByte() }) { "foto enviada sin borrar" }
        assertTrue(received.all { it == 0.toByte() }) { "foto recibida sin borrar" }
        assertArrayEquals(original, jpeg) // el array del llamante es suyo: no se borra
        // Con el motor parado no se guarda nada (ni siquiera como fallido).
        h.engine(0).sendText(bInA, "parado")
        assertTrue(h.engine(0).conversations.value.isEmpty())
        // Tras volver a arrancar el historial empieza vacío.
        h.engine(0).start()
        assertTrue(h.engine(0).conversations.value.isEmpty())
    }

    @Test
    fun conversationKeepsAtMost500Items() = runBlocking<Unit> {
        val (h, contacts) = pairedOffline(2)
        val engine = h.engine(0)
        val c = contacts.single()
        val ids = (0 until 510).map { engine.sendText(c, "m$it") }
        val list = engine.conversations.value.getValue(c)
        assertEquals(500, list.size)
        assertEquals(ids.drop(10), list.map { it.id }) // se descartan los 10 más antiguos, en orden
    }

    @Test
    fun invalidInputThrowsAndStoresNothing() = runBlocking<Unit> {
        val (h, bInA, _) = pairedOnline()
        val tooLong = "x".repeat(PeerSession.MAX_TEXT_CHARS + 1)
        assertThrows<IllegalArgumentException> { h.engine(0).sendText(bInA, tooLong) }
        assertThrows<IllegalArgumentException> { h.engine(0).sendPhoto(bInA, ByteArray(0)) }
        assertThrows<IllegalArgumentException> { h.engine(0).sendPhoto(bInA, ByteArray(PeerSession.MAX_PHOTO_BYTES + 1)) }
        // También sin conexión: la validación no depende de la sesión.
        assertThrows<IllegalArgumentException> { h.engine(0).sendText("sin-sesion", tooLong) }
        assertNull(h.engine(0).conversations.value[bInA])
        assertNull(h.engine(0).conversations.value["sin-sesion"])
        // En el límite sí se acepta.
        val ok = h.engine(0).sendText(bInA, "y".repeat(PeerSession.MAX_TEXT_CHARS))
        awaitStatus(h.engine(0), bInA, ok, DeliveryStatus.Delivered)
    }

    @Test
    fun concurrentSendsBothWaysAreAllRecorded() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val n = 40
        val fromA = (0 until n).map { i -> async(Dispatchers.Default) { h.engine(0).sendText(bInA, "a$i") } }
        val fromB = (0 until n).map { i -> async(Dispatchers.Default) { h.engine(1).sendText(aInB, "b$i") } }
        val idsA = fromA.map { it.await() }
        val idsB = fromB.map { it.await() }
        for ((engine, contact, mine, theirs) in listOf(
            Quad(h.engine(0), bInA, idsA, idsB),
            Quad(h.engine(1), aInB, idsB, idsA),
        )) {
            withTimeout(TIMEOUT) {
                engine.conversations.first { map ->
                    val list = map[contact].orEmpty()
                    list.size == 2 * n &&
                        list.filter { it.direction == Direction.OUT }.all { it.status == DeliveryStatus.Delivered }
                }
            }
            val list = engine.conversations.value.getValue(contact)
            assertEquals(mine.toSet(), list.filter { it.direction == Direction.OUT }.map { it.id }.toSet())
            assertEquals(theirs.toSet(), list.filter { it.direction == Direction.IN }.map { it.id }.toSet())
        }
    }

    private data class Quad(val engine: MessengerEngine, val contact: String, val mine: List<String>, val theirs: List<String>)

    // --- Pruebas de Conversations (sin red) ---

    private val token = Any()

    private fun open(clock: () -> Long = { 0L }, max: Int = 500) = Conversations(clock, max).also { it.open() }

    private fun outbound(session: Any = token, open: Boolean = true, id: () -> String) =
        Conversations.Outbound(session, { open }, id)

    /** En las pruebas unitarias todos los ids de contacto se dan por conocidos salvo que se diga. */
    private fun Conversations.addOutgoing(contactId: String, content: ChatContent, outbound: Conversations.Outbound?) =
        addOutgoing(contactId, content, { true }, outbound)

    @Test
    fun unknownContactStoresNothingAndNeverCallsTheSession() {
        val c = open()
        var called = false
        val photo = byteArrayOf(6, 6)
        val id = c.addOutgoing("c", ChatContent.Photo(photo), { false }, outbound { called = true; "x" })
        assertFalse(called)
        assertTrue(Regex("[0-9a-f]{24}").matches(id))
        assertTrue(photo.all { it == 0.toByte() })
        assertTrue(c.conversations.value.isEmpty())
    }

    private fun Conversations.status(contactId: String, id: String): DeliveryStatus? =
        conversations.value[contactId]?.firstOrNull { it.id == id }?.status

    @Test
    fun eventForIdIsNeverLostEvenIfItArrivesWhileSendIsReturning() {
        val c = open()
        val id = "a".repeat(24)
        val applierStarted = CountDownLatch(1)
        var applier: Thread? = null
        c.addOutgoing("c", ChatContent.Text("hola"), outbound {
            // El evento del id llega desde otro hilo ANTES de que la llamada termine de guardarlo.
            applier = Thread {
                applierStarted.countDown()
                c.onEvent("c", token, SessionEvent.Delivered(id))
            }.also { it.start() }
            assertTrue(applierStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(50) // da tiempo al otro hilo a intentar aplicar el evento
            id
        })
        applier!!.join(5_000)
        assertEquals(DeliveryStatus.Delivered, c.status("c", id))
    }

    @Test
    fun statusOnlyMovesForward() {
        val c = open()
        c.addOutgoing("c", ChatContent.Photo(ByteArray(100_000)), outbound { "p1" })
        assertEquals(DeliveryStatus.Sending(0, 100_000), c.status("c", "p1"))
        c.onEvent("c", token, SessionEvent.PhotoProgress("p1", 65_536, 100_000))
        c.onEvent("c", token, SessionEvent.PhotoProgress("p1", 32_768, 100_000)) // tardío: se ignora
        assertEquals(DeliveryStatus.Sending(65_536, 100_000), c.status("c", "p1"))
        c.onEvent("c", token, SessionEvent.Delivered("p1"))
        c.onEvent("c", token, SessionEvent.PhotoProgress("p1", 100_000, 100_000))
        c.onEvent("c", token, SessionEvent.Failed("p1", FailReason.NO_ACK))
        assertEquals(DeliveryStatus.Delivered, c.status("c", "p1"))

        c.addOutgoing("c", ChatContent.Text("t"), outbound { "t1" })
        c.onEvent("c", token, SessionEvent.Failed("t1", FailReason.NO_ACK))
        c.onEvent("c", token, SessionEvent.Delivered("t1"))
        assertEquals(DeliveryStatus.Failed(FailReason.NO_ACK), c.status("c", "t1"))
        // Los eventos de otra sesión no tocan el envío.
        c.addOutgoing("c", ChatContent.Text("u"), outbound { "u1" })
        c.onEvent("c", Any(), SessionEvent.Delivered("u1"))
        assertEquals(DeliveryStatus.Sending(0, 0), c.status("c", "u1"))
    }

    @Test
    fun sendRejectedByClosingSessionFailsWhenItCloses() {
        val c = open()
        val other = Any()
        // La sesión parecía abierta pero ya se estaba cerrando: PeerSession devolvió el id y su
        // Failed(NOT_CONNECTED) puede llegar después de Closed (cuando ya nadie recolecta).
        c.addOutgoing("c", ChatContent.Text("x"), outbound { "r1" })
        c.addOutgoing("c", ChatContent.Text("y"), outbound(session = other) { "r2" })
        c.onEvent("c", token, SessionEvent.Closed(CloseReason.IO))
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), c.status("c", "r1"))
        assertEquals(DeliveryStatus.Sending(0, 0), c.status("c", "r2")) // otra sesión: sigue
        // Con la sesión ya cerrada, ni se llama a send: fallo inmediato con un id propio.
        var called = false
        val id = c.addOutgoing("c", ChatContent.Text("z"), outbound(open = false) { called = true; "nunca" })
        assertFalse(called)
        assertEquals(DeliveryStatus.Failed(FailReason.NOT_CONNECTED), c.status("c", id))
        assertTrue(Regex("[0-9a-f]{24}").matches(id))
    }

    @Test
    fun incomingMessagesAreReceivedAndTimestampedByTheClock() {
        val now = AtomicLong(1_000)
        val c = open(clock = { now.get() })
        assertTrue(c.onEvent("c", token, SessionEvent.TextReceived("i1", "hola")))
        now.set(2_000)
        assertTrue(c.onEvent("c", token, SessionEvent.PhotoReceived("i2", byteArrayOf(1))))
        now.set(3_000)
        c.addOutgoing("c", ChatContent.Text("o"), null)
        assertFalse(c.onEvent("c", token, SessionEvent.PeerUnpaired))
        val list = c.conversations.value.getValue("c")
        assertEquals(listOf(1_000L, 2_000L, 3_000L), list.map { it.timestampMillis })
        assertEquals(listOf(DeliveryStatus.Received, DeliveryStatus.Received, DeliveryStatus.Failed(FailReason.NOT_CONNECTED)), list.map { it.status })
        assertEquals(listOf(Direction.IN, Direction.IN, Direction.OUT), list.map { it.direction })
    }

    @Test
    fun evictedPhotoIsWipedUnlessStillBeingSent() {
        val c = open(max = 2)
        val sendingPhoto = byteArrayOf(1, 1)
        val receivedPhoto = byteArrayOf(2, 2)
        c.addOutgoing("c", ChatContent.Photo(sendingPhoto), outbound { "p1" })
        c.onEvent("c", token, SessionEvent.PhotoReceived("i1", receivedPhoto))
        c.onEvent("c", token, SessionEvent.TextReceived("i2", "a")) // expulsa p1 (aún enviándose)
        c.onEvent("c", token, SessionEvent.TextReceived("i3", "b")) // expulsa i1
        assertEquals(listOf("i2", "i3"), c.conversations.value.getValue("c").map { it.id })
        assertTrue(receivedPhoto.all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 1), sendingPhoto) // PeerSession aún la lee
        c.onEvent("c", token, SessionEvent.PhotoProgress("p1", 2, 2))
        assertArrayEquals(byteArrayOf(1, 1), sendingPhoto)
        c.onEvent("c", token, SessionEvent.Delivered("p1")) // fin del envío: ahora sí se borra
        assertTrue(sendingPhoto.all { it == 0.toByte() })
    }

    @Test
    fun clearingAPhotoInFlightWipesItWhenTheSendEnds() {
        val c = open()
        val photo = byteArrayOf(5, 5)
        c.addOutgoing("c", ChatContent.Photo(photo), outbound { "p1" })
        c.clear("c")
        assertNull(c.conversations.value["c"])
        assertArrayEquals(byteArrayOf(5, 5), photo)
        c.onEvent("c", token, SessionEvent.Failed("p1", FailReason.CONNECTION_LOST))
        assertTrue(photo.all { it == 0.toByte() })
        assertNull(c.conversations.value["c"]) // el evento tardío no resucita la conversación
    }

    @Test
    fun clearAllWipesEverythingAndStopsRecording() {
        val c = open()
        val inFlight = byteArrayOf(7)
        val received = byteArrayOf(8)
        c.addOutgoing("c", ChatContent.Photo(inFlight), outbound { "p1" })
        c.onEvent("d", token, SessionEvent.PhotoReceived("i1", received))
        c.clear("c") // p1 queda pendiente de borrar al terminar su envío
        c.clearAll()
        assertTrue(c.conversations.value.isEmpty())
        assertTrue(inFlight.all { it == 0.toByte() })
        assertTrue(received.all { it == 0.toByte() })
        // Cerrado: nada se guarda; las fotos que llegan se borran.
        val late = byteArrayOf(3)
        assertFalse(c.onEvent("d", token, SessionEvent.PhotoReceived("i2", late)))
        assertTrue(late.all { it == 0.toByte() })
        var called = false
        val outgoing = byteArrayOf(4)
        c.addOutgoing("d", ChatContent.Photo(outgoing), outbound { called = true; "x" })
        assertFalse(called)
        assertTrue(outgoing.all { it == 0.toByte() })
        assertTrue(c.conversations.value.isEmpty())
    }

    @Test
    fun concurrentUpdatesAreNotLost() {
        val c = open(max = 10_000)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(500) { i ->
                    if (t % 2 == 0) {
                        val id = "t$t-$i"
                        c.addOutgoing("c", ChatContent.Text("x"), outbound { id })
                        c.onEvent("c", token, SessionEvent.Delivered(id))
                    } else {
                        c.onEvent("c", token, SessionEvent.TextReceived("r$t-$i", "y"))
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(10_000) }
        val list = c.conversations.value.getValue("c")
        assertEquals(4_000, list.size)
        assertTrue(list.filter { it.direction == Direction.OUT }.all { it.status == DeliveryStatus.Delivered })
    }

    /** Tor que se queda arrancando: `start()` solo pone `Starting(0)`; lo demás se delega. */
    private class StuckTor(private val delegate: FakeTor) : TorTransport by delegate {
        private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<TorState>(TorState.Off)
        override val state: kotlinx.coroutines.flow.StateFlow<TorState> = mutableState

        override suspend fun start() {
            mutableState.value = TorState.Starting(0)
        }

        override suspend fun stop() {
            delegate.stop()
            mutableState.value = TorState.Off
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL_MILLIS = 5L
    }
}
