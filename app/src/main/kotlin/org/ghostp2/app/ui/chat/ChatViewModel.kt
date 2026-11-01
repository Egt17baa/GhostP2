package org.ghostp2.app.ui.chat

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.ghostp2.app.AppContainer
import org.ghostp2.app.R
import org.ghostp2.app.photo.PhotoBitmaps
import org.ghostp2.app.photo.PhotoException
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.Presence

/**
 * Estado del chat. [presence] es `null` si el motor no informa del contacto (motor parado); [items] son
 * los elementos vivos del historial del motor (las fotos llevan su array vivo: ver `ChatContent.Photo`).
 */
data class ChatUiState(
    val name: String,
    val presence: Presence?,
    val engineOnline: Boolean,
    val identityChanged: Boolean,
    val items: List<ChatItem>,
)

/**
 * Chat de texto con el contacto [contactId]. La conversación es la del motor (solo en memoria) y el
 * borrador ([draft]) vive solo en este ViewModel: nada es guardable, así que si el sistema mata la app
 * el borrador se pierde (nunca llega al `Bundle` guardado ni al disco).
 *
 * Fotos: [pickPhoto] las pone en la cola de fotos del contenedor (que las recodifica y envía de una en
 * una), [preparingPhotos] cuenta las de este contacto aún sin entregar al motor, [photoErrors] es el
 * último aviso pendiente de mostrar y [bitmaps] guarda las miniaturas decodificadas (solo en memoria,
 * recortadas a las fotos vivas de la conversación y soltadas al destruir el ViewModel).
 */
class ChatViewModel(container: AppContainer, private val contactId: String) : ViewModel() {
    private val engine = container.engine
    private val photos = container.photos

    /** Lectura del contacto: aún sin leer, o leída (`null` ⇒ no existe). */
    private sealed interface Lookup {
        data object Loading : Lookup

        data class Read(val state: ChatUiState?) : Lookup
    }

    private val lookup: StateFlow<Lookup> =
        combine(container.contacts.contacts, engine.presence, engine.status, engine.conversations) { all, presence, status, chats ->
            val contact = all.firstOrNull { it.id == contactId }
            val p = presence[contactId]
            Lookup.Read(
                contact?.let {
                    ChatUiState(
                        name = it.name,
                        presence = p,
                        engineOnline = status == EngineStatus.Online,
                        identityChanged = it.status == ContactStatus.IDENTITY_CHANGED || p == Presence.IDENTITY_CHANGED,
                        items = chats[contactId].orEmpty(),
                    )
                },
            ) as Lookup
        }
            .catch { e ->
                if (e is CancellationException) throw e
                // No se pudo leer: como si no existiera (la lista mostrará su propio error). Sin registrar nada.
                emit(Lookup.Read(null))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Lookup.Loading)

    /** Estado del chat; `null` mientras se lee la primera vez o cuando el contacto ya no existe. */
    val state: StateFlow<ChatUiState?> =
        lookup.map { (it as? Lookup.Read)?.state }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** El contacto no existe (o ya no: lo borramos desde el detalle o nos borró él): la pantalla sale. */
    val gone: StateFlow<Boolean> =
        lookup.map { it is Lookup.Read && it.state == null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Miniaturas de las fotos de esta conversación (solo en memoria). */
    val bitmaps = PhotoBitmaps()

    /** Fotos de este contacto que esperan o se están recodificando ("Preparando foto…"). */
    val preparingPhotos: StateFlow<Int> =
        photos.preparing.map { it[contactId] ?: 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val mutablePhotoErrors = MutableStateFlow<PhotoError?>(null)

    /** Último aviso de foto pendiente de mostrar (la pantalla llama a [photoErrorShown] al mostrarlo). */
    val photoErrors: StateFlow<PhotoError?> = mutablePhotoErrors.asStateFlow()

    init {
        // Solo se conservan las miniaturas de las fotos que siguen vivas en la conversación.
        state.onEach { current ->
            bitmaps.retain(
                current?.items.orEmpty().filter { it.content is ChatContent.Photo }.mapTo(HashSet()) { it.id },
            )
        }.launchIn(viewModelScope)
        photos.errors
            .filter { (id, _) -> id == contactId }
            .onEach { (_, reason) -> mutablePhotoErrors.value = PhotoError.of(reason) }
            .launchIn(viewModelScope)
    }

    /**
     * Pone en la cola la foto [uri] (elegida en el selector del sistema; no se guarda en ningún sitio).
     * `false` solo si la cola está llena ("Espera a que terminen de enviarse las fotos"). Con el motor
     * no `Online`, el contacto inexistente o la identidad cambiada no se pone en cola y se avisa con
     * [PhotoError.NOT_ACTIVE] (devuelve `true`: no es la cola llena).
     */
    fun pickPhoto(uri: Uri): Boolean {
        val current = state.value
        if (current == null || current.identityChanged || engine.status.value != EngineStatus.Online) {
            mutablePhotoErrors.value = PhotoError.NOT_ACTIVE
            return true
        }
        val queued = photos.enqueue(contactId, uri)
        if (!queued) mutablePhotoErrors.value = PhotoError.QUEUE_FULL
        return queued
    }

    /** El aviso de [photoErrors] ya se mostró. */
    fun photoErrorShown() {
        mutablePhotoErrors.value = null
    }

    /** Texto que se está escribiendo. Solo en la memoria de este ViewModel (nunca guardable). */
    var draft: String by mutableStateOf("")

    /**
     * Envía [draft] tal cual y lo vacía. Devuelve `false` (y deja el borrador intacto) si el motor no
     * está `Online`, si el contacto no existe o tiene la identidad cambiada, si el borrador está vacío
     * (o solo tiene espacios) o si supera [PeerSession.MAX_TEXT_CHARS] caracteres Unicode. Con el
     * contacto sin sesión se envía igual: el motor lo deja en `Failed(NOT_CONNECTED)` (spec §6.7).
     */
    fun send(): Boolean {
        val text = draft
        val current = state.value ?: return false
        if (engine.status.value != EngineStatus.Online || current.identityChanged) return false
        if (text.isBlank() || text.codePointCount(0, text.length) > PeerSession.MAX_TEXT_CHARS) return false
        try {
            engine.sendText(contactId, text)
        } catch (e: IllegalArgumentException) {
            // Demasiado largo para el motor (no debería pasar tras la comprobación): no se guarda nada.
            return false
        }
        draft = ""
        return true
    }

    /** "Borrar chat": borra la conversación de la memoria (las fotos se rellenan con ceros). */
    fun clear() {
        engine.clearConversation(contactId)
    }

    override fun onCleared() {
        bitmaps.clear()
    }
}

/** Avisos de foto del chat, con su texto. */
enum class PhotoError(@StringRes val text: Int) {
    /** Ya hay 3 fotos entre esperando y enviándose. */
    QUEUE_FULL(R.string.chat_photo_queue_full),

    /** No se pudo leer o no es una imagen. */
    UNREADABLE(R.string.chat_photo_unreadable),

    /** No cabe en el máximo de una foto ni recodificada. */
    TOO_LARGE(R.string.chat_photo_too_large),

    /** GhostP2 no está activo (o no se puede enviar a este contacto). */
    NOT_ACTIVE(R.string.chat_photo_not_active),
    ;

    internal companion object {
        fun of(reason: PhotoException.Reason): PhotoError = when (reason) {
            PhotoException.Reason.UNREADABLE -> UNREADABLE
            PhotoException.Reason.TOO_LARGE -> TOO_LARGE
            PhotoException.Reason.NOT_ACTIVE -> NOT_ACTIVE
        }
    }
}
