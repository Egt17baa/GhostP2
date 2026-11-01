package org.ghostp2.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.ghostp2.app.AppContainer
import org.ghostp2.app.R
import org.ghostp2.app.StorageState

/**
 * Almacenamiento cifrado ilegible ([StorageState.Corrupted]) o que falla ([StorageState.Unavailable],
 * [StorageState.Failed]). "Reintentar" solo vuelve a leer; "Empezar de cero" borra todo: con datos
 * ilegibles, tras una confirmación; tras [StorageState.FAILURES_BEFORE_RESET] fallos seguidos, tras dos.
 * [onRetry] es lo que hace "Reintentar" (por defecto, volver a comprobar el almacenamiento).
 */
@Composable
fun StorageErrorScreen(
    container: AppContainer,
    state: StorageState,
    onRetry: suspend () -> Unit = { container.checkStorage() },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            StorageState.Corrupted -> CorruptedPanel(container, onRetry)
            is StorageState.Unavailable -> RetryPanel(
                container, state, stringResource(R.string.store_unavailable), stringResource(R.string.store_unavailable_detail), onRetry,
            )
            is StorageState.Failed -> RetryPanel(container, state, stringResource(R.string.store_failed), null, onRetry)
            StorageState.Unknown, StorageState.Ok -> Unit
        }
    }
}

@Composable
private fun CorruptedPanel(container: AppContainer, onRetry: suspend () -> Unit) {
    val ui = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    Text(stringResource(R.string.store_corrupted), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.store_corrupted_detail))
    if (failed) Text(stringResource(R.string.store_reset_failed))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Solo lectura: vuelve a comprobar (p. ej. Keystore tardó en responder) sin borrar nada.
        OutlinedButton(onClick = { ui.launch { onRetry() } }) { Text(stringResource(R.string.action_retry)) }
        Button(onClick = { confirming = true }) { Text(stringResource(R.string.store_reset)) }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.store_reset_confirm_title),
            text = stringResource(R.string.store_reset_confirm_text),
            onConfirm = {
                confirming = false
                // En el scope del proceso: salir de la pantalla no deja el borrado a medias.
                container.scope.launch { failed = !attempt { container.wipeCorrupted() } }
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun RetryPanel(
    container: AppContainer,
    state: StorageState.FailureStreak,
    title: String,
    detail: String?,
    onRetry: suspend () -> Unit,
) {
    val ui = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) } // 0: nada, 1: primera confirmación, 2: segunda
    Text(title, style = MaterialTheme.typography.titleMedium)
    detail?.let { Text(it) }
    Button(onClick = { ui.launch { onRetry() } }) { Text(stringResource(R.string.action_retry)) }
    if (state.canForceReset) {
        Text(stringResource(R.string.store_unavailable_persistent))
        if (failed) Text(stringResource(R.string.store_reset_failed))
        OutlinedButton(onClick = { step = 1 }) { Text(stringResource(R.string.store_reset)) }
    }
    when (step) {
        1 -> ConfirmDialog(
            title = stringResource(R.string.store_reset_confirm_title),
            text = stringResource(R.string.store_reset_confirm_text),
            onConfirm = { step = 2 },
            onDismiss = { step = 0 },
        )
        2 -> ConfirmDialog(
            title = stringResource(R.string.store_reset_confirm_again_title),
            text = stringResource(R.string.store_reset_confirm_again_text),
            onConfirm = {
                step = 0
                container.scope.launch { failed = !attempt { container.forceReset() } }
            },
            onDismiss = { step = 0 },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.store_reset_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** `true` si `block` termina sin lanzar; la cancelación se relanza (a diferencia de `runCatching`). */
private suspend inline fun attempt(block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
