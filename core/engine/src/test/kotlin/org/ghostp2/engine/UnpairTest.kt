package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.protocol.SessionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class UnpairTest {
    private val config = EngineConfig(backoffMinMillis = 50, backoffMaxMillis = 400, unpairDeliveryWindowMillis = 5_000)
    private var harness: EngineHarness? = null
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cerrar() {
        observers.cancel()
        harness?.close()
    }

    // --- Ayudas (como en ConnectionsTest/ConversationsTest) ---

    private suspend fun pairedOnline(cfg: EngineConfig = config): Triple<EngineHarness, String, String> {
        val h = EngineHarness(2, cfg).also { harness = it }
        h.startAll()
        val (bInA, aInB) = pair(h, 0, 1)
        awaitSameSession(h, bInA, aInB)
        return Triple(h, bInA, aInB)
    }

    /** Empareja `invitee` con `inviter`; devuelve (id del invitado en el invitador, id del invitador en el invitado). */
    private suspend fun pair(h: EngineHarness, inviter: Int, invitee: Int): Pair<String, String> {
        val before = h.contacts(inviter).all().map { it.id }.toSet()
        val handle = h.engine(inviter).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(h.engine(invitee).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        val inviteeId = h.contacts(inviter).all().map { it.id }.single { it !in before }
        val inviterId = contactIdFor(h.identities(inviter).load()!!.identityPublic)
        return inviteeId to inviterId
    }

    private suspend fun awaitSameSession(h: EngineHarness, bInA: String, aInB: String, i: Int = 0, j: Int = 1) {
        val settled = withTimeoutOrNull(TIMEOUT) {
            while (true) {
                val a = h.engine(i).sessionInitiatedByMe(bInA)
                val b = h.engine(j).sessionInitiatedByMe(aInB)
                if (a != null && b != null && a != b) return@withTimeoutOrNull true
                delay(POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        assertTrue(settled == true) { "sin sesión común" }
    }

    private fun unpairedNotice(engine: MessengerEngine) = observers.async(start = CoroutineStart.UNDISPATCHED) {
        engine.notices.filterIsInstance<Notice.ContactUnpairedYou>().first()
    }

    /** El motor no vuelve a llamar a nadie durante `millis` (ni al cambiar la red). */
    private suspend fun assertNoMoreCalls(h: EngineHarness, i: Int, millis: Long = 600) {
        val attempts = h.tor(i).connectAttempts.get()
        h.engine(i).onNetworkChanged()
        delay(millis)
        assertEquals(attempts, h.tor(i).connectAttempts.get()) { "el motor $i siguió llamando" }
    }

    // --- Pruebas del brief ---

    @Test
    fun deleteWithOpenSessionNotifiesPeer() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val notice = unpairedNotice(h.engine(1))
        h.engine(0).sendText(bInA, "hola")
        assertEquals(UnpairDelivery.DELIVERED, h.engine(0).deleteContact(bInA))
        // A: todo borrado (contacto, conversación, presencia, autorización y .onion sin contactos).
        assertTrue(h.contacts(0).all().isEmpty())
        assertNull(h.engine(0).conversations.value[bInA])
        assertNull(h.engine(0).presence.value[bInA])
        assertNull(h.engine(0).myOnion.value)
        // B: aviso con el nombre que tenía guardado y A borrado también.
        assertEquals(Notice.ContactUnpairedYou(aInB, "Contacto nuevo"), withTimeout(TIMEOUT) { notice.await() })
        withTimeout(TIMEOUT) { h.contacts(1).contacts.first { it.isEmpty() } }
        withTimeout(TIMEOUT) { h.engine(1).presence.first { !it.containsKey(aInB) } }
        withTimeout(TIMEOUT) { h.engine(1).myOnion.first { it == null } }
        assertNull(h.engine(1).conversations.value[aInB])
        assertTrue(h.network.published.value.isEmpty()) { "quedan servicios: ${h.network.published.value.keys}" }
        // Ninguno de los dos vuelve a llamar al otro.
        assertNoMoreCalls(h, 0)
        assertNoMoreCalls(h, 1)
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        assertEquals(EngineStatus.Online, h.engine(1).status.value)
    }

    @Test
    fun deleteWhileOfflineRetriesWithinWindow() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        h.engine(1).stop()
        withTimeout(TIMEOUT) { h.engine(0).presence.first { it[bInA] == Presence.OFFLINE } }
        val delete = async { h.engine(0).deleteContact(bInA) }
        delay(300)
        assertFalse(delete.isCompleted)
        // Mientras se intenta entregar, el contacto ya no es escribible: nada se guarda.
        h.engine(0).sendText(bInA, "durante")
        assertNull(h.engine(0).conversations.value[bInA])
        val notice = unpairedNotice(h.engine(1))
        h.engine(1).start()
        assertEquals(UnpairDelivery.DELIVERED, withTimeout(TIMEOUT) { delete.await() })
        assertTrue(h.contacts(0).all().isEmpty())
        assertEquals(aInB, withTimeout(TIMEOUT) { notice.await() }.contactId)
        withTimeout(TIMEOUT) { h.contacts(1).contacts.first { it.isEmpty() } }
        assertNoMoreCalls(h, 0)
    }

    @Test
    fun deleteWhileOfflineGivesUpAfterWindow() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline(config.copy(unpairDeliveryWindowMillis = 300))
        h.engine(1).stop()
        withTimeout(TIMEOUT) { h.engine(0).presence.first { it[bInA] == Presence.OFFLINE } }
        val started = System.nanoTime()
        assertEquals(UnpairDelivery.NOT_DELIVERED, h.engine(0).deleteContact(bInA))
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed in 250..3_000) { "ventana no respetada: $elapsed ms" }
        assertTrue(h.contacts(0).all().isEmpty())
        assertNull(h.engine(0).myOnion.value)
        assertTrue(h.tor(0).connectAttempts.get() > 0)
        assertNoMoreCalls(h, 0)
        // B vuelve: A ya no está publicada, así que B sigue teniendo a A, pero sin conexión.
        h.engine(1).start()
        assertEquals(listOf(aInB), h.contacts(1).all().map { it.id })
        delay(300)
        assertEquals(Presence.OFFLINE, h.engine(1).presence.value[aInB])
    }

    @Test
    fun deletingExposesTheContactsBeingDeleted() = runBlocking<Unit> {
        val (h, bInA, _) = pairedOnline(config.copy(unpairDeliveryWindowMillis = 1_000))
        assertEquals(emptySet<String>(), h.engine(0).deleting.value)
        h.engine(1).stop()
        withTimeout(TIMEOUT) { h.engine(0).presence.first { it[bInA] == Presence.OFFLINE } }
        val delete = observers.async { h.engine(0).deleteContact(bInA) }
        // Mientras intenta entregar `Unpair` (sin sesión, hasta 1 s) el contacto figura como "borrando".
        withTimeout(TIMEOUT) { h.engine(0).deleting.first { bInA in it } }
        assertEquals(UnpairDelivery.NOT_DELIVERED, withTimeout(TIMEOUT) { delete.await() })
        assertEquals(emptySet<String>(), h.engine(0).deleting.value)
        assertTrue(h.contacts(0).all().isEmpty())
    }

    @Test
    fun deletingLastContactUnpublishesOnion() = runBlocking<Unit> {
        val h = EngineHarness(3, config.copy(unpairDeliveryWindowMillis = 200)).also { harness = it }
        h.startAll()
        val (bInA, _) = pair(h, 0, 1)
        val (cInA, _) = pair(h, 0, 2)
        val onion = h.engine(0).myOnion.value!!
        h.engine(0).deleteContact(bInA)
        // Queda C: misma .onion, ya sin la autorización de B.
        assertEquals(onion, h.engine(0).myOnion.value)
        val service = h.network.published.value.getValue(onion)
        val cKey = h.contacts(0).all().single().myClientAuthPublic
        assertEquals(listOf(cKey.toList()), service.clientAuthPublicKeys.map { it.toList() })
        h.engine(0).deleteContact(cInA)
        assertNull(h.engine(0).myOnion.value)
        assertNull(h.network.published.value[onion])
        // La clave se conserva: con un contacto nuevo vuelve la misma dirección.
        pair(h, 0, 1)
        assertEquals(onion, h.engine(0).myOnion.value)
    }

    @Test
    fun wipeAllResetsIdentity() = runBlocking<Unit> {
        val (h, _, aInB) = pairedOnline()
        val oldIdentity = h.identities(0).load()!!
        val oldPublic = oldIdentity.identityPublic.copyOf()
        val oldOnion = h.engine(0).myOnion.value!!
        val oldContact = h.contacts(0).all().single()
        val notice = unpairedNotice(h.engine(1))
        val started = System.nanoTime()
        h.engine(0).wipeAll()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000) { "wipeAll tardó demasiado" }
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        assertNull(h.identities(0).load())
        assertTrue(h.contacts(0).all().isEmpty())
        assertNull(h.network.published.value[oldOnion])
        // Material de claves en memoria rellenado con ceros.
        assertTrue(oldIdentity.identityPrivate.all { it == 0.toByte() })
        assertTrue(oldContact.pairSecret.all { it == 0.toByte() })
        assertTrue(oldContact.clientAuthPrivateForPeerOnion.all { it == 0.toByte() })
        // B recibió el aviso (había sesión abierta).
        assertEquals(aInB, withTimeout(TIMEOUT) { notice.await() }.contactId)
        withTimeout(TIMEOUT) { h.contacts(1).contacts.first { it.isEmpty() } }
        // Siguiente arranque: identidad nueva y, al publicarse, .onion nueva.
        h.engine(0).start()
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        val newIdentity = h.identities(0).load()!!
        assertFalse(newIdentity.identityPublic.contentEquals(oldPublic))
        pair(h, 0, 1)
        val newOnion = h.engine(0).myOnion.value!!
        assertNotEquals(oldOnion, newOnion)
        assertEquals(newOnion, h.identities(0).load()!!.onion)
    }

    @Test
    fun renameAndVerifyPersist() = runBlocking<Unit> {
        val (h, bInA, _) = pairedOnline()
        val engine = h.engine(0)
        engine.renameContact(bInA, "  Beatriz  ")
        engine.setVerified(bInA, true)
        val stored = h.contacts(0).all().single()
        assertEquals("Beatriz", stored.name)
        assertTrue(stored.verified)
        engine.setVerified(bInA, false)
        assertFalse(h.contacts(0).all().single().verified)
        // Validación: vacío, solo espacios o más de 64 caracteres ⇒ IllegalArgumentException, sin cambios.
        assertThrows<IllegalArgumentException> { engine.renameContact(bInA, "   ") }
        assertThrows<IllegalArgumentException> { engine.renameContact(bInA, "x".repeat(MAX_CONTACT_NAME_CHARS + 1)) }
        engine.renameContact(bInA, "😀".repeat(MAX_CONTACT_NAME_CHARS)) // 64 caracteres (128 unidades UTF-16)
        assertEquals("😀".repeat(MAX_CONTACT_NAME_CHARS), h.contacts(0).all().single().name)
        // Id desconocido ⇒ NoSuchElementException.
        assertThrows<NoSuchElementException> { engine.renameContact("desconocido", "X") }
        assertThrows<NoSuchElementException> { engine.setVerified("desconocido", true) }
        // También con el motor parado (se guarda en el repositorio).
        engine.stop()
        engine.renameContact(bInA, "Bea")
        assertEquals("Bea", h.contacts(0).all().single().name)
        // Un contacto borrado ya no se puede renombrar (y no resucita).
        engine.start()
        engine.deleteContact(bInA)
        assertThrows<NoSuchElementException> { engine.renameContact(bInA, "Otra") }
        assertTrue(h.contacts(0).all().isEmpty())
    }

    // --- Pruebas adicionales ---

    @Test
    fun peerThatReceivesUnpairDoesNotReconnect() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        val bEvents = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(1).sessionEvents.first { (id, e) -> id == aInB && e is SessionEvent.Closed }
        }
        assertEquals(UnpairDelivery.DELIVERED, h.engine(0).deleteContact(bInA))
        // B cierra por PEER_UNPAIRED (el núcleo no responde con Bye) y deja de llamar.
        assertEquals(
            SessionEvent.Closed(org.ghostp2.core.protocol.CloseReason.PEER_UNPAIRED),
            withTimeout(TIMEOUT) { bEvents.await() }.second,
        )
        withTimeout(TIMEOUT) { h.contacts(1).contacts.first { it.isEmpty() } }
        assertNoMoreCalls(h, 1)
        assertNull(h.engine(1).sessionFor(aInB))
        // Mensajes a contactos borrados no se guardan en ningún lado.
        h.engine(0).sendText(bInA, "x")
        h.engine(1).sendText(aInB, "y")
        assertTrue(h.engine(0).conversations.value.isEmpty())
        assertTrue(h.engine(1).conversations.value.isEmpty())
    }

    @Test
    fun sendAfterForgetStoresNothingEvenWithStaleSnapshots() {
        val c = Conversations({ 0L }).also { it.open() }
        val out = Conversations.Outbound(Any(), { false }, { "x" })
        // Snapshot antiguo (anterior al alta) llega después de addKnown: el alta local no se pierde.
        c.addKnown("a")
        c.applySnapshot(emptyList())
        assertTrue(c.isKnown("a"))
        c.addOutgoing("a", ChatContent.Text("1"), out)
        assertEquals(1, c.conversations.value.getValue("a").size)
        c.applySnapshot(listOf("a")) // confirmado por el repositorio
        // Baja local: borra la conversación y ninguna instantánea atrasada la resucita.
        c.forget("a")
        assertNull(c.conversations.value["a"])
        c.applySnapshot(listOf("a"))
        assertFalse(c.isKnown("a"))
        c.addOutgoing("a", ChatContent.Text("2"), out)
        assertFalse(c.onEvent("a", Any(), SessionEvent.TextReceived("i", "3")))
        assertNull(c.conversations.value["a"])
        // Ni siquiera tras una instantánea sin el id seguida de otra vieja con él.
        c.applySnapshot(emptyList())
        c.applySnapshot(listOf("a"))
        assertFalse(c.isKnown("a"))
        // Volver a emparejar sí lo hace conocido otra vez.
        c.addKnown("a")
        c.applySnapshot(emptyList())
        assertTrue(c.isKnown("a"))
        // Un contacto no tocado localmente sigue al repositorio.
        c.applySnapshot(listOf("a", "b"))
        assertTrue(c.isKnown("b"))
        c.applySnapshot(listOf("a"))
        assertFalse(c.isKnown("b"))
    }

    @Test
    fun forgetRacingWithSnapshotsNeverResurrects() {
        // Muchas instantáneas «con a» en paralelo con forget: después de forget, nunca conocido.
        repeat(200) {
            val c = Conversations({ 0L }).also { it.open() }
            c.applySnapshot(listOf("a"))
            val t = Thread { repeat(50) { c.applySnapshot(listOf("a")) } }
            t.start()
            c.forget("a")
            val out = Conversations.Outbound(Any(), { false }, { "x" })
            c.addOutgoing("a", ChatContent.Text("tarde"), out)
            t.join(5_000)
            assertFalse(c.isKnown("a"))
            assertNull(c.conversations.value["a"])
        }
    }

    @Test
    fun deletingLastContactDuringPairingKeepsOnionPublished() = runBlocking<Unit> {
        val h = EngineHarness(3, config.copy(unpairDeliveryWindowMillis = 200)).also { harness = it }
        h.startAll()
        val (bInA, _) = pair(h, 0, 1)
        val onion = h.engine(0).myOnion.value!!
        // A invita a C y, mientras la invitación está en curso, borra a su único contacto.
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        h.engine(0).deleteContact(bInA)
        assertEquals(onion, h.engine(0).myOnion.value)
        val service = h.network.published.value.getValue(onion)
        assertTrue(service.requiresClientAuth)
        // El emparejamiento en curso termina bien y C llega a la .onion ofrecida.
        assertTrue(h.engine(2).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        val cInA = h.contacts(0).all().single().id
        withTimeout(TIMEOUT) { h.engine(0).presence.first { it[cInA] == Presence.ONLINE } }
        assertEquals(onion, h.engine(0).myOnion.value)
    }

    @Test
    fun concurrentDeletesAreIdempotentAndUnknownIdIsNotDelivered() = runBlocking<Unit> {
        val (h, bInA, _) = pairedOnline()
        val results = (1..4).map { async(Dispatchers.Default) { h.engine(0).deleteContact(bInA) } }.awaitAll()
        // Todas las llamadas simultáneas comparten el resultado del único borrado real.
        assertEquals(List(4) { UnpairDelivery.DELIVERED }, results)
        assertTrue(h.contacts(0).all().isEmpty())
        // Después (y con ids que nunca existieron): NOT_DELIVERED sin hacer nada.
        assertEquals(UnpairDelivery.NOT_DELIVERED, h.engine(0).deleteContact(bInA))
        assertEquals(UnpairDelivery.NOT_DELIVERED, h.engine(0).deleteContact("desconocido"))
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
    }

    @Test
    fun stopDuringDeleteStillDeletesLocally() = runBlocking<Unit> {
        val (h, bInA, _) = pairedOnline()
        h.engine(1).stop()
        withTimeout(TIMEOUT) { h.engine(0).presence.first { it[bInA] == Presence.OFFLINE } }
        val delete = async { h.engine(0).deleteContact(bInA) }
        delay(200)
        h.engine(0).stop()
        assertEquals(UnpairDelivery.NOT_DELIVERED, withTimeout(TIMEOUT) { delete.await() })
        assertTrue(h.contacts(0).all().isEmpty())
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        // Nada quedó publicado ni vivo: el borrado no volvió a publicar la .onion con el motor parado.
        assertTrue(h.network.published.value.isEmpty())
        assertNull(h.engine(0).myOnion.value)
        // Borrar con el motor parado también funciona (sin entrega posible).
        h.engine(1).start()
        val aInB = h.contacts(1).all().single().id
        h.engine(1).stop()
        assertEquals(UnpairDelivery.NOT_DELIVERED, h.engine(1).deleteContact(aInB))
        assertTrue(h.contacts(1).all().isEmpty())
    }

    @Test
    fun wipeAllWorksWhenStoppedAndWhenTorFails() = runBlocking<Unit> {
        val (h, _, _) = pairedOnline()
        h.engine(1).stop()
        h.engine(1).wipeAll()
        assertNull(h.identities(1).load())
        assertTrue(h.contacts(1).all().isEmpty())
        assertEquals(EngineStatus.Stopped, h.engine(1).status.value)

        // Tor que falla al parar y al quitar autorizaciones: el borrado se completa igualmente.
        val tor = BrokenTor(FakeTor(h.network))
        val ids = InMemoryIdentityRepository()
        val contacts = InMemoryContactRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = MessengerEngine(tor, ids, contacts, scope, config)
            engine.start()
            assertNotNull(ids.load())
            engine.wipeAll()
            assertEquals(EngineStatus.Stopped, engine.status.value)
            assertNull(ids.load())
            engine.start()
            assertEquals(EngineStatus.Online, engine.status.value)
            engine.stop()
        } finally {
            scope.cancel()
        }
    }

    // --- Ronda de correcciones 1 ---

    @Test
    fun deleteInWindowDoesNotWipeAFreshRepairing() = runBlocking<Unit> {
        val (h, bInA, aInB) = pairedOnline()
        // B se apaga y olvida a A (p. ej. lo borró sin poder avisar): A no puede entregar Unpair.
        h.engine(1).stop()
        h.contacts(1).wipe()
        withTimeout(TIMEOUT) { h.engine(0).presence.first { it[bInA] == Presence.OFFLINE } }
        val oldSecret = h.contacts(0).all().single().pairSecret.copyOf()
        val delete = async { h.engine(0).deleteContact(bInA) }
        delay(200)
        assertFalse(delete.isCompleted)
        // Dentro de la ventana, A y B se vuelven a emparejar: mismo id (deriva de la identidad).
        h.engine(1).start()
        pairEngines(h.engine(0), h.engine(1))
        assertEquals(listOf(aInB), h.contacts(1).all().map { it.id })
        // El borrado antiguo termina limpio (el enlace al que avisaba ya no existe) sin tocar el nuevo.
        assertEquals(UnpairDelivery.NOT_DELIVERED, withTimeout(TIMEOUT) { delete.await() })
        val stored = h.contacts(0).all().single()
        assertEquals(bInA, stored.id)
        assertFalse(stored.pairSecret.contentEquals(oldSecret))
        assertFalse(stored.pairSecret.all { it == 0.toByte() })
        awaitSameSession(h, bInA, aInB)
        val id = h.engine(0).sendText(bInA, "otra vez")
        withTimeout(TIMEOUT) {
            h.engine(0).conversations.first { m -> m[bInA]?.any { it.id == id && it.status == DeliveryStatus.Delivered } == true }
        }
        // B no recibió ningún Unpair: sigue teniendo a A.
        delay(300)
        assertEquals(listOf(aInB), h.contacts(1).all().map { it.id })
        assertNotNull(h.engine(0).myOnion.value)
    }

    @Test
    fun wipeAllZeroesLiveKeyMaterialEvenWithCopyingRepositories() = runBlocking<Unit> {
        val h = EngineHarness(1, config).also { harness = it }
        val ids = CopyingIdentityRepository()
        val contacts = CopyingContactRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val a = MessengerEngine(FakeTor(h.network), ids, contacts, scope, config)
            h.startAll()
            a.start()
            pairEngines(a, h.engine(0))
            val bInA = contacts.all().single().id
            withTimeout(TIMEOUT) { a.presence.first { it[bInA] == Presence.ONLINE } }
            val live = contacts.lastNonEmpty
            a.wipeAll()
            assertTrue(ids.handedOut.isNotEmpty())
            ids.handedOut.forEach { id ->
                assertTrue(id.identityPrivate.all { it == 0.toByte() }) { "identidad viva sin borrar" }
                id.onionPrivateKey?.let { k -> assertTrue(k.all { it == 0.toByte() }) { "clave .onion viva sin borrar" } }
            }
            live.forEach { c ->
                assertTrue(c.pairSecret.all { it == 0.toByte() }) { "pairSecret vivo sin borrar" }
                assertTrue(c.clientAuthPrivateForPeerOnion.all { it == 0.toByte() }) { "clave de autorización viva sin borrar" }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deleteZeroesTheDeletedContactSecretsHeldByTheEngine() = runBlocking<Unit> {
        val h = EngineHarness(1, config).also { harness = it }
        val contacts = CopyingContactRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val a = MessengerEngine(FakeTor(h.network), CopyingIdentityRepository(), contacts, scope, config)
            h.startAll()
            a.start()
            pairEngines(a, h.engine(0))
            val bInA = contacts.all().single().id
            withTimeout(TIMEOUT) { a.presence.first { it[bInA] == Presence.ONLINE } }
            val live = contacts.lastNonEmpty.single()
            assertEquals(UnpairDelivery.DELIVERED, a.deleteContact(bInA))
            assertTrue(live.pairSecret.all { it == 0.toByte() })
            assertTrue(live.clientAuthPrivateForPeerOnion.all { it == 0.toByte() })
            assertEquals(EngineStatus.Online, a.status.value)
            a.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun publicationFailureAfterDeleteIsRetried() = runBlocking<Unit> {
        val h = EngineHarness(2, config).also { harness = it }
        val tor = FlakyPublishTor(FakeTor(h.network))
        val contacts = InMemoryContactRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val a = MessengerEngine(tor, InMemoryIdentityRepository(), contacts, scope, config)
            h.startAll()
            a.start()
            pairEngines(a, h.engine(0))
            pairEngines(a, h.engine(1))
            val bIdentity = h.identities(0).load()!!.identityPublic
            val bInA = contactIdFor(bIdentity)
            val c = contacts.all().single { it.id != bInA }
            withTimeout(TIMEOUT) { a.presence.first { it[bInA] == Presence.ONLINE && it[c.id] == Presence.ONLINE } }
            val onion = a.myOnion.value!!
            val failed = observers.async(start = CoroutineStart.UNDISPATCHED) {
                a.notices.filterIsInstance<Notice.PublicationFailed>().first()
            }
            // Al borrar a B, Tor falla 4 veces seguidas al re-publicar (la del borrado y 3 reintentos).
            tor.failNext(4)
            a.deleteContact(bInA)
            assertEquals(Notice.PublicationFailed, withTimeout(TIMEOUT) { failed.await() })
            // Al final la .onion vuelve a estar publicada solo con la clave de C, y C conecta.
            withTimeout(TIMEOUT) {
                h.network.published.first { m ->
                    m[onion]?.clientAuthPublicKeys?.map { it.toList() } == listOf(c.myClientAuthPublic.toList())
                }
            }
            assertEquals(0, tor.pendingFailures())
            assertEquals(onion, a.myOnion.value)
            withTimeout(TIMEOUT) { a.presence.first { it[c.id] == Presence.ONLINE } }
            a.stop()
        } finally {
            scope.cancel()
        }
    }

    /** Empareja dos motores cualesquiera (ya arrancados). */
    private suspend fun pairEngines(inviter: MessengerEngine, invitee: MessengerEngine) {
        val handle = inviter.createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(invitee.acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
    }

    /** Tor cuyas próximas `n` publicaciones/retiradas de servicio fallan. */
    private class FlakyPublishTor(private val delegate: FakeTor) : TorTransport by delegate {
        private val failures = java.util.concurrent.atomic.AtomicInteger(0)

        fun failNext(n: Int) = failures.set(n)

        fun pendingFailures(): Int = failures.get().coerceAtLeast(0)

        private fun maybeFail() {
            if (failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw IOException("Tor falló al publicar")
        }

        override suspend fun publishService(
            privateKey: ByteArray?,
            clientAuthPublicKeys: List<ByteArray>,
            virtualPort: Int,
            localPort: Int,
        ): PublishedService {
            // El servicio temporal de invitación (sin autorización) no cuenta.
            if (clientAuthPublicKeys.isNotEmpty()) maybeFail()
            return delegate.publishService(privateKey, clientAuthPublicKeys, virtualPort, localPort)
        }

        override suspend fun unpublishService(onion: String) {
            maybeFail()
            delegate.unpublishService(onion)
        }
    }

    /** Repositorio de identidad que, como uno real, guarda y devuelve copias; recuerda lo que entregó o recibió. */
    private class CopyingIdentityRepository : IdentityRepository {
        @Volatile private var stored: Identity? = null
        val handedOut: MutableList<Identity> = java.util.Collections.synchronizedList(mutableListOf())

        override suspend fun load(): Identity? = stored?.deep()?.also { handedOut += it }

        override suspend fun save(identity: Identity) {
            handedOut += identity
            stored = identity.deep()
        }

        override suspend fun wipe() {
            stored = null
        }

        private fun Identity.deep() = Identity(identityPrivate.copyOf(), identityPublic.copyOf(), onionPrivateKey?.copyOf(), onion)
    }

    /** Repositorio de contactos que entrega copias (en `all()` y en el flujo) en vez de sus propios arrays. */
    private class CopyingContactRepository : ContactRepository {
        private val lock = Any()
        private var stored: List<Contact> = emptyList()
        private val emitted = MutableStateFlow<List<Contact>>(emptyList())
        override val contacts: StateFlow<List<Contact>> = emitted

        /** Última lista no vacía emitida por el flujo (la que tienen los enlaces del motor). */
        @Volatile var lastNonEmpty: List<Contact> = emptyList()
            private set

        override suspend fun all(): List<Contact> = synchronized(lock) { stored.map { it.deep() } }

        override suspend fun upsert(contact: Contact) = change { list ->
            val copy = contact.deep()
            if (list.any { it.id == copy.id }) list.map { if (it.id == copy.id) copy else it } else list + copy
        }

        override suspend fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

        override suspend fun wipe() = change { emptyList() }

        private fun change(f: (List<Contact>) -> List<Contact>) = synchronized(lock) {
            stored = f(stored)
            val out = stored.map { it.deep() }
            if (out.isNotEmpty()) lastNonEmpty = out
            emitted.value = out
        }

        private fun Contact.deep() = copy(
            peerIdentity = peerIdentity.copyOf(),
            clientAuthPrivateForPeerOnion = clientAuthPrivateForPeerOnion.copyOf(),
            myClientAuthPublic = myClientAuthPublic.copyOf(),
            pairSecret = pairSecret.copyOf(),
        )
    }

    /** Tor cuyo `stop` y `removeClientAuth` fallan siempre. */
    private class BrokenTor(private val delegate: FakeTor) : TorTransport by delegate {
        private val mutableState = MutableStateFlow<TorState>(TorState.Off)
        override val state: StateFlow<TorState> = mutableState

        override suspend fun start() {
            delegate.start()
            mutableState.value = TorState.Ready
        }

        override suspend fun stop() {
            delegate.stop()
            throw IOException("Tor no responde")
        }

        override suspend fun removeClientAuth(onion: String) {
            throw IOException("Tor no responde")
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL_MILLIS = 5L
    }
}
