package org.ghostp2.app.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import org.ghostp2.app.ui.GhostTextField
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS

/**
 * Tras emparejar: número de seguridad en 6 grupos (`null` ⇒ aún no se tiene o no se pudo calcular),
 * "Comparadlo por otro canal…", nombre ([name]), "Hemos comprobado que coincide" ([verified]),
 * "Guardar" ([onSave]) y "Más tarde" ([onLater]: se queda como "Contacto nuevo"). Con [saving] los dos
 * botones quedan desactivados. [error] es el id de cadena del ViewModel, que guarda el nombre y la
 * casilla en memoria.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameContactScreen(
    safetyNumber: String?,
    error: Int?,
    name: String,
    onNameChange: (String) -> Unit,
    verified: Boolean,
    onVerifiedChange: (Boolean) -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
    onLater: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.name_title)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.name_safety_title), style = MaterialTheme.typography.titleMedium)
            if (safetyNumber != null) {
                SafetyNumberGroups(safetyNumber)
            } else {
                Text(stringResource(R.string.name_safety_unavailable))
            }
            Text(stringResource(R.string.name_safety_compare))
            GhostTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.name_label),
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().toggleable(value = verified, role = Role.Checkbox, onValueChange = onVerifiedChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = verified, onCheckedChange = null)
                Text(stringResource(R.string.name_verified), Modifier.padding(start = 8.dp))
            }
            error?.let {
                // El de "demasiado largo" lleva el máximo; los demás ignoran el argumento.
                Text(stringResource(it, MAX_CONTACT_NAME_CHARS), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, enabled = !saving) { Text(stringResource(R.string.name_save)) }
                TextButton(onClick = onLater, enabled = !saving) { Text(stringResource(R.string.name_later)) }
            }
        }
    }
}

/** Los grupos de 5 cifras en dos filas de tres, en monoespaciada. */
@Composable
private fun SafetyNumberGroups(safetyNumber: String) {
    val groups = safetyNumber.split(' ').filter { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
