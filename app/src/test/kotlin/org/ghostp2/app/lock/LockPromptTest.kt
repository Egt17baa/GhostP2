package org.ghostp2.app.lock

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.fingerprint.FingerprintManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import org.ghostp2.app.MainActivity
import org.ghostp2.app.TestGhostApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowFingerprintManager

/**
 * `BiometricPrompt` de verdad sobre [MainActivity] en API 27 (el camino de compatibilidad de biometric
 * 1.1.0, con fragmentos en la actividad). El resultado se entrega por [LockPrompt.handleSucceeded] y
 * [LockPrompt.handleError], que es lo que llama el `AuthenticationCallback`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class, sdk = [27])
class LockPromptTest {
    /** Sin contenido propio: hace que Compose dibuje los fotogramas de la actividad lanzada en cada prueba. */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private var controller: ActivityController<MainActivity>? = null
    private val activity get() = controller!!.get()

    @Before
    fun setUp() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = true, lockEnabled = true, lockAfterMinutes = 1) } }
        deviceSecure(true)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        runBlocking { app.container.engine.stop() }
    }

    /** Huella registrada y bloqueo de pantalla seguro (o nada de eso). */
    @Suppress("DEPRECATION") // FingerprintManager: lo que usa biometric 1.1.0 en API 27.
    private fun deviceSecure(secure: Boolean) {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_FINGERPRINT, true)
        val fingerprints = Shadow.extract<ShadowFingerprintManager>(app.getSystemService(FingerprintManager::class.java))
        fingerprints.setIsHardwareDetected(true)
        fingerprints.setDefaultFingerprints(if (secure) 1 else 0)
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceSecure(secure)
        keyguard.setIsKeyguardSecure(secure)
    }

    private fun eventually(condition: () -> Boolean) = compose.waitUntil(TIMEOUT) { condition() }

    private fun idle() = compose.waitForIdle()

    private fun promptFragments() =
        activity.supportFragmentManager.fragments.count { it.tag == BIOMETRIC_FRAGMENT_TAG }

    private fun removePrompt() {
        val fm = activity.supportFragmentManager
        fm.findFragmentByTag(BIOMETRIC_FRAGMENT_TAG)?.let { fm.beginTransaction().remove(it).commitNow() }
    }

    /** Arranque en frío con el bloqueo: la actividad se reanuda y pide la huella sola. */
    private fun launchLocked() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        eventually { activity.supportFragmentManager.findFragmentByTag(BIOMETRIC_FRAGMENT_TAG) != null }
        idle()
        assertTrue(app.container.lock.locked.value)
    }

    @Test
    fun coldStartPromptsWithoutCrashingOnApi27() {
        launchLocked()
        repeat(5) { idle() }
        assertEquals(1, promptFragments())
        // Con core 1.18, FingerprintManagerCompat no ve la huella: en API 26-27 biometric 1.1.0 pasa a la
        // credencial del dispositivo de KeyguardManager. (Si algún día muestra su diálogo de huella, el tema
        // AppCompat lo cubre: MainActivityTest.themeSupportsAppCompatDialogs.)
        assertEquals(
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL",
            shadowOf(activity).peekNextStartedActivityForResult()?.intent?.action,
        )
        assertTrue(app.container.lock.locked.value)
    }

    @Test
    fun successUnlocks() {
        launchLocked()
        activity.lockPrompt.handleSucceeded()
        assertFalse(app.container.lock.locked.value)
    }

    @Test
    fun cancelStaysLockedWithoutPromptingAgainUntilResume() {
        launchLocked()
        removePrompt()
        activity.lockPrompt.handleError(BiometricPrompt.ERROR_USER_CANCELED)
        idle()
        assertTrue(app.container.lock.locked.value)
        assertEquals(0, promptFragments())

        // Al volver a primer plano se vuelve a pedir.
        controller!!.pause().resume()
        eventually { promptFragments() == 1 }
        assertTrue(app.container.lock.locked.value)
    }

    @Test
    fun returningFromThePromptItselfDoesNotPromptAgain() {
        launchLocked()
        // El diálogo pausa la actividad (credencial en otra pantalla) y el usuario lo cancela.
        controller!!.pause()
        removePrompt()
        activity.lockPrompt.handleError(BiometricPrompt.ERROR_USER_CANCELED)
        controller!!.resume()
        idle()
        assertEquals(0, promptFragments())
        assertTrue(app.container.lock.locked.value)
    }

    @Test
    fun noDeviceCredentialErrorRechecksThenDisables() {
        launchLocked()
        // Con el bloqueo de pantalla aún puesto, el error no desactiva nada.
        activity.lockPrompt.handleError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)
        assertTrue(app.container.lock.locked.value)

        deviceSecure(false)
        activity.lockPrompt.handleError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)
        assertFalse(app.container.lock.locked.value)
        assertTrue(app.container.lock.missingDeviceLockNotice.value)
        eventually { runBlocking { !app.storage.settings.get().lockEnabled } }
    }

    @Test
    fun doubleRequestShowsOnePrompt() {
        launchLocked()
        assertFalse(activity.lockPrompt.show("Desbloquear GhostP2"))
        idle()
        assertEquals(1, promptFragments())
    }

    @Test
    fun rotationKeepsASinglePrompt() {
        launchLocked()
        controller!!.recreate()
        idle()
        eventually { promptFragments() == 1 }
        assertNotNull(activity.supportFragmentManager.findFragmentByTag(BIOMETRIC_FRAGMENT_TAG))
        assertTrue(app.container.lock.locked.value)
    }

    @Test
    fun noPromptWithoutLock() {
        runBlocking { app.storage.settings.update { it.copy(lockEnabled = false) } }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        eventually { !app.container.lock.locked.value }
        idle()
        assertNull(activity.supportFragmentManager.findFragmentByTag(BIOMETRIC_FRAGMENT_TAG))
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
