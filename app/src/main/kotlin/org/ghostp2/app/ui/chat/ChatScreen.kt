package org.ghostp2.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.app.R
import org.ghostp2.app.photo.PhotoBitmaps
import org.ghostp2.app.ui.GhostTextField
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.Direction
import org.ghostp2.engine.Presence

/** A partir de cuántos caracteres Unicode se muestra el contador ("7 501 / 8 000"). */
internal const val COUNTER_THRESHOLD = 7_500

/**
 * Chat con un contacto. Barra superior: nombre y presencia, "Volver" y un menú con "Ver contacto"
 * ([onOpenContact]) y "Borrar chat" (con confirmación, [onClear]). Debajo, los mensajes (se desplaza al
 * último cuando llega uno nuevo) con su estado de entrega ([statusLabel]; los descartados, tachados) y la
 * entrada: desactivada sin motor `Online` ("Activa GhostP2 para enviar") o con la identidad cambiada
 * (aviso que lleva al detalle); con el contacto no `ONLINE` se avisa de que se descartará, pero se puede
 * enviar. Contador por encima de [COUNTER_THRESHOLD] caracteres; por encima del máximo no se envía.
 *
 * Fotos: [photoButton] va junto al campo (recibe si se puede enviar) y [photoContent] pinta el contenido
 * de las burbujas de foto ([PhotoContent]); la barra de progreso de `Sending` la pone la burbuja. Con
 * [preparingPhotos] > 0 se avisa de "Preparando foto…" y [photoError] se muestra una vez en una
 * snackbar (y se confirma con [onPhotoErrorShown]).
 * [state] `null` ⇒ cargando. El menú y el diálogo abiertos son simple `remember` (sin contenido).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenContact: () -> Unit,
    onClear: () -> Unit,
    photoButton: @Composable RowScope.(enabled: Boolean) -> Unit = {},
    photoContent: @Composable (ChatItem) -> Unit = { Text(stringResource(R.string.chat_photo)) },
    preparingPhotos: Int = 0,
    photoError: PhotoError? = null,
    onPhotoErrorShown: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val ui = rememberCoroutineScope()
    val errorText = photoError?.let { stringResource(it.text) }
    LaunchedEffect(photoError) {
        if (errorText != null) {
            // En el ámbito de la pantalla: confirmar el aviso no lo cierra; uno nuevo sustituye al anterior.
            snackbar.currentSnackbarData?.dismiss()
            ui.launch { snackbar.showSnackbar(errorText) }
            onPhotoErrorShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state?.name.orEmpty())
                        if (state != null) {
                            Text(presenceText(state), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_menu))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_view_contact)) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenContact()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contact_clear_chat)) },
                                    onClick = {
                                        menuOpen = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                Messages(state.items, photoContent, Modifier.weight(1f))
                Notices(state, onOpenContact)
                if (preparingPhotos > 0) NoticeText(stringResource(R.string.chat_photo_preparing))
                Input(state, draft, onDraftChange, onSend, photoButton)
            }
        }
    }

    if (confirmClear && state != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.contact_clear_chat_title, state.name)) },
            text = { Text(stringResource(R.string.contact_clear_chat_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.contact_clear_chat_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun presenceText(state: ChatUiState): String = when {
    state.identityChanged -> stringResource(R.string.contacts_identity_changed)
    state.presence == Presence.ONLINE -> stringResource(R.string.contacts_online)
    state.presence == Presence.CONNECTING -> stringResource(R.string.contacts_connecting)
    else -> stringResource(R.string.contacts_offline)
}

@Composable
private fun Messages(items: List<ChatItem>, photoContent: @Composable (ChatItem) -> Unit, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    // Al llegar uno nuevo (o al abrir), al último. Por id: un cambio de estado no desplaza.
    val lastId = items.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (items.isNotEmpty()) list.scrollToItem(items.lastIndex)
    }
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.chat_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id }) { item -> Bubble(item, photoContent) }
    }
}

@Composable
private fun Bubble(item: ChatItem, photoContent: @Composable (ChatItem) -> Unit) {
    val mine = item.direction == Direction.OUT
    val label = statusLabel(item.status)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (val content = item.content) {
                    is ChatContent.Text -> Text(messageText(content.text, label.struck))
                    is ChatContent.Photo, is ChatContent.PhotoDiscarded -> photoContent(item)
                }
                label.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        label.text?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = if (label.struck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** El texto del mensaje; tachado si se descartó (spec §6.7). */
private fun messageText(text: String, struck: Boolean): AnnotatedString =
    if (!struck) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text) } }
    }

/** Estado de una miniatura: decodificándose (fuera del hilo principal), lista o que no se puede mostrar. */
private sealed interface Thumbnail {
    data object Loading : Thumbnail

    class Ready(val image: ImageBitmap) : Thumbnail

    data object Discarded : Thumbnail
}

/**
 * Contenido de una burbuja de foto: la miniatura (de [bitmaps], decodificada una vez y fuera del hilo
 * principal), que al pulsarla llama a [onOpen]. Si la foto está descartada ([ChatContent.PhotoDiscarded])
 * o sus bytes ya están a cero (o no son una imagen), "Foto descartada para liberar memoria".
 */
@Composable
fun PhotoContent(item: ChatItem, bitmaps: PhotoBitmaps, onOpen: () -> Unit) {
    val jpeg = (item.content as? ChatContent.Photo)?.jpeg
    if (jpeg == null) {
        DiscardedPhoto()
        return
    }
    val thumbnail by produceState(
        initialValue = remember(item.id, jpeg) { bitmaps.cached(item.id, jpeg)?.let { Thumbnail.Ready(it) } ?: Thumbnail.Loading },
        item.id,
        jpeg,
    ) {
        if (value is Thumbnail.Loading) {
            value = withContext(Dispatchers.Default) { bitmaps.thumbnail(item.id, jpeg) }?.let { Thumbnail.Ready(it) } ?: Thumbnail.Discarded
        }
    }
    when (val current = thumbnail) {
        Thumbnail.Loading -> Box(
            Modifier.size(width = 160.dp, height = 120.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), MaterialTheme.shapes.small),
        )
        is Thumbnail.Ready -> Image(
            bitmap = current.image,
            contentDescription = stringResource(R.string.chat_photo),
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp)
                .aspectRatio(current.image.width.toFloat() / current.image.height)
                .clickable(onClick = onOpen),
        )
        Thumbnail.Discarded -> DiscardedPhoto()
    }
}

@Composable
private fun DiscardedPhoto() {
    Text(
        stringResource(R.string.chat_photo_discarded),
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Avisos sobre la entrada: identidad cambiada (lleva al detalle), motor parado o contacto no conectado. */
@Composable
private fun Notices(state: ChatUiState, onOpenContact: () -> Unit) {
    when {
        state.identityChanged ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.chat_identity_changed, state.name), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onOpenContact) { Text(stringResource(R.string.chat_view_contact)) }
                }
            }
        !state.engineOnline -> NoticeText(stringResource(R.string.chat_engine_stopped))
        state.presence != Presence.ONLINE -> NoticeText(stringResource(R.string.chat_contact_offline))
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Input(
    state: ChatUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    photoButton: @Composable RowScope.(enabled: Boolean) -> Unit,
) {
    val canWrite = state.engineOnline && !state.identityChanged
    val length = draft.codePointCount(0, draft.length)
    val fits = length <= PeerSession.MAX_TEXT_CHARS
    val canSend = canWrite && draft.isNotBlank() && fits
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (length > COUNTER_THRESHOLD) {
            Text(
                stringResource(R.string.chat_counter, length, PeerSession.MAX_TEXT_CHARS),
                style = MaterialTheme.typography.labelSmall,
                color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                textAlign = TextAlign.End,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            photoButton(canWrite)
            GhostTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.chat_message_label),
                enabled = canWrite,
            )
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}
