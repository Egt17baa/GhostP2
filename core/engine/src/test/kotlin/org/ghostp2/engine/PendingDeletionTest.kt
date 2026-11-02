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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingDeletionTest {
    private val config = EngineConfig(backoffMinMillis = 50, backoffMaxMillis = 400, unpairDeliveryWindowMillis = 5_000)
    private var harness: EngineHarness? = null
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cerrar() {
        observers.cancel()
        harness?.close()
    }

    /** A (0) y B (1) emparejados y en marcha; devuelve (id de B en A, id de A en B). */
    private suspend fun paired(cfg: EngineConfig = config): Pair<EngineHarness, Pair<String, String>> {
        val h = EngineHarness(2, cfg).also { harness = it }
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        val accepted = h.engine(1).acceptInvitation(waiting.text) as AcceptResult.Paired
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        return h to (h.contacts(0).all().single().id to accepted.contactId)
    }

    private suspend fun awaitJournalEmpty(journal: InMemoryDeletionJournal) {
        withTimeout(TIMEOUT) {
            while (journal.pending().isNotEmpty()) delay(POLL_MILLIS)
        }
    }

    @Test
    fun interruptedDeletionResumesOnStart() = runBlocking<Unit> {
        val (h, ids) = paired()
        val (bInA, aInB) = ids
        // Simula un proceso que murió a mitad de borrar a B: A parado con la entrada en el diario.
        h.engine(0).stop()
        h.journal(0).add(bInA)
        val seenDeleting = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(0).deleting.first { bInA in it }
        }
        val notice = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(1).notices.filterIsInstance<Notice.ContactUnpairedYou>().first()
        }
        h.engine(0).start()
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        withTimeout(TIMEOUT) { seenDeleting.await() }
        assertEquals(aInB, withTimeout(TIMEOUT) { notice.await() }.contactId)
        withTimeout(TIMEOUT) { h.contacts(0).contacts.first { it.isEmpty() } }
        withTimeout(TIMEOUT) { h.engine(0).deleting.first { it.isEmpty() } }
        awaitJournalEmpty(h.journal(0))
        withTimeout(TIMEOUT) { h.contacts(1).contacts.first { it.isEmpty() } }
    }

    @Test
    fun journalIsClearedAfterDelete() = runBlocking<Unit> {
        val (h, ids) = paired(config.copy(unpairDeliveryWindowMillis = 1_500))
        val (bInA, _) = ids
        assertTrue(h.journal(0).pending().isEmpty())
        // B parado: A espera la ventana de entrega y, mientras, el borrado consta en el diario.
        h.engine(1).stop()
        val delete = async { h.engine(0).deleteContact(bInA) }
        withTimeout(TIMEOUT) { h.engine(0).deleting.first { bInA in it } }
        withTimeout(TIMEOUT) {
            while (bInA !in h.journal(0).pending()) delay(POLL_MILLIS)
        }
        assertEquals(UnpairDelivery.NOT_DELIVERED, withTimeout(TIMEOUT) { delete.await() })
        assertTrue(h.contacts(0).all().isEmpty())
        assertTrue(h.journal(0).pending().isEmpty())
    }

    @Test
    fun staleJournalEntryIsDropped() = runBlocking<Unit> {
        val (h, ids) = paired()
        val (bInA, _) = ids
        val stale = "ab".repeat(16)
        h.engine(0).stop()
        h.journal(0).add(stale)
        val seenDeleting = observers.async(start = CoroutineStart.UNDISPATCHED) {
            h.engine(0).deleting.first { stale in it }
        }
        h.engine(0).start()
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        assertTrue(h.journal(0).pending().isEmpty())
        // No se borró nada: B sigue guardado y nunca se marcó en `deleting`.
        assertEquals(listOf(bInA), h.contacts(0).all().map { it.id })
        delay(300)
        assertFalse(seenDeleting.isCompleted)
        assertEquals(listOf(bInA), h.contacts(0).all().map { it.id })
    }

    @Test
    fun pairingRemovesStaleJournalEntry() = runBlocking<Unit> {
        val h = EngineHarness(2, config).also { harness = it }
        h.startAll()
        // Entrada antigua para B (p. ej. de un borrado anterior) antes de emparejar con él.
        val bInA = contactIdFor(h.identities(1).load()!!.identityPublic)
        h.journal(0).add(bInA)
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        assertTrue(h.engine(1).acceptInvitation(waiting.text) is AcceptResult.Paired)
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        assertTrue(h.journal(0).pending().isEmpty())
        // Al reiniciar, el emparejamiento nuevo sigue ahí: nada lo borra.
        h.engine(0).stop()
        h.engine(0).start()
        delay(300)
        assertEquals(listOf(bInA), h.contacts(0).all().map { it.id })
        assertTrue(h.engine(0).deleting.value.isEmpty())
    }

    @Test
    fun wipeAllClearsJournal() = runBlocking<Unit> {
        val (h, ids) = paired()
        h.journal(0).add(ids.first)
        h.journal(0).add("cd".repeat(16))
        h.engine(0).wipeAll()
        assertTrue(h.journal(0).pending().isEmpty())
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL_MILLIS = 5L
    }
}
