package org.ghostp2.app.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ghostp2.app.AppContainer
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.InvitationHandle
import org.ghostp2.engine.InvitationState

/** Estado de "Pegar" (y, en la Task 8, de "Escanear"): aceptar la invitación de otro. */
sealed interface AcceptUi {
    data object Idle : AcceptUi

    data object Working : AcceptUi

    /** [message] es el texto del motor (p. ej. "Invitación no válida: …"); nunca incluye el secreto. */
    data class Failed(val message: String) : AcceptUi

    data class Paired(val contactId: String) : AcceptUi
}

/**
 * Añadir contacto con el motor del contenedor: crea la invitación propia ("Mi código") y acepta la del
 * otro ("Pegar"). Todo vive solo en memoria: el texto de la invitación (con su secreto) no pasa por
 * ningún estado guardado ni se registra en ningún log.
 *
 * La invitación vive lo que este ViewModel: [onCleared] (salir de la pantalla, emparejar y pasar a
 * nombrar, o que la actividad termine) la cancela y el motor retira el servicio temporal. Al girar la
 * pantalla el ViewModel se conserva (en memoria) y la invitación sigue; si el sistema mata el proceso
 * no se restaura nada: el motor y la invitación desaparecen con él y, al volver, hace falta otra.
 */
class PairingViewModel(private val container: AppContainer) : ViewModel() {
    private var handle: InvitationHandle? = null
    private var watcher: Job? = null

    private val mutableInvitation = MutableStateFlow<InvitationState?>(null)

    /** Estado de la invitación propia; `null` hasta [createInvitation]. */
    val invitation: StateFlow<InvitationState?> = mutableInvitation.asStateFlow()

    private val mutableAccept = MutableStateFlow<AcceptUi>(AcceptUi.Idle)
    val accept: StateFlow<AcceptUi> = mutableAccept.asStateFlow()

    /** Contacto recién emparejado por cualquiera de los dos caminos (invitación propia o aceptada). */
    val pairedContactId: StateFlow<String?> =
        combine(invitation, accept) { mine, theirs ->
            (mine as? InvitationState.Paired)?.contactId ?: (theirs as? AcceptUi.Paired)?.contactId
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Crea una invitación nueva (cancela la anterior, si la hay). Sin motor `Online` queda en `Failed`. */
    fun createInvitation() {
        cancelInvitation()
        val created = container.engine.createInvitation()
        handle = created
        mutableInvitation.value = created.state.value
        watcher = viewModelScope.launch { created.state.collect { mutableInvitation.value = it } }
    }

    /** Cancela la invitación activa (idempotente) y la deja en `Cancelled`. */
    fun cancelInvitation() {
        // Primero se deja de copiar su estado: un `Waiting` atrasado no pisa el `Cancelled`.
        watcher?.cancel()
        watcher = null
        val current = handle ?: return
        handle = null
        current.cancel()
        val state = current.state.value
        mutableInvitation.value = if (state is InvitationState.Paired) state else InvitationState.Cancelled
    }

    /** Acepta la invitación pegada o escaneada; se recortan los espacios y saltos de línea de los extremos. */
    fun accept(text: String) {
        if (mutableAccept.value == AcceptUi.Working) return
        val trimmed = text.trim()
        mutableAccept.value = AcceptUi.Working
        viewModelScope.launch {
            // acceptInvitation nunca lanza salvo cancelación (que se propaga al salir de la pantalla).
            mutableAccept.value = when (val result = container.engine.acceptInvitation(trimmed)) {
                is AcceptResult.Paired -> AcceptUi.Paired(result.contactId)
                is AcceptResult.Failed -> AcceptUi.Failed(result.message)
            }
        }
    }

    override fun onCleared() {
        cancelInvitation()
    }
}
