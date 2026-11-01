package org.ghostp2.app.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import org.ghostp2.app.ui.GhostTextField
import org.ghostp2.app.ui.pairing.SafetyNumberGroups
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery

/** Último estado no nulo visto: tras borrar el contacto, el aviso del resultado aún muestra su nombre. */
private class LastState {
    var value: ContactDetailUiState? = null
}

/**
 * Detalle del contacto: estado (presencia y verificado), aviso de identidad cambiada con "Volver a
 * emparejar" ([onRepair]), número de seguridad en 6 grupos, "Marcar como verificado" / "Quitar
 * verificación", "Cambiar el nombre" (diálogo con [renameDraft], `null` ⇒ cerrado), "Borrar chat" y
 * "Borrar contacto" (los dos con confirmación). Con [busy] (guardando) o borrando, las acciones quedan
 * desactivadas. Con [deleteResult] se muestra el resultado del aviso y "Aceptar" llama a [onDeleteDone].
 * [state] `null` sin estado previo ⇒ cargando. Los diálogos abiertos son simple `remember` (sin contenido).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    state: ContactDetailUiState?,
    busy: Boolean,
    renameDraft: String?,
    renameError: Int?,
    actionError: Int?,
    deleteResult: UnpairDelivery?,
    onBack: () -> Unit,
    onStartRename: () -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onRename: () -> Unit,
    onCancelRename: () -> Unit,
    onSetVerified: (Boolean) -> Unit,
    onRepair: () -> Unit,
    onClearChat: () -> Unit,
    onDelete: () -> Unit,
    onDeleteDone: () -> Unit,
) {
    val last = remember { LastState() }
    if (state != null) last.value = state
    val shown = state ?: last.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shown?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (shown == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Details(
                state = shown,
                busy = busy,
                actionError = actionError,
                onStartRename = onStartRename,
                onSetVerified = onSetVerified,
                onRepair = onRepair,
                onClearChat = onClearChat,
                onDelete = onDelete,
                modifier = Modifier.padding(padding),
            )
            if (renameDraft != null && !shown.deleting && deleteResult == null) {
                RenameDialog(renameDraft, renameError, busy, onRenameDraftChange, onRename, onCancelRename)
            }
        }
    }

    if (deleteResult != null) {
        val name = shown?.name.orEmpty()
        val message = when (deleteResult) {
            UnpairDelivery.DELIVERED -> stringResource(R.string.contact_delete_delivered, name)
            UnpairDelivery.NOT_DELIVERED -> stringResource(R.string.contact_delete_not_delivered, name)
        }
        AlertDialog(
            onDismissRequest = onDeleteDone,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDeleteDone) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun Details(
    state: ContactDetailUiState,
    busy: Boolean,
    actionError: Int?,
    onStartRename: () -> Unit,
    onSetVerified: (Boolean) -> Unit,
    onRepair: () -> Unit,
    onClearChat: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val enabled = !busy && !state.deleting

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.deleting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.contacts_deleting), Modifier.padding(start = 8.dp))
            }
        }
        if (state.identityChanged) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.contact_identity_changed, state.name),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = onRepair, enabled = !state.deleting) { Text(stringResource(R.string.contact_repair)) }
                }
            }
        } else {
            Text(statusLine(state), style = MaterialTheme.typography.bodyMedium)
        }

        Text(stringResource(R.string.name_safety_title), style = MaterialTheme.typography.titleMedium)
        val number = state.safetyNumber
        if (number != null) SafetyNumberGroups(number) else Text(stringResource(R.string.name_safety_unavailable))
        Text(stringResource(R.string.name_safety_compare))
        if (!state.identityChanged) {
            // Verificar una identidad que ya no es la del contacto no tiene sentido: solo sin cambio.
            OutlinedButton(onClick = { onSetVerified(!state.verified) }, enabled = enabled) {
                Text(stringResource(if (state.verified) R.string.contact_unverify else R.string.contact_verify))
            }
        }
        actionError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }

        OutlinedButton(onClick = onStartRename, enabled = enabled) { Text(stringResource(R.string.contact_rename)) }
        OutlinedButton(onClick = { confirmClear = true }, enabled = !state.deleting) {
            Text(stringResource(R.string.contact_clear_chat))
        }
        Button(
            onClick = { confirmDelete = true },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(stringResource(R.string.contact_delete)) }
    }

    if (confirmClear) {
        Confirm(
            title = stringResource(R.string.contact_clear_chat_title, state.name),
            text = stringResource(R.string.contact_clear_chat_text),
            confirm = stringResource(R.string.contact_clear_chat_confirm),
            onConfirm = {
                confirmClear = false
                onClearChat()
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmDelete && !state.deleting) {
        Confirm(
            title = stringResource(R.string.contact_delete_title, state.name),
            text = stringResource(R.string.contact_delete_text),
            confirm = stringResource(R.string.contact_delete_confirm),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "en línea · verificado", "desconectado · sin verificar"… (sin presencia, motor parado: desconectado). */
@Composable
private fun statusLine(state: ContactDetailUiState): String {
    val presence = when (state.presence) {
        Presence.ONLINE -> stringResource(R.string.contacts_online)
        Presence.CONNECTING -> stringResource(R.string.contacts_connecting)
        else -> stringResource(R.string.contacts_offline)
    }
    val verified = stringResource(if (state.verified) R.string.contacts_verified else R.string.contact_not_verified)
    return "$presence · $verified"
}

@Composable
private fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RenameDialog(
    draft: String,
    error: Int?,
    busy: Boolean,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.contact_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostTextField(
                    value = draft,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.name_label),
                    singleLine = true,
                )
                // El de "demasiado largo" lleva el máximo; los demás ignoran el argumento.
                error?.let { Text(stringResource(it, MAX_CONTACT_NAME_CHARS), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text(stringResource(R.string.name_save)) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
