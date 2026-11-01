package org.ghostp2.app.ui.pairing

import android.Manifest
import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.ghostp2.app.TestGhostApp
import org.ghostp2.engine.InvitationState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrScannerTest {
    @get:Rule
    val compose = createComposeRule()

    private var activity: Activity? = null

    @Test
    fun deniedPermissionOffersPaste() {
        var pastes = 0
        compose.setContent {
            activity = LocalActivity.current
            QrScanner(onInvitation = {}, onUsePaste = { pastes++ })
        }
        compose.waitForIdle()

        // Se pide la cámara al mostrarse; Robolectric no la concede.
        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), shadowOf(activity).lastRequestedPermission?.requestedPermissions)
        compose.onNodeWithText("Sin permiso de cámara. Puedes pegar la invitación").assertExists()
        compose.onNodeWithText("Pegar").performClick()
        assertEquals(1, pastes)
    }

    @Test
    fun cameraIsRequestedOnlyOnTheScanTab() {
        compose.setContent {
            activity = LocalActivity.current
            AddContactScreen(
                online = true,
                invitation = InvitationState.Preparing,
                accept = AcceptUi.Idle,
                onCreateInvitation = {},
                onAccept = {},
                onActivate = {},
                onBack = {},
            )
        }
        compose.waitForIdle()
        assertNull(shadowOf(activity).lastRequestedPermission)

        compose.onNodeWithText("Escanear").performClick()
        compose.waitForIdle()
        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), shadowOf(activity).lastRequestedPermission?.requestedPermissions)

        // Denegada: el botón "Pegar" (no la pestaña) lleva a pegar el texto.
        compose.onAllNodesWithText("Pegar").assertCountEquals(2)
        compose.onNode(hasText("Pegar") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.onNodeWithText("Pega aquí el texto de la invitación que te ha dado tu contacto.").assertExists()
    }
}
