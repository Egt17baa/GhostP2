package org.ghostp2.app.ui.contact

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ContactDetailViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun viewModel(id: String): ContactDetailViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { ContactDetailViewModel(app.container, id) } })[ContactDetailViewModel::class]

    private fun stored(id: String) = runBlocking { app.storage.contacts.all() }.single { it.id == id }

    @Test
    fun showsSafetyNumber() {
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            val (id, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            // Funciona también con el motor parado (lee la identidad guardada).
            runBlocking { app.container.engine.stop() }
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value?.safetyNumber != null }
            val state = vm.state.value!!
            assertEquals(runBlocking { app.container.engine.safetyNumber(id) }, state.safetyNumber)
            assertTrue(Regex("""\d{5}( \d{5}){5}""").matches(state.safetyNumber!!))
            assertFalse(state.verified)
            assertFalse(state.identityChanged)
            assertFalse(state.deleting)
            assertFalse(vm.gone.value)
        }
    }

    @Test
    fun renameValidatesAndPersists() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value != null }

        assertFalse(vm.rename("   "))
        assertEquals(R.string.name_error_blank, vm.renameError.value)
        assertFalse(vm.rename("x".repeat(MAX_CONTACT_NAME_CHARS + 1)))
        assertEquals(R.string.name_error_too_long, vm.renameError.value)
        assertEquals("Contacto nuevo", stored("c1").name)

        assertTrue(vm.rename("  Ana  "))
        eventually(TIMEOUT) { vm.state.value?.name == "Ana" && !vm.busy.value }
        assertEquals("Ana", stored("c1").name)
        assertNull(vm.renameError.value)
    }

    @Test
    fun renameKeepsGoingAfterLeaving() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        app.storage.contactWriteDelayMillis = 300
        assertTrue(vm.rename("Ana"))
        assertTrue(vm.busy.value)
        // Ignorado mientras otra escritura está en curso.
        assertFalse(vm.rename("Bea"))
        store.clear()
        eventually(TIMEOUT) { stored("c1").name == "Ana" }
    }

    @Test
    fun renameFailureShowsError() {
        val vm = viewModel("no-existe")
        assertTrue(vm.rename("Ana"))
        eventually(TIMEOUT) { vm.renameError.value == R.string.name_error_save }
        assertFalse(vm.busy.value)
    }

    @Test
    fun verifyToggles() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value != null }

        vm.setVerified(true)
        eventually(TIMEOUT) { vm.state.value?.verified == true && !vm.busy.value }
        assertTrue(stored("c1").verified)

        vm.setVerified(false)
        eventually(TIMEOUT) { vm.state.value?.verified == false && !vm.busy.value }
        assertFalse(stored("c1").verified)
    }

    @Test
    fun identityChangedComesFromTheStoredStatus() {
        runBlocking { app.storage.contacts.upsert(contact("c1", ContactStatus.IDENTITY_CHANGED)) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value != null }
        assertTrue(vm.state.value!!.identityChanged)
        assertNull(vm.state.value!!.presence)
    }

    @Test
    fun missingContactIsGone() {
        val vm = viewModel("no-existe")
        eventually(TIMEOUT) { vm.gone.value }
        assertNull(vm.state.value)
    }

    @Test
    fun clearChatClearsTheConversation() {
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            val (id, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            runBlocking { withTimeout(TIMEOUT) { app.container.engine.presence.first { it[id] == Presence.ONLINE } } }
            app.container.engine.sendText(id, "hola")
            assertTrue(app.container.engine.conversations.value[id].orEmpty().isNotEmpty())
            val vm = viewModel(id)
            vm.clearChat()
            assertTrue(app.container.engine.conversations.value[id].orEmpty().isEmpty())
        }
    }

    @Test
    fun deleteReportsDelivery() {
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            val (id, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            runBlocking { withTimeout(TIMEOUT) { app.container.engine.presence.first { it[id] == Presence.ONLINE } } }
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value?.presence == Presence.ONLINE }

            vm.delete()
            eventually(TIMEOUT) { vm.deleteResult.value != null }
            assertEquals(UnpairDelivery.DELIVERED, vm.deleteResult.value)
            eventually(TIMEOUT) { vm.state.value == null }
            assertTrue(runBlocking { app.storage.contacts.all() }.isEmpty())
            // El borrado propio no cuenta como "desaparecido": la pantalla muestra el resultado.
            assertFalse(vm.gone.value)
        }
    }

    @Test
    fun deleteWithEngineStoppedIsNotDeliveredAndSurvivesLeaving() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value != null }
        vm.delete()
        // Salir enseguida (el ViewModel se destruye): el borrado sigue en el scope del contenedor.
        store.clear()
        eventually(TIMEOUT) { runBlocking { app.storage.contacts.all() }.isEmpty() }
    }

    @Test
    fun deleteResultWhenNotDelivered() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value != null }
        vm.delete()
        eventually(TIMEOUT) { vm.deleteResult.value != null }
        assertEquals(UnpairDelivery.NOT_DELIVERED, vm.deleteResult.value)
    }

    private companion object {
        const val TIMEOUT = 20_000L

        fun contact(id: String, status: ContactStatus = ContactStatus.ACTIVE) =
            Contact(id, "Contacto nuevo", ByteArray(32), "x".repeat(56), ByteArray(32), ByteArray(32), ByteArray(32), false, status)
    }
}
