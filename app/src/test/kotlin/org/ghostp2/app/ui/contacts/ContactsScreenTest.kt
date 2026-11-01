package org.ghostp2.app.ui.contacts

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.ui.Routes
import org.ghostp2.engine.Presence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ContactsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<ContactRow>()
    private var adds = 0
    private var settings = 0
    private var retries = 0
    private var disconnects = 0

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
            )
        }
    }

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
        compose.onNodeWithText("en línea").assertExists()
        compose.onNodeWithText("conectando…").assertExists()
        // OFFLINE y sin presencia (motor parado): "desconectado".
        compose.onAllNodesWithText("desconectado").assertCountEquals(2)
        compose.onAllNodesWithText("verificado").assertCountEquals(1)

        compose.onNodeWithText("Ana").performClick()
        assertEquals(listOf(ana), opened)
        assertEquals(Routes.chat("a"), openRoute(ana))
    }

    @Test
    fun identityChangedRowOpensDetail() {
        val bruno = row("b", "Bruno", Presence.IDENTITY_CHANGED, identityChanged = true)
        show(ContactsUiState.Loaded(listOf(bruno)))

        compose.onNodeWithText("Identidad cambiada · vuelve a emparejar").assertExists()
        compose.onAllNodesWithText("desconectado").assertCountEquals(0)
        compose.onNodeWithText("Bruno").performClick()
        assertEquals(listOf(bruno), opened)
        assertEquals(Routes.contact("b"), openRoute(bruno))
    }

    @Test
    fun deletingRowIsNotClickable() {
        show(ContactsUiState.Loaded(listOf(row("d", "Dani", Presence.ONLINE, deleting = true))))

        compose.onNodeWithText("Borrando…").assertExists()
        compose.onAllNodesWithText("en línea").assertCountEquals(0)
        compose.onNodeWithText("Dani").assertHasNoClickAction()
        compose.onNodeWithText("Dani").performClick()
        assertEquals(emptyList<ContactRow>(), opened)
    }

    @Test
    fun emptyStateOffersAddContact() {
        show(ContactsUiState.Loaded(emptyList()))

        compose.onNodeWithText("Aún no tienes contactos").assertExists()
        compose.onNodeWithText("Añadir contacto").performClick()
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
        compose.onNodeWithText("Reintentar").performClick()
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
