package org.ghostp2.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.style.TextDecoration
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.DeliveryStatus
import org.ghostp2.engine.Direction
import org.ghostp2.engine.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<String>()

    private var state by mutableStateOf<ChatUiState?>(ana())
    private var draft by mutableStateOf("")

    private fun show() = compose.setContent {
        ChatScreen(
            state = state,
            draft = draft,
            onDraftChange = {
                draft = it
                events += "draft"
            },
            onSend = { events += "send" },
            onBack = { events += "back" },
            onOpenContact = { events += "contact" },
            onClear = { events += "clear" },
        )
    }

    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    /** Desplaza la lista hasta el mensaje [text] (la lista perezosa solo compone lo visible). */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun isStruck(message: String): Boolean {
        scrollTo(message)
        return compose.onNodeWithText(message).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            .spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough }
    }

    private fun assertShown(text: String) =
        assertTrue(text, compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())

    @Test
    fun failedMessageShowsDiscardText() {
        state = ana(
            items = listOf(
                out("a", "no estaba", DeliveryStatus.Failed(FailReason.NOT_CONNECTED)),
                out("b", "se cortó", DeliveryStatus.Failed(FailReason.CONNECTION_LOST)),
                out("c", "sin acuse", DeliveryStatus.Failed(FailReason.NO_ACK)),
                out("d", "en camino", DeliveryStatus.Sending(0, 0)),
                out("e", "llegó", DeliveryStatus.Delivered),
                ChatItem("f", Direction.IN, ChatContent.Text("hola tú"), DeliveryStatus.Received, 0),
            ),
        )
        show()
        // Cada mensaje, con su etiqueta debajo.
        scrollTo("no estaba")
        assertShown("No entregado: tu contacto no está conectado. Descartado")
        scrollTo("se cortó")
        assertShown("No entregado. Descartado")
        scrollTo("sin acuse")
        assertShown("No entregado. Descartado")
        scrollTo("en camino")
        assertShown("enviando…")
        scrollTo("llegó")
        assertShown("✓ entregado")
        // Descartados: tachados; los demás, no.
        assertTrue(isStruck("no estaba"))
        assertTrue(isStruck("se cortó"))
        assertTrue(isStruck("sin acuse"))
        assertFalse(isStruck("en camino"))
        assertFalse(isStruck("llegó"))
        assertFalse(isStruck("hola tú"))
    }

    @Test
    fun identityChangedDisablesInput() {
        state = ana(identityChanged = true)
        draft = "hola"
        show()
        compose.onNodeWithText(text(R.string.chat_identity_changed, "Ana")).assertExists()
        compose.onNodeWithText(text(R.string.chat_message_label)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsNotEnabled()
        // El aviso lleva al detalle (donde se vuelve a emparejar).
        compose.onNodeWithText(text(R.string.chat_view_contact)).performClick()
        assertEquals(listOf("contact"), events)
        // Sin el aviso de "no conectado": el que manda es el de la identidad.
        compose.onAllNodesWithText(text(R.string.chat_contact_offline)).assertCountEquals(0)
    }

    @Test
    fun stoppedEngineDisablesSend() {
        state = ana(engineOnline = false, presence = null)
        draft = "hola"
        show()
        compose.onNodeWithText("Activa GhostP2 para enviar").assertExists()
        compose.onNodeWithText(text(R.string.chat_message_label)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsNotEnabled().performClick()
        assertTrue("send" !in events)
        compose.onAllNodesWithText(text(R.string.chat_contact_offline)).assertCountEquals(0)
    }

    @Test
    fun offlineContactWarnsButCanSend() {
        state = ana(presence = Presence.CONNECTING)
        show()
        compose.onNodeWithText("Tu contacto no está conectado: lo que envíes se descartará").assertExists()
        // Vacío: no se puede enviar.
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.chat_message_label)).assertIsEnabled().performTextInput("hola")
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsEnabled().performClick()
        assertEquals(listOf("draft", "send"), events)
    }

    @Test
    fun onlineContactHasNoWarning() {
        show()
        compose.onAllNodesWithText(text(R.string.chat_contact_offline)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.chat_engine_stopped)).assertCountEquals(0)
        compose.onNodeWithText("Ana").assertExists()
    }

    @Test
    fun counterAppearsPastTheThreshold() {
        draft = "x".repeat(COUNTER_FROM)
        show()
        compose.onAllNodesWithText("${COUNTER_FROM} / ${PeerSession.MAX_TEXT_CHARS}").assertCountEquals(0)
        draft = "x".repeat(COUNTER_FROM + 1)
        compose.onNodeWithText("${COUNTER_FROM + 1} / ${PeerSession.MAX_TEXT_CHARS}").assertExists()
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsEnabled()
        // Por encima del límite (en caracteres Unicode) no se puede enviar.
        draft = "😀".repeat(PeerSession.MAX_TEXT_CHARS + 1)
        compose.onNodeWithText("${PeerSession.MAX_TEXT_CHARS + 1} / ${PeerSession.MAX_TEXT_CHARS}").assertExists()
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsNotEnabled()
    }

    @Test
    fun menuOpensContactAndClearsAfterConfirming() {
        show()
        compose.onNodeWithContentDescription(text(R.string.chat_menu)).performClick()
        compose.onNodeWithText("Ver contacto").performClick()
        assertEquals(listOf("contact"), events)

        compose.onNodeWithContentDescription(text(R.string.chat_menu)).performClick()
        compose.onNodeWithText("Borrar chat").performClick()
        compose.onNodeWithText("Se borrará de la memoria de este móvil").assertExists()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        assertEquals(listOf("contact"), events)

        compose.onNodeWithContentDescription(text(R.string.chat_menu)).performClick()
        compose.onNodeWithText("Borrar chat").performClick()
        compose.onNodeWithText(text(R.string.contact_clear_chat_confirm)).performClick()
        assertEquals(listOf("contact", "clear"), events)
        compose.onAllNodesWithText("Se borrará de la memoria de este móvil").assertCountEquals(0)
    }

    @Test
    fun backCallsOnBack() {
        show()
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        assertEquals(listOf("back"), events)
    }

    @Test
    fun scrollsToTheNewestItem() {
        val many = (1..60).map { out("m$it", "mensaje $it", DeliveryStatus.Delivered) }
        state = ana(items = many)
        show()
        compose.onNodeWithText("mensaje 60").assertIsDisplayed()
        state = ana(items = many + ChatItem("n", Direction.IN, ChatContent.Text("el nuevo"), DeliveryStatus.Received, 0))
        compose.waitForIdle()
        compose.onNodeWithText("el nuevo").assertIsDisplayed()
    }

    @Test
    fun loadingShowsNoInput() {
        state = null
        show()
        compose.onAllNodesWithText(text(R.string.chat_message_label)).assertCountEquals(0)
    }

    private companion object {
        const val COUNTER_FROM = 7_500

        fun out(id: String, text: String, status: DeliveryStatus) = ChatItem(id, Direction.OUT, ChatContent.Text(text), status, 0)

        fun ana(
            presence: Presence? = Presence.ONLINE,
            engineOnline: Boolean = true,
            identityChanged: Boolean = false,
            items: List<ChatItem> = emptyList(),
        ) = ChatUiState("Ana", presence, engineOnline, identityChanged, items)
    }
}
