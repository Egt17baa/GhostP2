package org.ghostp2.app.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
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

        assertFalse(vm.setLock(true))
        assertTrue(vm.lockNeedsDeviceLock.value)
        Thread.sleep(200)
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

        // Los valores fuera de las listas no se guardan.
        vm.setLockAfter(7)
        vm.setMaxPhotoSide(999)
        Thread.sleep(200)
        assertEquals(15, stored().lockAfterMinutes)
        assertEquals(3072, stored().maxPhotoSidePx)

        vm.setStartOnBoot(false)
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
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
