package org.ghostp2.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineStatus

/**
 * Aviso del estado del motor encima de la lista de contactos. Con el motor `Online` no muestra nada.
 * [startFailed] ([org.ghostp2.app.AppContainer.startFailed]) manda sobre el estado: el arranque falló
 * por un error inesperado y el motor quedó detenido. [bridged]: el ajuste «Puentes» no es «No» (R30), y el
 * arranque dice «Conectando por puente…» (sin nombrar el puente).
 */
@Composable
fun EngineBanner(status: EngineStatus, startFailed: Boolean, onActivate: () -> Unit, onDisconnect: () -> Unit, bridged: Boolean = false) {
    val retry = stringResource(R.string.action_retry)
    when {
        startFailed -> Banner(stringResource(R.string.banner_unexpected), primary = retry to onActivate)
        status == EngineStatus.Stopped ->
            Banner(stringResource(R.string.banner_stopped), primary = stringResource(R.string.action_activate) to onActivate)
        status is EngineStatus.StartingTor ->
            Banner(stringResource(startingText(bridged), status.percent), secondary = onDisconnect)
        status is EngineStatus.Error && status.kind == EngineErrorKind.STORAGE ->
            Banner(stringResource(R.string.banner_storage_error), primary = retry to onActivate)
        // Un Tor anterior no se detuvo: solo se arregla reiniciando el proceso (reintentar lo vuelve a probar).
        status is EngineStatus.Error && status.kind == EngineErrorKind.TOR_STUCK ->
            Banner(stringResource(R.string.banner_tor_stuck), primary = retry to onActivate, secondary = onDisconnect)
        // R30: nunca se reintenta sin puente; "Reintentar" vuelve a probar con el mismo ajuste.
        status is EngineStatus.Error && status.kind == EngineErrorKind.BRIDGE_FAILED ->
            Banner(stringResource(R.string.banner_bridge_failed), primary = retry to onActivate, secondary = onDisconnect)
        status is EngineStatus.Error ->
            Banner(stringResource(R.string.banner_tor_error), primary = retry to onActivate, secondary = onDisconnect)
        else -> Unit // Online: nada
    }
}

/** "Conectando por puente… N %" con un ajuste de puentes; si no, "Conectando a la red Tor… N %". */
@StringRes
internal fun startingText(bridged: Boolean): Int = if (bridged) R.string.banner_starting_bridge else R.string.banner_starting

/** Texto con, opcionalmente, un botón principal y "Desconectar" ([secondary]). */
@Composable
private fun Banner(text: String, primary: Pair<String, () -> Unit>? = null, secondary: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
                secondary?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.action_disconnect)) } }
            }
        }
    }
}
