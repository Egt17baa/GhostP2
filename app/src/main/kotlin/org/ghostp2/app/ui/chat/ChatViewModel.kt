package org.ghostp2.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.ghostp2.app.AppContainer
import org.ghostp2.core.protocol.PeerSession
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
 * Fotos (Tasks 11–12): aquí se añadirán el envío por la cola de fotos y las miniaturas; el estado ya
 * trae los elementos de foto del historial.
 */
class ChatViewModel(container: AppContainer, private val contactId: String) : ViewModel() {
    private val engine = container.engine

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
}
