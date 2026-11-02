package org.ghostp2.app.ui.pairing

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ghostp2.app.R
import org.ghostp2.app.ui.GhostTextField
import org.ghostp2.app.ui.copySensitive
import org.ghostp2.app.ui.startingText
import org.ghostp2.engine.EngineErrorKind
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.InvitationState

/**
 * Añadir contacto, con tres pestañas: "Mi código" (QR y texto de [invitation], "Caduca en mm:ss",
 * "Copiar", "Compartir" y "Crear otra" al caducar, cancelarse o fallar), "Escanear" ([QrScanner],
 * que acepta al leer y pasa a "Pegar") y "Pegar" ([GhostTextField] con [pasted] y "Aceptar", con
 * el estado [accept]). Sin el motor `Online` ([status]): parado, "Activa GhostP2 para añadir
 * contactos" y "Activar"; arrancando, "Conectando a la red Tor…" (o "Conectando por puente…" con
 * [bridged], R30); con error (o [startFailed]), el aviso del error y "Reintentar" ([onActivate] en los dos casos).
 *
 * El texto de la invitación no se puede seleccionar (R24): solo se copia con "Copiar", marcado como
 * sensible. Con el motor `Online` y sin invitación (o cancelada al parar el motor) se pide una con
 * [onCreateInvitation]. [pasted] lo guarda el ViewModel en memoria; la pestaña elegida solo vive en la
 * composición (nada guardable). [clock] es el reloj de pared de la cuenta atrás.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    status: EngineStatus,
    invitation: InvitationState?,
    accept: AcceptUi,
    pasted: String,
    onPastedChange: (String) -> Unit,
    onCreateInvitation: () -> Unit,
    onAccept: (String) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit,
    startFailed: Boolean = false,
    bridged: Boolean = false,
    clock: () -> Long = System::currentTimeMillis,
) {
    var tab by remember { mutableIntStateOf(TAB_MINE) }
    val online = status == EngineStatus.Online

    LaunchedEffect(online) {
        if (online && (invitation == null || invitation == InvitationState.Cancelled)) onCreateInvitation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_add)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!online) {
                NotOnline(status, startFailed, bridged, onActivate)
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.add_tab_mine, R.string.add_tab_scan, R.string.add_tab_paste).forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                }
            }
            when (tab) {
                TAB_MINE -> MyCode(invitation, onCreateInvitation, clock)
                // Al leer una invitación se acepta y se pasa a "Pegar", que muestra el progreso o el fallo.
                TAB_SCAN -> QrScanner(
                    onInvitation = {
                        onAccept(it)
                        tab = TAB_PASTE
                    },
                    onUsePaste = { tab = TAB_PASTE },
                )
                else -> Paste(pasted, onPastedChange, accept, onAccept)
            }
        }
    }
}

/** Motor no `Online`: qué pasa y, salvo mientras arranca, cómo activarlo (o reintentar). */
@Composable
private fun NotOnline(status: EngineStatus, startFailed: Boolean, bridged: Boolean, onActivate: () -> Unit) {
    val retry = stringResource(R.string.action_retry)
    val (message, action) = when {
        startFailed -> stringResource(R.string.banner_unexpected) to retry
        status is EngineStatus.StartingTor -> stringResource(startingText(bridged), status.percent) to null
        status is EngineStatus.Error && status.kind == EngineErrorKind.STORAGE -> stringResource(R.string.banner_storage_error) to retry
        status is EngineStatus.Error && status.kind == EngineErrorKind.TOR_STUCK -> stringResource(R.string.banner_tor_stuck) to retry
        status is EngineStatus.Error && status.kind == EngineErrorKind.BRIDGE_FAILED -> stringResource(R.string.banner_bridge_failed) to retry
        status is EngineStatus.Error -> stringResource(R.string.banner_tor_error) to retry
        else -> stringResource(R.string.add_not_online) to stringResource(R.string.action_activate)
    }
    Centered {
        if (action == null) CircularProgressIndicator()
        Text(message, textAlign = TextAlign.Center)
        action?.let { Button(onClick = onActivate) { Text(it) } }
    }
}

@Composable
private fun MyCode(invitation: InvitationState?, onCreateInvitation: () -> Unit, clock: () -> Long) {
    when (invitation) {
        null, InvitationState.Preparing -> Centered {
            CircularProgressIndicator()
            Text(stringResource(R.string.add_preparing))
        }
        is InvitationState.Waiting -> Waiting(invitation, clock)
        // Al emparejar se pasa enseguida a nombrar al contacto.
        is InvitationState.Paired -> Centered { CircularProgressIndicator() }
        InvitationState.Expired -> CreateAnother(stringResource(R.string.add_expired), onCreateInvitation)
        InvitationState.Cancelled -> CreateAnother(stringResource(R.string.add_cancelled), onCreateInvitation)
        is InvitationState.Failed -> CreateAnother(invitation.message, onCreateInvitation)
    }
}

@Composable
private fun Waiting(waiting: InvitationState.Waiting, clock: () -> Long) {
    val context = LocalContext.current
    // Un píxel por módulo (tamaño nativo); el Image lo amplía sin suavizar. Solo en memoria.
    val qr = remember(waiting.text) { QrCode.toImageBitmap(QrCode.encode(waiting.text, 0)) }
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(waiting.expiresAtMillis) {
        while (true) {
            now = clock()
            delay(TICK_MILLIS)
        }
    }
    val clipLabel = stringResource(R.string.add_clip_label)
    var copied by remember(waiting.text) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            bitmap = qr,
            contentDescription = stringResource(R.string.add_qr_description),
            modifier = Modifier.size(QR_SIZE),
            filterQuality = FilterQuality.None,
        )
        Text(stringResource(R.string.add_expires_in, countdown(waiting.expiresAtMillis - now)))
        Text(stringResource(R.string.add_mine_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        // Sin SelectionContainer (R24): copiar desde la selección no marcaría el portapapeles como sensible.
        Text(waiting.text, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                copySensitive(context, clipLabel, waiting.text)
                copied = true
            }) { Text(stringResource(R.string.add_copy)) }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, waiting.text)
                try {
                    context.startActivity(Intent.createChooser(send, null))
                } catch (_: ActivityNotFoundException) {
                    // Ninguna app puede compartir: queda "Copiar".
                }
            }) { Text(stringResource(R.string.add_share)) }
        }
        if (copied) Text(stringResource(R.string.add_copied), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CreateAnother(message: String, onCreateInvitation: () -> Unit) {
    Centered {
        Text(message, textAlign = TextAlign.Center)
        Button(onClick = onCreateInvitation) { Text(stringResource(R.string.add_create_another)) }
    }
}

@Composable
private fun Paste(text: String, onText: (String) -> Unit, accept: AcceptUi, onAccept: (String) -> Unit) {
    val working = accept == AcceptUi.Working
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.add_paste_hint))
        GhostTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.add_paste_label),
            enabled = !working,
        )
        Button(onClick = { onAccept(text) }, enabled = !working && text.isNotBlank()) {
            Text(stringResource(R.string.add_accept))
        }
        when (accept) {
            AcceptUi.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(stringResource(R.string.add_working))
            }
            is AcceptUi.Failed -> Text(accept.message, color = MaterialTheme.colorScheme.error)
            AcceptUi.Idle, is AcceptUi.Paired -> Unit
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) { content() }
}

/** "mm:ss" de lo que queda (redondeado hacia arriba; nunca negativo). */
internal fun countdown(remainingMillis: Long): String {
    val seconds = ((remainingMillis.coerceAtLeast(0) + 999) / 1000)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

private const val TAB_MINE = 0
private const val TAB_SCAN = 1
private const val TAB_PASTE = 2
private const val TICK_MILLIS = 1_000L
private val QR_SIZE = 240.dp
