package org.ghostp2.app.ui.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ghostp2.app.AppContainer
import org.ghostp2.app.R
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS

/**
 * Nombrar al contacto recién emparejado: muestra el número de seguridad y guarda el nombre y la marca
 * de verificado. El nombre escrito lo guarda la pantalla en memoria (nunca en estado guardable).
 */
class NameContactViewModel(private val container: AppContainer, private val contactId: String) : ViewModel() {
    private val mutableSafetyNumber = MutableStateFlow<String?>(null)

    /** Número de seguridad (6 grupos de 5 cifras separados por espacios); `null` mientras se lee o si falla. */
    val safetyNumber: StateFlow<String?> = mutableSafetyNumber.asStateFlow()

    private val mutableError = MutableStateFlow<Int?>(null)

    /** Id de la cadena del último error ([R.string.name_error_blank], …), o `null`. */
    val error: StateFlow<Int?> = mutableError.asStateFlow()

    private val mutableSaved = MutableStateFlow(false)

    /** El nombre y la verificación ya están guardados: la pantalla pasa al chat. */
    val saved: StateFlow<Boolean> = mutableSaved.asStateFlow()

    private var saving = false

    init {
        viewModelScope.launch {
            mutableSafetyNumber.value = try {
                container.engine.safetyNumber(contactId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Contacto borrado o almacenamiento ilegible: no hay número que mostrar (sin registrar nada).
                null
            }
        }
    }

    /**
     * Guarda [name] (recortado) y [verified]. Vacío o con más de [MAX_CONTACT_NAME_CHARS] caracteres ⇒
     * `false` y [error], sin guardar nada. Si no, empieza a guardar y devuelve `true`; al terminar,
     * [saved] (o [error] con [R.string.name_error_save] si el almacenamiento falla).
     */
    fun save(name: String, verified: Boolean): Boolean {
        val trimmed = name.trim()
        val problem = nameProblem(trimmed)
        if (problem != null) {
            mutableError.value = problem
            return false
        }
        mutableError.value = null
        if (saving) return true
        saving = true
        viewModelScope.launch {
            try {
                container.engine.renameContact(contactId, trimmed)
                container.engine.setVerified(contactId, verified)
                mutableSaved.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableError.value = R.string.name_error_save
            } finally {
                saving = false
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
