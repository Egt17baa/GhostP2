package org.ghostp2.app.ui.settings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.GhostService
import org.ghostp2.app.Notifications
import org.ghostp2.app.bridgeSupplier
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.nextStoppedService
import org.ghostp2.app.pairWithPeer
import org.ghostp2.app.ui.copySensitive
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.Notice
import org.ghostp2.engine.Presence
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.BridgeSetup
import org.ghostp2.engine.bridges.BuiltInBridges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class SettingsViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        app.tor.stopGate?.complete(Unit)
        app.tor.forgetGate?.complete(Unit)
        watchers.forEach { it.cancel() }
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun viewModel(): SettingsViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { SettingsViewModel(app.container) } })[SettingsViewModel::class]

    private fun stored(): AppSettings = runBlocking { app.storage.settings.get() }

    /** Como en producción: el Tor falso lee el ajuste guardado en cada arranque. */
    private fun withBridgeSupplier() {
        app.tor.bridges = bridgeSupplier(app.storage.settings)
    }

    /** ViewModel con su estado recogido, como mientras se ve la pantalla. */
    private fun watchedViewModel(): SettingsViewModel {
        val vm = viewModel()
        val watcher = app.container.scope.launch { vm.state.collect {} }
        watchers += watcher
        eventually { vm.state.value == stored() }
        return vm
    }

    private val watchers = mutableListOf<kotlinx.coroutines.Job>()

    private fun online() {
        assertTrue(runBlocking { withTimeout(TIMEOUT) { app.container.start().await() } })
        eventually { app.container.engine.status.value == EngineStatus.Online }
    }

    /**
     * Las paradas de Tor que hagan falta (ninguna si ya estaba parado, como tras caerse en `Error`), un único
     * borrado y después un único arranque, en ese orden.
     */
    private fun assertStopForgetStart(calls: List<String>) {
        val forget = calls.indexOf("forget")
        assertEquals("$calls", 1, calls.count { it == "forget" })
        assertEquals("$calls", 1, calls.count { it == "start" })
        assertTrue("$calls", forget >= 0 && calls.subList(0, forget).all { it == "stop" })
        assertEquals("$calls", "start", calls.last())
        assertTrue("$calls", calls.subList(forget + 1, calls.size - 1).isEmpty())
    }

    /** Llamadas al Tor falso desde ahora. */
    private fun torCallsFrom(mark: Int) = app.tor.calls.drop(mark)

    @Test
    fun changeWhileOnlineNeedsReconnect() {
        withBridgeSupplier()
        online()
        val vm = watchedViewModel()
        val mark = app.tor.calls.size

        assertEquals(BridgeChange.NEEDS_RECONNECT, vm.setBridges(BridgeMode.OBFS4, null))

        // Hasta confirmar, nada: ni se guarda ni se toca Tor.
        Thread.sleep(200)
        eventually { true }
        assertEquals(BridgeMode.NONE, stored().bridgeMode)
        assertEquals(emptyList<String>(), torCallsFrom(mark))
        assertEquals(EngineStatus.Online, app.container.engine.status.value)

        // Cancelar lo olvida: confirmar después no hace nada.
        vm.cancelReconnect()
        vm.confirmReconnect()
        Thread.sleep(200)
        eventually { true }
        assertEquals(BridgeMode.NONE, stored().bridgeMode)
        assertEquals(emptyList<String>(), torCallsFrom(mark))

        // Mientras Tor arranca también hay que confirmar.
        runBlocking { app.container.stop().join() }
        app.tor.holdStarts = true
        app.container.start()
        eventually { app.container.engine.status.value is EngineStatus.StartingTor }
        assertEquals(BridgeChange.NEEDS_RECONNECT, vm.setBridges(BridgeMode.SNOWFLAKE, null))
        assertEquals(BridgeMode.NONE, stored().bridgeMode)
    }

    @Test
    fun confirmReconnectStopsForgetsAndStarts() {
        withBridgeSupplier()
        online()
        val vm = watchedViewModel()
        val mark = app.tor.calls.size

        assertEquals(BridgeChange.NEEDS_RECONNECT, vm.setBridges(BridgeMode.OBFS4, null))
        vm.confirmReconnect()

        eventually(TIMEOUT) {
            app.container.engine.status.value == EngineStatus.Online && torCallsFrom(mark).lastOrNull() == "start"
        }
        assertEquals(BridgeMode.OBFS4, stored().bridgeMode)
        // Primero se para Tor (con el motor), después se borra su estado y por último arranca de nuevo.
        assertStopForgetStart(torCallsFrom(mark))
        assertEquals("stop", torCallsFrom(mark).first()) // estaba en línea: se para antes de borrar
        assertEquals(1, app.tor.forgetBridgeStateCalls.get())
        // El arranque nuevo lee el ajuste ya guardado.
        assertEquals(listOf(null, BridgeSetup(BuiltInBridges.OBFS4)), app.tor.bridgeSetups.toList())
        assertTrue(app.tor.bridged)
    }

    @Test
    fun changeWhileErrorReconnectsWithoutDialog() {
        withBridgeSupplier()
        online()
        runBlocking { app.tor.stop() } // Tor se cae: Error (R18)
        eventually { app.container.engine.status.value is EngineStatus.Error }
        val vm = watchedViewModel()
        val mark = app.tor.calls.size

        assertEquals(BridgeChange.SAVED, vm.setBridges(BridgeMode.SNOWFLAKE, null))

        eventually(TIMEOUT) { app.container.engine.status.value == EngineStatus.Online }
        assertEquals(BridgeMode.SNOWFLAKE, stored().bridgeMode)
        assertStopForgetStart(torCallsFrom(mark))
        assertEquals(BridgeSetup(BuiltInBridges.SNOWFLAKE), app.tor.bridgeSetups.last())
    }

    @Test
    fun changeWhileStoppedOnlySavesAndForgets() {
        withBridgeSupplier()
        val vm = watchedViewModel()
        val custom = BuiltInBridges.OBFS4.take(2).joinToString("\r\n") { "Bridge  " + it.line + " " }

        assertEquals(BridgeChange.SAVED, vm.setBridges(BridgeMode.CUSTOM, custom))

        eventually { app.tor.forgetBridgeStateCalls.get() == 1 }
        assertEquals(BridgeMode.CUSTOM, stored().bridgeMode)
        // Se guardan las líneas normalizadas (sin "Bridge" ni espacios de más).
        assertEquals(BuiltInBridges.OBFS4.take(2).map { it.line }, stored().customBridges)
        Thread.sleep(200)
        eventually { true }
        assertEquals(listOf("forget"), app.tor.calls.toList())
        assertEquals(EngineStatus.Stopped, app.container.engine.status.value)
    }

    @Test
    fun sameValueIsNoop() {
        runBlocking {
            app.storage.settings.update {
                it.copy(bridgeMode = BridgeMode.CUSTOM, customBridges = BuiltInBridges.OBFS4.take(1).map { b -> b.line })
            }
        }
        withBridgeSupplier()
        online()
        val vm = watchedViewModel()
        val writes = java.util.concurrent.CopyOnWriteArrayList<AppSettings>()
        app.storage.settings.onWrite = { writes += it }
        val mark = app.tor.calls.size

        // Las mismas líneas, aunque se peguen de otra forma.
        assertEquals(BridgeChange.SAVED, vm.setBridges(BridgeMode.CUSTOM, "Bridge " + BuiltInBridges.OBFS4[0].line + "\n\n"))
        Thread.sleep(300)
        eventually { true }
        assertEquals(emptyList<AppSettings>(), writes.toList())
        assertEquals(emptyList<String>(), torCallsFrom(mark))
        assertEquals(EngineStatus.Online, app.container.engine.status.value)
        assertEquals(0, app.tor.forgetBridgeStateCalls.get())
    }

    @Test
    fun invalidCustomSavesNothing() {
        val vm = watchedViewModel()
        val writes = java.util.concurrent.CopyOnWriteArrayList<AppSettings>()
        app.storage.settings.onWrite = { writes += it }

        assertEquals(BridgeChange.INVALID, vm.setBridges(BridgeMode.CUSTOM, "obfs4 basura"))
        assertEquals(BridgeChange.INVALID, vm.setBridges(BridgeMode.CUSTOM, " \n "))
        assertEquals(BridgeChange.INVALID, vm.setBridges(BridgeMode.CUSTOM, null))
        val eleven = (1..11).joinToString("\n") { n ->
            "obfs4 192.0.2.$n:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0"
        }
        assertEquals(BridgeChange.INVALID, vm.setBridges(BridgeMode.CUSTOM, eleven))

        // La siguiente escritura es la primera.
        vm.setStartOnBoot(true)
        eventually { writes.isNotEmpty() }
        assertEquals(listOf(AppSettings(startOnBoot = true)), writes.toList())
        assertEquals(0, app.tor.forgetBridgeStateCalls.get())
    }

    @Test
    fun otherModeKeepsSavedCustomLines() {
        val lines = BuiltInBridges.OBFS4.take(1).map { it.line }
        runBlocking { app.storage.settings.update { it.copy(bridgeMode = BridgeMode.CUSTOM, customBridges = lines) } }
        val vm = watchedViewModel()

        assertEquals(BridgeChange.SAVED, vm.setBridges(BridgeMode.NONE, null))

        eventually { stored().bridgeMode == BridgeMode.NONE }
        assertEquals(lines, stored().customBridges)
        // Pasar a "No" también borra el estado de Tor.
        eventually { app.tor.forgetBridgeStateCalls.get() == 1 }
    }

    /** El servicio en primer plano con el motor en línea, como tras "Activar". */
    private fun serviceOnline(): ServiceController<GhostService> {
        val controller = Robolectric.buildService(GhostService::class.java, GhostService.startIntent(app))
            .create().startCommand(0, 1)
        eventually { app.container.engine.status.value == EngineStatus.Online }
        return controller
    }

    /**
     * Tras confirmar "Reconectar" con el motor aún parándose por [disconnect]: el ajuste queda guardado y el estado de
     * Tor borrado, pero nada vuelve a arrancar (ni motor, ni Tor, ni servicio).
     */
    private fun assertConfirmAfterDisconnectDoesNotStart(disconnect: (ServiceController<GhostService>) -> Unit) {
        withBridgeSupplier()
        val controller = serviceOnline()
        val service = controller.get()
        val vm = watchedViewModel()
        assertEquals(BridgeChange.NEEDS_RECONNECT, vm.setBridges(BridgeMode.OBFS4, null))
        // La parada de Tor tarda (como en un móvil): el motor sigue sin estar `Stopped` mientras tanto.
        val slowStop = CompletableDeferred<Unit>()
        app.tor.stopGate = slowStop
        val mark = app.tor.calls.size

        disconnect(controller)
        eventually { torCallsFrom(mark).contains("stop") }
        assertTrue(app.container.engine.status.value != EngineStatus.Stopped)
        vm.confirmReconnect()
        eventually { stored().bridgeMode == BridgeMode.OBFS4 }
        Thread.sleep(200)
        slowStop.complete(Unit)

        eventually { app.container.engine.status.value == EngineStatus.Stopped && app.tor.forgetBridgeStateCalls.get() == 1 }
        Thread.sleep(300)
        eventually { true }
        assertEquals(EngineStatus.Stopped, app.container.engine.status.value)
        assertFalse("${torCallsFrom(mark)}", torCallsFrom(mark).contains("start"))
        assertEquals(listOf<BridgeSetup?>(null), app.tor.bridgeSetups.toList())
        assertFalse(service.inForeground)
    }

    /** I1: "Desconectar" en la notificación con el diálogo abierto; después "Reconectar". */
    @Test
    fun reconnectAfterNotificationDisconnectDoesNotStart() {
        assertConfirmAfterDisconnectDoesNotStart { controller ->
            controller.get().onStartCommand(GhostService.stopIntent(app), 0, 2)
        }
    }

    /** I1: "Desconectar" en la app (para el servicio y el motor) con el diálogo abierto; después "Reconectar". */
    @Test
    fun reconnectAfterAppDisconnectDoesNotStart() {
        assertConfirmAfterDisconnectDoesNotStart { controller ->
            // Como MainActivity.disconnect: el sistema destruye el servicio parado.
            app.container.autoDisconnect.cancel()
            app.stopService(GhostService.stopIntent(app))
            app.container.stop()
            controller.destroy()
        }
    }

    /** I1: "Desconectar al salir de la app" (R29) corta con el diálogo abierto; al volver, "Reconectar". */
    @Test
    fun reconnectAfterAutoDisconnectDoesNotStart() {
        runBlocking { app.storage.settings.update { it.copy(disconnectAfterMinutes = 2) } }
        val auto = app.container.autoDisconnect
        eventually { auto.minutes == 2 }
        auto.pollMillis = Long.MAX_VALUE
        assertConfirmAfterDisconnectDoesNotStart { controller ->
            auto.onStop(changingConfigurations = false)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
            auto.onStart()
            // Corta onStart o, si su temporizador llegó a ver ya el plazo vencido, él mismo (en otro hilo).
            assertEquals(GhostService::class.java.name, nextStoppedService()?.component?.className)
            // El sistema destruye el servicio parado antes de que el usuario vuelva al diálogo.
            controller.destroy()
        }
    }

    /** Con el servicio activo, "Reconectar" sigue parando, borrando y arrancando de nuevo, y el servicio sigue. */
    @Test
    fun reconnectWithTheServiceActiveStillRestarts() {
        withBridgeSupplier()
        val service = serviceOnline().get()
        val vm = watchedViewModel()
        val mark = app.tor.calls.size

        assertEquals(BridgeChange.NEEDS_RECONNECT, vm.setBridges(BridgeMode.OBFS4, null))
        vm.confirmReconnect()

        eventually(TIMEOUT) {
            app.container.engine.status.value == EngineStatus.Online && torCallsFrom(mark).lastOrNull() == "start"
        }
        assertStopForgetStart(torCallsFrom(mark))
        assertEquals(BridgeSetup(BuiltInBridges.OBFS4), app.tor.bridgeSetups.last())
        assertTrue(app.tor.bridged)
        Thread.sleep(300)
        eventually { true }
        assertTrue(service.inForeground)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(app).nextStoppedService)
    }

    /**
     * M1: un cambio con el motor parado borra el estado de Tor; un "Activar" pedido mientras tanto espera a ese borrado
     * (no arranca Tor para que el borrado lo pare después) y termina en línea, por el puente nuevo.
     */
    @Test
    fun startDuringAStoppedChangeWaitsForTheForget() {
        withBridgeSupplier()
        val vm = watchedViewModel()
        val slowForget = CompletableDeferred<Unit>()
        app.tor.forgetGate = slowForget

        assertEquals(BridgeChange.SAVED, vm.setBridges(BridgeMode.OBFS4, null))
        eventually { app.tor.calls.contains("forget") }
        val started = app.container.start()
        Thread.sleep(300)
        eventually { true }
        assertEquals(listOf("forget"), app.tor.calls.toList())
        slowForget.complete(Unit)

        assertTrue(runBlocking { withTimeout(TIMEOUT) { started.await() } })
        eventually { app.container.engine.status.value == EngineStatus.Online }
        assertEquals(listOf("forget", "start"), app.tor.calls.toList())
        assertEquals(listOf<BridgeSetup?>(BridgeSetup(BuiltInBridges.OBFS4)), app.tor.bridgeSetups.toList())
    }

    @Test
    fun lockNeedsDeviceCredential() {
        app.container.deviceLockAvailable = { false }
        val vm = viewModel()

        val writes = java.util.concurrent.CopyOnWriteArrayList<AppSettings>()
        app.storage.settings.onWrite = { writes += it }
        assertFalse(vm.setLock(true))
        assertTrue(vm.lockNeedsDeviceLock.value)
        // Las escrituras van en orden: si setLock(true) hubiera guardado algo, iría antes que esta.
        vm.setStartOnBoot(true)
        eventually { writes.isNotEmpty() }
        assertEquals(listOf(AppSettings(startOnBoot = true)), writes.toList())
        assertFalse(stored().lockEnabled)

        app.container.deviceLockAvailable = { true }
        assertTrue(vm.setLock(true))
        assertFalse(vm.lockNeedsDeviceLock.value)
        eventually { stored().lockEnabled }
        // Desactivarlo nunca exige bloqueo de pantalla.
        app.container.deviceLockAvailable = { false }
        assertTrue(vm.setLock(false))
        eventually { !stored().lockEnabled }
    }

    @Test
    fun disconnectAfterPersists() {
        val vm = viewModel()
        vm.setDisconnectAfter(5)
        eventually { stored().disconnectAfterMinutes == 5 }

        // Fuera de la lista no se guarda: la siguiente escritura es la primera.
        val writes = java.util.concurrent.CopyOnWriteArrayList<AppSettings>()
        app.storage.settings.onWrite = { writes += it }
        vm.setDisconnectAfter(7)
        vm.setDisconnectAfter(0)
        eventually { writes.isNotEmpty() }
        assertEquals(listOf(AppSettings()), writes.toList())
    }

    @Test
    fun optionsPersist() {
        val vm = viewModel()
        // Como la pantalla: el estado solo se lee mientras alguien lo recoge.
        val watcher = app.container.scope.launch { vm.state.collect {} }
        eventually { vm.state.value != null }

        vm.setLockAfter(15)
        vm.setHideNames(true)
        vm.setMaxPhotoSide(3072)
        vm.setStartOnBoot(true)

        eventually { stored() == AppSettings(lockAfterMinutes = 15, hideNotificationNames = true, maxPhotoSidePx = 3072, startOnBoot = true) }
        eventually { vm.state.value == stored() }

        // Los valores fuera de las listas no se guardan: la siguiente escritura es la primera.
        val writes = java.util.concurrent.CopyOnWriteArrayList<AppSettings>()
        app.storage.settings.onWrite = { writes += it }
        vm.setLockAfter(7)
        vm.setMaxPhotoSide(999)
        vm.setStartOnBoot(false)
        eventually { writes.isNotEmpty() }
        assertEquals(1, writes.size)
        assertEquals(15, stored().lockAfterMinutes)
        assertEquals(3072, stored().maxPhotoSidePx)

        vm.setHideNames(false)
        eventually { !stored().startOnBoot && !stored().hideNotificationNames }
        watcher.cancel()
    }

    @Test
    fun startOnBootIsOffByDefault() {
        assertFalse(AppSettings().startOnBoot)
        assertFalse(stored().startOnBoot)
    }

    @Test
    fun wipeAllReturnsToOnboarding() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = true, hideNotificationNames = true, startOnBoot = true) } }
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking {
                peer.startAll()
                val (peerInMine, mineInPeer) = pairWithPeer(app, peer, TIMEOUT)
                withTimeout(TIMEOUT) {
                    app.container.engine.presence.first { it[peerInMine] == Presence.ONLINE }
                    peer.engine(0).presence.first { it[mineInPeer] == Presence.ONLINE }
                }
                val unpaired = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT) { peer.engine(0).notices.filterIsInstance<Notice.ContactUnpairedYou>().first() }
                }
                // Notificaciones con nombres y una invitación copiada: no deben sobrevivir al borrado.
                shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                Notifications.post(app, Notifications.forNotice(app, Notice.MessageArrived(peerInMine), "Ana", hideNames = false)!!)
                Notifications.postStopped(app)
                assertEquals(2, notificationManager().allNotifications.size)
                copySensitive(app, app.getString(R.string.add_clip_label), "ghostp2:invitacion")
                val vm = viewModel()

                vm.wipeAll()
                assertTrue(vm.wiping.value)

                eventually(TIMEOUT) { !vm.wiping.value }
                assertEquals(mineInPeer, unpaired.await().contactId)
            }
        }
        assertTrue(runBlocking { app.storage.contacts.all() }.isEmpty())
        assertNull(runBlocking { app.storage.identities.load() })
        assertEquals(AppSettings(), stored())
        assertFalse(stored().onboardingDone)
        assertEquals(EngineStatus.Stopped, app.container.engine.status.value)
        assertTrue(app.tor.wipeStateCalls.get() >= 1)
        assertEquals(org.ghostp2.app.StorageState.Ok, app.container.storage.value)
        assertTrue(notificationManager().allNotifications.isEmpty())
        assertFalse(clipboard().hasPrimaryClip())
    }

    @Test
    fun wipeAllLeavesForeignClipsAlone() {
        clipboard().setPrimaryClip(ClipData.newPlainText("otra app", "ghostp2:no es nuestro"))
        runBlocking { app.container.wipeAll() }
        assertEquals("ghostp2:no es nuestro", clipboard().primaryClip!!.getItemAt(0).text.toString())

        // Sin etiqueta propia pero marcado como sensible y con el prefijo: es una invitación copiada.
        val sensitive = ClipData.newPlainText("x", "  GHOSTP2:abc")
        sensitive.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard().setPrimaryClip(sensitive)
        runBlocking { app.container.wipeAll() }
        assertFalse(clipboard().hasPrimaryClip())
    }

    @Test
    fun hidingNamesCancelsPostedContactNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for (notice in listOf(Notice.MessageArrived("c1"), Notice.ContactUnpairedYou("c2", "Bea"), Notice.IdentityChanged("c3"))) {
            Notifications.post(app, Notifications.forNotice(app, notice, "Ana", hideNames = false)!!)
        }
        Notifications.postStopped(app)
        val vm = viewModel()

        vm.setHideNames(false)
        assertEquals(4, notificationManager().allNotifications.size)
        vm.setHideNames(true)

        val left = notificationManager().allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        assertEquals(listOf("GhostP2 se ha detenido"), left)
        eventually { stored().hideNotificationNames }
    }

    @Test
    fun startRequestedWhileTheWipeHoldsTheStorageDoesNotStart() {
        // Durante el borrado del motor (con el cerrojo del almacenamiento tomado).
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        app.storage.identityWipeReached = reached
        app.storage.identityWipeGate = gate
        val wipe = app.container.scope.async { app.container.wipeAll() }
        runBlocking { withTimeout(TIMEOUT) { reached.await() } }
        val started = app.container.start()
        gate.complete(Unit)
        runBlocking { withTimeout(TIMEOUT) { wipe.await() } }

        assertFalse(runBlocking { withTimeout(TIMEOUT) { started.await() } })
        assertNull(runBlocking { app.storage.identities.load() })
        assertEquals(EngineStatus.Stopped, app.container.engine.status.value)
    }

    @Test
    fun startRequestedAfterTheWipeStoppedEverythingDoesNotStart() {
        // Detenido ya todo, durante la comprobación final (aún con el cerrojo tomado).
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        app.storage.probeReached = reached
        app.storage.probeGate = gate
        val wipe = app.container.scope.async { app.container.wipeAll() }
        runBlocking { withTimeout(TIMEOUT) { reached.await() } }
        app.storage.probeReached = null
        val started = app.container.start()
        gate.complete(Unit)
        runBlocking { withTimeout(TIMEOUT) { wipe.await() } }

        assertFalse(runBlocking { withTimeout(TIMEOUT) { started.await() } })
        assertNull(runBlocking { app.storage.identities.load() })
        assertEquals(EngineStatus.Stopped, app.container.engine.status.value)

        // Terminado el borrado, "Activar" arranca con normalidad.
        app.storage.probeGate = null
        assertTrue(runBlocking { withTimeout(TIMEOUT) { app.container.start().await() } })
    }

    private fun notificationManager() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun clipboard() = app.getSystemService(ClipboardManager::class.java)

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
