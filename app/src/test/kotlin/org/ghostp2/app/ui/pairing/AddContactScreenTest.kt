package org.ghostp2.app.ui.pairing

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.engine.InvitationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddContactScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** Contexto de la pantalla (la actividad): el portapapeles es el suyo. */
    private var context: Context? = null

    private var creates = 0
    private var activations = 0
    private val accepted = mutableListOf<String>()

    private fun show(online: Boolean, invitation: InvitationState?, accept: AcceptUi = AcceptUi.Idle) {
        compose.setContent {
            context = LocalContext.current
            AddContactScreen(
                online = online,
                invitation = invitation,
                accept = accept,
                onCreateInvitation = { creates++ },
                onAccept = { accepted += it },
                onActivate = { activations++ },
                onBack = {},
                clock = { NOW },
            )
        }
    }

    @Test
    fun waitingShowsQrTextAndCountdown() {
        show(online = true, invitation = InvitationState.Waiting(INVITATION, NOW + 125_000))

        compose.onNodeWithContentDescription("Código QR de tu invitación").assertExists()
        compose.onNodeWithText(INVITATION).assertExists()
        compose.onNodeWithText("Caduca en 02:05").assertExists()
        compose.onAllNodesWithText("Crear otra").assertCountEquals(0)
        // Ya hay una invitación: no se crea otra.
        assertEquals(0, creates)

        compose.onNodeWithText("Compartir").assertExists()
        compose.onNodeWithText("Copiar").performScrollTo().performClick()
        val clip = context!!.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(INVITATION, clip.getItemAt(0).text.toString())
        // Marcada como sensible: Android 13+ no la muestra en la vista previa del portapapeles.
        assertEquals(true, clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        compose.onNodeWithText("Invitación copiada").assertExists()
    }

    @Test
    fun notOnlineShowsActivate() {
        show(online = false, invitation = null)

        compose.onNodeWithText("Activa GhostP2 para añadir contactos").assertExists()
        compose.onAllNodesWithText("Copiar").assertCountEquals(0)
        compose.onNodeWithText("Activar").performClick()
        assertEquals(1, activations)
        assertEquals(0, creates)
    }

    @Test
    fun onlineWithoutInvitationCreatesOne() {
        show(online = true, invitation = null)
        compose.waitForIdle()
        assertEquals(1, creates)
    }

    @Test
    fun expiredOffersCreateAnother() {
        show(online = true, invitation = InvitationState.Expired)

        compose.onNodeWithText("La invitación ha caducado").assertExists()
        compose.onAllNodesWithText(INVITATION).assertCountEquals(0)
        compose.onNodeWithText("Crear otra").performClick()
        assertEquals(1, creates)
    }

    @Test
    fun failedShowsMessageAndCreateAnother() {
        show(online = true, invitation = InvitationState.Failed("No se pudo crear la invitación"))

        compose.onNodeWithText("No se pudo crear la invitación").assertExists()
        compose.onNodeWithText("Crear otra").performClick()
        assertEquals(1, creates)
    }

    @Test
    fun pasteTabAcceptsTheText() {
        show(online = true, invitation = InvitationState.Preparing)

        compose.onNodeWithText("Pegar").performClick()
        compose.onNodeWithText("Aceptar").assertIsNotEnabled()
        compose.onNodeWithText("Invitación").performTextInput(" $INVITATION\n")
        compose.onNodeWithText("Aceptar").performClick()
        assertEquals(listOf(" $INVITATION\n"), accepted)
    }

    @Test
    fun pasteTabShowsFailureAndProgress() {
        show(online = true, invitation = InvitationState.Preparing, accept = AcceptUi.Failed("Invitación no válida: formato incorrecto"))
        compose.onNodeWithText("Pegar").performClick()
        compose.onNodeWithText("Invitación no válida: formato incorrecto").assertExists()
    }

    @Test
    fun workingDisablesAccept() {
        show(online = true, invitation = InvitationState.Preparing, accept = AcceptUi.Working)
        compose.onNodeWithText("Pegar").performClick()
        compose.onNodeWithText("Emparejando…").assertExists()
        compose.onNodeWithText("Aceptar").assertIsNotEnabled()
    }

    @Test
    fun nameScreenShowsSafetyNumberAndSaves() {
        val saves = mutableListOf<Pair<String, Boolean>>()
        var later = 0
        compose.setContent {
            NameContactScreen(
                safetyNumber = "12345 67890 11111 22222 33333 44444",
                error = null,
                onSave = { name, verified -> saves += name to verified },
                onLater = { later++ },
            )
        }
        listOf("12345", "67890", "11111", "22222", "33333", "44444").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Comparadlo por otro canal (en persona o por llamada)").assertExists()

        compose.onNodeWithText("Nombre").performTextInput("Ana")
        compose.onNodeWithText("Hemos comprobado que coincide").performClick()
        compose.onNodeWithText("Hemos comprobado que coincide").assertIsOn()
        compose.onNodeWithText("Guardar").performClick()
        assertEquals(listOf("Ana" to true), saves)

        compose.onNodeWithText("Más tarde").performClick()
        assertEquals(1, later)
    }

    @Test
    fun nameScreenShowsError() {
        compose.setContent {
            NameContactScreen(safetyNumber = null, error = R.string.name_error_blank, onSave = { _, _ -> }, onLater = {})
        }
        compose.onNodeWithText("Escribe un nombre").assertExists()
    }

    private companion object {
        const val NOW = 1_000_000_000L
        val INVITATION = "ghostp2:" + "a".repeat(56) + "." + "b".repeat(26)
    }
}
