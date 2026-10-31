package org.ghostp2.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.data.StoreCorruptedException
import org.ghostp2.data.StoreUnavailableException
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationState
import org.ghostp2.engine.Presence
import org.ghostp2.engine.TorState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        controller.get().onStartCommand(GhostService.stopIntent(app), 0, 2)

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
        assertEquals(1, app.tor.wipeStateCalls.get())
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

    @Test
    fun stickyRestartWithNullIntentStartsForegroundAndEngine() {
        val controller = Robolectric.buildService(GhostService::class.java).create()
        val service = controller.get()

        // Lo que hace el sistema al recrear un servicio START_STICKY tras matar el proceso.
        val mode = service.onStartCommand(null, Service.START_FLAG_RETRY, 1)

        assertEquals(Service.START_STICKY, mode)
        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        assertFalse(shadow.isForegroundStopped)
        eventually { engine.status.value == EngineStatus.Online }
        assertEquals(1, networkCallbacks().size)
    }

    @Test
    fun stickyRestartWithBrokenStorageStopsAndAsksToOpenTheApp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.storage.failure = StoreUnavailableException(IOException("x"))
        val service = Robolectric.buildService(GhostService::class.java).create().get()

        service.onStartCommand(null, Service.START_FLAG_RETRY, 1)

        eventually { shadowOf(service).isStoppedBySelf }
        assertEquals(EngineStatus.Stopped, engine.status.value)
        val titles = notificationManager().allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        assertTrue(titles.toString(), "GhostP2 se ha detenido" in titles)
    }

    @Test
    fun stickyRestartNotAllowedInForegroundStopsWithoutStartingEngine() {
        val service = Robolectric.buildService(GhostService::class.java).create().get()
        service.enterForeground = { throw ForegroundServiceStartNotAllowedException("segundo plano") }

        service.onStartCommand(null, Service.START_FLAG_RETRY, 1) // no lanza: sin bucle de cierres

        val shadow = shadowOf(service)
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(networkCallbacks().isEmpty())
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(TorState.Off, app.tor.state.value)
        assertEquals(StorageState.Unknown, app.container.storage.value) // ni siquiera se comprobó
    }

    @Test
    fun resetIsOfferedAfterMixedConsecutiveFailures() {
        val container = app.container
        app.storage.failure = StoreUnavailableException(IOException("x"))
        runBlocking { container.checkStorage() }
        app.storage.failure = IllegalStateException("inesperado")
        runBlocking { container.checkStorage() }
        val failed = container.storage.value as StorageState.Failed
        assertEquals(2, failed.failures)
        assertFalse(failed.canForceReset)
        app.storage.failure = StoreUnavailableException(IOException("x"))
        runBlocking { container.checkStorage() }
        assertTrue((container.storage.value as StorageState.Unavailable).canForceReset)

        app.storage.failure = null
        runBlocking { container.checkStorage() }
        assertEquals(StorageState.Ok, container.storage.value)
        app.storage.failure = IllegalStateException("inesperado")
        runBlocking { container.checkStorage() }
        assertEquals(StorageState.Failed(1), container.storage.value) // la racha empieza de nuevo
    }

    @Test
    fun unknownIntentStopsService() {
        val controller = Robolectric.buildService(GhostService::class.java, Intent("org.ghostp2.app.action.OTRA")).create()
        controller.startCommand(0, 1)
        val shadow = shadowOf(controller.get())

        assertTrue(shadow.isStoppedBySelf)
        assertTrue(networkCallbacks().isEmpty())
        assertEquals(EngineStatus.Stopped, engine.status.value)
    }

    @Test
    fun engineErrorTurnsStatusNotificationIntoOfflineWithDisconnect() {
        // Sin POST_NOTIFICATIONS: la notificación fija se actualiza con startForeground igualmente.
        val shadow = shadowOf(startService().get())
        eventually { engine.status.value == EngineStatus.Online }

        runBlocking { app.tor.stop() } // Tor se cae sin pedirlo: el motor pasa a Error (R18)

        eventually { engine.status.value is EngineStatus.Error }
        eventually {
            shadow.lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString() == "GhostP2 sin conexión · error"
        }
        assertFalse(shadow.isForegroundStopped)
        val n = shadow.lastForegroundNotification
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Desconectar", n.actions.single().title.toString())
    }

    @Test
    fun messageArrivedPostsNotificationWithOnlyTheContactName() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        startService()
        eventually { engine.status.value == EngineStatus.Online }
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking {
                peer.startAll()
                // El otro lado invita; GhostP2 (el del AppContainer) acepta.
                val handle = peer.engine(0).createInvitation()
                val waiting = withTimeout(TIMEOUT) {
                    handle.state.first { it is InvitationState.Waiting }
                } as InvitationState.Waiting
                assertTrue(engine.acceptInvitation(waiting.text) is AcceptResult.Paired)
                withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
                val peerInMine = app.storage.contacts.all().single().id
                engine.renameContact(peerInMine, "Ana")
                val mineInPeer = peer.contacts(0).all().single().id
                // ONLINE puede verse antes que la sesión: se reenvía hasta que llegue.
                withTimeout(TIMEOUT) {
                    while (engine.conversations.value[peerInMine].isNullOrEmpty()) {
                        if (peer.engine(0).presence.value[mineInPeer] == Presence.ONLINE) {
                            peer.engine(0).sendText(mineInPeer, SECRET)
                        }
                        delay(100)
                    }
                }
            }
        }
        eventually {
            notificationManager().allNotifications.any { it.channelId == Notifications.CHANNEL_MESSAGES }
        }
        val n = notificationManager().allNotifications.single { it.channelId == Notifications.CHANNEL_MESSAGES }
        assertEquals("Nuevo mensaje de Ana", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(n.extras.getCharSequence(Notification.EXTRA_TEXT))
        val keys = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
        )
        val everything = listOf(n, n.publicVersion).flatMap { m ->
            keys.mapNotNull { m.extras.getCharSequence(it)?.toString() } + listOfNotNull(m.tickerText?.toString())
        }
        assertFalse(everything.any { it.contains(SECRET) })
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
    }

    private fun notificationManager() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private companion object {
        const val TIMEOUT = 20_000L
        const val SECRET = "hola, esto es secreto"
    }
}
