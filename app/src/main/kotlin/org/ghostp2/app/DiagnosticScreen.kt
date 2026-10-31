package org.ghostp2.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.Contact
import org.ghostp2.engine.DeliveryStatus
import org.ghostp2.engine.Direction
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationHandle
import org.ghostp2.engine.InvitationState
import org.ghostp2.engine.Notice
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery

/*
 * Pantalla de diagnóstico TEMPORAL para la prueba manual entre dos móviles (el plan 3 la sustituye por la
 * interfaz definitiva). Solo texto: no muestra fotos ni guarda nada; no registra contenido en ningún log.
 */

/** Acciones que la pantalla pide a la actividad (permiso de notificaciones, servicio, batería). */
class DiagnosticActions(
    val activate: () -> Unit,
    val disconnect: () -> Unit,
    val requestBatteryExemption: () -> Unit,
)

@Composable
fun DiagnosticScreen(container: AppContainer, actions: DiagnosticActions, batteryIgnoring: Boolean) {
    val storage by container.storage.collectAsState()
    LaunchedEffect(Unit) { if (container.storage.value == StorageState.Unknown) container.checkStorage() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleLarge)
        when (val s = storage) {
            StorageState.Corrupted -> CorruptedPanel(container)
            is StorageState.Unavailable -> RetryPanel(
                container, s, stringResource(R.string.store_unavailable), stringResource(R.string.store_unavailable_detail),
            )
            is StorageState.Failed -> RetryPanel(container, s, stringResource(R.string.diag_unexpected), null)
            StorageState.Unknown -> Unit
            StorageState.Ok -> EnginePanel(container, actions, batteryIgnoring)
        }
    }
}

@Composable
private fun CorruptedPanel(container: AppContainer) {
    val ui = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    Text(stringResource(R.string.store_corrupted), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.store_corrupted_detail))
    if (failed) Text(stringResource(R.string.store_reset_failed))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Solo lectura: vuelve a comprobar (p. ej. Keystore tardó en responder) sin borrar nada.
        OutlinedButton(onClick = { ui.launch { container.checkStorage() } }) { Text(stringResource(R.string.diag_retry)) }
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
private fun RetryPanel(container: AppContainer, state: StorageState.FailureStreak, title: String, detail: String?) {
    val ui = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) } // 0: nada, 1: primera confirmación, 2: segunda
    Text(title, style = MaterialTheme.typography.titleMedium)
    detail?.let { Text(it) }
    Button(onClick = { ui.launch { container.checkStorage() } }) { Text(stringResource(R.string.diag_retry)) }
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

/** Contactos guardados, o error de lectura (se muestra, no se esconde como lista vacía). */
private sealed interface ContactsView {
    data object Loading : ContactsView

    data class Loaded(val list: List<Contact>) : ContactsView

    data object Failed : ContactsView
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.store_reset_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_cancel)) } },
    )
}

@Composable
private fun EnginePanel(container: AppContainer, actions: DiagnosticActions, batteryIgnoring: Boolean) {
    val engine = container.engine
    val status by engine.status.collectAsState()
    val myOnion by engine.myOnion.collectAsState()
    val presence by engine.presence.collectAsState()
    val startFailed by container.startFailed.collectAsState()
    val lastDiagnostic by container.lastDiagnostic.collectAsState()
    var contactsAttempt by remember { mutableIntStateOf(0) }
    val contactsView by remember(contactsAttempt) {
        container.contacts.contacts
            .map<List<Contact>, ContactsView> { ContactsView.Loaded(it) }
            .catch { emit(ContactsView.Failed) }
    }.collectAsState(ContactsView.Loading)
    val contacts = (contactsView as? ContactsView.Loaded)?.list.orEmpty()
    var openChat by remember { mutableStateOf<String?>(null) }

    Text(statusText(status))
    if (startFailed) Text(stringResource(R.string.diag_unexpected))
    when (status) {
        EngineStatus.Stopped -> Button(onClick = actions.activate) {
            Text(stringResource(if (startFailed) R.string.diag_retry else R.string.diag_activate))
        }
        is EngineStatus.Error -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.activate) { Text(stringResource(R.string.diag_retry)) }
            OutlinedButton(onClick = actions.disconnect) { Text(stringResource(R.string.diag_disconnect)) }
        }
        else -> OutlinedButton(onClick = actions.disconnect) { Text(stringResource(R.string.diag_disconnect)) }
    }
    when (val notice = lastDiagnostic) {
        Notice.PublicationFailed -> Text(stringResource(R.string.diag_last_notice_publication))
        is Notice.ClientAuthFailed -> Text(pluralStringResource(R.plurals.diag_last_notice_client_auth, notice.contactIds.size, notice.contactIds.size))
        else -> Unit
    }
    Text(myOnion?.let { stringResource(R.string.diag_my_onion, abbreviate(it)) } ?: stringResource(R.string.diag_my_onion_none))
    if (batteryIgnoring) {
        Text(stringResource(R.string.diag_battery_ok))
    } else {
        Text(stringResource(R.string.diag_battery_on))
        OutlinedButton(onClick = actions.requestBatteryExemption) { Text(stringResource(R.string.diag_battery_request)) }
    }

    HorizontalDivider()
    val online = contacts.count { presence[it.id] == Presence.ONLINE }
    Text(stringResource(R.string.diag_contacts, contacts.size, online), style = MaterialTheme.typography.titleMedium)
    if (contactsView == ContactsView.Failed) {
        Text(stringResource(R.string.diag_contacts_error))
        OutlinedButton(onClick = { contactsAttempt++ }) { Text(stringResource(R.string.diag_retry)) }
    }
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    val deliveredText = stringResource(R.string.diag_delete_delivered)
    val notDeliveredText = stringResource(R.string.diag_delete_not_delivered)
    val deleteFailedText = stringResource(R.string.diag_delete_failed)
    contacts.forEach { contact ->
        Text(
            "${contact.name} · ${presenceText(presence[contact.id])}",
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openChat = contact.id }
                .padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openChat = contact.id }) { Text(stringResource(R.string.diag_open_chat)) }
            TextButton(onClick = { container.engine.clearConversation(contact.id) }) {
                Text(stringResource(R.string.diag_clear_chat))
            }
            TextButton(onClick = { confirmDelete = contact }) { Text(stringResource(R.string.diag_delete_contact)) }
        }
    }
    deleteMessage?.let { message ->
        Text(message)
        // Aviso breve: desaparece a los pocos segundos.
        LaunchedEffect(message) {
            delay(DELETE_MESSAGE_MILLIS)
            deleteMessage = null
        }
    }
    confirmDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.diag_delete_confirm_title, contact.name)) },
            text = { Text(stringResource(R.string.diag_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (openChat == contact.id) openChat = null
                    deleteMessage = null
                    val pending = container.deleteContact(contact.id)
                    // El resultado llega aunque se salga de la pantalla; solo se muestra si sigue abierta.
                    container.scope.launch {
                        deleteMessage = when (runCatching { pending.await() }.getOrNull()) {
                            UnpairDelivery.DELIVERED -> deliveredText.format(contact.name)
                            UnpairDelivery.NOT_DELIVERED -> notDeliveredText.format(contact.name)
                            null -> deleteFailedText
                        }
                    }
                }) { Text(stringResource(R.string.diag_delete_contact)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
    val chatContact = contacts.firstOrNull { it.id == openChat }
    if (chatContact != null) {
        MiniChat(container, chatContact, engineOnline = status == EngineStatus.Online, onClose = { openChat = null })
    }

    HorizontalDivider()
    InvitationPanel(container, engineOnline = status == EngineStatus.Online)
}

@Composable
private fun statusText(status: EngineStatus): String = when (status) {
    EngineStatus.Stopped -> stringResource(R.string.diag_status_stopped)
    is EngineStatus.StartingTor -> stringResource(R.string.diag_status_starting, status.percent)
    EngineStatus.Online -> stringResource(R.string.diag_status_online)
    is EngineStatus.Error -> stringResource(R.string.diag_status_error, status.message)
}

@Composable
private fun presenceText(presence: Presence?): String = when (presence) {
    Presence.ONLINE -> stringResource(R.string.diag_presence_online)
    Presence.CONNECTING -> stringResource(R.string.diag_presence_connecting)
    Presence.IDENTITY_CHANGED -> stringResource(R.string.diag_presence_identity_changed)
    Presence.OFFLINE, null -> stringResource(R.string.diag_presence_offline)
}

/** Primeros y últimos caracteres de la .onion (sin ".onion"). */
private fun abbreviate(onion: String): String =
    if (onion.length <= 16) "$onion.onion" else "${onion.take(8)}…${onion.takeLast(6)}.onion"

@Composable
private fun InvitationPanel(container: AppContainer, engineOnline: Boolean) {
    val context = LocalContext.current
    val ui = rememberCoroutineScope()
    var handle by remember { mutableStateOf<InvitationHandle?>(null) }
    var pasted by remember { mutableStateOf("") }
    var accepting by remember { mutableStateOf(false) }
    var acceptResult by remember { mutableStateOf<AcceptResult?>(null) }
    // Una invitación activa se cancela al salir de la pantalla.
    DisposableEffect(Unit) { onDispose { handle?.cancel?.invoke() } }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = engineOnline,
            onClick = {
                handle?.cancel?.invoke()
                handle = container.engine.createInvitation()
            },
        ) { Text(stringResource(R.string.diag_create_invitation)) }
    }
    handle?.let { h ->
        val state by h.state.collectAsState()
        when (val s = state) {
            InvitationState.Preparing -> Text(stringResource(R.string.diag_invitation_preparing))
            is InvitationState.Waiting -> {
                Text(stringResource(R.string.diag_invitation_waiting))
                SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copySensitive(context, s.text) }) { Text(stringResource(R.string.diag_copy)) }
                    OutlinedButton(onClick = { h.cancel() }) { Text(stringResource(R.string.diag_cancel)) }
                }
            }
            is InvitationState.Paired -> Text(stringResource(R.string.diag_invitation_paired, s.safetyNumber))
            InvitationState.Expired -> Text(stringResource(R.string.diag_invitation_expired))
            InvitationState.Cancelled -> Text(stringResource(R.string.diag_invitation_cancelled))
            is InvitationState.Failed -> Text(stringResource(R.string.diag_invitation_failed, s.message))
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.diag_paste_invitation), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text(stringResource(R.string.diag_invitation_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = engineOnline && !accepting && pasted.isNotBlank(),
        onClick = {
            accepting = true
            acceptResult = null
            val text = pasted.trim()
            ui.launch {
                try {
                    acceptResult = container.engine.acceptInvitation(text)
                    if (acceptResult is AcceptResult.Paired) pasted = ""
                } finally {
                    accepting = false
                }
            }
        },
    ) { Text(stringResource(R.string.diag_accept)) }
    if (accepting) Text(stringResource(R.string.diag_accepting))
    when (val r = acceptResult) {
        is AcceptResult.Paired -> Text(stringResource(R.string.diag_invitation_paired, r.safetyNumber))
        is AcceptResult.Failed -> Text(stringResource(R.string.diag_invitation_failed, r.message))
        null -> Unit
    }
}

/** Copia la invitación marcándola como sensible (Android 13+ no la muestra en la vista previa). */
private fun copySensitive(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(context.getString(R.string.diag_clipboard_label), text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

/**
 * Chat mínimo de texto. Solo envía con el motor en línea (regla R15: con el motor parado no se guarda
 * nada). `ONLINE` puede verse un instante antes de que haya sesión: entonces el mensaje queda
 * "no enviado" (`Failed(NOT_CONNECTED)`), sin más. Las fotos recibidas se muestran como "[foto]".
 */
@Composable
private fun MiniChat(container: AppContainer, contact: Contact, engineOnline: Boolean, onClose: () -> Unit) {
    val conversations by container.engine.conversations.collectAsState()
    val items: List<ChatItem> = conversations[contact.id].orEmpty()
    var draft by remember(contact.id) { mutableStateOf("") }
    var sendFailed by remember(contact.id) { mutableStateOf(false) }

    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.diag_chat_with, contact.name), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClose) { Text(stringResource(R.string.diag_close)) }
    }
    items.takeLast(MAX_SHOWN).forEach { item -> Text(chatLine(item)) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.diag_message_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (!engineOnline) Text(stringResource(R.string.diag_send_only_online))
    Button(
        enabled = engineOnline && draft.isNotBlank(),
        onClick = {
            // Texto demasiado largo u otro fallo: aviso breve, el borrador se conserva.
            runCatching { container.engine.sendText(contact.id, draft) }
                .onSuccess {
                    draft = ""
                    sendFailed = false
                }
                .onFailure { sendFailed = true }
        },
    ) { Text(stringResource(R.string.diag_send)) }
    if (sendFailed) Text(stringResource(R.string.diag_send_failed))
}

@Composable
private fun chatLine(item: ChatItem): String {
    val body = when (val c = item.content) {
        is ChatContent.Text -> c.text
        is ChatContent.Photo -> stringResource(R.string.diag_photo) // nunca se leen los bytes vivos
        is ChatContent.PhotoDiscarded -> stringResource(R.string.diag_photo_discarded)
    }
    val arrow = if (item.direction == Direction.OUT) "→" else "←"
    val state = when (item.status) {
        DeliveryStatus.Received -> ""
        is DeliveryStatus.Sending -> " (${stringResource(R.string.diag_sending)})" // textos: Sending(0, 0)
        DeliveryStatus.Delivered -> " (${stringResource(R.string.diag_delivered)})"
        is DeliveryStatus.Failed -> " (${stringResource(R.string.diag_failed)})"
    }
    return "$arrow $body$state"
}

private const val MAX_SHOWN = 50
private const val DELETE_MESSAGE_MILLIS = 6_000L
