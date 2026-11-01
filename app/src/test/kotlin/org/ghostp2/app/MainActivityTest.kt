package org.ghostp2.app

import android.Manifest
import android.os.Looper
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.ghostp2.engine.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp

    @After
    fun tearDown() {
        runBlocking { app.container.engine.stop() }
    }

    @Test
    fun windowHasFlagSecure() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Sin capturas de pantalla ni miniatura en recientes.
        assertTrue(activity.window.attributes.flags and FLAG_SECURE != 0)
        // BiometricPrompt necesita una FragmentActivity.
        assertTrue(FragmentActivity::class.java.isInstance(activity))
    }

    /**
     * En API 26-28, biometric 1.1.0 puede mostrar su `FingerprintDialogFragment`, un `AlertDialog` de
     * AppCompat creado con la actividad: con un tema que no es AppCompat lanza al mostrarse y, como el
     * bloqueo se pide solo al abrir, la app se cerraría en cada arranque.
     */
    @Test
    @Config(sdk = [27])
    fun themeSupportsAppCompatDialogs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        AlertDialog.Builder(activity).setMessage("x").create().show()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Android puede negar el arranque del servicio en primer plano (`IllegalStateException`, en API 31+
     * `ForegroundServiceStartNotAllowedException`): la app no se cierra y el aviso sigue ofreciendo Activar.
     */
    @Test
    fun refusedServiceStartDoesNotCrash() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = true, lockEnabled = false) } }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var starts = 0
        controller.get().startForeground = {
            starts++
            throw IllegalStateException("startForegroundService() not allowed")
        }
        compose.waitUntilAtLeastOneExists(hasText("GhostP2 está desactivado"), TIMEOUT)
        compose.onNodeWithText("Activar").performClick()
        compose.waitUntil(TIMEOUT) { starts == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("Activar").assertExists()
        assertEquals(EngineStatus.Stopped, app.container.engine.status.value)
        controller.pause().stop().destroy()
    }

    /** Con la actividad ya en segundo plano (p. ej. al llegar tarde la respuesta del permiso) no se arranca. */
    @Test
    fun noServiceStartWhileInBackground() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var starts = 0
        controller.get().startForeground = { starts++ }
        controller.pause().stop()
        controller.get().startGhostService()
        assertEquals(0, starts)
        controller.start().resume()
        controller.get().startGhostService()
        assertEquals(1, starts)
        controller.pause().stop().destroy()
    }

    @Test
    fun backgroundLocksThroughActivityLifecycle() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = true, lockEnabled = false) } }
        app.container.deviceLockAvailable = { true }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val lock = app.container.lock
        eventually { !lock.locked.value }

        // Se activa "Inmediatamente" con la app abierta: no bloquea hasta salir.
        runBlocking { app.storage.settings.update { it.copy(lockEnabled = true, lockAfterMinutes = 0) } }
        eventually { lock.enabled }
        assertFalse(lock.locked.value)

        // Girar el móvil (la actividad se recrea) no cuenta como salir.
        controller.recreate()
        assertFalse(lock.locked.value)

        controller.pause()
        controller.stop()
        controller.start()
        assertTrue(lock.locked.value)
        controller.stop().destroy()
    }

    /** «Desconectar al salir» (R29) a través del ciclo de vida de la actividad. */
    @Test
    fun backgroundDisconnectsThroughActivityLifecycle() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = true, lockEnabled = false, disconnectAfterMinutes = 2) } }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        eventually { app.container.autoDisconnect.minutes == 2 }

        // Girar el móvil, o salir y volver enseguida, no corta nada.
        controller.recreate()
        controller.pause().stop().restart().resume()
        assertNull(shadowOf(app).nextStoppedService)

        // Fuera más del plazo: al volver se para el servicio antes de nada.
        controller.pause().stop()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        controller.restart()
        assertEquals(GhostService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        controller.resume().pause().stop().destroy()
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
