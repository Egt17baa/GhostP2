package org.ghostp2.app

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class MainActivityTest {
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
}
