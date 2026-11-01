package org.ghostp2.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.ghostp2.app.AppContainer
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.Presence
import java.text.Collator
import java.util.Locale

/**
 * Fila de la lista de contactos. [presence] es `null` si el motor no informa de este contacto (p. ej.
 * con el motor parado); [identityChanged] manda sobre la presencia y [deleting] sobre todo lo demás.
 */
data class ContactRow(
    val id: String,
    val name: String,
    val presence: Presence?,
    val verified: Boolean,
    val identityChanged: Boolean,
    val deleting: Boolean,
)

/**
 * Filas de [contacts], ordenadas por nombre sin distinguir mayúsculas (ni acentos al comparar primero;
 * a igualdad, por id). `identityChanged` si el contacto guardado es `IDENTITY_CHANGED` o su presencia lo es.
 */
fun contactRows(contacts: List<Contact>, presence: Map<String, Presence>, deleting: Set<String>): List<ContactRow> {
    // Collator no es seguro entre hilos: uno por llamada. SECONDARY: "ana" = "Ana", "Álvaro" junto a "Alberto".
    val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
    return contacts
        .map { c ->
            val p = presence[c.id]
            ContactRow(
                id = c.id,
                name = c.name,
                presence = p,
                verified = c.verified,
                identityChanged = c.status == ContactStatus.IDENTITY_CHANGED || p == Presence.IDENTITY_CHANGED,
                deleting = c.id in deleting,
            )
        }
        .sortedWith(compareBy<ContactRow, String>(collator) { it.name }.thenBy { it.id })
}

sealed interface ContactsUiState {
    data object Loading : ContactsUiState

    /** No se pudo leer la lista: "Reintentar" (nunca se muestra como lista vacía). */
    data object Failed : ContactsUiState

    data class Loaded(val rows: List<ContactRow>) : ContactsUiState
}

/**
 * Lista de contactos: los guardados ([AppContainer.contacts]) con la presencia y los borrados en curso del
 * motor del contenedor. Solo en memoria; se recoge mientras la pantalla está visible (y 5 s más, para
 * los giros de pantalla).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel(container: AppContainer) : ViewModel() {
    /** Cada [retry] vuelve a suscribirse a los contactos. */
    private val attempt = MutableStateFlow(0)

    val state: StateFlow<ContactsUiState> = attempt
        .flatMapLatest {
            combine(container.contacts.contacts, container.engine.presence, container.engine.deleting) { c, p, d ->
                ContactsUiState.Loaded(contactRows(c, p, d)) as ContactsUiState
            }
                .onStart { emit(ContactsUiState.Loading) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    // La causa no se registra (podría citar datos de la app).
                    emit(ContactsUiState.Failed)
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ContactsUiState.Loading)

    /** "Reintentar" tras [ContactsUiState.Failed]. */
    fun retry() {
        attempt.update { it + 1 }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
