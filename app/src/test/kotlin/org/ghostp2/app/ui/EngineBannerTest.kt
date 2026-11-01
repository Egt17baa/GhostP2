package org.ghostp2.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onChildren
import org.ghostp2.app.TestGhostApp
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class EngineBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private var activations = 0
    private var disconnections = 0

    private fun show(status: EngineStatus, startFailed: Boolean = false) {
        compose.setContent {
            EngineBanner(status, startFailed, onActivate = { activations++ }, onDisconnect = { disconnections++ })
        }
    }

    private fun assertAbsent(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)

    @Test
    fun stoppedOffersActivate() {
        show(EngineStatus.Stopped)
        compose.onNodeWithText("GhostP2 está desactivado").assertExists()
        compose.onNodeWithText("Activar").performClick()
        assertEquals(1, activations)
        assertAbsent("Desconectar")
    }

    @Test
    fun startingShowsPercentAndDisconnect() {
        show(EngineStatus.StartingTor(40))
        compose.onNodeWithText("Conectando a la red Tor… 40 %").assertExists()
        compose.onNodeWithText("Desconectar").performClick()
        assertEquals(1, disconnections)
        assertAbsent("Activar")
    }

    @Test
    fun onlineShowsNothing() {
        show(EngineStatus.Online)
        compose.onRoot().onChildren().assertCountEquals(0)
        listOf("Activar", "Reintentar", "Desconectar").forEach(::assertAbsent)
    }

    @Test
    fun torErrorOffersRetryAndDisconnect() {
        show(EngineStatus.Error("x", EngineErrorKind.TOR))
        compose.onNodeWithText("Sin conexión con Tor").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithText("Desconectar").performClick()
        assertEquals(1, activations)
        assertEquals(1, disconnections)
    }

    @Test
    fun stuckTorSaysToReopenTheApp() {
        show(EngineStatus.Error("x", EngineErrorKind.TOR_STUCK))
        compose.onNodeWithText("Tor no responde. Cierra GhostP2 desde Recientes y vuelve a abrirlo.").assertExists()
        assertAbsent("Sin conexión con Tor")
        // Reintentar sigue disponible: cada intento vuelve a probar a detener el Tor colgado.
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, activations)
    }

    @Test
    fun storageErrorOffersRetry() {
        show(EngineStatus.Error("x", EngineErrorKind.STORAGE))
        compose.onNodeWithText("No se pudieron leer o guardar tus datos cifrados").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, activations)
        assertAbsent("Desconectar")
    }

    @Test
    fun startFailedOffersRetry() {
        show(EngineStatus.Stopped, startFailed = true)
        compose.onNodeWithText("Error inesperado").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, activations)
        assertAbsent("GhostP2 está desactivado")
        assertAbsent("Activar")
    }
}
