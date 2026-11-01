package org.ghostp2.app.ui.chat

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.app.AppContainer
import org.ghostp2.app.R
import org.ghostp2.app.photo.PhotoBitmaps
import org.ghostp2.app.photo.PhotoSaver
import org.ghostp2.engine.ChatContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lo que muestra el visor. */
sealed interface ViewerPhoto {
    /** Decodificándose. */
    data object Loading : ViewerPhoto

    class Ready(val image: ImageBitmap) : ViewerPhoto

    /** Descartada por el presupuesto de fotos, o sus bytes se pusieron a cero (o no son una imagen). */
    data object Discarded : ViewerPhoto

    /** El elemento ya no está en el historial (borrado, motor parado o la app se reinició). */
    data object Gone : ViewerPhoto
}

/** Resultado de "Guardar". */
enum class SaveResult(@StringRes val text: Int) {
    SAVED(R.string.photo_saved),
    FAILED(R.string.photo_save_failed),
}

/** Nombre propuesto al guardar: `GhostP2-yyyyMMdd-HHmmss.jpg` en la hora local. */
internal fun saveFileName(now: Date = Date()): String =
    "GhostP2-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now) + ".jpg"

/**
 * Visor de la foto [itemId] del chat con [contactId] (la ruta solo lleva los dos ids). La imagen se
 * decodifica una vez (fuera del hilo principal, con la lectura segura de [PhotoBitmaps.full]) y solo en
 * memoria; se vuelve a mirar si el elemento cambia de contenido (descartada) o desaparece. Los cambios
 * de estado de entrega (progreso) no vuelven a decodificar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerViewModel(
    container: AppContainer,
    private val contactId: String,
    private val itemId: String,
    sidePx: Int = VIEWER_SIDE_PX,
) : ViewModel() {
    private val engine = container.engine
    private val bitmaps = PhotoBitmaps()

    /** Contenido actual del elemento (`null` si ya no está). */
    private fun content(): ChatContent? =
        engine.conversations.value[contactId]?.firstOrNull { it.id == itemId }?.content

    val photo: StateFlow<ViewerPhoto> =
        engine.conversations
            .map { all -> all[contactId]?.firstOrNull { it.id == itemId }?.content }
            // Por identidad: un cambio de estado conserva el mismo contenido (y no se compara la foto entera).
            .distinctUntilChanged { old, new -> old === new }
            .mapLatest { content ->
                when (content) {
                    is ChatContent.Photo -> bitmaps.full(content.jpeg, sidePx)?.let { ViewerPhoto.Ready(it) } ?: ViewerPhoto.Discarded
                    is ChatContent.PhotoDiscarded -> ViewerPhoto.Discarded
                    else -> ViewerPhoto.Gone
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ViewerPhoto.Loading)

    private val mutableSaveResult = MutableStateFlow<SaveResult?>(null)

    /** Resultado del último "Guardar", pendiente de mostrar. */
    val saveResult: StateFlow<SaveResult?> = mutableSaveResult.asStateFlow()

    /**
     * Escribe la foto en [target] (el documento que el usuario acaba de crear en el selector del
     * sistema). Sigue aunque se salga del visor, para no dejar un archivo a medias.
     */
    fun save(resolver: ContentResolver, target: Uri) {
        val jpeg = (content() as? ChatContent.Photo)?.jpeg
        viewModelScope.launch {
            val ok = withContext(NonCancellable) {
                if (jpeg == null) false else PhotoSaver.save(resolver, target, jpeg)
            }
            mutableSaveResult.value = if (ok) SaveResult.SAVED else SaveResult.FAILED
        }
    }

    /** El resultado de [saveResult] ya se mostró. */
    fun saveResultShown() {
        mutableSaveResult.value = null
    }

    private companion object {
        /** Lado mayor mínimo al decodificar para el visor (submuestreado hasta < 2× esto). */
        const val VIEWER_SIDE_PX = 2048
    }
}

/**
 * Visor a pantalla completa: la imagen, "Volver" y "Guardar". "Guardar" abre el selector del sistema
 * (`CreateDocument("image/jpeg")`, con el nombre `GhostP2-yyyyMMdd-HHmmss.jpg`): es la única vía por la
 * que una foto llega al disco y solo si el usuario elige el destino; [onSaveTo] recibe el documento.
 * Descartada ⇒ "Foto descartada para liberar memoria"; ya no está ⇒ "La foto ya no está en memoria".
 * Es la misma actividad (con `FLAG_SECURE`): ni capturas ni miniatura en recientes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    photo: ViewerPhoto,
    saveResult: SaveResult?,
    onBack: () -> Unit,
    onSaveTo: (Uri) -> Unit,
    onSaveResultShown: () -> Unit,
) {
    val createDocument = rememberLauncherForActivityResult(CreateDocument("image/jpeg")) { uri -> uri?.let(onSaveTo) }
    val snackbar = remember { SnackbarHostState() }
    val ui = rememberCoroutineScope()
    val resultText = saveResult?.let { stringResource(it.text) }
    LaunchedEffect(saveResult) {
        if (resultText != null) {
            snackbar.currentSnackbarData?.dismiss()
            ui.launch { snackbar.showSnackbar(resultText) }
            onSaveResultShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_photo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (photo is ViewerPhoto.Ready) {
                        TextButton(onClick = { createDocument.launch(saveFileName()) }) { Text(stringResource(R.string.photo_save)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (photo) {
                ViewerPhoto.Loading -> CircularProgressIndicator()
                is ViewerPhoto.Ready -> Image(
                    bitmap = photo.image,
                    contentDescription = stringResource(R.string.chat_photo),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                ViewerPhoto.Discarded -> Message(R.string.chat_photo_discarded)
                ViewerPhoto.Gone -> Message(R.string.photo_gone)
            }
        }
    }
}

@Composable
private fun Message(@StringRes text: Int) {
    Text(
        stringResource(text),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
