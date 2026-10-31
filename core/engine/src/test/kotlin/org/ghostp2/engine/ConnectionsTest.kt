package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.protocol.CloseReason
import org.ghostp2.core.protocol.SessionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ConnectionsTest {
    private val config = EngineConfig(backoffMinMillis = 50, backoffMaxMillis = 400)
    private var harness: EngineHarness? = null
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cerrar() {
        observers.cancel()
        harness?.close()
    }

    /** Dos motores arrancados y emparejados; devuelve (id de B en A, id de A en B). */
    private suspend fun pairedHarness(): Triple<EngineHarness, String, String> {
        val h = EngineHarness(2, config).also { harness = it }
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(h.engine(1).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        return Triple(h, h.contacts(0).all().single().id, h.contacts(1).all().single().id)
    }

    private suspend fun awaitPresence(engine: MessengerEngine, id: String, expected: Presence) {
        withTimeout(TIMEOUT) { engine.presence.first { it[id] == expected } }
    }

    /**
     * Espera a que ambos lados estén `ONLINE` sobre la misma conexión (iniciada por uno solo de los dos).
     * Una sustitución de sesión no cambia la presencia (`ONLINE` → `ONLINE`), así que se sondea el estado.
     */
    private suspend fun awaitSameSession(h: EngineHarness, bInA: String, aInB: String) {
        val settled = withTimeoutOrNull(TIMEOUT) {
            while (true) {
                val a = h.engine(0).sessionInitiatedByMe(bInA)
                val b = h.engine(1).sessionInitiatedByMe(aInB)
                if (h.engine(0).presence.value[bInA] == Presence.ONLINE &&
                    h.engine(1).presence.value[aInB] == Presence.ONLINE &&
                    a != null && b != null && a != b
                ) {
                    return@withTimeoutOrNull true
                }
                delay(POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        assertTrue(settled == true) {
            "sin sesión común: A=${h.engine(0).presence.value}/${h.engine(0).sessionInitiatedByMe(bInA)} " +
                "B=${h.engine(1).presence.value}/${h.engine(1).sessionInitiatedByMe(aInB)}"
        }
    }

    /** Envía un texto de A a B y espera a que B lo reciba y A reciba el acuse. */
    private suspend fun exchangeText(h: EngineHarness, bInA: String, aInB: String, text: String) {
        val received = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(1).sessionEvents.first { (id, e) -> id == aInB && e is SessionEvent.TextReceived && e.text == text }
        }
        val session = checkNotNull(h.engine(0).sessionFor(bInA)) { "A sin sesión" }
        val delivered = observers.async(start = CoroutineStart.UNDISPATCHED) {
            session.events.first { it is SessionEvent.Delivered || it is SessionEvent.Failed }
        }
        val id = session.sendText(text)
        withTimeout(TIMEOUT) {
            assertEquals(SessionEvent.Delivered(id), delivered.await())
            received.await()
        }
    }

    @Test
    fun pairedContactsConnectAutomatically() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitPresence(h.engine(0), bInA, Presence.ONLINE)
        awaitPresence(h.engine(1), aInB, Presence.ONLINE)
        awaitSameSession(h, bInA, aInB)
        assertTrue(h.engine(0).sessionFor(bInA)!!.isOpen.value)
        assertTrue(h.engine(1).sessionFor(aInB)!!.isOpen.value)
        exchangeText(h, bInA, aInB, "hola")
    }

    @Test
    fun reconnectsAfterNetworkDrop() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitSameSession(h, bInA, aInB)
        h.network.setOnline(false)
        awaitPresence(h.engine(0), bInA, Presence.OFFLINE)
        awaitPresence(h.engine(1), aInB, Presence.OFFLINE)
        assertNull(h.engine(0).sessionFor(bInA))
        // Sin red los reintentos fallan y la espera crece hasta el máximo (400 ms en la prueba).
        delay(300)
        h.network.setOnline(true)
        h.engine(0).onNetworkChanged()
        h.engine(1).onNetworkChanged()
        awaitSameSession(h, bInA, aInB)
        exchangeText(h, bInA, aInB, "de vuelta")
    }

    @Test
    fun simultaneousConnectKeepsExactlyOneSession() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        val byes = AtomicInteger()
        val closes = Collections.synchronizedList(mutableListOf<CloseReason>())
        for (i in 0..1) {
            observers.launch(start = CoroutineStart.UNDISPATCHED) {
                h.engine(i).sessionEvents.filter { it.second is SessionEvent.Closed }.collect { (_, e) ->
                    val reason = (e as SessionEvent.Closed).reason
                    closes += reason
                    if (reason == CloseReason.PEER_BYE) byes.incrementAndGet()
                }
            }
        }
        awaitSameSession(h, bInA, aInB)
        repeat(20) { round ->
            h.network.setOnline(false)
            awaitPresence(h.engine(0), bInA, Presence.OFFLINE)
            awaitPresence(h.engine(1), aInB, Presence.OFFLINE)
            h.network.setOnline(true)
            val before = listOf(h.tor(0).connectAttempts.get(), h.tor(1).connectAttempts.get())
            // Reinicio simultáneo de los dos bucles: ambos intentan conectar a la vez.
            listOf(0, 1).map { i -> async(Dispatchers.Default) { h.engine(i).onNetworkChanged() } }.awaitAll()
            awaitSameSession(h, bInA, aInB)
            for (i in 0..1) {
                assertTrue(h.tor(i).connectAttempts.get() > before[i], "ronda $round: el motor $i no llamó")
            }
            exchangeText(h, bInA, aInB, "ronda $round")
            assertEquals(0, byes.get(), "ningún Closed(PEER_BYE) (cierres vistos: $closes)")
        }
        assertEquals(0, byes.get(), "ningún Closed(PEER_BYE) (cierres vistos: $closes)")
    }

    @Test
    fun identityChangedStopsRetrying() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitSameSession(h, bInA, aInB)
        val notice = observers.async(start = CoroutineStart.UNDISPATCHED) { h.engine(0).notices.first() }
        // B deja de poder llamar a A (así solo A inicia) y cambia sus credenciales para A: al llamar,
        // A recibe msg2 con otra PSK ⇒ HandshakeFailedException(AUTH) en el iniciador.
        h.tor(1).removeClientAuth(h.engine(0).myOnion.value!!)
        val stored = h.contacts(1).all().single()
        h.contacts(1).upsert(stored.copy(pairSecret = Primitives.randomBytes(32)))
        h.network.setOnline(false)
        awaitPresence(h.engine(0), bInA, Presence.OFFLINE)
        h.network.setOnline(true)
        h.engine(0).onNetworkChanged()
        awaitPresence(h.engine(0), bInA, Presence.IDENTITY_CHANGED)
        assertEquals(ContactStatus.IDENTITY_CHANGED, h.contacts(0).all().single().status)
        assertEquals(Notice.IdentityChanged(bInA), withTimeout(TIMEOUT) { notice.await() })
        assertNull(h.engine(0).sessionFor(bInA))
        // No vuelve a intentarlo: ni por la espera (máx. 400 ms) ni al cambiar la red.
        val attempts = h.tor(0).connectAttempts.get()
        h.engine(0).onNetworkChanged()
        delay(900)
        assertEquals(attempts, h.tor(0).connectAttempts.get())
        assertEquals(Presence.IDENTITY_CHANGED, h.engine(0).presence.value[bInA])
    }

    @Test
    fun repairingAfterIdentityChangedRestartsTheLoop() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitSameSession(h, bInA, aInB)
        val onionA = h.engine(0).myOnion.value!!
        val bStored = h.contacts(1).all().single()
        // Igual que identityChangedStopsRetrying: A marca a B como IDENTITY_CHANGED.
        h.tor(1).removeClientAuth(onionA)
        h.contacts(1).upsert(bStored.copy(pairSecret = Primitives.randomBytes(32)))
        h.network.setOnline(false)
        awaitPresence(h.engine(0), bInA, Presence.OFFLINE)
        h.network.setOnline(true)
        h.engine(0).onNetworkChanged()
        awaitPresence(h.engine(0), bInA, Presence.IDENTITY_CHANGED)
        // «Volver a emparejar»: credenciales coherentes otra vez y contacto ACTIVE ⇒ bucle nuevo.
        h.contacts(1).upsert(bStored)
        h.tor(1).addClientAuth(onionA, bStored.clientAuthPrivateForPeerOnion)
        h.contacts(0).upsert(h.contacts(0).all().single().copy(status = ContactStatus.ACTIVE))
        awaitSameSession(h, bInA, aInB)
        exchangeText(h, bInA, aInB, "otra vez")
    }

    @Test
    fun deletingContactStopsItsLoopAndClosesItsSession() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitSameSession(h, bInA, aInB)
        val bClosed = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(1).sessionEvents.first { (id, e) -> id == aInB && e is SessionEvent.Closed }
        }
        h.contacts(0).delete(bInA)
        withTimeout(TIMEOUT) { h.engine(0).presence.first { !it.containsKey(bInA) } }
        // Se cierra con Bye (Task 7 enviará antes Unpair).
        assertEquals(SessionEvent.Closed(CloseReason.PEER_BYE), withTimeout(TIMEOUT) { bClosed.await() }.second)
        assertNull(h.engine(0).sessionFor(bInA))
        awaitPresence(h.engine(1), aInB, Presence.OFFLINE)
        // Sin bucle: A no vuelve a llamar a B (ni por espera ni al cambiar la red).
        val attempts = h.tor(0).connectAttempts.get()
        h.engine(0).onNetworkChanged()
        delay(900)
        assertEquals(attempts, h.tor(0).connectAttempts.get())
        assertNull(h.engine(0).presence.value[bInA])
    }

    @Test
    fun stoppedPeerIsOffline() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedHarness()
        awaitSameSession(h, bInA, aInB)
        h.engine(1).stop()
        withTimeout(2_000) { h.engine(0).presence.first { it[bInA] == Presence.OFFLINE } }
        assertNull(h.engine(0).sessionFor(bInA))
        // El motor detenido no deja presencia ni sesiones.
        assertEquals(emptyMap<String, Presence>(), h.engine(1).presence.value)
        assertNull(h.engine(1).sessionFor(aInB))
    }

    @Test
    fun contactAddedWhileRunningGetsItsOwnLoop() = runBlocking<Unit> {
        // Tres motores: A empareja con B y después con C; cada contacto conecta por separado.
        val h = EngineHarness(3, config).also { harness = it }
        h.startAll()
        for (peer in 1..2) {
            val handle = h.engine(0).createInvitation()
            val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
            assertTrue(h.engine(peer).acceptInvitation(waiting.text) is AcceptResult.Paired)
            withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        }
        val ids = h.contacts(0).all().map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        ids.forEach { awaitPresence(h.engine(0), it, Presence.ONLINE) }
        awaitPresence(h.engine(1), h.contacts(1).all().single().id, Presence.ONLINE)
        awaitPresence(h.engine(2), h.contacts(2).all().single().id, Presence.ONLINE)
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL_MILLIS = 5L
    }
}
