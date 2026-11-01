package org.ghostp2.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import org.ghostp2.app.ui.Routes
import org.ghostp2.engine.Presence

/**
 * Adónde lleva pulsar una fila: con la identidad cambiada, al detalle del contacto (hay que volver a
 * emparejar); si no, al chat. Las filas que se están borrando no se pueden pulsar.
 */
fun openRoute(row: ContactRow): String = if (row.identityChanged) Routes.contact(row.id) else Routes.chat(row.id)

/**
 * Lista de contactos con [banner] (el aviso del motor) encima. Barra superior: "Añadir contacto",
 * "Ajustes" y, si [onDisconnect] no es `null` (motor `Online`), "Desconectar".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    banner: @Composable () -> Unit,
    onOpen: (ContactRow) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.contacts_add))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.contacts_settings))
                    }
                    onDisconnect?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.action_disconnect),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            banner()
            when (state) {
                ContactsUiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ContactsUiState.Failed ->
                    Message(stringResource(R.string.contacts_failed), stringResource(R.string.action_retry), onRetry)
                is ContactsUiState.Loaded ->
                    if (state.rows.isEmpty()) {
                        Message(stringResource(R.string.contacts_empty), stringResource(R.string.contacts_add), onAdd)
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.rows, key = { it.id }) { row ->
                                ContactItem(row, onOpen)
                                HorizontalDivider()
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun ContactItem(row: ContactRow, onOpen: (ContactRow) -> Unit) {
    val status = when {
        row.deleting -> stringResource(R.string.contacts_deleting)
        row.identityChanged -> stringResource(R.string.contacts_identity_changed)
        else -> when (row.presence) {
            Presence.ONLINE -> stringResource(R.string.contacts_online)
            Presence.CONNECTING -> stringResource(R.string.contacts_connecting)
            // Sin presencia (motor parado) o desconectado.
            else -> stringResource(R.string.contacts_offline)
        }
    }
    ListItem(
        headlineContent = { Text(row.name) },
        supportingContent = {
            Text(
                status,
                color = if (row.identityChanged && !row.deleting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (row.verified) {
            { Text(stringResource(R.string.contacts_verified), style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
        // Mientras se borra no se puede abrir.
        modifier = if (row.deleting) Modifier else Modifier.clickable { onOpen(row) },
    )
}

/** Texto centrado con un botón (lista vacía o error). */
@Composable
private fun Message(text: String, action: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Row { Button(onClick = onClick) { Text(action) } }
    }
}
