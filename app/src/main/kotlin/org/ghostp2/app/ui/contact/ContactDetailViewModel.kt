package org.ghostp2.app.ui.contact

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.app.AppContainer
import org.ghostp2.app.R
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery

/**
 * Detalle de un contacto. [presence] es `null` si el motor no informa de él (motor parado);
 * [safetyNumber] es `null` mientras se lee o si no se pudo calcular; [deleting] mientras se borra
 * (desde aquí o desde el motor).
 */
data class ContactDetailUiState(
    val name: String,
    val verified: Boolean,
    val identityChanged: Boolean,
    val presence: Presence?,
    val safetyNumber: String?,
    val deleting: Boolean,
)

/**
 * Detalle del contacto [contactId]: número de seguridad, verificar, renombrar, borrar el chat y borrar
 * el contacto. Las escrituras y el borrado, una vez empezados, terminan aunque se salga de la pantalla
 * (`NonCancellable`; el borrado va en el scope del contenedor). Todo vive en memoria.
 */
class ContactDetailViewModel(private val container: AppContainer, private val contactId: String) : ViewModel() {
    private val engine = container.engine

    private val safetyNumber = MutableStateFlow<String?>(null)

    /** Borrado pedido desde esta pantalla (antes incluso de que el motor lo apunte en `deleting`). */
    private val ownDelete = MutableStateFlow(false)

    /** El contacto guardado, o `null` si no existe; ausente (sin valor) hasta la primera lectura. */
    private sealed interface Lookup {
        data object Loading : Lookup

        data class Read(val state: ContactDetailUiState?) : Lookup
    }

    private val lookup: StateFlow<Lookup> =
        combine(container.contacts.contacts, engine.presence, engine.deleting, safetyNumber, ownDelete) { all, presence, deleting, number, own ->
            val contact = all.firstOrNull { it.id == contactId }
            val p = presence[contactId]
            Lookup.Read(
                contact?.let {
                    ContactDetailUiState(
                        name = it.name,
                        verified = it.verified,
                        identityChanged = it.status == ContactStatus.IDENTITY_CHANGED || p == Presence.IDENTITY_CHANGED,
                        presence = p,
                        safetyNumber = number,
                        deleting = own || contactId in deleting,
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

    /** Estado del contacto; `null` mientras se lee la primera vez o cuando ya no existe. */
    val state: StateFlow<ContactDetailUiState?> =
        lookup.map { (it as? Lookup.Read)?.state }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * El contacto no existe (o ya no) sin haberlo borrado desde aquí (p. ej. él nos borró): la pantalla
     * vuelve a la lista. Tras un borrado propio no se activa: se muestra [deleteResult].
     */
    val gone: StateFlow<Boolean> =
        combine(lookup, ownDelete) { l, own -> !own && l is Lookup.Read && l.state == null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val mutableBusy = MutableStateFlow(false)

    /** Guardando un nombre o la verificación: los botones quedan desactivados. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableRenameDraft = MutableStateFlow<String?>(null)

    /** Nombre que se está escribiendo en el diálogo de renombrar; `null` con el diálogo cerrado. */
    val renameDraft: StateFlow<String?> = mutableRenameDraft.asStateFlow()

    private val mutableRenameError = MutableStateFlow<Int?>(null)

    /** Id de cadena del error al renombrar ([R.string.name_error_blank], …), o `null`. */
    val renameError: StateFlow<Int?> = mutableRenameError.asStateFlow()

    private val mutableActionError = MutableStateFlow<Int?>(null)

    /** Id de cadena del error de la última acción (verificar o borrar), o `null`. */
    val actionError: StateFlow<Int?> = mutableActionError.asStateFlow()

    private val mutableDeleteResult = MutableStateFlow<UnpairDelivery?>(null)

    /** Resultado del borrado pedido desde aquí: si el contacto recibió el aviso. */
    val deleteResult: StateFlow<UnpairDelivery?> = mutableDeleteResult.asStateFlow()

    init {
        viewModelScope.launch {
            safetyNumber.value = try {
                engine.safetyNumber(contactId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Contacto desconocido o almacenamiento ilegible: sin número (y sin registrar nada).
                null
            }
        }
    }

    /** Abre el diálogo de renombrar con el nombre actual. */
    fun startRename() {
        mutableRenameError.value = null
        mutableRenameDraft.value = state.value?.name.orEmpty()
    }

    fun setRenameDraft(value: String) {
        mutableRenameDraft.value = value
    }

    /** Cierra el diálogo sin guardar (no corta un guardado ya empezado). */
    fun cancelRename() {
        mutableRenameDraft.value = null
        mutableRenameError.value = null
    }

    /**
     * Guarda [name] recortado. Vacío o con más de [MAX_CONTACT_NAME_CHARS] caracteres ⇒ `false` y
     * [renameError]; con otra escritura en curso ⇒ `false` sin hacer nada. Si no, empieza a guardar y
     * devuelve `true`; al terminar cierra el diálogo (o deja [R.string.name_error_save] si falla).
     */
    fun rename(name: String): Boolean {
        val trimmed = name.trim()
        val problem = nameProblem(trimmed)
        if (problem != null) {
            mutableRenameError.value = problem
            return false
        }
        mutableRenameError.value = null
        return write(onFailure = { mutableRenameError.value = R.string.name_error_save }) {
            engine.renameContact(contactId, trimmed)
            mutableRenameDraft.value = null
        }
    }

    /** "Marcar como verificado" / "Quitar verificación". */
    fun setVerified(v: Boolean) {
        mutableActionError.value = null
        write(onFailure = { mutableActionError.value = R.string.name_error_save }) {
            engine.setVerified(contactId, v)
        }
    }

    /** Borra la conversación (solo está en memoria; las fotos se rellenan con ceros). */
    fun clearChat() {
        engine.clearConversation(contactId)
    }

    /**
     * Borra el contacto avisándole ([AppContainer.deleteContact], en el scope del contenedor: salir de
     * la pantalla no lo corta). Una sola vez; el resultado llega a [deleteResult].
     */
    fun delete() {
        if (ownDelete.value) return
        mutableActionError.value = null
        ownDelete.value = true
        val result = container.deleteContact(contactId)
        viewModelScope.launch {
            try {
                mutableDeleteResult.value = result.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // El almacenamiento no pudo borrarlo: se avisa y se puede reintentar.
                mutableActionError.value = R.string.contact_delete_failed
                ownDelete.value = false
            }
        }
    }

    /** Escritura persistente que termina aunque se salga de la pantalla; `false` si ya hay otra en curso. */
    private fun write(onFailure: () -> Unit, block: suspend () -> Unit): Boolean {
        if (mutableBusy.value) return false
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
            } finally {
                mutableBusy.value = false
            }
        }
        return true
    }

    @StringRes
    private fun nameProblem(trimmed: String): Int? = when {
        trimmed.isEmpty() -> R.string.name_error_blank
        trimmed.codePointCount(0, trimmed.length) > MAX_CONTACT_NAME_CHARS -> R.string.name_error_too_long
        else -> null
    }
}
