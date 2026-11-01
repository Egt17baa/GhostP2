package org.ghostp2.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import org.ghostp2.app.AppContainer
import org.ghostp2.app.StorageState
import org.ghostp2.app.ui.contacts.ContactsScreen
import org.ghostp2.app.ui.contacts.ContactsViewModel
import org.ghostp2.app.ui.contacts.openRoute
import org.ghostp2.app.ui.onboarding.OnboardingRoute
import org.ghostp2.app.ui.pairing.AddContactScreen
import org.ghostp2.app.ui.pairing.NameContactScreen
import org.ghostp2.app.ui.pairing.NameContactViewModel
import org.ghostp2.app.ui.pairing.PairingViewModel
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.EngineStatus

/** Lo que la interfaz pide a la actividad: servicio, permiso de notificaciones y exención de batería. */
class ActivityActions(
    val activate: () -> Unit,
    val disconnect: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val requestNotifications: () -> Unit,
)

/**
 * Raíz de la interfaz. Sin comprobar el almacenamiento: lo comprueba y muestra la carga; si falla:
 * [StorageErrorScreen]; con los ajustes aún sin leer: solo la carga; mientras `onboardingDone` sea falso:
 * la bienvenida; después, el `NavHost` (inicio en [Routes.CONTACTS]).
 */
@Composable
fun GhostRoot(container: AppContainer, actions: ActivityActions) {
    val storage by container.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (container.storage.value == StorageState.Unknown) container.checkStorage() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = storage) {
            StorageState.Unknown -> Loading()
            StorageState.Ok -> ReadyRoot(container, actions)
            StorageState.Corrupted, is StorageState.Unavailable, is StorageState.Failed ->
                Box(Modifier.safeDrawingPadding()) { StorageErrorScreen(container, state) }
        }
    }
}

/**
 * Almacenamiento comprobado: espera a los ajustes y después navega. Si el flujo de ajustes falla, se
 * vuelve a comprobar el almacenamiento: si sigue mal, la raíz pasa a [StorageErrorScreen] (y este flujo
 * deja de recogerse); si no, se reintenta, hasta [SETTINGS_ATTEMPTS] lecturas. Agotadas, se muestra
 * [StorageErrorScreen] con [StorageState.Failed] (nunca una carga sin fin) y "Reintentar" vuelve a
 * empezar; cada tanda agotada cuenta como un fallo de la racha (a la tercera se ofrece empezar de cero).
 */
@Composable
private fun ReadyRoot(container: AppContainer, actions: ActivityActions) {
    var round by remember { mutableIntStateOf(0) }
    var exhaustedRounds by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var onboardingSaveFailed by remember { mutableStateOf(false) }
    val ui = rememberCoroutineScope()
    val settingsFlow = remember(container, round) {
        container.settings.settings
            .retryWhen { _, attempt ->
                attempt + 1 < SETTINGS_ATTEMPTS &&
                    container.checkStorage() == StorageState.Ok &&
                    run {
                        delay(SETTINGS_RETRY_MILLIS)
                        true
                    }
            }
            .catch {
                // Nunca se cierra la app; si el almacenamiento falla, ya lo muestra la raíz.
                if (container.storage.value == StorageState.Ok) {
                    exhaustedRounds++
                    exhausted = true
                }
            }
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
    if (exhausted) {
        Box(Modifier.safeDrawingPadding()) {
            StorageErrorScreen(container, StorageState.Failed(exhaustedRounds), onRetry = {
                if (container.checkStorage() == StorageState.Ok) {
                    exhausted = false
                    round++
                }
            })
        }
        return
    }
    val current = settings
    if (current == null) {
        Loading()
        return
    }
    if (!current.onboardingDone) {
        OnboardingRoute(actions, saveFailed = onboardingSaveFailed, onFinish = {
            if (!finishing) {
                finishing = true
                onboardingSaveFailed = false
                ui.launch {
                    try {
                        onboardingSaveFailed = !finishOnboarding(container, actions)
                    } finally {
                        finishing = false
                    }
                }
            }
        })
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CONTACTS) {
        composable(Routes.CONTACTS) {
            val vm = ghostViewModel(container) { ContactsViewModel(it) }
            val state by vm.state.collectAsStateWithLifecycle()
            val status by container.engine.status.collectAsStateWithLifecycle()
            ContactsScreen(
                state = state,
                banner = { RootBanner(container, actions) },
                onOpen = { row -> navController.openIfRegistered(openRoute(row)) },
                onAdd = { navController.openIfRegistered(Routes.ADD) },
                onSettings = { navController.openIfRegistered(Routes.SETTINGS) },
                onRetry = vm::retry,
                onDisconnect = actions.disconnect.takeIf { status != EngineStatus.Stopped },
            )
        }
        composable(Routes.ADD) {
            // La invitación vive lo que este ViewModel: al salir (atrás o al emparejar) se cancela.
            val vm = ghostViewModel(container) { PairingViewModel(it) }
            val status by container.engine.status.collectAsStateWithLifecycle()
            val invitation by vm.invitation.collectAsStateWithLifecycle()
            val accept by vm.accept.collectAsStateWithLifecycle()
            val paired by vm.pairedContactId.collectAsStateWithLifecycle()
            LaunchedEffect(paired) {
                paired?.let { id ->
                    // A nombrar al contacto, sin dejar "Añadir" en la pila.
                    navController.navigate(Routes.name(id)) { popUpTo(Routes.ADD) { inclusive = true } }
                }
            }
            AddContactScreen(
                online = status == EngineStatus.Online,
                invitation = invitation,
                accept = accept,
                onCreateInvitation = vm::createInvitation,
                onAccept = vm::accept,
                onActivate = { ui.launch { if (container.checkStorage() == StorageState.Ok) actions.activate() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.NAME) { entry ->
            val id = entry.idArg()
            val vm = ghostViewModel(container) { NameContactViewModel(it, id) }
            val safetyNumber by vm.safetyNumber.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            val saved by vm.saved.collectAsStateWithLifecycle()
            LaunchedEffect(saved) { if (saved) navController.leaveNaming(id) }
            NameContactScreen(
                safetyNumber = safetyNumber,
                error = error,
                onSave = { name, verified -> vm.save(name, verified) },
                onLater = { navController.leaveNaming(id) },
            )
        }
    }
}

/**
 * Sale de nombrar al contacto [id] hacia su chat (sin dejar la pantalla de nombrar en la pila). Mientras
 * el chat no esté en el grafo (Task 10), vuelve a la lista de contactos.
 */
private fun NavController.leaveNaming(id: String) {
    val chat = Routes.chat(id)
    if (graph.findNode(chat) != null) {
        navigate(chat) { popUpTo(Routes.NAME) { inclusive = true } }
    } else {
        popBackStack(Routes.CONTACTS, inclusive = false)
    }
}

/**
 * Navega a [route] solo si su destino ya está en el grafo: las pantallas de añadir, detalle, chat y
 * ajustes llegan en tareas posteriores y, hasta entonces, pulsar no hace nada (en vez de cerrar la app).
 */
internal fun NavController.openIfRegistered(route: String) {
    if (graph.findNode(route) != null) navigate(route)
}

/**
 * [EngineBanner] del motor del contenedor. "Activar" y "Reintentar" comprueban antes el almacenamiento:
 * si falla, la raíz muestra [StorageErrorScreen] en lugar de arrancar.
 */
@Composable
private fun RootBanner(container: AppContainer, actions: ActivityActions) {
    val status by container.engine.status.collectAsStateWithLifecycle()
    val startFailed by container.startFailed.collectAsStateWithLifecycle()
    val ui = rememberCoroutineScope()
    EngineBanner(
        status = status,
        startFailed = startFailed,
        onActivate = { ui.launch { if (container.checkStorage() == StorageState.Ok) actions.activate() } },
        onDisconnect = actions.disconnect,
    )
}

/**
 * "Activar GhostP2" de la bienvenida: primero la marca como hecha y solo después activa. Si no se puede
 * guardar, no se activa y se vuelve a comprobar el almacenamiento: si falla, la raíz muestra
 * [StorageErrorScreen]; si no, devuelve falso y la bienvenida muestra el error junto al botón.
 */
private suspend fun finishOnboarding(container: AppContainer, actions: ActivityActions): Boolean {
    try {
        container.settings.update { it.copy(onboardingDone = true) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // El almacenamiento falla: la raíz pasa a la pantalla de error y este aviso ya no se ve.
        container.checkStorage()
        return false
    }
    actions.activate()
    return true
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private const val SETTINGS_ATTEMPTS = 3
private const val SETTINGS_RETRY_MILLIS = 1_000L
