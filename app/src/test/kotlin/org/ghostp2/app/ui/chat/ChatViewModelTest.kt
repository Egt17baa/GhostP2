package org.ghostp2.app.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.DeliveryStatus
import org.ghostp2.engine.Direction
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ChatViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun viewModel(id: String, owner: ViewModelStore = store): ChatViewModel =
        ViewModelProvider.create(owner, viewModelFactory { initializer { ChatViewModel(app.container, id) } })[ChatViewModel::class]

    private fun items(id: String) = app.container.engine.conversations.value[id].orEmpty()

    /** GhostP2 en marcha y emparejado con el motor 0 de [peer], con su presencia `ONLINE`. */
    private fun pairedOnline(peer: EngineHarness): String {
        assertTrue(runBlocking { app.container.start().await() })
        val (id, _) = runBlocking {
            peer.startAll()
            pairWithPeer(app, peer, TIMEOUT)
        }
        runBlocking { withTimeout(TIMEOUT) { app.container.engine.presence.first { it[id] == Presence.ONLINE } } }
        return id
    }

    @Test
    fun sendWhileContactOfflineIsDiscarded() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            // El otro lado se para: sin sesión, lo enviado se descarta (sin cola ni reintento).
            runBlocking { peer.engine(0).stop() }
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value.let { it != null && it.presence != Presence.ONLINE } }
            assertTrue(vm.state.value!!.engineOnline)

            vm.draft = "hola"
            assertTrue(vm.send())
            assertEquals("", vm.draft)
            eventually(TIMEOUT) { vm.state.value?.items?.singleOrNull()?.status == DeliveryStatus.Failed(FailReason.NOT_CONNECTED) }
            val item = vm.state.value!!.items.single()
            assertEquals(Direction.OUT, item.direction)
            assertEquals(ChatContent.Text("hola"), item.content)
        }
    }

    @Test
    fun sendWithEngineStoppedStoresNothing() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value != null }
        assertFalse(vm.state.value!!.engineOnline)
        assertNull(vm.state.value!!.presence)

        vm.draft = "hola"
        assertFalse(vm.send())
        assertEquals("hola", vm.draft)
        assertTrue(app.container.engine.conversations.value.isEmpty())
        assertTrue(vm.state.value!!.items.isEmpty())
    }

    @Test
    fun tooLongTextKeepsDraft() {
        assertTrue(runBlocking { app.container.start().await() })
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value?.engineOnline == true }

        // Vacío o solo espacios: no se envía.
        vm.draft = "  \n "
        assertFalse(vm.send())
        assertEquals("  \n ", vm.draft)

        // Se cuentan caracteres Unicode (puntos de código), como el protocolo: 8 001 emoji no caben.
        val tooLong = "😀".repeat(PeerSession.MAX_TEXT_CHARS + 1)
        vm.draft = tooLong
        assertFalse(vm.send())
        assertEquals(tooLong, vm.draft)
        assertTrue(items("c1").isEmpty())

        // Justo en el límite sí se envía (aquí se descarta: el contacto no tiene sesión).
        vm.draft = "😀".repeat(PeerSession.MAX_TEXT_CHARS)
        assertTrue(vm.send())
        assertEquals("", vm.draft)
    }

    @Test
    fun deliveredToOnlinePeer() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value?.presence == Presence.ONLINE }

            // `ONLINE` solo con la sesión ya arrancada: el primer envío se entrega.
            vm.draft = "hola"
            assertTrue(vm.send())
            assertEquals("", vm.draft)
            eventually(TIMEOUT) { vm.state.value?.items?.singleOrNull()?.status.let { it == DeliveryStatus.Delivered || it is DeliveryStatus.Failed } }
            val sent = vm.state.value!!.items.single()
            assertEquals(DeliveryStatus.Delivered, sent.status)
            assertEquals(Direction.OUT, sent.direction)
            assertEquals(ChatContent.Text("hola"), sent.content)

            // Y el otro lado lo recibe.
            val theirs = runBlocking { peer.contacts(0).all() }.single().id
            eventually(TIMEOUT) { peer.engine(0).conversations.value[theirs].orEmpty().any { it.id == sent.id } }
            assertEquals(DeliveryStatus.Received, peer.engine(0).conversations.value[theirs]!!.single { it.id == sent.id }.status)
        }
    }

    @Test
    fun clearEmptiesConversation() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value != null }
            vm.draft = "hola"
            assertTrue(vm.send())
            eventually(TIMEOUT) { vm.state.value!!.items.isNotEmpty() }

            vm.clear()
            assertTrue(items(id).isEmpty())
            eventually(TIMEOUT) { vm.state.value!!.items.isEmpty() }
        }
    }

    @Test
    fun draftIsNotSaveable() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")
        vm.draft = "borrador secreto"
        // El sistema mata el proceso (o se destruye la pantalla): el ViewModel muere con su borrador.
        store.clear()
        // Al recrearse no hay estado guardado del que restaurarlo: empieza vacío.
        val again = viewModel("c1", ViewModelStore())
        assertNotEquals(vm, again)
        assertEquals("", again.draft)
    }

    @Test
    fun identityChangedRefusesToSend() {
        assertTrue(runBlocking { app.container.start().await() })
        runBlocking { app.storage.contacts.upsert(contact("c1", ContactStatus.IDENTITY_CHANGED)) }
        val vm = viewModel("c1")
        eventually(TIMEOUT) { vm.state.value?.identityChanged == true && vm.state.value?.engineOnline == true }
        vm.draft = "hola"
        assertFalse(vm.send())
        assertEquals("hola", vm.draft)
        assertTrue(items("c1").isEmpty())
    }

    @Test
    fun missingContactIsGone() {
        val vm = viewModel("no-existe")
        eventually(TIMEOUT) { vm.gone.value }
        assertNull(vm.state.value)
        vm.draft = "hola"
        assertFalse(vm.send())
    }

    private companion object {
        const val TIMEOUT = 20_000L

        fun contact(id: String, status: ContactStatus = ContactStatus.ACTIVE) =
            Contact(id, "Ana", ByteArray(32), "x".repeat(56), ByteArray(32), ByteArray(32), ByteArray(32), false, status)
    }
}
