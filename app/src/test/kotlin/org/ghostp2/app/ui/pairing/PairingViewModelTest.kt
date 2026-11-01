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
import org.ghostp2.core.protocol.FrameStream
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.EngineConfig
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationHandle
import org.ghostp2.engine.InvitationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.concurrent.thread

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

    /** Invitación de un motor de [peer] en `Waiting`, y su handle. */
    private fun peerInvitation(peer: EngineHarness): Pair<InvitationHandle, String> {
        val handle = peer.engine(0).createInvitation()
        val waiting = runBlocking { withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } }
        return handle to (waiting as InvitationState.Waiting).text
    }

    @Test
    fun engineStoppingMidAcceptGivesNotActiveAndOnlineClearsIt() {
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val (_, text) = peerInvitation(peer)
            // La conexión del lado aceptante se queda esperando en la puerta (hasta que llegue otra).
            app.network.gateNextConnects(2)
            val vm = viewModel()
            vm.accept(text)
            assertEquals(AcceptUi.Working, vm.accept.value)
            eventually(TIMEOUT) { app.network.gateOpenSlots() == 1 }

            val stopping = app.container.stop()
            eventually(TIMEOUT) { app.container.engine.status.value != EngineStatus.Online }
            // Abre la puerta con otra conexión (falla, da igual): el motor ya está parándose.
            runBlocking {
                try {
                    peer.tor(0).connect("a".repeat(56), 1, 1_000)
                } catch (_: IOException) {
                }
            }
            runBlocking { stopping.join() }
            eventually(TIMEOUT) { vm.accept.value is AcceptUi.Failed }
            assertEquals(AcceptUi.Failed("GhostP2 no está activo"), vm.accept.value)
            assertTrue(runBlocking { app.storage.contacts.all() }.isEmpty())

            // Al volver a estar Online, el fallo deja de mostrarse.
            assertTrue(runBlocking { app.container.start().await() })
            eventually(TIMEOUT) { vm.accept.value == AcceptUi.Idle }
        }
    }

    @Test
    fun unreachableInvitationCannotConnect() {
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val (handle, text) = peerInvitation(peer)
            val onion = text.removePrefix("ghostp2:").substringBefore('.')
            handle.cancel()
            eventually(TIMEOUT) { onion !in app.network.published.value }

            val vm = viewModel()
            vm.accept(text)
            eventually(TIMEOUT) { vm.accept.value is AcceptUi.Failed }
            assertEquals(AcceptUi.Failed("No se pudo conectar con la invitación (¿caducada o ya usada?)"), vm.accept.value)
        }
    }

    @Test
    fun tamperedHandshakeIsAMismatch() {
        // Invitador falso: lee el primer mensaje y contesta con un segundo mensaje alterado (bytes al
        // azar del tamaño de un XX válido) ⇒ el aceptante no lo puede autenticar (motivo AUTH).
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val onion = runBlocking {
                    peer.tor(0).publishService(null, emptyList(), EngineConfig().virtualPort, server.localPort).onion
                }
                val fake = thread {
                    try {
                        server.accept().use { socket ->
                            val stream = FrameStream(socket.getInputStream(), socket.getOutputStream(), socket)
                            runBlocking {
                                stream.receive()
                                stream.send(ByteArray(96).also { SecureRandom().nextBytes(it) })
                                stream.receive() // espera a que el otro lado cierre
                            }
                        }
                    } catch (_: IOException) {
                    }
                }

                val vm = viewModel()
                vm.accept("ghostp2:$onion." + "a".repeat(26))
                eventually(TIMEOUT) { vm.accept.value is AcceptUi.Failed }
                assertEquals(AcceptUi.Failed("La invitación no coincide"), vm.accept.value)
                assertNull(vm.pairedContactId.value)
                assertTrue(runBlocking { app.storage.contacts.all() }.isEmpty())
                server.close()
                fake.join(TIMEOUT)
            }
        }
    }

    @Test
    fun wrongSecretCannotConnect() {
        // Con otro secreto el invitador corta en el tercer mensaje (XXpsk3): el aceptante solo ve el
        // cierre. Y el intento fallido no consume la invitación.
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val (handle, text) = peerInvitation(peer)
            val forged = text.substringBeforeLast('.') + "." + "a".repeat(26)
            assertNotEquals(text, forged)

            val vm = viewModel()
            vm.accept(forged)
            eventually(TIMEOUT) { vm.accept.value is AcceptUi.Failed }
            assertEquals(AcceptUi.Failed("No se pudo conectar con la invitación (¿caducada o ya usada?)"), vm.accept.value)
            assertTrue(handle.state.value is InvitationState.Waiting)
        }
    }

    @Test
    fun editingThePastedTextClearsTheFailure() {
        val vm = viewModel()
        vm.setPasted("hola")
        vm.accept(vm.pasted.value)
        eventually(TIMEOUT) { vm.accept.value is AcceptUi.Failed }
        vm.setPasted("hola!")
        assertEquals("hola!", vm.pasted.value)
        assertEquals(AcceptUi.Idle, vm.accept.value)
    }

    @Test
    fun acceptingClearsOwnWaitingInvitationWhenLeaving() {
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val vm = viewModel()
            vm.createInvitation()
            val mine = waitingText(vm).removePrefix("ghostp2:").substringBefore('.')
            val (_, theirs) = peerInvitation(peer)

            vm.accept(theirs)
            eventually(TIMEOUT) { vm.pairedContactId.value != null }
            // Emparejado por "Pegar": la mía sigue esperando hasta salir de la pantalla.
            assertTrue(mine in app.network.published.value)
            store.clear()
            eventually(TIMEOUT) { mine !in app.network.published.value }
        }
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
