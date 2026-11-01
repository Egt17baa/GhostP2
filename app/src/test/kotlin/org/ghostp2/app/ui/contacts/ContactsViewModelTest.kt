package org.ghostp2.app.ui.contacts

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ghostp2.app.DeleteOutcome
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ContactsViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()
    private val collectors = CoroutineScope(Job() + Dispatchers.Main)

    @After
    fun tearDown() {
        collectors.cancel()
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    /** ViewModel con un recolector (como la pantalla), para que su estado se calcule. */
    private fun viewModel(): ContactsViewModel {
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ContactsViewModel(app.container) } })[ContactsViewModel::class]
        collectors.launch { vm.state.collect {} }
        collectors.launch { vm.deleteNotice.collect {} }
        return vm
    }

    @Test
    fun rowsFlagIdentityChangedAndDeleting() {
        val rows = contactRows(
            contacts = listOf(
                contact("a", "Ana", verified = true),
                contact("b", "Bruno", status = ContactStatus.IDENTITY_CHANGED),
                contact("c", "Carla"),
                contact("d", "Dani"),
            ),
            presence = mapOf("a" to Presence.ONLINE, "c" to Presence.IDENTITY_CHANGED, "d" to Presence.CONNECTING),
            deleting = setOf("d"),
        )

        assertEquals(
            listOf(
                ContactRow("a", "Ana", Presence.ONLINE, verified = true, identityChanged = false, deleting = false),
                // Guardado como IDENTITY_CHANGED, aunque no haya presencia (motor parado).
                ContactRow("b", "Bruno", null, verified = false, identityChanged = true, deleting = false),
                // Solo la presencia lo dice.
                ContactRow("c", "Carla", Presence.IDENTITY_CHANGED, verified = false, identityChanged = true, deleting = false),
                ContactRow("d", "Dani", Presence.CONNECTING, verified = false, identityChanged = false, deleting = true),
            ),
            rows,
        )
    }

    @Test
    fun rowsAreSortedByName() {
        val rows = contactRows(
            listOf(contact("1", "carla"), contact("2", "Bruno"), contact("3", "ana"), contact("4", "Álvaro"), contact("5", "Zoe")),
            emptyMap(),
            emptySet(),
        )
        assertEquals(listOf("Álvaro", "ana", "Bruno", "carla", "Zoe"), rows.map { it.name })
    }

    @Test
    fun rowsAreSortedInSpanishWithEnyeAfterN() {
        val rows = contactRows(
            listOf(contact("1", "Óscar"), contact("2", "ñandú"), contact("3", "Nuria"), contact("4", "Noa")),
            emptyMap(),
            emptySet(),
        )
        // En español la ñ es una letra propia, entre la n y la o.
        assertEquals(listOf("Noa", "Nuria", "ñandú", "Óscar"), rows.map { it.name })
    }

    @Test
    fun pairedPeerShowsOnline() {
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            val vm = viewModel()
            val (peerInMine, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            eventually(TIMEOUT) {
                (vm.state.value as? ContactsUiState.Loaded)?.rows?.singleOrNull()?.presence == Presence.ONLINE
            }
            val row = (vm.state.value as ContactsUiState.Loaded).rows.single()
            assertEquals(peerInMine, row.id)
            assertEquals(false, row.identityChanged)
            assertEquals(false, row.deleting)
        }
    }

    @Test
    fun failingFlowGivesFailedAndRetryLoads() {
        app.storage.contactsFailure = IOException("x")
        val vm = viewModel()
        eventually(TIMEOUT) { vm.state.value == ContactsUiState.Failed }

        app.storage.contactsFailure = null
        vm.retry()
        eventually(TIMEOUT) { vm.state.value == ContactsUiState.Loaded(emptyList()) }
    }

    @Test
    fun failureAfterLoadedGivesFailedAndRetryLoads() {
        val later = CompletableDeferred<Exception>()
        app.storage.contactsLaterFailure = later
        runBlocking { app.storage.contacts.upsert(contact("a", "Ana")) }
        val vm = viewModel()
        eventually(TIMEOUT) { (vm.state.value as? ContactsUiState.Loaded)?.rows?.map { it.name } == listOf("Ana") }

        // El flujo falla a mitad, con la lista ya cargada: "Reintentar", nunca una lista vacía ni la vieja.
        later.complete(IOException("x"))
        eventually(TIMEOUT) { vm.state.value == ContactsUiState.Failed }

        app.storage.contactsLaterFailure = null
        vm.retry()
        eventually(TIMEOUT) { (vm.state.value as? ContactsUiState.Loaded)?.rows?.map { it.name } == listOf("Ana") }
    }

    @Test
    fun stoppingTheEngineTurnsTheRowBackToDisconnected() {
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            val vm = viewModel()
            runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            eventually(TIMEOUT) {
                (vm.state.value as? ContactsUiState.Loaded)?.rows?.singleOrNull()?.presence == Presence.ONLINE
            }
            runBlocking { app.container.engine.stop() }
            // Sin presencia (motor parado): la fila se muestra "desconectado" y sigue en la lista.
            eventually(TIMEOUT) {
                (vm.state.value as? ContactsUiState.Loaded)?.rows?.singleOrNull()?.presence == null
            }
            val row = (vm.state.value as ContactsUiState.Loaded).rows.single()
            assertEquals(false, row.deleting)
            assertEquals(false, row.identityChanged)
        }
    }

    @Test
    fun deleteResultLeftForTheListIsShownOnce() {
        runBlocking { app.storage.contacts.upsert(contact("a", "Ana")) }
        val vm = viewModel()
        // Borrado sin detalle que espere el resultado (se salió antes de que llegara).
        assertEquals(UnpairDelivery.NOT_DELIVERED, runBlocking { app.container.deleteContact("a", "Ana").await() })
        eventually(TIMEOUT) { vm.deleteNotice.value != null }
        assertEquals(DeleteOutcome("a", "Ana", UnpairDelivery.NOT_DELIVERED), vm.deleteNotice.value)
        vm.deleteNoticeShown("a")
        eventually(TIMEOUT) { vm.deleteNotice.value == null }
    }

    @Test
    fun deleteResultWatchedByADetailIsNotShownInTheList() {
        runBlocking { app.storage.contacts.upsert(contact("a", "Ana")) }
        val vm = viewModel()
        app.container.watchDeleteOutcome("a")
        runBlocking { app.container.deleteContact("a", "Ana").await() }
        eventually(TIMEOUT) { (vm.state.value as? ContactsUiState.Loaded)?.rows?.isEmpty() == true }
        assertEquals(null, vm.deleteNotice.value)
        // El detalle se cierra sin haberlo mostrado: ahora sí lo enseña la lista.
        app.container.unwatchDeleteOutcome("a")
        eventually(TIMEOUT) { vm.deleteNotice.value?.contactId == "a" }
    }

    private companion object {
        const val TIMEOUT = 20_000L

        fun contact(id: String, name: String, verified: Boolean = false, status: ContactStatus = ContactStatus.ACTIVE) =
            Contact(id, name, ByteArray(32), "x".repeat(56), ByteArray(32), ByteArray(32), ByteArray(32), verified, status)
    }
}
