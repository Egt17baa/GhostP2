package org.ghostp2.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.ghostp2.app.StorageState
import org.ghostp2.app.TestGhostApp
import org.ghostp2.data.StoreCorruptedException
import org.ghostp2.data.StoreUnavailableException
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineStatus
import org.junit.After
import org.junit.Assert.assertEquals
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
class GhostRootTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val container get() = app.container

    private var activations = 0

    /** Como la actividad, pero sin servicio: "Activar" arranca el motor del contenedor directamente. */
    private val actions = ActivityActions(
        activate = {
            activations++
            container.start()
        },
        disconnect = { container.stop() },
        requestBatteryExemption = {},
        requestNotifications = {},
    )

    @After
    fun tearDown() {
        runBlocking { container.engine.stop() }
    }

    private fun show() = compose.setContent { GhostRoot(container, actions) }

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), TIMEOUT)

    @Test
    fun corruptedStorageShowsRetryAndReset() {
        app.storage.failure = StoreCorruptedException(IOException("x"))
        show()

        waitForText("No se pudieron leer tus datos cifrados")
        compose.onNodeWithText("Reintentar").assertExists()
        compose.onNodeWithText("Empezar de cero").assertExists()
        assertEquals(StorageState.Corrupted, container.storage.value)
    }

    @Test
    fun resetOfferedAfterThreeUnavailable() {
        app.storage.failure = StoreUnavailableException(IOException("x"))
        show()

        waitForText("No se pudo acceder a tus datos cifrados")
        compose.onAllNodesWithText("Empezar de cero").assertCountEquals(0)
        for (expected in 2..StorageState.FAILURES_BEFORE_RESET) {
            compose.onNodeWithText("Reintentar").performClick()
            compose.waitUntil(TIMEOUT) { (container.storage.value as? StorageState.Unavailable)?.failures == expected }
        }

        // Tras 3 fallos seguidos: "Empezar de cero" con doble confirmación.
        compose.onNodeWithText("Empezar de cero").performClick()
        compose.onNodeWithText("¿Empezar de cero?").assertExists()
        compose.onNodeWithText("Borrar todo").performClick()
        compose.onNodeWithText("¿Seguro? Última confirmación").assertExists()
        assertEquals(0, app.storage.forceResets)
        compose.onNodeWithText("Borrar todo").performClick()

        compose.waitUntil(TIMEOUT) { container.storage.value == StorageState.Ok }
        assertEquals(1, app.storage.forceResets)
        waitForText("GhostP2 está desactivado")
    }

    @Test
    fun identitySaveFailureShowsStorageBanner() {
        app.storage.saveFailure = StoreUnavailableException(IOException("x"))
        show()

        waitForText("GhostP2 está desactivado")
        compose.onNodeWithText("Activar").performClick()

        waitForText("No se pudieron leer o guardar tus datos cifrados")
        compose.onNodeWithText("Reintentar").assertExists()
        // La comprobación (solo lectura) pasa: no es la pantalla de error del almacenamiento.
        assertEquals(StorageState.Ok, container.storage.value)
        assertEquals(EngineErrorKind.STORAGE, (container.engine.status.value as EngineStatus.Error).kind)
    }

    @Test
    fun storageBannerRetryWithFailingStorageShowsStorageError() {
        app.storage.saveFailure = StoreUnavailableException(IOException("x"))
        show()
        waitForText("GhostP2 está desactivado")
        compose.onNodeWithText("Activar").performClick()
        waitForText("No se pudieron leer o guardar tus datos cifrados")
        assertEquals(1, activations)

        // Ahora tampoco se puede leer: "Reintentar" comprueba antes y no vuelve a arrancar.
        app.storage.failure = StoreUnavailableException(IOException("x"))
        compose.onNodeWithText("Reintentar").performClick()

        waitForText("No se pudo acceder a tus datos cifrados")
        assertEquals(StorageState.Unavailable(1), container.storage.value)
        assertEquals(1, activations)
    }

    @Test
    fun corruptedResetNeedsOneConfirmationAndWipes() {
        app.storage.failure = StoreCorruptedException(IOException("x"))
        show()
        waitForText("No se pudieron leer tus datos cifrados")

        compose.onNodeWithText("Empezar de cero").performClick()
        compose.onNodeWithText("¿Empezar de cero?").assertExists()
        assertEquals(0, app.tor.wipeStateCalls.get())
        app.storage.failure = null // tras borrar, el almacenamiento nuevo se lee bien
        compose.onNodeWithText("Borrar todo").performClick()

        // Una sola confirmación: wipeCorrupted (wipeAll del motor, con el estado de Tor), no forceReset.
        compose.onAllNodesWithText("¿Seguro? Última confirmación").assertCountEquals(0)
        compose.waitUntil(TIMEOUT) { container.storage.value == StorageState.Ok }
        assertEquals(1, app.tor.wipeStateCalls.get())
        assertEquals(0, app.storage.forceResets)
        waitForText("GhostP2 está desactivado")
    }

    @Test
    fun unreadableSettingsStopRetryingAndShowStorageError() {
        app.storage.settings.failure = IllegalStateException("x")
        show()

        // Tras unas pocas lecturas fallidas: error con "Reintentar", no una carga sin fin.
        waitForText("Ha ocurrido un error inesperado. Tus datos no se han tocado.")
        assertEquals(StorageState.Ok, container.storage.value)

        app.storage.settings.failure = null
        compose.onNodeWithText("Reintentar").performClick()
        waitForText("GhostP2 está desactivado")
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
