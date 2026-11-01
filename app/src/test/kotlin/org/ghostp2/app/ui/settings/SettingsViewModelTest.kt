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
import org.ghostp2.app.Notifications
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
import org.ghostp2.app.ui.copySensitive
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.Notice
import org.ghostp2.engine.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class SettingsViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun viewModel(): SettingsViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { SettingsViewModel(app.container) } })[SettingsViewModel::class]

    private fun stored(): AppSettings = runBlocking { app.storage.settings.get() }

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
