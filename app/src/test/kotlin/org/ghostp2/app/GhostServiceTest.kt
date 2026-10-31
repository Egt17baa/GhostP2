package org.ghostp2.app

import android.net.ConnectivityManager
import kotlinx.coroutines.runBlocking
import org.ghostp2.data.StoreCorruptedException
import org.ghostp2.data.StoreUnavailableException
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.TorState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class GhostServiceTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val engine get() = app.container.engine

    private fun networkCallbacks() =
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks

    private fun startService(): ServiceController<GhostService> =
        Robolectric.buildService(GhostService::class.java, GhostService.startIntent(app)).create().startCommand(0, 1)

    @After
    fun tearDown() {
        runBlocking { engine.stop() }
    }

    @Test
    fun serviceStartsInForeground() {
        val controller = startService()
        val shadow = shadowOf(controller.get())

        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(Notifications.STATUS_ID, shadow.lastForegroundNotificationId)
        assertFalse(shadow.isForegroundStopped)
        assertEquals(Notifications.CHANNEL_STATUS, shadow.lastForegroundNotification.channelId)
        // El motor del AppContainer (con FakeTor) arranca.
        eventually { engine.status.value == EngineStatus.Online }
        assertEquals(TorState.Ready, app.tor.state.value)
        assertEquals(1, networkCallbacks().size)
        // Un cambio de red solo reinicia las esperas: el motor sigue en línea.
        networkCallbacks().first().onAvailable(ShadowNetwork.newInstance(7))
        assertEquals(EngineStatus.Online, engine.status.value)
    }

    @Test
    fun stopActionStopsService() {
        val controller = startService()
        val shadow = shadowOf(controller.get())
        eventually { engine.status.value == EngineStatus.Online }

        controller.withIntent(GhostService.stopIntent(app)).startCommand(0, 2)

        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(networkCallbacks().isEmpty())
        eventually { engine.status.value == EngineStatus.Stopped && app.tor.state.value == TorState.Off }
    }

    @Test
    fun destroyStopsEngineAndUnregistersNetworkCallback() {
        val controller = startService()
        eventually { engine.status.value == EngineStatus.Online }

        controller.destroy()

        assertTrue(networkCallbacks().isEmpty())
        eventually { engine.status.value == EngineStatus.Stopped }
    }

    @Test
    fun corruptedStorageNeverStartsEngine() {
        app.storage.failure = StoreCorruptedException(IOException("x"))
        val controller = startService()
        val shadow = shadowOf(controller.get())

        eventually { shadow.isStoppedBySelf }
        assertEquals(StorageState.Corrupted, app.container.storage.value)
        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(TorState.Off, app.tor.state.value)
        assertTrue(networkCallbacks().isEmpty())
    }

    @Test
    fun unavailableStorageCountsFailuresAndOffersResetAfterRepeatedOnes() {
        app.storage.failure = StoreUnavailableException(IOException("x"))
        val container = app.container

        repeat(StorageState.FAILURES_BEFORE_RESET - 1) {
            runBlocking { container.checkStorage() }
        }
        val before = container.storage.value as StorageState.Unavailable
        assertFalse(before.canForceReset)

        runBlocking { container.checkStorage() }
        val after = container.storage.value as StorageState.Unavailable
        assertEquals(StorageState.FAILURES_BEFORE_RESET, after.failures)
        assertTrue(after.canForceReset)

        runBlocking { container.forceReset() }
        assertEquals(1, app.storage.forceResets)
        assertEquals(StorageState.Ok, container.storage.value)
    }

    @Test
    fun wipeCorruptedStartsFromScratch() {
        app.storage.failure = StoreCorruptedException(IOException("x"))
        val container = app.container
        runBlocking { container.checkStorage() }
        assertEquals(StorageState.Corrupted, container.storage.value)
        app.storage.failure = null

        runBlocking { container.wipeCorrupted() }

        assertEquals(StorageState.Ok, container.storage.value)
        assertEquals(1, app.tor.wipeStateCalls.get())
    }
}
