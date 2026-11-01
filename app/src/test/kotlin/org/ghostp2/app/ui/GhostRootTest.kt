package org.ghostp2.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.StorageState
import org.ghostp2.app.TestGhostApp
import org.ghostp2.data.StoreCorruptedException
import org.ghostp2.data.StoreUnavailableException
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class GhostRootTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val container get() = app.container

    private var activations = 0

    /** Orden de las escrituras de ajustes ("save") y de las activaciones ("activate"). */
    private val events = CopyOnWriteArrayList<String>()

    /** Como la actividad, pero sin servicio: "Activar" arranca el motor del contenedor directamente. */
    private val actions = ActivityActions(
        activate = {
            activations++
            events += "activate"
            container.start()
        },
        disconnect = { container.stop() },
        requestBatteryExemption = {},
        requestNotifications = {},
    )

    @Before
    fun setUp() {
        // Salvo en onboardingShownUntilDone, la bienvenida ya está hecha.
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = true) } }
        app.storage.settings.onWrite = { events += "save" }
    }

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
        // Se ha borrado todo, ajustes incluidos: vuelve la bienvenida.
        waitForText("Bienvenido a GhostP2")
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

    @Test
    fun onboardingShownUntilDone() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = false) } }
        show()

        waitForText("Bienvenido a GhostP2")
        compose.onAllNodesWithText("GhostP2 está desactivado").assertCountEquals(0)
        // En Robolectric (API 35) faltan el permiso de notificaciones y la exención de batería.
        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText("Siguiente").performClick()
        assertEquals(0, activations)
        assertFalse(runBlocking { app.storage.settings.get() }.onboardingDone)

        compose.onNodeWithText("Activar GhostP2").performClick()
        compose.waitUntil(TIMEOUT) { activations == 1 }
        assertTrue(runBlocking { app.storage.settings.get() }.onboardingDone)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Bienvenido a GhostP2").fetchSemanticsNodes().isEmpty() }
    }

    /** Muestra la bienvenida y llega a su último paso (en Robolectric hay tres). */
    private fun showOnboardingAtLastStep() {
        runBlocking { app.storage.settings.update { it.copy(onboardingDone = false) } }
        events.clear()
        show()
        waitForText("Bienvenido a GhostP2")
        compose.onNodeWithText("Siguiente").performClick()
        compose.onNodeWithText("Siguiente").performClick()
    }

    @Test
    fun onboardingSavesBeforeActivating() {
        showOnboardingAtLastStep()
        compose.onNodeWithText("Activar GhostP2").performClick()
        compose.waitUntil(TIMEOUT) { activations == 1 }
        assertEquals(listOf("save", "activate"), events.toList())
    }

    @Test
    fun onboardingWriteFailureDoesNotActivateAndSaysSo() {
        showOnboardingAtLastStep()
        app.storage.settings.writeFailure = StoreUnavailableException(IOException("x"))
        compose.onNodeWithText("Activar GhostP2").performClick()

        // El almacenamiento se sigue leyendo bien: el error se ve junto al botón.
        waitForText("Ha ocurrido un error inesperado. Tus datos no se han tocado.")
        assertEquals(StorageState.Ok, container.storage.value)
        assertEquals(0, activations)
        assertFalse(runBlocking { app.storage.settings.get() }.onboardingDone)

        // Al volver a intentarlo con la escritura arreglada: se guarda y después se activa.
        app.storage.settings.writeFailure = null
        compose.onNodeWithText("Activar GhostP2").performClick()
        compose.waitUntil(TIMEOUT) { activations == 1 }
        assertEquals(listOf("save", "activate"), events.toList())
    }

    @Test
    fun onboardingWriteFailureWithBadStorageShowsStorageError() {
        showOnboardingAtLastStep()
        app.storage.settings.writeFailure = StoreUnavailableException(IOException("x"))
        app.storage.failure = StoreUnavailableException(IOException("x"))
        compose.onNodeWithText("Activar GhostP2").performClick()

        waitForText("No se pudo acceder a tus datos cifrados")
        assertEquals(StorageState.Unavailable(1), container.storage.value)
        assertEquals(0, activations)
        assertFalse(runBlocking { app.storage.settings.get() }.onboardingDone)
    }

    @Test
    fun contactListWithBannerAndDisconnectWhileRunning() {
        show()
        waitForText("Aún no tienes contactos")
        compose.onNodeWithText("GhostP2 está desactivado").assertExists()
        compose.onAllNodesWithContentDescription("Desconectar").assertCountEquals(0)
        // Añadir contacto con el motor parado: pide activarlo; "Volver" regresa a la lista.
        compose.onNodeWithText("Añadir contacto").performClick()
        waitForText("Activa GhostP2 para añadir contactos")
        compose.onNodeWithContentDescription("Volver").performClick()
        waitForText("Aún no tienes contactos")

        compose.onNodeWithText("Activar").performClick()
        compose.waitUntil(TIMEOUT) { container.engine.status.value == EngineStatus.Online }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Desconectar"), TIMEOUT)
        compose.onNodeWithContentDescription("Desconectar").performClick()
        compose.waitUntil(TIMEOUT) { container.engine.status.value == EngineStatus.Stopped }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithContentDescription("Desconectar").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun openIfRegisteredOnlyNavigatesToKnownDestinations() {
        var nav: NavHostController? = null
        compose.setContent {
            val controller = rememberNavController().also { nav = it }
            NavHost(controller, startDestination = Routes.CONTACTS) {
                composable(Routes.CONTACTS) { Text("lista") }
                composable(Routes.CHAT) { entry -> Text("chat ${entry.idArg()}") }
            }
        }
        compose.waitUntil(TIMEOUT) { nav != null }
        compose.runOnUiThread { nav!!.openIfRegistered(Routes.contact("a/b")) }
        compose.onNodeWithText("lista").assertExists()
        compose.runOnUiThread { nav!!.openIfRegistered(Routes.chat("a/b")) }
        waitForText("chat a/b")
    }

    @Test
    fun addContactPairsThenNamesAndLeavesNoInvitation() {
        show()
        waitForText("Aún no tienes contactos")
        compose.onNodeWithText("Activar").performClick()
        compose.waitUntil(TIMEOUT) { container.engine.status.value == EngineStatus.Online }
        compose.onNodeWithContentDescription("Añadir contacto").performClick()

        // "Mi código": se crea la invitación y se ve su texto.
        val invitation = hasText("ghostp2:", substring = true)
        compose.waitUntilAtLeastOneExists(invitation, PAIR_TIMEOUT)
        val text = compose.onNode(invitation).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.onNodeWithText("Caduca en", substring = true).assertExists()

        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            assertTrue(runBlocking { peer.engine(0).acceptInvitation(text) } is AcceptResult.Paired)

            // Emparejado: a nombrar, con el número de seguridad del motor.
            compose.waitUntilAtLeastOneExists(hasText("Comparadlo por otro canal (en persona o por llamada)"), PAIR_TIMEOUT)
            val id = runBlocking { app.storage.contacts.all() }.single().id
            val safety = runBlocking { container.engine.safetyNumber(id) }
            compose.waitUntilAtLeastOneExists(hasText(safety.split(' ').first()), TIMEOUT)

            compose.onNodeWithText("Guardar").performClick()
            waitForText("Escribe un nombre")
            compose.onNodeWithText("Nombre").performTextInput("Ana")
            compose.onNodeWithText("Hemos comprobado que coincide").performClick()
            compose.onNodeWithText("Guardar").performClick()

            // Sin chat todavía en el grafo: vuelve a la lista (y "Añadir" ya no está en la pila).
            waitForText("Ana")
            val stored = runBlocking { app.storage.contacts.all() }.single()
            assertEquals("Ana", stored.name)
            assertTrue(stored.verified)
            compose.onNodeWithText("verificado").assertExists()
            // Ningún servicio temporal sigue publicado.
            assertTrue(app.network.published.value.values.all { it.requiresClientAuth })
        }
    }

    @Test
    fun leavingAddContactCancelsTheInvitation() {
        show()
        waitForText("Aún no tienes contactos")
        compose.onNodeWithText("Activar").performClick()
        compose.waitUntil(TIMEOUT) { container.engine.status.value == EngineStatus.Online }
        compose.onNodeWithContentDescription("Añadir contacto").performClick()
        compose.waitUntilAtLeastOneExists(hasText("ghostp2:", substring = true), PAIR_TIMEOUT)
        assertTrue(app.network.published.value.values.any { !it.requiresClientAuth })

        compose.onNodeWithContentDescription("Volver").performClick()
        waitForText("Aún no tienes contactos")
        compose.waitUntil(TIMEOUT) { app.network.published.value.values.none { !it.requiresClientAuth } }
    }

    @Test
    fun acceptingFromPasteNamesAndRemovesOwnInvitation() {
        show()
        waitForText("Aún no tienes contactos")
        compose.onNodeWithText("Activar").performClick()
        compose.waitUntil(TIMEOUT) { container.engine.status.value == EngineStatus.Online }
        compose.onNodeWithContentDescription("Añadir contacto").performClick()
        val invitation = hasText("ghostp2:", substring = true)
        compose.waitUntilAtLeastOneExists(invitation, PAIR_TIMEOUT)
        val mine = compose.onNode(invitation).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            .removePrefix("ghostp2:").substringBefore('.')
        assertTrue(mine in app.network.published.value)

        EngineHarness(1, network = app.network).use { peer ->
            runBlocking { peer.startAll() }
            val handle = peer.engine(0).createInvitation()
            val theirs = runBlocking { withTimeout(PAIR_TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } }
                as InvitationState.Waiting

            compose.onNode(hasText("Pegar") and hasRole(Role.Tab)).performClick()
            compose.onNodeWithText("Invitación").performTextInput(theirs.text)
            compose.onNodeWithText("Aceptar").performClick()

            // Emparejado aceptando: a nombrar, y "Añadir" (con mi invitación) ha salido de la pila.
            compose.waitUntilAtLeastOneExists(hasText("Comparadlo por otro canal (en persona o por llamada)"), PAIR_TIMEOUT)
            compose.waitUntil(TIMEOUT) { mine !in app.network.published.value }
            compose.onNodeWithText("Más tarde").performClick()
            waitForText("Contacto nuevo")
        }
    }

    @Test
    fun activateFromAddWithFailingStorageShowsStorageError() {
        show()
        waitForText("Aún no tienes contactos")
        compose.onNodeWithText("Añadir contacto").performClick()
        waitForText("Activa GhostP2 para añadir contactos")

        app.storage.failure = StoreUnavailableException(IOException("x"))
        compose.onNodeWithText("Activar").performClick()

        waitForText("No se pudo acceder a tus datos cifrados")
        assertEquals(0, activations)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        const val PAIR_TIMEOUT = 30_000L
        const val TIMEOUT = 10_000L
    }
}
