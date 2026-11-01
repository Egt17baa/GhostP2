package org.ghostp2.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import org.ghostp2.app.AppContainer

/*
 * Convenciones de la interfaz (todas las pantallas):
 * - Los flujos se recogen en los composables con `collectAsStateWithLifecycle()`, nunca con
 *   `collectAsState()`: con la actividad en segundo plano no se recoge ni se recompone nada.
 * - Los ViewModel se crean con [ghostViewModel]: reciben el único [AppContainer] del proceso (su motor,
 *   su Tor y su almacenamiento) y nunca construyen repositorios, motores ni transportes propios.
 * - Sin estado guardable (ni el manejador de estado guardado del ViewModel): todo vive solo en memoria. Los únicos datos que el
 *   sistema guarda son las rutas, que solo llevan ids ([Routes]); se leen con [idArg].
 */

/**
 * ViewModel de la pantalla actual (del destino de navegación, si lo hay), creado una sola vez con
 * `create(container)`; p. ej. `ghostViewModel(container) { ChatViewModel(it, entry.idArg()) }`.
 */
@Composable
inline fun <reified VM : ViewModel> ghostViewModel(container: AppContainer, crossinline create: (AppContainer) -> VM): VM =
    viewModel { create(container) }

/** Argumento de ruta `name` (`{id}`, `{item}`) ya descodificado, tal como se pasó a [Routes]. */
fun NavBackStackEntry.idArg(name: String = "id"): String =
    requireNotNull(arguments?.getString(name)) { "Falta el argumento de ruta $name" }
