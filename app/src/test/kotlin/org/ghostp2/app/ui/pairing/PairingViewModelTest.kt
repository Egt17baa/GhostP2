package org.ghostp2.app.ui.pairing

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.InvitationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class PairingViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        assertTrue(runBlocking { app.container.start().await() })
    }

    @After
    fun tearDown() {
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun viewModel(): PairingViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { PairingViewModel(app.container) } })[PairingViewModel::class]

    private fun waitingText(vm: PairingViewModel): String {
        eventually(TIMEOUT) { vm.invitation.value is InvitationState.Waiting }
        return (vm.invitation.value as InvitationState.Waiting).text
    }

    @Test
    fun invitationShowsWaitingThenPairs() {
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val vm = viewModel()
            assertNull(vm.invitation.value)
            vm.createInvitation()
            val text = waitingText(vm)
            assertNull(vm.pairedContactId.value)

            assertTrue(runBlocking { peer.engine(0).acceptInvitation(text) } is AcceptResult.Paired)
            eventually(TIMEOUT) { vm.pairedContactId.value != null }
            assertEquals(runBlocking { app.storage.contacts.all() }.single().id, vm.pairedContactId.value)
            assertTrue(vm.invitation.value is InvitationState.Paired)
        }
    }

    @Test
    fun pastedInvitationIsTrimmed() {
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val handle = peer.engine(0).createInvitation()
            val waiting = runBlocking { withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } }
            val text = (waiting as InvitationState.Waiting).text
            val vm = viewModel()

            vm.accept("  \n\t" + text.uppercase() + " \r\n ")
            eventually(TIMEOUT) { vm.accept.value is AcceptUi.Paired }
            val id = (vm.accept.value as AcceptUi.Paired).contactId
            assertEquals(runBlocking { app.storage.contacts.all() }.single().id, id)
            assertEquals(id, vm.pairedContactId.value)
        }
    }

    @Test
    fun garbageFailsWithMessage() {
        val vm = viewModel()
        vm.accept("hola, esto no es una invitación")
        eventually(TIMEOUT) { vm.accept.value is AcceptUi.Failed }
        val message = (vm.accept.value as AcceptUi.Failed).message
        assertTrue(message, message.startsWith("Invitación no válida"))
        assertNull(vm.pairedContactId.value)
    }

    @Test
    fun clearingTheViewModelCancelsTheInvitation() {
        val vm = viewModel()
        vm.createInvitation()
        val onion = waitingText(vm).removePrefix("ghostp2:").substringBefore('.')
        val service = app.network.published.value[onion]
        // El servicio temporal está publicado y sin autorización de cliente.
        assertTrue(service != null && !service.requiresClientAuth)

        store.clear()

        assertEquals(InvitationState.Cancelled, vm.invitation.value)
        eventually(TIMEOUT) { onion !in app.network.published.value }
    }

    @Test
    fun cancelInvitationThenCreateAnother() {
        val vm = viewModel()
        vm.createInvitation()
        val first = waitingText(vm)
        vm.cancelInvitation()
        assertEquals(InvitationState.Cancelled, vm.invitation.value)

        vm.createInvitation()
        eventually(TIMEOUT) { (vm.invitation.value as? InvitationState.Waiting)?.text?.let { it != first } == true }
        val onion = first.removePrefix("ghostp2:").substringBefore('.')
        eventually(TIMEOUT) { onion !in app.network.published.value }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
