package org.ghostp2.app.ui.pairing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
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
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun withCamera() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    /** Escáner con una vista de cámara falsa (sin CameraX). */
    private fun showScanner(onUsePaste: () -> Unit = {}) {
        compose.setContent {
            activity = LocalActivity.current
            QrScannerContent(onUsePaste = onUsePaste) { Text("vista de la cámara") }
        }
        compose.waitForIdle()
    }

    /** Respuesta del diálogo del sistema a la última petición de permiso. */
    private fun answerPermission(granted: Boolean) {
        val request = shadowOf(activity).lastRequestedPermission!!
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        compose.runOnIdle {
            activity!!.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
        }
        compose.waitForIdle()
    }

    @Test
    fun grantingThePermissionShowsTheCamera() {
        showScanner()
        compose.onNodeWithText("Sin permiso de cámara. Puedes pegar la invitación").assertExists()
        compose.onAllNodesWithText("vista de la cámara").assertCountEquals(0)

        answerPermission(granted = true)
        compose.onNodeWithText("vista de la cámara").assertExists()
        compose.onAllNodesWithText("Sin permiso de cámara. Puedes pegar la invitación").assertCountEquals(0)
    }

    @Test
    fun alreadyGrantedShowsTheCameraWithoutAsking() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        showScanner()
        compose.onNodeWithText("vista de la cámara").assertExists()
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun deniedOnceWithRationaleDoesNotOfferSettings() {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, true)
        showScanner()
        answerPermission(granted = false)
        compose.onNodeWithText("Sin permiso de cámara. Puedes pegar la invitación").assertExists()
        compose.onAllNodesWithText("Abrir ajustes").assertCountEquals(0)
    }

    @Test
    fun permanentlyDeniedOffersAppSettings() {
        showScanner()
        // Antes de la respuesta no está "bloqueado", aunque no haya justificación.
        compose.onAllNodesWithText("Abrir ajustes").assertCountEquals(0)

        // Denegado y sin justificación: el sistema ya no preguntará.
        answerPermission(granted = false)
        compose.onNodeWithText("El permiso de cámara está bloqueado", substring = true).assertExists()
        compose.onNodeWithText("Pegar").assertExists()
        compose.onNodeWithText("Abrir ajustes").performClick()

        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${app.packageName}", opened.dataString)
    }

    @Test
    fun noCameraOffersPasteWithoutAsking() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        var pastes = 0
        showScanner(onUsePaste = { pastes++ })

        assertNull(shadowOf(activity).lastRequestedPermission)
        compose.onNodeWithText("Este móvil no tiene cámara. Puedes pegar la invitación").assertExists()
        compose.onNodeWithText("Pegar").performClick()
        assertEquals(1, pastes)
    }

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
                status = EngineStatus.Online,
                invitation = InvitationState.Preparing,
                accept = AcceptUi.Idle,
                pasted = "",
                onPastedChange = {},
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
