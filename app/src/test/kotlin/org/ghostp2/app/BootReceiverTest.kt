package org.ghostp2.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import org.ghostp2.data.StoreUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class BootReceiverTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp

    private fun settle(startOnBoot: Boolean, onboardingDone: Boolean) = runBlocking {
        app.storage.settings.update { it.copy(startOnBoot = startOnBoot, onboardingDone = onboardingDone) }
    }

    /** Entrega [action] al receptor y espera a que termine su trabajo en segundo plano. */
    private fun deliver(action: String) {
        BootReceiver().onReceive(app, Intent(action))
        runBlocking { BootReceiver.lastWork?.join() }
    }

    private fun startedService(): Intent? = shadowOf(app).nextStartedService

    @Test
    fun bootStartsServiceOnlyWhenEnabled() {
        settle(startOnBoot = false, onboardingDone = true)
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertNull(startedService())

        settle(startOnBoot = true, onboardingDone = true)
        deliver(Intent.ACTION_BOOT_COMPLETED)
        val started = startedService()!!
        assertEquals(GhostService::class.java.name, started.component?.className)
        assertEquals(GhostService.ACTION_START, started.action)
        assertTrue(started.getBooleanExtra(GhostService.EXTRA_FROM_BOOT, false))
    }

    @Test
    fun bootDoesNothingBeforeOnboarding() {
        settle(startOnBoot = true, onboardingDone = false)
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertNull(startedService())
    }

    @Test
    fun bootDoesNothingWhenStorageFails() {
        settle(startOnBoot = true, onboardingDone = true)
        app.storage.failure = StoreUnavailableException(IOException("x"))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertNull(startedService())

        app.storage.failure = null
        app.storage.settings.getFailure = IllegalStateException("ilegible")
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertNull(startedService())
    }

    @Test
    fun otherActionsAreIgnored() {
        settle(startOnBoot = true, onboardingDone = true)
        BootReceiver.lastWork = null
        for (action in listOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "org.ghostp2.OTRA")) {
            BootReceiver().onReceive(app, Intent(action))
        }
        assertNull(BootReceiver.lastWork)
        assertNull(startedService())
    }

    @Test
    fun receiverIsDeclaredForBootCompleted() {
        val found = app.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName), 0)
        assertEquals(listOf(BootReceiver::class.java.name), found.map { it.activityInfo.name })
        assertTrue(found.single().activityInfo.exported)
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in requested.orEmpty())
    }
}
