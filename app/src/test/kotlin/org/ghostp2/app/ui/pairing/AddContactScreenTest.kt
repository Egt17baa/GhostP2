package org.ghostp2.app.ui.pairing

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Menús de texto pedidos (copiar, pegar…): con un texto seleccionable, una pulsación larga abre uno. */
    private var textMenus = 0

    private val toolbar = object : TextToolbar {
        override val status get() = TextToolbarStatus.Hidden

        override fun hide() {}

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            textMenus++
        }
    }

    private fun show(
        online: Boolean,
        invitation: InvitationState?,
        accept: AcceptUi = AcceptUi.Idle,
        status: EngineStatus = if (online) EngineStatus.Online else EngineStatus.Stopped,
        startFailed: Boolean = false,
    ) {
        compose.setContent {
            context = LocalContext.current
            var pasted by remember { mutableStateOf("") }
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                AddContactScreen(
                    status = status,
                    invitation = invitation,
                    accept = accept,
                    pasted = pasted,
                    onPastedChange = { pasted = it },
                    onCreateInvitation = { creates++ },
                    onAccept = { accepted += it },
                    onActivate = { activations++ },
                    onBack = {},
                    startFailed = startFailed,
                    clock = { NOW },
                )
            }
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
    fun invitationTextIsNotSelectable() {
        show(online = true, invitation = InvitationState.Waiting(INVITATION, NOW + 125_000))

        val node = compose.onNodeWithText(INVITATION)
        // R24: ni acción de copiar ni de selección en el nodo…
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CopyText))
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetSelection))
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        // …y una pulsación larga no selecciona nada ni abre el menú de copiar.
        node.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, textMenus)
        assertNull(context!!.getSystemService(ClipboardManager::class.java).primaryClip)
    }

    @Test
    fun startingShowsConnectingInsteadOfActivate() {
        show(online = false, invitation = null, status = EngineStatus.StartingTor(40))

        compose.onNodeWithText("Conectando a la red Tor…", substring = true).assertExists()
        compose.onAllNodesWithText("Activa GhostP2 para añadir contactos").assertCountEquals(0)
        compose.onAllNodesWithText("Activar").assertCountEquals(0)
        assertEquals(0, creates)
    }

    @Test
    fun failedStartShowsErrorAndRetry() {
        show(online = false, invitation = null, startFailed = true)
        compose.onNodeWithText("Error inesperado").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, activations)
    }

    @Test
    fun storageErrorShowsErrorAndRetry() {
        show(online = false, invitation = null, status = EngineStatus.Error("x", EngineErrorKind.STORAGE))
        compose.onNodeWithText("No se pudieron leer o guardar tus datos cifrados").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, activations)
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
        var saves = 0
        var later = 0
        var name by mutableStateOf("")
        var verified by mutableStateOf(false)
        compose.setContent {
            NameContactScreen(
                safetyNumber = "12345 67890 11111 22222 33333 44444",
                error = null,
                name = name,
                onNameChange = { name = it },
                verified = verified,
                onVerifiedChange = { verified = it },
                saving = false,
                onSave = { saves++ },
                onLater = { later++ },
            )
        }
        listOf("12345", "67890", "11111", "22222", "33333", "44444").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Comparadlo por otro canal (en persona o por llamada)").assertExists()

        compose.onNodeWithText("Nombre").performTextInput("Ana")
        compose.onNodeWithText("Hemos comprobado que coincide").performClick()
        compose.onNodeWithText("Hemos comprobado que coincide").assertIsOn()
        compose.onNodeWithText("Guardar").performClick()
        assertEquals(1, saves)
        assertEquals("Ana", name)
        assertTrue(verified)

        compose.onNodeWithText("Más tarde").performClick()
        assertEquals(1, later)
    }

    @Test
    fun nameScreenWhileSavingDisablesButtons() {
        compose.setContent {
            NameContactScreen("12345 67890 11111 22222 33333 44444", null, "Ana", {}, false, {}, saving = true, onSave = {}, onLater = {})
        }
        compose.onNodeWithText("Guardar").assertIsNotEnabled()
        compose.onNodeWithText("Más tarde").assertIsNotEnabled()
    }

    @Test
    fun nameScreenShowsError() {
        compose.setContent {
            NameContactScreen(null, R.string.name_error_blank, "", {}, false, {}, saving = false, onSave = {}, onLater = {})
        }
        compose.onNodeWithText("Escribe un nombre").assertExists()
    }

    private companion object {
        const val NOW = 1_000_000_000L
        val INVITATION = "ghostp2:" + "a".repeat(56) + "." + "b".repeat(26)
    }
}
