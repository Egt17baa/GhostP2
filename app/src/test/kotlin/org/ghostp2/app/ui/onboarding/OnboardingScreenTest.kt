package org.ghostp2.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.ui.ActivityActions
import org.ghostp2.app.ui.onboarding.OnboardingStep.BATTERY
import org.ghostp2.app.ui.onboarding.OnboardingStep.NOTIFICATIONS
import org.ghostp2.app.ui.onboarding.OnboardingStep.WELCOME
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var notificationRequests = 0
    private var batteryRequests = 0
    private var finishes = 0

    private fun show(steps: List<OnboardingStep>, batteryIgnoring: Boolean = false) = compose.setContent {
        OnboardingScreen(
            steps = steps,
            batteryIgnoring = batteryIgnoring,
            onRequestNotifications = { notificationRequests++ },
            onRequestBattery = { batteryRequests++ },
            onFinish = { finishes++ },
        )
    }

    @Test
    fun welcomeExplainsDiscard() {
        show(listOf(WELCOME, NOTIFICATIONS, BATTERY))
        compose.onNodeWithText("se descarta", substring = true).assertExists()
        compose.onNodeWithText("sin servidores", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("Tor tarda", substring = true).assertExists()
    }

    @Test
    fun finishActivatesAndMarksDone() {
        show(listOf(WELCOME, NOTIFICATIONS, BATTERY))
        compose.onAllNodesWithText("Activar GhostP2").assertCountEquals(0)

        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText("Permitir notificaciones").performClick()
        assertEquals(1, notificationRequests)
        compose.onNodeWithText("Siguiente").performClick()

        compose.onNodeWithText("Quitar el ahorro de batería").performClick()
        assertEquals(1, batteryRequests)
        assertEquals(0, finishes)
        compose.onAllNodesWithText("Siguiente").assertCountEquals(0)
        compose.onNodeWithText("Activar GhostP2").performClick()
        assertEquals(1, finishes)
    }

    @Test
    fun welcomeAloneFinishesAtOnce() {
        show(listOf(WELCOME))
        compose.onAllNodesWithText("Siguiente").assertCountEquals(0)
        compose.onNodeWithText("Activar GhostP2").performClick()
        assertEquals(1, finishes)
    }

    @Test
    fun exemptBatteryHidesTheRequest() {
        show(listOf(BATTERY), batteryIgnoring = true)
        compose.onAllNodesWithText("Quitar el ahorro de batería").assertCountEquals(0)
        compose.onNodeWithText("ya no limita a GhostP2", substring = true).assertExists()
    }

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Vuelta de un diálogo o de los ajustes del sistema: onPause y onResume. */
    private fun pauseAndResume() {
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
    }

    /** [OnboardingRoute] con el estado real (de Robolectric) y un ciclo de vida que controla la prueba. */
    private fun showRoute(saveFailed: Boolean = false) {
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        val actions = ActivityActions(
            activate = {},
            disconnect = {},
            requestBatteryExemption = { batteryRequests++ },
            requestNotifications = { notificationRequests++ },
        )
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                OnboardingRoute(actions, saveFailed = saveFailed, onFinish = { finishes++ })
            }
        }
    }

    @Test
    fun batteryExemptionIsRereadOnResume() {
        val app = RuntimeEnvironment.getApplication()
        val power = shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        showRoute()
        compose.onNodeWithText("Siguiente").performClick() // bienvenida
        compose.onNodeWithText("Siguiente").performClick() // notificaciones (API 35 sin permiso)
        compose.onNodeWithText("Quitar el ahorro de batería").performClick()
        assertEquals(1, batteryRequests)

        // El usuario acepta en el diálogo del sistema y vuelve.
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        pauseAndResume()
        compose.onNodeWithText("ya no limita a GhostP2", substring = true).assertExists()
        compose.onAllNodesWithText("Quitar el ahorro de batería").assertCountEquals(0)
        // Los pasos no cambian: sigue siendo el último y se puede terminar.
        compose.onNodeWithText("Activar GhostP2").performClick()
        assertEquals(1, finishes)
    }

    @Test
    fun grantedNotificationsAreRereadOnResume() {
        showRoute()
        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText("Permitir notificaciones").performClick()
        assertEquals(1, notificationRequests)

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        pauseAndResume()
        compose.onNodeWithText("Notificaciones permitidas.").assertExists()
        compose.onAllNodesWithText("Permitir notificaciones").assertCountEquals(0)
        compose.onNodeWithText("Siguiente").assertExists() // sigue en este paso
    }

    @Test
    fun permanentlyDeniedNotificationsOfferTheAppSettings() {
        showRoute()
        compose.onNodeWithText("Siguiente").performClick()
        // Antes de pedirlo no está "bloqueado", aunque el sistema no dé justificación.
        compose.onAllNodesWithText("Abrir ajustes de notificaciones").assertCountEquals(0)
        compose.onNodeWithText("Permitir notificaciones").performClick()

        // Denegado y sin justificación (Robolectric no la da): el sistema ya no preguntará.
        pauseAndResume()
        compose.onNodeWithText("están bloqueadas", substring = true).assertExists()
        compose.onAllNodesWithText("Permitir notificaciones").assertCountEquals(0)
        compose.onNodeWithText("Abrir ajustes de notificaciones").performClick()

        val app = RuntimeEnvironment.getApplication()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun saveFailureIsShownNextToFinish() {
        compose.setContent {
            OnboardingScreen(
                steps = listOf(WELCOME, BATTERY),
                batteryIgnoring = false,
                onRequestNotifications = {},
                onRequestBattery = {},
                onFinish = { finishes++ },
                saveFailed = true,
            )
        }
        // Solo en el último paso, junto a "Activar GhostP2".
        compose.onAllNodesWithText(STORE_FAILED).assertCountEquals(0)
        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText(STORE_FAILED).assertExists()
        compose.onNodeWithText("Activar GhostP2").performClick()
        assertEquals(1, finishes)
    }

    private companion object {
        const val STORE_FAILED = "Ha ocurrido un error inesperado. Tus datos no se han tocado."
    }
}
