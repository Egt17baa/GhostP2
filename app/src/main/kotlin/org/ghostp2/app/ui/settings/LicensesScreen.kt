package org.ghostp2.app.ui.settings

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ghostp2.app.R
import java.io.IOException

/** Un archivo de `assets/licenses/`: [name] (p. ej. `LICENSES/GPL-3.0.txt`) y su texto. */
data class LicenseFile(val name: String, val text: String)

/** Estado de [LicensesScreen]. */
sealed interface LicensesState {
    data object Loading : LicensesState

    data class Loaded(val files: List<LicenseFile>) : LicensesState

    /** No se pudieron leer los archivos de la APK. */
    data object Failed : LicensesState
}

/** Carpeta de los avisos de terceros en los assets de la APK (la llena la tarea `licenseAssets` de Gradle). */
private const val LICENSES_ASSET_DIR = "licenses"

private const val NOTICES = "THIRD_PARTY_NOTICES.md"

/**
 * Lee de los assets de la APK, sin red, `THIRD_PARTY_NOTICES.md` y después cada texto de `LICENSES/` por orden de
 * nombre. Lanza `IOException` si falta alguno.
 */
internal fun readLicenseFiles(assets: AssetManager): List<LicenseFile> {
    val texts = assets.list("$LICENSES_ASSET_DIR/LICENSES").orEmpty().sorted().map { "LICENSES/$it" }
    if (texts.isEmpty()) throw IOException("Faltan los textos de licencia")
    return (listOf(NOTICES) + texts).map { name ->
        LicenseFile(name, assets.open("$LICENSES_ASSET_DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}

/**
 * Ajustes › Acerca de › «Licencias de terceros»: los avisos de terceros y los textos completos de las licencias,
 * tal cual vienen en la APK, en una lista que se desplaza (un elemento por párrafo: los textos son largos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(state: LicensesState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            LicensesState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            LicensesState.Failed -> Text(
                stringResource(R.string.licenses_load_failed),
                Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
            is LicensesState.Loaded -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                for (file in state.files) {
                    item {
                        HorizontalDivider()
                        Text(
                            file.name,
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(paragraphs(file.text)) { paragraph ->
                        Text(
                            paragraph,
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Párrafos de [text] (separados por líneas en blanco), sin los vacíos. */
private fun paragraphs(text: String): List<String> =
    text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
