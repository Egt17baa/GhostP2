package org.ghostp2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghostp2.app.AppContainer
import org.ghostp2.app.Notifications
import org.ghostp2.data.AppSettings

/**
 * Ajustes de la app sobre `container.settings`. Las escrituras van al scope del proceso (salir de la
 * pantalla no deja un ajuste a medias) y en el orden en que se piden. Si una falla, [saveFailed] y se
 * vuelve a comprobar el almacenamiento (si está mal, la raíz muestra el error).
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    /** Ajustes actuales; `null` mientras se leen (o si no se pueden leer: la raíz muestra el error). */
    val state: StateFlow<AppSettings?> = container.settings.settings
        .catch { container.checkStorage() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val mutableLockNeedsDeviceLock = MutableStateFlow(false)

    /** Se pidió activar el bloqueo sin bloqueo de pantalla en el móvil ("Configura un bloqueo de pantalla…"). */
    val lockNeedsDeviceLock: StateFlow<Boolean> = mutableLockNeedsDeviceLock.asStateFlow()

    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = mutableSaveFailed.asStateFlow()

    private val mutableWiping = MutableStateFlow(false)

    /** "Borrar todo" en curso. */
    val wiping: StateFlow<Boolean> = mutableWiping.asStateFlow()

    private val mutableWipeFailed = MutableStateFlow(false)
    val wipeFailed: StateFlow<Boolean> = mutableWipeFailed.asStateFlow()

    /** Ordena las escrituras: cada una toma el cerrojo antes de devolver el control (UNDISPATCHED). */
    private val writes = Mutex()

    /**
     * Activa o desactiva "Bloqueo con huella o PIN". Activarlo exige bloqueo de pantalla en el móvil
     * ([AppContainer.deviceLockAvailable]); sin él devuelve `false`, no guarda nada y marca
     * [lockNeedsDeviceLock].
     */
    fun setLock(enabled: Boolean): Boolean {
        if (enabled && !container.deviceLockAvailable(container.appContext)) {
            mutableLockNeedsDeviceLock.value = true
            return false
        }
        mutableLockNeedsDeviceLock.value = false
        save { it.copy(lockEnabled = enabled) }
        return true
    }

    /** "Bloquear tras": uno de [AppSettings.LOCK_MINUTES]; otro valor se ignora. */
    fun setLockAfter(minutes: Int) {
        if (minutes in AppSettings.LOCK_MINUTES) save { it.copy(lockAfterMinutes = minutes) }
    }

    /** Al activarlo se quitan también las notificaciones de contactos ya publicadas (con nombre). */
    fun setHideNames(hide: Boolean) {
        if (hide) Notifications.cancelContactNotifications(container.appContext)
        save { it.copy(hideNotificationNames = hide) }
    }

    /** "Tamaño máximo" de las fotos: uno de [AppSettings.PHOTO_SIDES]; otro valor se ignora. */
    fun setMaxPhotoSide(px: Int) {
        if (px in AppSettings.PHOTO_SIDES) save { it.copy(maxPhotoSidePx = px) }
    }

    /** "Desconectar al salir de la app" (R29): uno de [AppSettings.DISCONNECT_MINUTES]; otro valor se ignora. */
    fun setDisconnectAfter(minutes: Int) {
        if (minutes in AppSettings.DISCONNECT_MINUTES) save { it.copy(disconnectAfterMinutes = minutes) }
    }

    /** "Activar GhostP2 al encender el móvil" (R20). */
    fun setStartOnBoot(on: Boolean) = save { it.copy(startOnBoot = on) }

    /**
     * "Borrar todo" ([AppContainer.wipeAll]) en el scope del proceso: la pantalla desaparece en cuanto
     * los ajustes vuelven a los de por defecto (la raíz pasa a la bienvenida) y el borrado no debe
     * cortarse por eso. Ignorado mientras otro está en curso.
     */
    fun wipeAll() {
        if (!mutableWiping.compareAndSet(expect = false, update = true)) return
        mutableWipeFailed.value = false
        container.scope.launch {
            try {
                container.wipeAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableWipeFailed.value = true
            } finally {
                mutableWiping.value = false
            }
        }
    }

    private fun save(transform: (AppSettings) -> AppSettings) {
        mutableSaveFailed.value = false
        container.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                try {
                    container.settings.update(transform)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableSaveFailed.value = true
                    container.checkStorage()
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
