package org.ghostp2.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.lock.Armed
import org.ghostp2.app.lock.ArmedFile
import org.ghostp2.data.StoreCorruptedException
import org.ghostp2.data.StoreUnavailableException
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.Presence
import org.ghostp2.engine.TorState
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.BridgeSetup
import org.ghostp2.engine.bridges.BuiltInBridges
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.IOException
import java.time.Duration

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
                val (peerInMine, mineInPeer) = pairWithPeer(app, peer, TIMEOUT)
                engine.renameContact(peerInMine, "Ana")
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

    @Test
    fun stopUsesTheLatestStartIdSoAQueuedStartIsNotLost() {
        val controller = startService() // startId 1
        val service = controller.get()
        eventually { engine.status.value == EngineStatus.Online }

        service.onStartCommand(GhostService.stopIntent(app), 0, 2)

        // stopSelfResult(2): si el sistema ya tuviera encolado un START posterior (3), no se detendría.
        assertEquals(2, shadowOf(service).stopSelfResultId)
    }

    @Test
    fun brokenStorageStopsWithTheLatestStartId() {
        app.storage.failure = StoreUnavailableException(IOException("x"))
        val service = Robolectric.buildService(GhostService::class.java, GhostService.startIntent(app)).create().get()

        service.onStartCommand(GhostService.startIntent(app), 0, 5)

        eventually { shadowOf(service).isStoppedBySelf }
        assertEquals(5, shadowOf(service).stopSelfResultId)
    }

    @Test
    fun containerAndServiceScopesSwallowChildCrashes() {
        val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val service = startService().get()
            assertNotNull(app.container.scope.coroutineContext[CoroutineExceptionHandler])
            assertNotNull(service.scope.coroutineContext[CoroutineExceptionHandler])
            runBlocking {
                app.container.scope.launch { throw IllegalStateException("fallo simulado") }.join()
                service.scope.launch { throw IllegalStateException("fallo simulado") }.join()
                // Como los scopes del motor: SupervisorJob hijo que hereda el contexto del proceso.
                val child = CoroutineScope(app.container.scope.coroutineContext + SupervisorJob(app.container.scope.coroutineContext[Job]))
                child.launch { throw IllegalStateException("fallo simulado") }.join()
            }
            assertTrue(app.container.scope.isActive)
            assertTrue(service.scope.isActive)
            assertTrue(uncaught.toString(), uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun networkReturnRestartsAnEngineInErrorAtMostOncePer30Seconds() {
        var now = 1_000_000L
        val service = startService().get()
        service.elapsedRealtime = { now }
        eventually { engine.status.value == EngineStatus.Online }
        val callback = networkCallbacks().single()

        runBlocking { app.tor.stop() } // Tor se cae: Error (R18)
        eventually { engine.status.value is EngineStatus.Error }
        callback.onAvailable(ShadowNetwork.newInstance(1))
        eventually { engine.status.value == EngineStatus.Online } // un reinicio al volver la red

        runBlocking { app.tor.stop() }
        eventually { engine.status.value is EngineStatus.Error }
        now += 10_000
        callback.onAvailable(ShadowNetwork.newInstance(2))
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("antes de 30 s no se reinicia otra vez", engine.status.value is EngineStatus.Error)

        now += 25_000
        callback.onAvailable(ShadowNetwork.newInstance(3))
        eventually { engine.status.value == EngineStatus.Online }
    }

    @Test
    fun networkReturnRetriesSameBridgeNeverDirect() {
        runBlocking { app.storage.settings.update { it.copy(bridgeMode = BridgeMode.OBFS4) } }
        app.tor.bridges = bridgeSupplier(app.storage.settings)
        app.tor.startFailure = TorState.Error("puente caído", bridge = true)
        var now = 1_000_000L
        val service = startService().get()
        service.elapsedRealtime = { now }
        eventually { (engine.status.value as? EngineStatus.Error)?.kind == EngineErrorKind.BRIDGE_FAILED }
        val callback = networkCallbacks().single()

        // Vuelve la red con el puente aún caído: otro intento, con el mismo puente, y sigue en error.
        callback.onAvailable(ShadowNetwork.newInstance(1))
        eventually { app.tor.bridgeSetups.size == 2 }
        eventually { (engine.status.value as? EngineStatus.Error)?.kind == EngineErrorKind.BRIDGE_FAILED }

        // Pasados 30 s y con el puente ya bien: en línea, por el puente.
        app.tor.startFailure = null
        now += 31_000
        callback.onAvailable(ShadowNetwork.newInstance(2))
        eventually { engine.status.value == EngineStatus.Online }
        val obfs4 = BridgeSetup(BuiltInBridges.OBFS4)
        assertEquals(listOf(obfs4, obfs4, obfs4), app.tor.bridgeSetups.toList())
        assertTrue(app.tor.bridged)
    }

    @Test
    fun reconnectWhileStartingKeepsTheServiceAndStartsAgain() {
        app.tor.holdStarts = true
        val controller = startService()
        val service = controller.get()
        // Tor ya arrancando (retenido en Starting), no solo el motor.
        eventually { engine.status.value is EngineStatus.StartingTor && app.tor.state.value is TorState.Starting }
        app.tor.holdStarts = false
        val mark = app.tor.calls.size

        val restarted = app.container.reconnect()

        assertTrue(runBlocking { withTimeout(20_000) { restarted.await() } })
        eventually { engine.status.value == EngineStatus.Online }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        // El servicio no se enteró de la parada intermedia: sigue en primer plano con su NetworkCallback.
        assertTrue(service.inForeground)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, networkCallbacks().size)
        assertEquals(EngineStatus.Online, engine.status.value)
        val calls = app.tor.calls.drop(mark)
        assertEquals("$calls", listOf("forget", "start"), calls.dropWhile { it == "stop" })
        assertTrue("$calls", calls.first() == "stop")
    }

    @Test
    fun reconnectWithBrokenStorageStopsTheService() {
        startService()
        eventually { engine.status.value == EngineStatus.Online }
        app.storage.failure = StoreUnavailableException(IOException("x"))

        val restarted = app.container.reconnect()

        assertFalse(runBlocking { withTimeout(20_000) { restarted.await() } })
        assertNotNull(nextStoppedService())
        assertEquals(EngineStatus.Stopped, engine.status.value)
    }

    @Test
    fun networkReturnDoesNotRestartAfterTheUserDisconnected() {
        val controller = startService()
        val service = controller.get()
        eventually { engine.status.value == EngineStatus.Online }
        val callback = networkCallbacks().single()
        runBlocking { app.tor.stop() }
        eventually { engine.status.value is EngineStatus.Error }

        service.onStartCommand(GhostService.stopIntent(app), 0, 2) // "Desconectar"
        eventually { engine.status.value == EngineStatus.Stopped }
        callback.onAvailable(ShadowNetwork.newInstance(1)) // un aviso tardío del callback ya retirado
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(EngineStatus.Stopped, engine.status.value)
    }

    @Test
    fun corruptedStorageCanBeRecheckedWithoutWipingAnything() {
        app.storage.failure = StoreCorruptedException(IOException("x"))
        val container = app.container
        runBlocking { container.checkStorage() }
        assertEquals(StorageState.Corrupted, container.storage.value)
        // "Reintentar" (solo lectura): si el fallo era pasajero, se recupera sin borrar nada.
        app.storage.failure = null
        runBlocking { container.checkStorage() }
        assertEquals(StorageState.Ok, container.storage.value)
        assertEquals(0, app.storage.forceResets)
        assertEquals(0, app.tor.wipeStateCalls.get())
    }

    @Test
    fun hiddenNamesSettingIsHonoured() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { app.storage.settings.update { it.copy(hideNotificationNames = true) } }
        startService()
        eventually { engine.status.value == EngineStatus.Online }

        receiveMessage(name = "Ana")

        eventually { messageNotifications().isNotEmpty() }
        val n = messageNotifications().single()
        assertEquals("Nuevo mensaje", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(texts(n).any { it.contains("Ana") })
    }

    @Test
    fun unreadableSettingsHideTheName() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.storage.settings.getFailure = IllegalStateException("ilegible")
        startService()
        eventually { engine.status.value == EngineStatus.Online }

        receiveMessage(name = "Ana")

        eventually { messageNotifications().isNotEmpty() }
        assertFalse(texts(messageNotifications().single()).any { it.contains("Ana") })
    }

    @Test
    fun openChatGetsNoMessageNotification() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        startService()
        eventually { engine.status.value == EngineStatus.Online }

        receiveMessage(name = "Ana", beforeSend = { app.container.chatShown(it) }) { peer, mineInPeer ->
            // Un aviso posterior del mismo flujo: cuando se ve su notificación, el del mensaje ya se trató.
            peer.engine(0).deleteContact(mineInPeer)
        }

        val titles = { notificationManager().allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() } }
        eventually { "Ana te ha eliminado de sus contactos" in titles() }
        // Las dos van al canal de mensajes: se distinguen por el título.
        assertFalse(titles().toString(), titles().any { it.orEmpty().startsWith("Nuevo mensaje") })
    }

    @Test
    fun notificationsResumeAfterTheChatIsHidden() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        startService()
        eventually { engine.status.value == EngineStatus.Online }

        receiveMessage(name = "Ana", beforeSend = { id ->
            app.container.chatShown(id)
            app.container.chatHidden("otro") // ocultar otro chat no cambia el abierto
            assertEquals(id, app.container.foregroundChatId)
            app.container.chatHidden(id)
            assertNull(app.container.foregroundChatId)
        })

        eventually { messageNotifications().isNotEmpty() }
        assertEquals("Nuevo mensaje de Ana", messageNotifications().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun stickyRestartRethrowsOtherIllegalStateException() {
        val service = Robolectric.buildService(GhostService::class.java).create().get()
        service.enterForeground = { throw IllegalStateException("otro fallo") }

        val thrown = runCatching { service.onStartCommand(null, Service.START_FLAG_RETRY, 1) }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is IllegalStateException && thrown !is ForegroundServiceStartNotAllowedException)
        assertEquals("otro fallo", thrown!!.message)
    }

    @Test
    fun destroyLeavesTheForegroundState() {
        val controller = startService()
        eventually { engine.status.value == EngineStatus.Online }
        assertTrue(controller.get().inForeground)

        controller.destroy()

        assertFalse(controller.get().inForeground)
    }

    @Test
    fun bootStartNotAllowedInForegroundStopsAndAsksToOpenTheApp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val service = Robolectric.buildService(GhostService::class.java).create().get()
        service.enterForeground = { throw ForegroundServiceStartNotAllowedException("segundo plano") }

        service.onStartCommand(GhostService.startIntent(app, fromBoot = true), 0, 1) // no lanza

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(networkCallbacks().isEmpty())
        assertEquals(EngineStatus.Stopped, engine.status.value)
        val titles = notificationManager().allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        assertTrue(titles.toString(), "GhostP2 se ha detenido" in titles)
    }

    @Test
    fun bootStartWithBrokenStorageAsksToOpenTheApp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.storage.failure = StoreUnavailableException(IOException("x"))
        val service = Robolectric.buildService(GhostService::class.java).create().get()

        service.onStartCommand(GhostService.startIntent(app, fromBoot = true), 0, 1)

        eventually { shadowOf(service).isStoppedBySelf }
        val titles = notificationManager().allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        assertTrue(titles.toString(), "GhostP2 se ha detenido" in titles)
    }

    /**
     * «Desconectar al salir» (R29) con la vuelta justo al vencer el plazo: se pide parar el servicio y el
     * motor; al destruirlo el sistema no queda nada a medias (ni la notificación fija «activo»).
     */
    @Test
    fun expiryStopsServiceAndEngine() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = autoDisconnectWithAConversation()
        val service = controller.get()

        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(TorState.Off, app.tor.state.value)
        assertTrue(engine.conversations.value.isEmpty())
        assertFalse(service.inForeground)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(networkCallbacks().isEmpty())
        assertTrue(notificationManager().allNotifications.none { it.channelId == Notifications.CHANNEL_STATUS })
    }

    /** Tras el corte automático, «Activar» funciona igual que tras «Desconectar». */
    @Test
    fun activateAfterAutoDisconnectWorks() {
        autoDisconnectWithAConversation()

        val again = startService()
        eventually { engine.status.value == EngineStatus.Online }
        assertTrue(again.get().inForeground)
        assertEquals(1, networkCallbacks().size)
        // El corte fue de la salida anterior: volver a la app ahora no corta nada.
        app.container.autoDisconnect.onStart()
        assertNull(shadowOf(app).nextStoppedService)
        assertEquals(EngineStatus.Online, engine.status.value)
    }

    /**
     * I1: Android mató el proceso con «Desconectar al salir» (R29) armado y el plazo venció antes del
     * reinicio `START_STICKY`: el servicio se detiene sin pasar a primer plano, sin motor ni Tor y sin
     * ninguna notificación.
     */
    @Test
    fun stickyRestartWithExpiredDeadlineStopsWithoutStarting() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        withStoredDeadline()
        armedFile().save(Armed(BOOT, SystemClock.elapsedRealtime() - 1, 2))
        val controller = Robolectric.buildService(GhostService::class.java).create()
        val service = controller.get()

        service.onStartCommand(null, 0, 1)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(service.inForeground)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(networkCallbacks().isEmpty())
        assertNull(armedFile().load())
        assertTrue(app.container.autoDisconnect.returnHome.value)
        controller.destroy()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(TorState.Off, app.tor.state.value)
        assertTrue(notificationManager().allNotifications.isEmpty())
    }

    /**
     * I1: reinicio `START_STICKY` con el plazo aún por vencer: arranca como siempre y el temporizador,
     * vuelto a armar por lo que queda, corta solo (servicio y motor) sin que nadie abra la app.
     */
    @Test
    fun stickyRestartWithFutureDeadlineRearmsAndCuts() {
        withStoredDeadline()
        app.container.autoDisconnect.pollMillis = 20
        armedFile().save(Armed(BOOT, SystemClock.elapsedRealtime() + 60_000, 2))
        val controller = Robolectric.buildService(GhostService::class.java).create()
        val service = controller.get()

        service.onStartCommand(null, 0, 1)

        eventually { engine.status.value == EngineStatus.Online }
        assertTrue(service.inForeground)
        Thread.sleep(200)
        assertNull(shadowOf(app).nextStoppedService)
        assertNotNull(armedFile().load())

        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        assertEquals(GhostService::class.java.name, nextStoppedService()?.component?.className)
        controller.destroy()
        eventually { engine.status.value == EngineStatus.Stopped && app.tor.state.value == TorState.Off }
        assertNull(armedFile().load())
    }

    /**
     * m1: el plazo venció con el proceso muerto y el usuario abre la app (proceso nuevo) antes de que
     * llegue el reinicio `START_STICKY` retrasado: la app desconecta y ese reinicio se detiene sin pasar
     * a primer plano, sin motor ni Tor y sin ninguna notificación.
     */
    @Test
    fun openingTheAppBeforeAnExpiredStickyRestartStopsIt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        withStoredDeadline()
        armedFile().save(Armed(BOOT, SystemClock.elapsedRealtime() - 1, 2))

        // La actividad primero.
        app.container.autoDisconnect.onStart()
        assertNull(armedFile().load())
        assertTrue(app.container.autoDisconnect.returnHome.value)

        val controller = Robolectric.buildService(GhostService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(service.inForeground)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(networkCallbacks().isEmpty())
        controller.destroy()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(TorState.Off, app.tor.state.value)
        assertTrue(notificationManager().allNotifications.isEmpty())
    }

    /** m1, en el otro orden: el reinicio STICKY vencido llega antes y abrir la app después no corta otra vez. */
    @Test
    fun expiredStickyRestartBeforeOpeningTheAppStopsOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        withStoredDeadline()
        armedFile().save(Armed(BOOT, SystemClock.elapsedRealtime() - 1, 2))
        val controller = Robolectric.buildService(GhostService::class.java).create()
        val service = controller.get()

        // El servicio primero.
        service.onStartCommand(null, 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()

        app.container.autoDisconnect.onStart()
        assertNull(shadowOf(app).nextStoppedService)
        assertNull(armedFile().load())
        assertTrue(app.container.autoDisconnect.returnHome.value)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.inForeground)
        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(TorState.Off, app.tor.state.value)
        assertTrue(notificationManager().allNotifications.isEmpty())
    }

    /** Un plazo guardado en otro arranque del móvil (`elapsedRealtime` empezó de cero) no se usa. */
    @Test
    fun stickyRestartIgnoresDeadlineFromAnotherBoot() {
        withStoredDeadline()
        armedFile().save(Armed(BOOT - 1, SystemClock.elapsedRealtime() - 1, 2))
        val service = Robolectric.buildService(GhostService::class.java).create().get()

        service.onStartCommand(null, 0, 1)

        eventually { engine.status.value == EngineStatus.Online }
        assertTrue(service.inForeground)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(app).nextStoppedService)
        assertNull(armedFile().load())
    }

    /** El arranque al encender (R20) nunca actúa sobre un plazo guardado: es de antes del reinicio. */
    @Test
    fun bootStartIgnoresStoredDeadline() {
        withStoredDeadline()
        armedFile().save(Armed(BOOT, SystemClock.elapsedRealtime() - 1, 2))
        val service = Robolectric.buildService(GhostService::class.java).create().get()

        service.onStartCommand(GhostService.startIntent(app, fromBoot = true), 0, 1)

        eventually { engine.status.value == EngineStatus.Online }
        assertTrue(service.inForeground)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(armedFile().load())
        // Tampoco un reinicio STICKY posterior.
        service.onStartCommand(null, 0, 2)
        Thread.sleep(200)
        assertEquals(EngineStatus.Online, engine.status.value)
        assertTrue(service.inForeground)
    }

    /** «Desconectar» de la notificación con el plazo armado: ya no queda nada que retomar. */
    @Test
    fun notificationDisconnectClearsStoredDeadline() {
        bootCount(BOOT)
        runBlocking { app.storage.settings.update { it.copy(disconnectAfterMinutes = 2) } }
        val auto = app.container.autoDisconnect
        eventually { auto.minutes == 2 }
        val controller = startService()
        eventually { engine.status.value == EngineStatus.Online }
        auto.onStop(changingConfigurations = false)
        assertNotNull(armedFile().load())

        controller.get().onStartCommand(GhostService.stopIntent(app), 0, 2)

        assertNull(armedFile().load())
        assertFalse(auto.resumeAfterRestart())
    }

    private fun armedFile() = ArmedFile(File(app.noBackupFilesDir, ArmedFile.NAME))

    /**
     * Como en un móvil con «A los 2 minutos»: el plazo solo se guarda con un valor distinto de «Nunca»
     * (que lo borraría al leer los ajustes) y con el número de arranque [BOOT].
     */
    private fun withStoredDeadline() {
        runBlocking { app.storage.settings.update { it.copy(disconnectAfterMinutes = 2) } }
        bootCount(BOOT)
        eventually { app.container.autoDisconnect.minutes == 2 }
    }

    private fun bootCount(n: Int) {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, n)
    }

    /**
     * Con «A los 2 minutos», motor en línea y una conversación: sale de la app y vuelve cuando ya pasaron
     * 2 minutos (el temporizador no llega a despertar: solo la comprobación de `onStart` puede cortar).
     * Comprueba que se pidió parar el servicio una sola vez y lo destruye, como haría el sistema.
     */
    private fun autoDisconnectWithAConversation(): ServiceController<GhostService> {
        runBlocking { app.storage.settings.update { it.copy(disconnectAfterMinutes = 2) } }
        val auto = app.container.autoDisconnect
        eventually { auto.minutes == 2 }
        auto.pollMillis = Long.MAX_VALUE
        val controller = startService()
        eventually { engine.status.value == EngineStatus.Online }
        receiveMessage("Ana")
        assertFalse(engine.conversations.value.isEmpty())

        auto.onStop(changingConfigurations = false)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        auto.onStart()

        assertEquals(GhostService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        controller.destroy()
        eventually { engine.status.value == EngineStatus.Stopped && app.tor.state.value == TorState.Off }
        Thread.sleep(300)
        assertNull(shadowOf(app).nextStoppedService)
        return controller
    }

    /**
     * Empareja con un contacto llamado [name], llama a [beforeSend] con su id y espera a recibir un mensaje
     * suyo; después, [afterReceived] con el otro lado aún en marcha.
     */
    private fun receiveMessage(
        name: String,
        beforeSend: (String) -> Unit = {},
        afterReceived: suspend (EngineHarness, String) -> Unit = { _, _ -> },
    ) {
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking {
                peer.startAll()
                val (peerInMine, mineInPeer) = pairWithPeer(app, peer, TIMEOUT)
                engine.renameContact(peerInMine, name)
                beforeSend(peerInMine)
                withTimeout(TIMEOUT) {
                    while (engine.conversations.value[peerInMine].isNullOrEmpty()) {
                        if (peer.engine(0).presence.value[mineInPeer] == Presence.ONLINE) {
                            peer.engine(0).sendText(mineInPeer, SECRET)
                        }
                        delay(100)
                    }
                }
                afterReceived(peer, mineInPeer)
            }
        }
    }

    private fun messageNotifications() =
        notificationManager().allNotifications.filter { it.channelId == Notifications.CHANNEL_MESSAGES }

    private fun texts(n: Notification): List<String> {
        val keys = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
        )
        return listOf(n, n.publicVersion).flatMap { m ->
            keys.mapNotNull { m.extras.getCharSequence(it)?.toString() } + listOfNotNull(m.tickerText?.toString())
        }
    }

    private fun notificationManager() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private companion object {
        const val TIMEOUT = 20_000L
        const val BOOT = 5
        const val SECRET = "hola, esto es secreto"
    }
}
