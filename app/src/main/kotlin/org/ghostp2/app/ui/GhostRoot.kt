package org.ghostp2.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import org.ghostp2.app.ui.onboarding.OnboardingRoute
import org.ghostp2.data.AppSettings

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
        OnboardingRoute(actions, onFinish = {
            if (!finishing) {
                finishing = true
                ui.launch {
                    try {
                        finishOnboarding(container, actions)
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
            // Provisional: la lista de contactos (Task 6) pone aquí su pantalla con este aviso.
            Column(Modifier.safeDrawingPadding()) { RootBanner(container, actions) }
        }
    }
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
 * "Activar GhostP2" de la bienvenida: la marca como hecha y después activa. Si no se puede guardar, se
 * vuelve a comprobar el almacenamiento (si falla, la raíz muestra [StorageErrorScreen]) y no se activa.
 */
private suspend fun finishOnboarding(container: AppContainer, actions: ActivityActions) {
    try {
        container.settings.update { it.copy(onboardingDone = true) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        container.checkStorage()
        return
    }
    actions.activate()
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private const val SETTINGS_ATTEMPTS = 3
private const val SETTINGS_RETRY_MILLIS = 1_000L
