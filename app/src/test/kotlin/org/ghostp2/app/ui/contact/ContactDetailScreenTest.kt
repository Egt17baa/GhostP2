package org.ghostp2.app.ui.contact

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS
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
class ContactDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<String>()

    private var state by mutableStateOf<ContactDetailUiState?>(ana())
    private var busy by mutableStateOf(false)
    private var renameDraft by mutableStateOf<String?>(null)
    private var renameError by mutableStateOf<Int?>(null)
    private var actionError by mutableStateOf<Int?>(null)
    private var deleteResult by mutableStateOf<UnpairDelivery?>(null)
    private var lastName by mutableStateOf<String?>(null)

    private fun show() = compose.setContent {
        ContactDetailScreen(
            state = state,
            busy = busy,
            renameDraft = renameDraft,
            renameError = renameError,
            actionError = actionError,
            deleteResult = deleteResult,
            onBack = { events += "back" },
            onStartRename = { events += "startRename" },
            onRenameDraftChange = { events += "draft:$it" },
            onRename = { events += "rename" },
            onCancelRename = { events += "cancelRename" },
            onSetVerified = { events += "verified:$it" },
            onRepair = { events += "repair" },
            onClearChat = { events += "clearChat" },
            onDelete = { events += "delete" },
            onDeleteDone = { events += "deleteDone" },
            lastName = lastName,
        )
    }

    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test
    fun safetyNumberIsGrouped() {
        show()
        // Seis grupos sueltos (en dos filas de tres), nunca la cadena entera de una pieza.
        SAFETY.split(' ').forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodesWithText(SAFETY).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.name_safety_compare)).assertExists()
        compose.onNodeWithText("Ana").assertExists()
        compose.onNodeWithText("${text(R.string.contacts_online)} · ${text(R.string.contact_not_verified)}").assertExists()
    }

    @Test
    fun missingSafetyNumberSaysSo() {
        state = ana().copy(safetyNumber = null)
        show()
        compose.onNodeWithText(text(R.string.name_safety_unavailable)).assertExists()
    }

    @Test
    fun identityChangedShowsRepairAction() {
        state = ana().copy(identityChanged = true, presence = Presence.IDENTITY_CHANGED)
        show()
        compose.onNodeWithText(text(R.string.contact_identity_changed, "Ana")).assertExists()
        // Verificar una identidad que ya no es la suya no tiene sentido.
        compose.onAllNodesWithText(text(R.string.contact_verify)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.contact_repair)).performClick()
        assertEquals(listOf("repair"), events)
    }

    @Test
    fun verifyLabelsToggle() {
        show()
        compose.onNodeWithText(text(R.string.contact_verify)).performScrollTo().performClick()
        state = ana().copy(verified = true)
        compose.onNodeWithText(text(R.string.contact_unverify)).performScrollTo().performClick()
        compose.onNodeWithText("${text(R.string.contacts_online)} · ${text(R.string.contacts_verified)}").assertExists()
        assertEquals(listOf("verified:true", "verified:false"), events)
    }

    @Test
    fun busyOrDeletingDisablesActions() {
        busy = true
        show()
        compose.onNodeWithText(text(R.string.contact_verify)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.contact_rename)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.contact_delete)).performScrollTo().assertIsNotEnabled()
        busy = false
        state = ana().copy(deleting = true)
        compose.onNodeWithText(text(R.string.contacts_deleting)).assertExists()
        compose.onNodeWithText(text(R.string.contact_delete)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.contact_clear_chat)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.contact_verify)).assertIsNotEnabled()
    }

    @Test
    fun deleteAsksForConfirmation() {
        show()
        compose.onNodeWithText(text(R.string.contact_delete)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.contact_delete_title, "Ana")).assertExists()
        compose.onNodeWithText(text(R.string.contact_delete_text)).assertExists()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onAllNodesWithText(text(R.string.contact_delete_title, "Ana")).assertCountEquals(0)
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithText(text(R.string.contact_delete)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.contact_delete_confirm)).performClick()
        assertEquals(listOf("delete"), events)
    }

    @Test
    fun deliveredResultThenBackToTheList() {
        show()
        state = ana().copy(deleting = true)
        compose.waitForIdle()
        // El contacto ya no existe y llega el resultado: se sigue viendo su nombre en el aviso.
        state = null
        deleteResult = UnpairDelivery.DELIVERED
        compose.onNodeWithText(text(R.string.contact_delete_delivered, "Ana")).assertExists()
        compose.onNodeWithText(text(R.string.action_ok)).performClick()
        assertEquals(listOf("deleteDone"), events)
    }

    @Test
    fun resultWithoutAPreviousStateUsesTheViewModelName() {
        // Como tras girar la pantalla con el contacto ya borrado: esta composición nunca lo vio.
        state = null
        lastName = "Ana"
        deleteResult = UnpairDelivery.DELIVERED
        show()
        compose.onNodeWithText(text(R.string.contact_delete_delivered, "Ana")).assertExists()
    }

    @Test
    fun notDeliveredResult() {
        show()
        compose.waitForIdle()
        state = null
        deleteResult = UnpairDelivery.NOT_DELIVERED
        compose.onNodeWithText(text(R.string.contact_delete_not_delivered, "Ana")).assertExists()
    }

    @Test
    fun clearChatAsksForConfirmation() {
        show()
        compose.onNodeWithText(text(R.string.contact_clear_chat)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.contact_clear_chat_title, "Ana")).assertExists()
        compose.onNodeWithText(text(R.string.contact_clear_chat_text)).assertExists()
        compose.onNodeWithText(text(R.string.contact_clear_chat_confirm)).performClick()
        assertEquals(listOf("clearChat"), events)
    }

    @Test
    fun renameDialogEditsAndShowsErrors() {
        show()
        compose.onNodeWithText(text(R.string.contact_rename)).performClick()
        assertEquals(listOf("startRename"), events)
        renameDraft = "Ana"
        renameError = R.string.name_error_too_long
        compose.onNodeWithText(text(R.string.name_error_too_long, MAX_CONTACT_NAME_CHARS)).assertExists()
        compose.onNodeWithText(text(R.string.name_label)).performTextReplacement("Bea")
        compose.onNodeWithText(text(R.string.name_save)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        assertEquals(listOf("startRename", "draft:Bea", "rename", "cancelRename"), events)
    }

    @Test
    fun actionErrorIsShown() {
        actionError = R.string.name_error_save
        show()
        compose.onNodeWithText(text(R.string.name_error_save)).assertExists()
    }

    private companion object {
        const val SAFETY = "12345 67890 11111 22222 33333 44444"

        fun ana() = ContactDetailUiState(
            name = "Ana",
            verified = false,
            identityChanged = false,
            presence = Presence.ONLINE,
            safetyNumber = SAFETY,
            deleting = false,
        )
    }
}
