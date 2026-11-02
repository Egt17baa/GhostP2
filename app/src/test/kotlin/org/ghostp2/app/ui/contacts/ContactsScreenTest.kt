package org.ghostp2.app.ui.contacts

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.ghostp2.app.DeleteOutcome
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.ui.Routes
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ContactsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    private val opened = mutableListOf<ContactRow>()
    private var adds = 0
    private var settings = 0
    private var retries = 0
    private var disconnects = 0

    private var deleteNotice by mutableStateOf<DeleteOutcome?>(null)
    private val noticesShown = mutableListOf<String>()

    private fun show(state: ContactsUiState, canDisconnect: Boolean = false) {
        compose.setContent {
            ContactsScreen(
                state = state,
                banner = { Text("AVISO") },
                onOpen = { opened += it },
                onAdd = { adds++ },
                onSettings = { settings++ },
                onRetry = { retries++ },
                onDisconnect = if (canDisconnect) ({ disconnects++ }) else null,
                deleteNotice = deleteNotice,
                onDeleteNoticeShown = {
                    noticesShown += it
                    deleteNotice = null
                },
            )
        }
    }

    /** La fila (un solo nodo, con sus textos unidos) de [name] con [status]. */
    private fun rowOf(name: String, status: String) = hasText(name) and hasText(status)

    /** Etiqueta de la acción de pulsar de un nodo. */
    private fun clickLabel(matcher: SemanticsMatcher): String? =
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.OnClick].label

    private fun row(
        id: String,
        name: String,
        presence: Presence? = null,
        verified: Boolean = false,
        identityChanged: Boolean = false,
        deleting: Boolean = false,
    ) = ContactRow(id, name, presence, verified, identityChanged, deleting)

    @Test
    fun rowShowsPresenceAndVerified() {
        val ana = row("a", "Ana", Presence.ONLINE, verified = true)
        show(ContactsUiState.Loaded(listOf(ana, row("b", "Bruno", Presence.CONNECTING), row("c", "Carla", Presence.OFFLINE), row("d", "Dani"))))

        compose.onNodeWithText("AVISO").assertExists()
        // Cada estado, en la fila de su contacto.
        compose.onNode(rowOf("Ana", "en línea") and hasText("verificado")).assertExists()
        compose.onNode(rowOf("Bruno", "conectando…")).assertExists()
        // OFFLINE y sin presencia (motor parado): "desconectado".
        compose.onNode(rowOf("Carla", "desconectado")).assertExists()
        compose.onNode(rowOf("Dani", "desconectado")).assertExists()
        compose.onAllNodesWithText("desconectado").assertCountEquals(2)
        compose.onAllNodesWithText("verificado").assertCountEquals(1)

        compose.onNode(rowOf("Ana", "en línea") and hasClickAction()).performClick()
        assertEquals(listOf(ana), opened)
        assertEquals(Routes.chat("a"), openRoute(ana))
        // La acción de pulsar dice adónde lleva.
        assertEquals(text(R.string.contacts_open_chat), clickLabel(hasText("Ana") and hasClickAction()))
    }

    @Test
    fun identityChangedRowOpensDetail() {
        val bruno = row("b", "Bruno", Presence.IDENTITY_CHANGED, identityChanged = true)
        show(ContactsUiState.Loaded(listOf(bruno)))

        compose.onNode(rowOf("Bruno", "Identidad cambiada · vuelve a emparejar")).assertExists()
        compose.onAllNodesWithText("desconectado").assertCountEquals(0)
        compose.onNode(hasText("Bruno") and hasClickAction()).performClick()
        assertEquals(listOf(bruno), opened)
        assertEquals(Routes.contact("b"), openRoute(bruno))
        assertEquals(text(R.string.chat_view_contact), clickLabel(hasText("Bruno") and hasClickAction()))
    }

    @Test
    fun deletingRowIsNotClickable() {
        show(ContactsUiState.Loaded(listOf(row("d", "Dani", Presence.ONLINE, deleting = true))))

        compose.onNode(rowOf("Dani", "Borrando…")).assertExists().assertHasNoClickAction()
        compose.onAllNodesWithText("en línea").assertCountEquals(0)
        compose.onNode(rowOf("Dani", "Borrando…")).performClick()
        assertEquals(emptyList<ContactRow>(), opened)
    }

    @Test
    fun deleteResultIsShownOnceAsASnackbar() {
        deleteNotice = DeleteOutcome("a", "Ana", UnpairDelivery.DELIVERED)
        show(ContactsUiState.Loaded(emptyList()))

        compose.onNodeWithText(text(R.string.contact_delete_delivered, "Ana")).assertExists()
        assertEquals(listOf("a"), noticesShown)

        deleteNotice = DeleteOutcome("b", "Bruno", UnpairDelivery.NOT_DELIVERED)
        compose.onNodeWithText(text(R.string.contact_delete_not_delivered, "Bruno")).assertExists()
        assertEquals(listOf("a", "b"), noticesShown)
    }

    @Test
    fun emptyStateOffersAddContact() {
        show(ContactsUiState.Loaded(emptyList()))

        compose.onNodeWithText("Aún no tienes contactos").assertExists()
        // El botón con texto (no el icono de la barra, que solo tiene descripción).
        compose.onNode(hasText("Añadir contacto") and hasClickAction()).performClick()
        assertEquals(1, adds)
        // Barra superior: añadir y ajustes; sin "Desconectar" con el motor parado.
        compose.onNodeWithContentDescription("Añadir contacto").performClick()
        assertEquals(2, adds)
        compose.onNodeWithContentDescription("Ajustes").performClick()
        assertEquals(1, settings)
        compose.onAllNodesWithContentDescription("Desconectar").assertCountEquals(0)
    }

    @Test
    fun failedStateOffersRetry() {
        show(ContactsUiState.Failed)

        compose.onNodeWithText("No se pudieron cargar tus contactos").assertExists()
        compose.onAllNodesWithText("Aún no tienes contactos").assertCountEquals(0)
        compose.onNode(hasText("Reintentar") and hasClickAction()).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun runningEngineOffersDisconnect() {
        show(ContactsUiState.Loading, canDisconnect = true)

        compose.onNodeWithContentDescription("Desconectar").performClick()
        assertEquals(1, disconnects)
        compose.onAllNodesWithText("Aún no tienes contactos").assertCountEquals(0)
    }
}
