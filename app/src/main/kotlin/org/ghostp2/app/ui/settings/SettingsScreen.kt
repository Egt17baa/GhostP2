package org.ghostp2.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.Notice
import org.ghostp2.engine.bridges.BridgeMode

/**
 * Ajustes: Seguridad (bloqueo y "Bloquear tras"), Notificaciones ("Ocultar el nombre del contacto"),
 * Fotos ("Tamaño máximo"), Segundo plano (exención de batería, ayuda del fabricante [manufacturerHint],
 * "Activar GhostP2 al encender el móvil" y "Desconectar al salir de la app", R29), Puentes (el modo actual;
 * al tocarlo, [onOpenBridges] abre [BridgesScreen], R30), Datos ("Borrar todo" con doble confirmación) y
 * Acerca de (versión, licencias, «Licencias de terceros» con [onOpenLicenses] hacia [LicensesScreen], mi .onion
 * abreviada y el último aviso de diagnóstico). [settings] `null` ⇒ cargando.
 * Los diálogos abiertos son simple `remember` (sin contenido).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings?,
    batteryIgnoring: Boolean,
    @StringRes manufacturerHint: Int,
    version: String,
    myOnion: String?,
    lastDiagnostic: Notice?,
    lockNeedsDeviceLock: Boolean,
    saveFailed: Boolean,
    wiping: Boolean,
    wipeFailed: Boolean,
    onBack: () -> Unit,
    onSetLock: (Boolean) -> Unit,
    onSetLockAfter: (Int) -> Unit,
    onSetHideNames: (Boolean) -> Unit,
    onSetMaxPhotoSide: (Int) -> Unit,
    onRequestBattery: () -> Unit,
    onSetStartOnBoot: (Boolean) -> Unit,
    onSetDisconnectAfter: (Int) -> Unit,
    onWipeAll: () -> Unit,
    onOpenBridges: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saveFailed) Text(stringResource(R.string.settings_save_failed), color = MaterialTheme.colorScheme.error)

            Section(R.string.settings_section_security)
            SwitchRow(R.string.settings_lock, settings.lockEnabled, onSetLock)
            if (lockNeedsDeviceLock) {
                Text(stringResource(R.string.settings_lock_needs_device_lock), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.settings_lock_after), style = MaterialTheme.typography.bodyLarge)
            for (minutes in AppSettings.LOCK_MINUTES) {
                RadioRow(stringResource(lockAfterLabel(minutes)), settings.lockAfterMinutes == minutes) { onSetLockAfter(minutes) }
            }

            Section(R.string.settings_section_notifications)
            SwitchRow(R.string.settings_hide_names, settings.hideNotificationNames, onSetHideNames)
            Text(stringResource(R.string.settings_hide_names_detail), style = MaterialTheme.typography.bodySmall)

            Section(R.string.settings_section_photos)
            Text(stringResource(R.string.settings_photo_max), style = MaterialTheme.typography.bodyLarge)
            for (side in AppSettings.PHOTO_SIDES) {
                RadioRow(stringResource(R.string.settings_photo_side, side), settings.maxPhotoSidePx == side) { onSetMaxPhotoSide(side) }
            }

            Section(R.string.settings_section_background)
            Text(stringResource(if (batteryIgnoring) R.string.settings_battery_ok else R.string.settings_battery_limited))
            if (!batteryIgnoring) {
                OutlinedButton(onClick = onRequestBattery) { Text(stringResource(R.string.settings_battery_allow)) }
            }
            Text(stringResource(manufacturerHint), style = MaterialTheme.typography.bodySmall)
            SwitchRow(R.string.settings_start_on_boot, settings.startOnBoot, onSetStartOnBoot)
            Text(stringResource(R.string.settings_start_on_boot_detail), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_disconnect_after), style = MaterialTheme.typography.bodyLarge)
            for (minutes in AppSettings.DISCONNECT_MINUTES) {
                RadioRow(stringResource(disconnectAfterLabel(minutes)), settings.disconnectAfterMinutes == minutes) {
                    onSetDisconnectAfter(minutes)
                }
            }
            Text(stringResource(R.string.settings_disconnect_after_detail), style = MaterialTheme.typography.bodySmall)

            Section(R.string.settings_section_bridges)
            Text(
                stringResource(R.string.settings_bridges_row, stringResource(bridgeModeLabel(settings.bridgeMode))),
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenBridges).padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )

            Section(R.string.settings_section_data)
            WipeAll(wiping = wiping, wipeFailed = wipeFailed, onWipeAll = onWipeAll)

            Section(R.string.settings_section_about)
            Text(stringResource(R.string.settings_version, version))
            Text(stringResource(R.string.settings_license))
            Text(stringResource(R.string.settings_license_components), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.settings_third_party_licenses),
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenLicenses).padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (myOnion != null) stringResource(R.string.settings_my_onion, abbreviateOnion(myOnion))
                else stringResource(R.string.settings_my_onion_none),
            )
            Text(diagnosticText(lastDiagnostic), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "Borrar todo" con dos confirmaciones seguidas; desactivado mientras se borra. */
@Composable
private fun WipeAll(wiping: Boolean, wipeFailed: Boolean, onWipeAll: () -> Unit) {
    var step by remember { mutableIntStateOf(0) } // 0: nada, 1: primera confirmación, 2: segunda
    if (wiping) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.settings_wiping), Modifier.padding(start = 8.dp))
        }
    }
    if (wipeFailed) Text(stringResource(R.string.settings_wipe_failed), color = MaterialTheme.colorScheme.error)
    Button(
        onClick = { step = 1 },
        enabled = !wiping,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) { Text(stringResource(R.string.settings_wipe)) }

    when (step) {
        1 -> Confirm(
            title = stringResource(R.string.settings_wipe_confirm_title),
            text = stringResource(R.string.settings_wipe_confirm_text),
            confirm = stringResource(R.string.settings_wipe_confirm),
            onConfirm = { step = 2 },
            onDismiss = { step = 0 },
        )
        2 -> Confirm(
            title = stringResource(R.string.settings_wipe_confirm_again_title),
            text = stringResource(R.string.settings_wipe_confirm_again_text),
            confirm = stringResource(R.string.settings_wipe_confirm_again),
            onConfirm = {
                step = 0
                onWipeAll()
            },
            onDismiss = { step = 0 },
        )
    }
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
private fun Section(@StringRes title: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** Nombre del modo de «Puentes», el mismo en Ajustes y en [BridgesScreen]. */
@StringRes
internal fun bridgeModeLabel(mode: BridgeMode): Int = when (mode) {
    BridgeMode.NONE -> R.string.bridges_none
    BridgeMode.OBFS4 -> R.string.bridges_obfs4
    BridgeMode.SNOWFLAKE -> R.string.bridges_snowflake
    BridgeMode.CUSTOM -> R.string.bridges_custom
}

@StringRes
private fun lockAfterLabel(minutes: Int): Int = when (minutes) {
    0 -> R.string.settings_lock_after_0
    1 -> R.string.settings_lock_after_1
    5 -> R.string.settings_lock_after_5
    else -> R.string.settings_lock_after_15
}

@StringRes
private fun disconnectAfterLabel(minutes: Int): Int = when (minutes) {
    0 -> R.string.settings_disconnect_after_0
    2 -> R.string.settings_disconnect_after_2
    5 -> R.string.settings_disconnect_after_5
    else -> R.string.settings_disconnect_after_15
}

@Composable
private fun diagnosticText(notice: Notice?): String = when (notice) {
    Notice.PublicationFailed -> stringResource(R.string.settings_diagnostic_publication)
    is Notice.ClientAuthFailed ->
        pluralStringResource(R.plurals.settings_diagnostic_client_auth, notice.contactIds.size, notice.contactIds.size)
    else -> stringResource(R.string.settings_diagnostic_none)
}

/** Dirección .onion abreviada: los 8 primeros caracteres, "…" y los 6 últimos (`abcdefgh…uvwxyz.onion`). */
fun abbreviateOnion(onion: String): String {
    val name = onion.removeSuffix(".onion")
    if (name.length <= ONION_HEAD + ONION_TAIL) return onion
    return name.take(ONION_HEAD) + "…" + name.takeLast(ONION_TAIL) + ".onion"
}

private const val ONION_HEAD = 8
private const val ONION_TAIL = 6
