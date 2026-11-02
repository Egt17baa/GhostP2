package org.ghostp2.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import org.ghostp2.app.ui.GhostTextField
import org.ghostp2.app.ui.pairing.QrCodeScanner
import org.ghostp2.app.ui.pairing.ScanTexts
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.bridges.BridgeLines
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.ParseResult

/** Textos del escáner de «Mis puentes» ("Puedes pegar los puentes"). */
internal val BRIDGE_SCAN_TEXTS = ScanTexts(
    noCamera = R.string.bridges_scan_no_camera,
    noPermission = R.string.bridges_scan_no_permission,
    blocked = R.string.bridges_scan_blocked,
    cameraFailed = R.string.bridges_scan_camera_failed,
    hint = R.string.bridges_scan_hint,
)

/**
 * Líneas de un QR de bridges.torproject.org (`['obfs4 …', 'obfs4 …']` o texto plano), normalizadas y una por
 * línea; `null` si el QR no es una lista válida de «Mis puentes» (otro QR, Snowflake, más de 10…), que se ignora.
 */
internal fun bridgeQrLines(text: String): String? =
    (BridgeLines.parseQr(text) as? ParseResult.Ok)?.lines?.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.line }

/** El escáner real (CameraX), que solo entrega QR de puentes válidos. */
@Composable
private fun CameraBridgeScanner(onScanned: (String) -> Unit, onClose: () -> Unit) {
    QrCodeScanner(accept = ::bridgeQrLines, onResult = onScanned, onUsePaste = onClose, texts = BRIDGE_SCAN_TEXTS)
}

/**
 * Ajustes › Puentes (R30): la ayuda y las opciones No, obfs4, Snowflake y Mis puentes, con el modo guardado en
 * [settings] marcado (`null` ⇒ cargando). Elegir Snowflake pide antes confirmar su aviso ("Usar Snowflake").
 * Con «Mis puentes», un campo de varias líneas ([GhostTextField], teclado ASCII sin sugerencias, sin
 * autocorrección y sin aprender; sin copiar nada al portapapeles) con las líneas guardadas y "Escanear QR"
 * ([scanner], que entrega el texto del QR y vuelve al campo).
 *
 * "Guardar" valida antes «Mis puentes» con [BridgeLines.parse] ("Línea N no válida", "Máximo 10 puentes",
 * "Pega al menos un puente") y, si no es válido, no llama a [onSave]. Si [onSave] responde
 * [BridgeChange.NEEDS_RECONNECT], muestra el diálogo de reconexión: [onConfirmReconnect] o [onCancelReconnect].
 * Al quedar guardado, [onDone]. El modo elegido y el texto viven solo en la composición (nada guardable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgesScreen(
    settings: AppSettings?,
    saveFailed: Boolean,
    onBack: () -> Unit,
    onSave: (BridgeMode, String?) -> BridgeChange,
    onConfirmReconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    onDone: () -> Unit,
    scanner: @Composable (onScanned: (String) -> Unit, onClose: () -> Unit) -> Unit = { onScanned, onClose ->
        CameraBridgeScanner(onScanned, onClose)
    },
) {
    // `null` = aún lo guardado.
    var chosen by remember { mutableStateOf<BridgeMode?>(null) }
    var typed by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf<List<BridgeError>>(emptyList()) }
    var askSnowflake by remember { mutableStateOf(false) }
    var askReconnect by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bridges_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (settings == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val mode = chosen ?: settings.bridgeMode
        val text = typed ?: settings.customBridges.joinToString("\n")
        if (scanning) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                scanner(
                    { scanned ->
                        bridgeQrLines(scanned)?.let {
                            typed = it
                            errors = emptyList()
                            scanning = false
                        }
                    },
                    { scanning = false },
                )
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saveFailed) Text(stringResource(R.string.settings_save_failed), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.bridges_help))
            Column(Modifier.selectableGroup()) {
                for (option in BridgeMode.entries) {
                    ModeRow(bridgeModeLabel(option), selected = mode == option) {
                        when {
                            option == mode -> Unit
                            option == BridgeMode.SNOWFLAKE -> askSnowflake = true
                            else -> chosen = option
                        }
                        errors = emptyList()
                    }
                }
            }
            if (mode == BridgeMode.CUSTOM) {
                Text(stringResource(R.string.bridges_custom_detail), style = MaterialTheme.typography.bodySmall)
                GhostTextField(
                    value = text,
                    onValueChange = {
                        typed = it
                        errors = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.bridges_custom_label),
                    maxLines = CUSTOM_MAX_LINES,
                    keyboardType = KeyboardType.Ascii,
                    noSuggestions = true,
                )
                for (error in errors) Text(error.text(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { scanning = true }) { Text(stringResource(R.string.bridges_scan)) }
            }
            Button(onClick = {
                val custom = if (mode == BridgeMode.CUSTOM) text else null
                errors = custom?.let(::customErrors).orEmpty()
                if (errors.isEmpty()) {
                    when (onSave(mode, custom)) {
                        BridgeChange.SAVED -> onDone()
                        BridgeChange.NEEDS_RECONNECT -> askReconnect = true
                        // El ViewModel valida igual: no debería pasar.
                        BridgeChange.INVALID -> errors = listOf(BridgeError.Empty)
                    }
                }
            }) { Text(stringResource(R.string.bridges_save)) }
        }
    }

    if (askSnowflake) {
        AlertDialog(
            onDismissRequest = { askSnowflake = false },
            text = { Text(stringResource(R.string.bridges_snowflake_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    askSnowflake = false
                    chosen = BridgeMode.SNOWFLAKE
                }) { Text(stringResource(R.string.bridges_snowflake_use)) }
            },
            dismissButton = { TextButton(onClick = { askSnowflake = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (askReconnect) {
        AlertDialog(
            onDismissRequest = {
                askReconnect = false
                onCancelReconnect()
            },
            text = { Text(stringResource(R.string.bridges_reconnect_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askReconnect = false
                    onConfirmReconnect()
                    onDone()
                }) { Text(stringResource(R.string.bridges_reconnect)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askReconnect = false
                    onCancelReconnect()
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Un error de «Mis puentes» que se muestra bajo el campo. */
private sealed interface BridgeError {
    data class Line(val number: Int) : BridgeError
    data object TooMany : BridgeError
    data object Empty : BridgeError
}

@Composable
private fun BridgeError.text(): String = when (this) {
    is BridgeError.Line -> stringResource(R.string.bridges_line_invalid, number)
    BridgeError.TooMany -> stringResource(R.string.bridges_too_many)
    BridgeError.Empty -> stringResource(R.string.bridges_empty)
}

/** Errores de [text] como «Mis puentes»: uno por línea no válida, más de [BridgeLines.MAX] o ninguna línea. */
private fun customErrors(text: String): List<BridgeError> = when (val parsed = BridgeLines.parse(text)) {
    is ParseResult.Ok -> if (parsed.lines.isEmpty()) listOf(BridgeError.Empty) else emptyList()
    is ParseResult.Invalid ->
        parsed.lineNumbers.map { BridgeError.Line(it) } + if (parsed.tooMany) listOf(BridgeError.TooMany) else emptyList()
}

@Composable
private fun ModeRow(@StringRes label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), Modifier.padding(start = 8.dp))
    }
}

/** Alto máximo del campo, en líneas (las de más se desplazan dentro). */
private const val CUSTOM_MAX_LINES = 8
