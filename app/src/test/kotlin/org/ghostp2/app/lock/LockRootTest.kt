package org.ghostp2.app.lock

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.ghostp2.app.StorageState
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.ui.ActivityActions
import org.ghostp2.app.ui.GhostRoot
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class LockRootTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val container get() = app.container

    private val actions = ActivityActions(
        activate = { container.start() },
        disconnect = { container.stop() },
        requestBatteryExemption = {},
        requestNotifications = {},
    )

    @After
    fun tearDown() {
        runBlocking { container.engine.stop() }
    }

    /** Bienvenida hecha, bloqueo activado y un contacto guardado ("Ana"). */
    private fun lockEnabledWithContact(onboardingDone: Boolean = true) = runBlocking {
        app.storage.settings.update { it.copy(onboardingDone = onboardingDone, lockEnabled = true) }
        app.storage.contacts.upsert(
            Contact("c1", "Ana", ByteArray(32), "x".repeat(56), ByteArray(32), ByteArray(32), ByteArray(32), false, ContactStatus.ACTIVE),
        )
    }

    private fun show() = compose.setContent { GhostRoot(container, actions) }

    private fun assertNothingSensitive() {
        compose.onAllNodesWithText("Ana").assertCountEquals(0)
        compose.onAllNodesWithText("Bienvenido a GhostP2").assertCountEquals(0)
        compose.onAllNodesWithText("Aún no tienes contactos").assertCountEquals(0)
    }

    @Test
    fun lockedRootShowsOnlyLockScreen() {
        lockEnabledWithContact()
        container.deviceLockAvailable = { true }
        show()

        compose.waitUntilAtLeastOneExists(hasText("GhostP2 está bloqueado"), TIMEOUT)
        compose.onNodeWithText("Desbloquear").assertExists()
        assertNothingSensitive()
        assertTrue(container.lock.locked.value)

        // Al desbloquear aparece la lista.
        compose.runOnIdle { container.lock.unlock() }
        compose.waitUntilAtLeastOneExists(hasText("Ana"), TIMEOUT)
        compose.onAllNodesWithText("GhostP2 está bloqueado").assertCountEquals(0)
    }

    @Test
    fun unlockReturnsToTheOpenScreen() {
        runBlocking {
            app.storage.settings.update { it.copy(onboardingDone = true, lockAfterMinutes = 0) }
            app.storage.contacts.upsert(
                Contact("c1", "Ana", ByteArray(32), "x".repeat(56), ByteArray(32), ByteArray(32), ByteArray(32), false, ContactStatus.ACTIVE),
            )
            app.storage.settings.update { it.copy(lockEnabled = true) }
        }
        container.deviceLockAvailable = { true }
        container.lock.configure(enabled = false, afterMinutes = 0)
        show()
        compose.waitUntilAtLeastOneExists(hasText("Ana"), TIMEOUT)
        compose.onNodeWithText("Ana").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Activa GhostP2 para enviar"), TIMEOUT)

        // Sale de la app y vuelve: bloqueada; al desbloquear sigue en el chat.
        compose.runOnIdle {
            container.lock.configure(enabled = true, afterMinutes = 0)
            container.lock.onStop(changingConfigurations = false)
            container.lock.onStart()
        }
        compose.waitUntilAtLeastOneExists(hasText("GhostP2 está bloqueado"), TIMEOUT)
        compose.onAllNodesWithText("Activa GhostP2 para enviar").assertCountEquals(0)
        compose.runOnIdle { container.lock.unlock() }
        compose.waitUntilAtLeastOneExists(hasText("Activa GhostP2 para enviar"), TIMEOUT)
    }

    @Test
    fun lockedRootHidesOnboardingToo() {
        lockEnabledWithContact(onboardingDone = false)
        container.deviceLockAvailable = { true }
        show()

        compose.waitUntilAtLeastOneExists(hasText("GhostP2 está bloqueado"), TIMEOUT)
        assertNothingSensitive()
    }

    @Test
    fun nothingSensitiveWhileSettingsLoad() {
        lockEnabledWithContact()
        // Antes de crear el contenedor: también retiene la lectura del bloqueo.
        val gate = CompletableDeferred<Unit>()
        app.storage.settings.emitGate = gate
        container.deviceLockAvailable = { true }
        show()

        compose.waitUntil(TIMEOUT) { container.storage.value == StorageState.Ok }
        // Ajustes sin leer: solo la carga, en varios fotogramas seguidos.
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertNothingSensitive()
            compose.onAllNodesWithText("GhostP2 está bloqueado").assertCountEquals(0)
        }

        // Al llegar los ajustes, fotograma a fotograma: ni un solo fotograma con la lista antes del bloqueo.
        compose.mainClock.autoAdvance = false
        gate.complete(Unit)
        val deadline = System.currentTimeMillis() + TIMEOUT
        var frames = 0
        while (compose.onAllNodesWithText("GhostP2 está bloqueado").fetchSemanticsNodes().isEmpty()) {
            assertNothingSensitive()
            check(System.currentTimeMillis() < deadline) { "La pantalla de bloqueo no llegó en $frames fotogramas" }
            compose.mainClock.advanceTimeByFrame()
            frames++
            Thread.sleep(1)
        }
        assertNothingSensitive()
        // Y en los siguientes tampoco.
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            assertNothingSensitive()
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun settingsReachingTheRootBeforeTheLockShowNothing() {
        lockEnabledWithContact()
        // Antes de crear el contenedor (que empieza a leer los ajustes para el bloqueo al crearse).
        val lockFeed = CompletableDeferred<Unit>()
        app.storage.settings.offMainGate = lockFeed
        container.deviceLockAvailable = { true }
        compose.mainClock.autoAdvance = false
        show()

        // La raíz ya tiene los ajustes (bloqueo activado) y el bloqueo aún no: falla cerrado.
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (compose.onAllNodesWithText("GhostP2 está bloqueado").fetchSemanticsNodes().isEmpty()) {
            assertNothingSensitive()
            check(System.currentTimeMillis() < deadline) { "La pantalla de bloqueo no llegó" }
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(1)
        }
        lockFeed.complete(Unit)
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(1)
            assertNothingSensitive()
        }
        assertTrue(container.lock.locked.value)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun missingDeviceCredentialDisablesLock() {
        lockEnabledWithContact()
        var asked = 0
        container.deviceLockAvailable = {
            asked++
            false
        }
        show()

        compose.waitUntilAtLeastOneExists(
            hasText("Tu móvil ya no tiene bloqueo de pantalla; se ha desactivado el bloqueo de GhostP2"),
            TIMEOUT,
        )
        assertTrue(asked > 0)
        assertFalse(container.lock.locked.value)
        compose.waitUntil(TIMEOUT) { runBlocking { !app.storage.settings.get().lockEnabled } }
        compose.waitUntilAtLeastOneExists(hasText("Ana"), TIMEOUT)

        // El aviso se cierra y no vuelve.
        compose.onNodeWithText("Aceptar").performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("Tu móvil ya no tiene bloqueo de pantalla; se ha desactivado el bloqueo de GhostP2")
                .fetchSemanticsNodes().isEmpty()
        }
        assertFalse(container.lock.missingDeviceLockNotice.value)
    }

    @Test
    fun unlockButtonChecksDeviceLockAgain() {
        lockEnabledWithContact()
        var available = true
        container.deviceLockAvailable = { available }
        show()

        compose.waitUntilAtLeastOneExists(hasText("GhostP2 está bloqueado"), TIMEOUT)
        // El bloqueo de pantalla del móvil se quita con GhostP2 ya en la pantalla de bloqueo.
        available = false
        compose.onNodeWithText("Desbloquear").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Ana"), TIMEOUT)
        compose.waitUntil(TIMEOUT) { runBlocking { !app.storage.settings.get().lockEnabled } }
    }

    @Test
    fun settingsRetriesStopUntilStorageIsCheckedAgain() {
        val settings = app.storage.settings
        settings.failure = IOException("x")
        container.lockRetryMillis = 1
        // 1 lectura + 4 reintentos con espera; después, nada hasta otra comprobación del almacenamiento.
        compose.waitUntil(TIMEOUT) { settings.collections.get() == 5 }
        Thread.sleep(300)
        assertEquals(5, settings.collections.get())
        assertTrue(container.lock.locked.value)

        settings.failure = null
        runBlocking { container.checkStorage() }
        compose.waitUntil(TIMEOUT) { !container.lock.locked.value }
        assertEquals(6, settings.collections.get())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
